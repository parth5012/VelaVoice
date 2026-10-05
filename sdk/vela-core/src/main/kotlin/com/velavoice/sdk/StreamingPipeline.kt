package com.velavoice.sdk

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.velavoice.sdk.whisper.WhisperConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Ticket 88 (map #81): fail closed on cleartext transports. Only encrypted
 * schemes are accepted; a blank endpoint falls back to the secure built-in
 * default. The scheme alone is reported so no key material or host details
 * can leak through the error. Enforced both at [StreamingPipeline.Builder.build]
 * time and on every [StreamingPipeline.start] after config reconciliation, so
 * a start-time endpoint override cannot bypass it.
 */
internal fun validateEndpointScheme(endpoint: String) {
    if (endpoint.isBlank()) return
    if (endpoint.startsWith("wss://") || endpoint.startsWith("https://")) return
    val scheme = endpoint.substringBefore("://", missingDelimiterValue = "unknown")
    throw IllegalArgumentException(
        "Insecure transcription endpoint scheme \"$scheme\" rejected: " +
            "endpoints must use wss:// or https://"
    )
}

/**
 * High-level streaming transcription pipeline.
 *
 * Orchestrates:
 * - Audio capture with chunked buffering
 * - Backend selection (local whisper.cpp or cloud OpenAI)
 * - Commit boundary detection (VAD pause, sentence boundary, max time)
 * - Final full-pass cleanup on stop
 * - Revision marker emission to the UI
 *
 * Architecture: single-threaded sequential processing.
 */
class StreamingPipeline internal constructor(
    private val localTranscriber: StreamingTranscriber?,
    private val cloudTranscriber: StreamingTranscriber?,
    private val defaultStreamConfig: StreamConfig
) {
    private var streamConfig: StreamConfig = defaultStreamConfig
    private var callback: StreamingTranscriptionCallback? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val stateLock = Any()
    /** Whether the current session may feed audio to [cloudTranscriber]; fail-closed. */
    @Volatile
    private var sessionUploadAllowed = false
    private val uploadRefusalReported = AtomicBoolean(false)

    // Commit boundary tracking
    @Volatile
    internal var lastActivityTime = 0L
    @Volatile
    internal var lastCommitTime = 0L
    @Volatile
    internal var currentSegmentStart = 0
    @Volatile
    internal var committedText = ""
    private var isSessionLocal = true

    /** The configuration derived from [Builder]; used by [start] when no explicit config is given. */
    fun config(): StreamConfig = defaultStreamConfig

    // Audio constants
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE = resolveBufferSize()

        private fun resolveBufferSize(): Int {
            val min = try {
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            } catch (e: Throwable) {
                0
            }
            return if (min <= 0) SAMPLE_RATE else min
        }

        // Commit boundary defaults
        const val VAD_PAUSE_MS = 500L
        const val MAX_SEGMENT_MS = 8000L
        const val FINAL_PASS_SECONDS = 5
    }

    class Builder(private val context: Context) {
        private var whisperModelPath: String = ""
        private var apiKey: String = ""
        private var endpoint: String = ""
        private var model: String = "gpt-live-transcribe"
        private var language: String = "en"
        private var numThreads: Int = 4
        private var chunkDurationMs: Int = 3000
        private var windowDurationMs: Int = 15000
        private var overlapMs: Int = 1500
        private var resetIntervalMs: Long = 30000
        private var useVad: Boolean = true
        private var vadThreshold: Float = 0.02f
        private var privacySensitive: Boolean = false
        private var consentToUpload: Boolean = false
        private var allowCustomEndpoint: Boolean = false

        fun whisperModelPath(path: String) = apply { this.whisperModelPath = path }
        fun apiKey(key: String) = apply { this.apiKey = key }
        fun endpoint(url: String) = apply { this.endpoint = url }
        fun allowCustomEndpoint(allow: Boolean) = apply { this.allowCustomEndpoint = allow }
        fun model(model: String) = apply { this.model = model }
        fun language(lang: String) = apply { this.language = lang }
        fun threads(n: Int) = apply { this.numThreads = n }
        fun chunkDurationMs(ms: Int) = apply { this.chunkDurationMs = ms }
        fun windowDurationMs(ms: Int) = apply { this.windowDurationMs = ms }
        fun overlapMs(ms: Int) = apply { this.overlapMs = ms }
        fun resetIntervalMs(ms: Long) = apply { this.resetIntervalMs = ms }

        fun useVad(enabled: Boolean) = apply { this.useVad = enabled }
        fun vadThreshold(threshold: Float) = apply { this.vadThreshold = threshold }
        fun privacySensitive(sensitive: Boolean) = apply { this.privacySensitive = sensitive }
        fun consentToUpload(consent: Boolean) = apply { this.consentToUpload = consent }

        fun build(): StreamingPipeline {
            validateEndpointScheme(endpoint)
            val streamConfig = StreamConfig(
                modelPath = whisperModelPath,
                language = language,
                numThreads = numThreads,
                apiKey = apiKey,
                endpoint = endpoint.ifBlank { StreamConfig().endpoint },
                model = model,
                chunkDurationMs = chunkDurationMs,
                windowDurationMs = windowDurationMs,
                overlapMs = overlapMs,
                resetIntervalMs = resetIntervalMs,
                useVad = useVad,
                vadThreshold = vadThreshold,
                privacySensitive = privacySensitive,
                consentToUpload = consentToUpload
            )

            val localTranscriber = if (whisperModelPath.isNotBlank()) {
                LocalStreamingTranscriber(WhisperConfig(whisperModelPath, language, numThreads))
            } else null

        val cloudTranscriber: StreamingTranscriber? = if (apiKey.isNotBlank()) {
            if (model.contains("gemini") || endpoint.contains("googleapis.com")) {
                val liveModel = if (model.isBlank() || model == "gpt-live-transcribe" || model.contains("gemini-3.5")) {
                    GeminiTranscriptionProvider.MODEL_TRANSCRIBE_LIVE
                } else {
                    model
                }
                GeminiTranscriptionProvider(
                    rawModel = liveModel,
                    baseUrl = endpoint.takeIf { it.isNotBlank() },
                    allowCustomEndpoint = allowCustomEndpoint
                )
            } else if (endpoint.startsWith("ws://") || endpoint.startsWith("wss://")) {
                CloudStreamingTranscriber()
            } else {
                val resolvedEndpoint = endpoint.takeIf { it.isNotBlank() && !it.startsWith("ws") } ?: when {
                    model.contains("whisper-large") || apiKey.startsWith("gsk_") ->
                        "https://api.groq.com/openai/v1/audio/transcriptions"
                    else ->
                        "https://api.openai.com/v1/audio/transcriptions"
                }
                WhisperRestTranscriptionProvider(
                    apiKey = apiKey,
                    model = model.ifBlank { "whisper-1" },
                    endpoint = resolvedEndpoint
                )
            }
        } else null

            return StreamingPipeline(localTranscriber, cloudTranscriber, streamConfig)
        }
    }

    fun setCallback(callback: StreamingTranscriptionCallback) {
        this.callback = callback
    }

    /**
     * Start streaming transcription using the configuration accumulated by [Builder].
     * @param mode "local" for whisper.cpp, "cloud" for OpenAI WebSocket
     */
    fun start(mode: String) {
        start(mode, defaultStreamConfig)
    }

    /**
     * Start streaming transcription.
     * @param mode "local" for whisper.cpp, "cloud" for OpenAI WebSocket
     * @param config stream configuration; blank credential/model fields fall back to the
     *   values supplied to [Builder]
     */
    fun start(mode: String, config: StreamConfig) {
        if (isRecording.get()) return
        val resolved = reconcile(config)
        // CodeRabbit review on #143: reconcile() can swap in a start-time
        // endpoint override after build()-time validation. Re-validate here
        // so http:///ws:// overrides are rejected before any key is sent.
        try {
            validateEndpointScheme(resolved.endpoint)
        } catch (e: IllegalArgumentException) {
            callback?.onError(VelaError(e.message ?: "Insecure transcription endpoint rejected"))
            return
        }
        this.streamConfig = resolved
        this.committedText = ""
        this.currentSegmentStart = 0
        this.lastCommitTime = System.currentTimeMillis()
        this.lastActivityTime = System.currentTimeMillis()
        this.uploadRefusalReported.set(false)

        // Cloud-upload contract (StreamConfig KDoc): explicit consent only, never
        // for privacy-sensitive sessions. Enforced before any transcriber starts.
        val uploadAllowed = resolved.allowsCloudUpload()
        this.sessionUploadAllowed = uploadAllowed
        var sessionLocal = mode == "local"
        if (!sessionLocal && !uploadAllowed) {
            if (localTranscriber == null) {
                callback?.onError(VelaError(
                    "Cloud upload blocked: requires consentToUpload=true and privacySensitive=false"
                ))
                return
            }
            Log.w("StreamingPipeline", "Cloud upload not permitted for this session; forcing local mode")
            sessionLocal = true
        }
        this.isSessionLocal = sessionLocal

        // Start the appropriate transcriber
        val transcriber = if (isSessionLocal) localTranscriber else cloudTranscriber
        if (transcriber == null) {
            callback?.onError(VelaError("No transcriber available for mode: $mode"))
            return
        }

        transcriber.setCallback(createTranscriberCallback())
        transcriber.start(resolved)

        // Start audio capture
        isRecording.set(true)
        if (!startAudioCapture(transcriber)) {
            isRecording.set(false)
            transcriber.stop()
            return
        }

        Log.d("StreamingPipeline", "Started in $mode mode")
    }

    internal fun reconcile(config: StreamConfig): StreamConfig {
        val d = defaultStreamConfig
        return config.copy(
            modelPath = config.modelPath.ifBlank { d.modelPath },
            language = config.language.ifBlank { d.language },
            numThreads = if (config.numThreads > 0) config.numThreads else d.numThreads,
            apiKey = config.apiKey.ifBlank { d.apiKey },
            endpoint = config.endpoint.ifBlank { d.endpoint },
            model = config.model.ifBlank { d.model }
        )
    }

    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)

        // Stop audio capture
        stopAudioCapture()

        // Final cleanup: commit remaining partials
        commitRemaining()

        // Stop transcriber
        val transcriber = if (isSessionLocal) localTranscriber else cloudTranscriber
        transcriber?.stop()

        Log.d("StreamingPipeline", "Stopped")
    }

    fun release() {
        stop()
        localTranscriber?.release()
        cloudTranscriber?.release()
    }

    private fun startAudioCapture(transcriber: StreamingTranscriber): Boolean {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback?.onError(VelaError("Microphone initialization failed"))
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()

            val record = audioRecord ?: return false

            recordingThread = Thread({
                val buffer = ShortArray(BUFFER_SIZE / 2)
                val byteBuffer = ByteArray(BUFFER_SIZE)
                var lastVadCheck = System.currentTimeMillis()

                while (isRecording.get()) {
                    val readResult = record.read(buffer, 0, buffer.size)
                    if (readResult > 0) {
                        var sumSquares = 0.0
                        for (i in 0 until readResult) {
                            val shortVal = buffer[i]
                            sumSquares += shortVal * shortVal
                            byteBuffer[i * 2] = (shortVal.toInt() and 0xff).toByte()
                            byteBuffer[i * 2 + 1] = ((shortVal.toInt() shr 8) and 0xff).toByte()
                        }

                        val audioBytes = byteBuffer.copyOfRange(0, readResult * 2)

                        // Emit to transcriber (upload-gated)
                        dispatchEmit(transcriber, audioBytes)

                        // Amplitude callback
                        val rms = sqrt(sumSquares / readResult)
                        val normalized = (rms / 32768.0).toFloat()
                        callback?.onAmplitude(normalized)

                        // VAD-based commit boundary detection
                        val now = System.currentTimeMillis()
                        if (now - lastVadCheck > 100) {
                            checkCommitBoundary(normalized, now)
                            lastVadCheck = now
                        }

                        // Update activity time if above threshold
                        if (normalized > (streamConfig.vadThreshold)) {
                            lastActivityTime = now
                        }
                    }
                }
            }, "StreamingAudioCapture")
            recordingThread?.start()
            return true

        } catch (e: SecurityException) {
            callback?.onError(VelaError("Mic permission denied"))
        } catch (e: Exception) {
            callback?.onError(VelaError("Audio capture error: ${e.message}"))
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e("StreamingPipeline", "Error releasing AudioRecord", e)
        }
        audioRecord = null
        return false
    }

    /**
     * Sole path from the audio thread to a transcriber. Refuses to feed the
     * cloud transcriber when the session did not pass the upload contract,
     * so a future refactor cannot bypass the gate set in [start].
     */
    internal fun dispatchEmit(transcriber: StreamingTranscriber, audioChunk: ByteArray) {
        if (transcriber === cloudTranscriber && !sessionUploadAllowed) {
            if (uploadRefusalReported.compareAndSet(false, true)) {
                callback?.onError(VelaError(
                    "Cloud upload refused: requires consentToUpload=true and privacySensitive=false"
                ))
            }
            return
        }
        transcriber.emit(audioChunk)
    }

    private fun stopAudioCapture() {
        try {
            recordingThread?.join(2000)
            recordingThread = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e("StreamingPipeline", "Error stopping audio", e)
        }
    }

    internal fun checkCommitBoundary(amplitude: Float, now: Long) {
        val timeSinceLastCommit = now - lastCommitTime
        val timeSinceLastActivity = now - lastActivityTime

        // Commit if: VAD pause detected OR max segment time reached
        val shouldCommit = (timeSinceLastActivity > VAD_PAUSE_MS && amplitude < streamConfig.vadThreshold) ||
                timeSinceLastCommit > MAX_SEGMENT_MS

        if (!shouldCommit) return

        val snapshot = committedText
        val start = currentSegmentStart
        if (start >= snapshot.length) {
            lastCommitTime = now
            return
        }

        val segmentText = snapshot.substring(start)
        if (segmentText.isNotBlank()) {
            callback?.onRevisionMarker(
                RevisionMarker(
                    type = "commit",
                    text = segmentText.trim(),
                    range = start until snapshot.length
                )
            )
        }
        currentSegmentStart = snapshot.length
        lastCommitTime = now
    }

    private fun commitRemaining() {
        val snapshot = committedText
        val start = currentSegmentStart
        if (start >= snapshot.length) return
        val remaining = snapshot.substring(start).trim()
        if (remaining.isNotEmpty()) {
            callback?.onRevisionMarker(
                RevisionMarker(
                    type = "commit",
                    text = remaining,
                    range = start until snapshot.length
                )
            )
        }
        currentSegmentStart = snapshot.length
    }

    internal fun createTranscriberCallback(): StreamingTranscriptionCallback {
        return object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {
                when (marker.type) {
                    "partial" -> {
                        // Append to committed text buffer
                        synchronized(stateLock) {
                            committedText += marker.text + " "
                        }
                        callback?.onRevisionMarker(marker)
                    }
                    "commit" -> {
                        callback?.onRevisionMarker(marker)
                    }
                }
            }

            override fun onFinal(text: String) {
                callback?.onFinal(text)
            }

            override fun onError(error: VelaException) {
                callback?.onError(error)
            }

            override fun onAmplitude(normalized: Float) {
                // Amplitude handled by audio capture thread
            }
        }
    }
}
