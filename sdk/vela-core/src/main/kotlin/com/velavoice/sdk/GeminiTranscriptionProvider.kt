package com.velavoice.sdk

import com.velavoice.sdk.whisper.AudioConverter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Gemini transcription provider (default: gemini-3.6 voice / multimodal model).
 *
 * Connects to the Google Gemini REST endpoint for verbatim speech-to-text transcription:
 * https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * Authenticates using the 'x-goog-api-key' request header.
 *
 * Supports both standalone burst transcription [transcribe] and streaming session
 * integration [StreamingTranscriber].
 */
class GeminiTranscriptionProvider(
    private val client: OkHttpClient = defaultOkHttpClient(),
    rawModel: String = DEFAULT_MODEL,
    baseUrl: String? = null,
    val allowCustomEndpoint: Boolean = false,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
) : StreamingTranscriber {

    val model: String = normalizeModel(rawModel)
    val baseUrl: String = baseUrl ?: "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

    init {
        validateEndpoint(this.baseUrl, allowCustomEndpoint)
    }

    companion object {
        const val ALLOWED_SCHEME = "https"
        const val ALLOWED_HOST = "generativelanguage.googleapis.com"
        const val HEADER_API_KEY = "x-goog-api-key"

        const val MODEL_TRANSCRIBE_LIVE = "gemini-3.5-transcribe-live"
        const val MODEL_TRANSCRIBE_BATCH = "gemini-3.5-transcribe"
        const val DEFAULT_MODEL = MODEL_TRANSCRIBE_BATCH
        const val DEFAULT_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-transcribe:generateContent"
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16

        fun validateEndpoint(url: String, allowCustomEndpoint: Boolean = false) {
            val httpUrl = url.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Invalid Gemini endpoint URL: $url")
            if (!allowCustomEndpoint) {
                if (httpUrl.scheme != ALLOWED_SCHEME || httpUrl.host != ALLOWED_HOST) {
                    throw IllegalArgumentException(
                        "Gemini endpoint must match https://$ALLOWED_HOST/ allowlist, got: $url"
                    )
                }
            }
            if (httpUrl.queryParameter("key") != null) {
                throw IllegalArgumentException(
                    "Gemini endpoint must not include API key in query parameters: $url"
                )
            }
        }

        fun normalizeModel(rawModel: String?, isStreaming: Boolean = false): String {
            if (rawModel.isNullOrBlank()) {
                return if (isStreaming) MODEL_TRANSCRIBE_LIVE else MODEL_TRANSCRIBE_BATCH
            }
            val trimmed = rawModel.trim()
            return when (trimmed) {
                "gemini-3.5", "3.5", "gemini-3.5-flash" ->
                    if (isStreaming) MODEL_TRANSCRIBE_LIVE else MODEL_TRANSCRIBE_BATCH
                "gemini-3.6", "3.6", "gemini-3.6-flash" ->
                    if (isStreaming) MODEL_TRANSCRIBE_LIVE else MODEL_TRANSCRIBE_BATCH
                "gemini-2.0", "2.0" -> "gemini-2.0-flash"
                "gemini-1.5", "1.5" -> "gemini-1.5-flash"
                else -> trimmed
            }
        }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
    ): ByteArray = AudioConverter.pcmToWav(pcmAudio, sampleRate, channels, bitsPerSample)

    /**
     * Encodes bytes to Base64 using android.util.Base64 with fallback to java.util.Base64.
     */
    fun encodeBase64(bytes: ByteArray): String {
        return try {
            val base64Class = Class.forName("android.util.Base64")
            val encodeMethod = base64Class.getMethod(
                "encodeToString",
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            encodeMethod.invoke(null, bytes, 2) as String // 2 = NO_WRAP
        } catch (t: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    /**
     * Formats Gemini 2.0 Flash generateContent request body JSON.
     */
    fun buildRequestBodyJson(base64Audio: String): String {
        val systemInstruction = JSONObject().apply {
            val parts = JSONArray().apply {
                put(JSONObject().apply {
                    put(
                        "text",
                        "You are a professional verbatim speech-to-text transcriber. Output ONLY the exact spoken transcription of the audio. Do not add notes, explanations, commentary, or conversational replies."
                    )
                })
            }
            put("parts", parts)
        }

        val inlineData = JSONObject().apply {
            put("mime_type", "audio/wav")
            put("data", base64Audio)
        }

        val userParts = JSONArray().apply {
            put(JSONObject().apply {
                put("inline_data", inlineData)
            })
            put(JSONObject().apply {
                put("text", "Transcribe this audio verbatim.")
            })
        }

        val content = JSONObject().apply {
            put("role", "user")
            put("parts", userParts)
        }

        val contents = JSONArray().apply {
            put(content)
        }

        val generationConfig = JSONObject().apply {
            put("temperature", 0.0)
        }

        val root = JSONObject().apply {
            put("system_instruction", systemInstruction)
            put("contents", contents)
            put("generationConfig", generationConfig)
        }

        return root.toString()
    }

    /**
     * Parses candidates[0].content.parts[0].text and cleans markdown fences / whitespace.
     */
    fun parseGeminiResponse(responseJson: String): String {
        if (responseJson.isBlank()) return ""
        return try {
            val json = JSONObject(responseJson)
            val candidates = json.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val firstCandidate = candidates.optJSONObject(0) ?: return ""
            val content = firstCandidate.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) return ""
            val firstPart = parts.optJSONObject(0) ?: return ""
            val text = firstPart.optString("text", "")
            cleanTranscript(text)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Cleans whitespace and markdown code fences (e.g. ```markdown ... ```).
     */
    fun cleanTranscript(raw: String): String {
        var cleaned = raw.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst(Regex("""^```[a-zA-Z0-9_-]*\r?\n?"""), "")
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.replace(Regex("""\r?\n?```$"""), "")
            }
        }
        return cleaned.trim()
    }

    /**
     * Transcribes 16kHz 16-bit mono PCM audio synchronously via Gemini model (default: gemini-2.0-flash).
     */
    @Throws(VelaException::class)
    fun transcribe(pcmAudio: ByteArray, apiKey: String, targetModel: String? = null): String {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            throw VelaException.Network("Invalid Gemini API key or unauthorized access.")
        }

        val effectiveModel = normalizeModel(targetModel ?: this.model)
        val effectiveBaseUrl = if (effectiveModel != this.model) {
            "https://generativelanguage.googleapis.com/v1beta/models/$effectiveModel:generateContent"
        } else {
            this.baseUrl
        }
        validateEndpoint(effectiveBaseUrl, allowCustomEndpoint)

        val wavAudio = pcmToWav(pcmAudio)
        val base64Audio = encodeBase64(wavAudio)
        val requestBodyJson = buildRequestBodyJson(base64Audio)

        val request = Request.Builder()
            .url(effectiveBaseUrl)
            .header(HEADER_API_KEY, trimmedKey)
            .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response: Response
        try {
            response = client.newCall(request).execute()
        } catch (e: IOException) {
            throw VelaException.Network("Network error communicating with Gemini: ${e.message}", e)
        } catch (t: Throwable) {
            throw VelaException.Network("Unexpected error communicating with Gemini: ${t.message}", t)
        }

        response.use { res ->
            when (res.code) {
                200 -> {
                    val bodyString = res.body?.string().orEmpty()
                    return parseGeminiResponse(bodyString)
                }
                404 -> {
                    // Auto-fallback to gemini-2.0-flash if model not found in Google AI Studio
                    if (effectiveModel != "gemini-2.0-flash") {
                        return transcribe(pcmAudio, apiKey, "gemini-2.0-flash")
                    }
                    // Status + body length only: the body must never reach logcat (ticket #79).
                    val bodyString = res.body?.string().orEmpty()
                    throw VelaException.Network(
                        "Gemini model '$effectiveModel' was not found in Google AI Studio (${bodyString.length} bytes)"
                    )
                }
                429 -> {
                    throw VelaException.Network(
                        "Gemini rate limit exceeded. Free tier limit is 15 requests/minute. Please wait a moment."
                    )
                }
                400, 403 -> {
                    throw VelaException.Network("Invalid Gemini API key or unauthorized access.")
                }
                else -> {
                    // Keep only the short, provider-authored error message; a JSON body
                    // without one must never be interpolated whole (map #72, ticket #79).
                    val errorMsg = try {
                        val body = res.body?.string().orEmpty()
                        if (body.isNotEmpty()) {
                            val json = JSONObject(body)
                            json.optJSONObject("error")?.optString("message")
                                ?.takeIf { it.isNotBlank() }
                                ?.take(200)
                                ?: "no structured error message (${body.length} bytes)"
                        } else {
                            res.message
                        }
                    } catch (e: Exception) {
                        res.message
                    }
                    throw VelaException.Network("Gemini API error ${res.code}: $errorMsg")
                }
            }
        }
    }

    // --- StreamingTranscriber implementation ---

    override fun start(config: StreamConfig) {
        if (isRunning.get()) return
        this.streamConfig = config
        synchronized(bufferLock) {
            audioBuffer.reset()
        }
        if (config.apiKey.isBlank()) {
            callback?.onError(VelaException.Network("Invalid Gemini API key or unauthorized access."))
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
        val effectiveModel = streamConfig?.model?.takeIf { it.isNotBlank() && it != "gpt-live-transcribe" } ?: this.model
        val key = streamConfig?.apiKey.orEmpty()
        executor.execute {
            try {
                val transcript = transcribe(pcmAudio, key, effectiveModel)
                callback?.onRevisionMarker(
                    RevisionMarker("commit", transcript, 0 until transcript.length)
                )
                callback?.onFinal(transcript)
            } catch (ve: VelaException) {
                callback?.onError(ve)
            } catch (t: Throwable) {
                callback?.onError(
                    VelaException.Network("Gemini transcription failed: ${t.message}", t)
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
    }

    private fun computeRms(audioChunk: ByteArray): Float {
        val samples = audioChunk.size / 2
        if (samples == 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until samples) {
            val sample =
                (audioChunk[i * 2].toInt() and 0xFF) or (audioChunk[i * 2 + 1].toInt() shl 8)
            val sampleShort = sample.toShort()
            val normalized = sampleShort / 32768.0
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / samples).toFloat()
        return (rms * 5f).coerceIn(0f, 1f)
    }
}
