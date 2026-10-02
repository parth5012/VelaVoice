package com.velavoice.sdk

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * REST transcription provider for OpenAI-compatible Whisper endpoints.
 *
 * Supports Groq (https://api.groq.com/openai/v1/audio/transcriptions),
 * OpenAI (https://api.openai.com/v1/audio/transcriptions), and custom self-hosted
 * Whisper-compatible servers.
 */
class WhisperRestTranscriptionProvider(
    private val client: OkHttpClient = defaultOkHttpClient(),
    val apiKey: String = "",
    val model: String = "whisper-1",
    val endpoint: String = "https://api.openai.com/v1/audio/transcriptions",
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : StreamingTranscriber {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
        private val AUDIO_WAV_MEDIA_TYPE = "audio/wav".toMediaType()

        private fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    private var callback: StreamingTranscriptionCallback? = null
    private var streamConfig: StreamConfig? = null
    private val isRunning = AtomicBoolean(false)
    private val audioBuffer = ByteArrayOutputStream()
    private val bufferLock = Any()

    /**
     * Converts raw 16-bit 16kHz mono PCM audio bytes into standard 44-byte WAV format.
     */
    fun pcmToWav(
        pcmAudio: ByteArray,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmAudio.size
        val chunkSize = 36 + dataSize

        val header = ByteArray(44)
        // "RIFF"
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (chunkSize and 0xff).toByte()
        header[5] = ((chunkSize shr 8) and 0xff).toByte()
        header[6] = ((chunkSize shr 16) and 0xff).toByte()
        header[7] = ((chunkSize shr 24) and 0xff).toByte()

        // "WAVE"
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // "fmt " sub-chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // SubChunk1Size (16 for PCM)
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat (1 = PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (blockAlign and 0xff).toByte()
        header[33] = ((blockAlign shr 8) and 0xff).toByte()
        header[34] = (bitsPerSample and 0xff).toByte()
        header[35] = ((bitsPerSample shr 8) and 0xff).toByte()

        // "data" sub-chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        return header + pcmAudio
    }

    /**
     * Parses the JSON response from OpenAI/Groq/Custom REST transcription endpoint.
     */
    fun parseResponse(responseJson: String): String {
        if (responseJson.isBlank()) return ""
        return try {
            val json = JSONObject(responseJson)
            json.optString("text", "").trim()
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * Transcribes 16kHz 16-bit mono PCM audio synchronously via REST multipart call.
     */
    @Throws(VelaException::class)
    fun transcribe(
        pcmAudio: ByteArray,
        apiKey: String = this.apiKey,
        model: String = this.model,
        endpoint: String = this.endpoint
    ): String {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw VelaException.Network("API key is required for cloud transcription.")
        }

        val wavAudio = pcmToWav(pcmAudio)
        val normalizedEndpoint = if (endpoint.endsWith("/audio/transcriptions")) {
            endpoint
        } else {
            endpoint.trimEnd('/') + "/audio/transcriptions"
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart(
                "file",
                "audio.wav",
                wavAudio.toRequestBody(AUDIO_WAV_MEDIA_TYPE)
            )
            .build()

        val request = Request.Builder()
            .url(normalizedEndpoint)
            .addHeader("Authorization", "Bearer $trimmedKey")
            .post(requestBody)
            .build()

        val response: Response
        try {
            response = client.newCall(request).execute()
        } catch (e: IOException) {
            throw VelaException.Network("Network error communicating with transcription provider: ${e.message}", e)
        } catch (t: Throwable) {
            throw VelaException.Network("Unexpected error communicating with transcription provider: ${t.message}", t)
        }

        response.use { res ->
            when (res.code) {
                200 -> {
                    val bodyString = res.body?.string().orEmpty()
                    return parseResponse(bodyString)
                }
                429 -> throw VelaException.Network(
                    "Rate limit or quota exceeded for transcription provider. Please check your account limits."
                )
                401, 403 -> throw VelaException.Network(
                    "Invalid API key or unauthorized access for transcription provider."
                )
                else -> {
                    // Status + body length only: the body may echo the audio transcript
                    // (map #72, ticket #79), so it must never reach logcat via the exception.
                    val errBody = res.body?.string().orEmpty()
                    throw VelaException.Network(
                        "Transcription API returned HTTP ${res.code} (${errBody.length} bytes)"
                    )
                }
            }
        }
    }

    override fun start(config: StreamConfig) {
        if (isRunning.get()) return
        this.streamConfig = config
        synchronized(bufferLock) {
            audioBuffer.reset()
        }

        val key = config.apiKey.ifBlank { this.apiKey }
        if (key.isBlank()) {
            callback?.onError(VelaException.Network("API key is required for cloud transcription."))
            return
        }

        isRunning.set(true)
    }

    override fun emit(audioChunk: ByteArray) {
        if (!isRunning.get() || audioChunk.isEmpty()) return
        synchronized(bufferLock) {
            audioBuffer.write(audioChunk)
        }

        callback?.let { cb ->
            val amp = computeRms(audioChunk)
            cb.onAmplitude(amp)
        }
    }

    override fun stop() {
        if (!isRunning.getAndSet(false)) return
        val pcmAudio = synchronized(bufferLock) {
            val bytes = audioBuffer.toByteArray()
            audioBuffer.reset()
            bytes
        }

        val key = streamConfig?.apiKey?.takeIf { it.isNotBlank() } ?: this.apiKey
        val effectiveModel = streamConfig?.model?.takeIf { it.isNotBlank() && it != "gpt-live-transcribe" } ?: this.model
        val effectiveEndpoint = streamConfig?.endpoint?.takeIf { it.isNotBlank() && !it.startsWith("ws") } ?: this.endpoint

        executor.execute {
            try {
                val transcript = transcribe(pcmAudio, key, effectiveModel, effectiveEndpoint)
                callback?.onRevisionMarker(
                    RevisionMarker("commit", transcript, 0 until transcript.length)
                )
                callback?.onFinal(transcript)
            } catch (ve: VelaException) {
                callback?.onError(ve)
            } catch (t: Throwable) {
                callback?.onError(
                    VelaException.Network("Transcription failed: ${t.message}", t)
                )
            }
        }
    }

    override fun setCallback(callback: StreamingTranscriptionCallback) {
        this.callback = callback
    }

    override fun release() {
        isRunning.set(false)
        synchronized(bufferLock) {
            audioBuffer.reset()
        }
        callback = null
    }

    private fun computeRms(audioChunk: ByteArray): Float {
        if (audioChunk.size < 2) return 0.0f
        var sumSquares = 0.0
        val sampleCount = audioChunk.size / 2
        for (i in 0 until sampleCount) {
            val low = audioChunk[i * 2].toInt() and 0xFF
            val high = audioChunk[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSquares / sampleCount)
        return (rms / 32768.0).toFloat().coerceIn(0.0f, 1.0f)
    }
}
