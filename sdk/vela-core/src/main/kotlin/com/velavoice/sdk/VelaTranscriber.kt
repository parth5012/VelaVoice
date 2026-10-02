package com.velavoice.sdk

import android.content.Context
import com.velavoice.sdk.cleaner.CleanerConfig
import com.velavoice.sdk.cleaner.DictionaryKeywords
import com.velavoice.sdk.cleaner.PersonalDictionary
import com.velavoice.sdk.cleaner.TextCleaner
import com.velavoice.sdk.whisper.WhisperConfig
import com.velavoice.sdk.whisper.WhisperEngine


/**
 * Runtime context for a single Scribe rewrite call (Ticket 003 / Ticket 004).
 * The IME supplies surrounding editor text and app metadata; these are injected into
 * the LLM prompt by [TextCleaner].
 *
 * [privacySensitive] must be set by the IME when the active editor is a password/PII field
 * (Ticket 004): Scribe and LLM cleanup are then force-disabled for this call and only
 * local rule-based cleanup runs.
 */
data class ScribeInput(
    val contextBefore: String? = null,
    val contextAfter: String? = null,
    val appName: String? = null,
    val inputType: String? = null,
    val overrideStyle: String? = null,
    val privacySensitive: Boolean = false
)

class VelaTranscriber private constructor(
    private val whisperEngine: WhisperEngine,
    private val textCleaner: TextCleaner?,
    private val audioRecorder: AudioRecorder,
    private val dictionaryKeywords: DictionaryKeywords? = null
) {
    class Builder(private val context: Context) {
        private var whisperModelPath: String? = null
        private var language: String = "en"
        private var threads: Int = 4
        private var useLlmCleaner: Boolean = false
        private var llmModelPath: String? = null
        private var personalDictionary: PersonalDictionary? = null
        private var customFillers: List<String>? = null
        private var dictionaryKeywords: DictionaryKeywords? = null
        private var scribeEnabled: Boolean = false
        private var defaultScribeStyle: String = "Professional"
        private var customSystemPrompt: String? = null

        fun whisperModel(path: String) = apply { this.whisperModelPath = path }
        fun language(lang: String) = apply { this.language = lang }
        fun threads(n: Int) = apply { this.threads = n }
        fun useLlmCleaner(enable: Boolean, modelPath: String? = null) = apply {
            this.useLlmCleaner = enable
            this.llmModelPath = modelPath
        }
        fun personalDictionary(dict: PersonalDictionary) = apply { this.personalDictionary = dict }
        fun customFillers(fillers: List<String>) = apply { this.customFillers = fillers }
        fun dictionaryKeywords(keywords: DictionaryKeywords) = apply { this.dictionaryKeywords = keywords }

        /** Enable Scribe (intent-based rewrite) with the given default style. */
        fun scribe(enable: Boolean, defaultStyle: String = "Professional", customSystemPrompt: String? = null) = apply {
            this.scribeEnabled = enable
            this.defaultScribeStyle = defaultStyle
            this.customSystemPrompt = customSystemPrompt
        }

        fun build(): VelaTranscriber {
            val modelPath = whisperModelPath ?: throw IllegalStateException("whisperModel() required")
            val whisperConfig = WhisperConfig(modelPath, language, threads)
            val engine = WhisperEngine(whisperConfig)
            val cleaner = if (personalDictionary != null || customFillers != null || useLlmCleaner || dictionaryKeywords != null || scribeEnabled) {
                TextCleaner(
                    CleanerConfig(
                        useLlm = useLlmCleaner,
                        llmModelPath = llmModelPath,
                        personalDictionary = personalDictionary,
                        customFillers = customFillers,
                        dictionaryKeywords = dictionaryKeywords,
                        scribeEnabled = scribeEnabled,
                        defaultScribeStyle = defaultScribeStyle,
                        customSystemPrompt = customSystemPrompt
                    )
                )
            } else {
                null
            }
            val recorder = AudioRecorder()
            return VelaTranscriber(engine, cleaner, recorder, dictionaryKeywords)
        }
    }

    /** Transcribe pre-recorded PCM 16-bit 16kHz mono audio bytes */
    fun transcribe(audioBytes: ByteArray): TranscriptionResult = transcribe(audioBytes, ScribeInput())

    /**
     * Transcribe pre-recorded PCM audio with optional Scribe context. When Scribe is enabled
     * in the cleaner config, [ScribeInput] fields are injected into the rewrite prompt.
     */
    fun transcribe(audioBytes: ByteArray, scribeInput: ScribeInput): TranscriptionResult {
        val raw = whisperEngine.transcribe(audioBytes, buildInitialPrompt())
        val cleaned = textCleaner?.clean(
            raw,
            contextBefore = scribeInput.contextBefore,
            contextAfter = scribeInput.contextAfter,
            appName = scribeInput.appName,
            inputType = scribeInput.inputType,
            overrideStyle = scribeInput.overrideStyle,
            privacySensitive = scribeInput.privacySensitive
        ) ?: raw
        val durationMs = ((audioBytes.size / 2) / 16L) // 16 samples/ms
        return TranscriptionResult(raw, cleaned, durationMs)
    }

        private fun buildInitialPrompt(): String? =
        dictionaryKeywords?.getKeywords()?.takeIf { it.isNotEmpty() }?.joinToString(", ")

    /** Start recording and transcribe live */
    fun startRecording(callback: VelaRecordingCallback) {
        audioRecorder.start(whisperEngine, textCleaner, callback, ScribeInput(), buildInitialPrompt())
    }

    /** Start recording with Scribe context (surrounding text / app metadata from the IME) */
    fun startRecording(callback: VelaRecordingCallback, scribeInput: ScribeInput) {
        audioRecorder.start(whisperEngine, textCleaner, callback, scribeInput, buildInitialPrompt())
    }

    /** Stop recording and commit transcription */
    fun stopRecording(clean: Boolean = true) {
        audioRecorder.stop(clean)
    }

    /** Cancel recording and discard audio without transcribing */
    fun cancelRecording() {
        audioRecorder.cancel()
    }

    /**
     * Start streaming transcription.
     *
     * **Cloud upload requires explicit opt-in** (map #72 ticket #77): `mode =
     * "cloud"` is refused unless [StreamConfig.consentToUpload] is `true` and
     * [StreamConfig.privacySensitive] is `false`. See [StreamConfig.allowsCloudUpload].
     *
     * @param mode "local" for whisper.cpp, "cloud" for OpenAI WebSocket
     * @param callback receives revision markers and final results
     * @param config optional stream configuration overrides
     */
    fun startStreaming(
        mode: String,
        callback: StreamingTranscriptionCallback,
        config: StreamConfig = StreamConfig()
    ) {
        val streamConfig = config.copy(
            modelPath = config.modelPath.ifBlank { whisperEngine.let { "" } },
            language = config.language.ifBlank { "en" },
            numThreads = if (config.numThreads > 0) config.numThreads else 4
        )

        if (mode == "cloud" && !streamConfig.allowsCloudUpload()) {
            callback.onError(VelaError(
                "Cloud upload blocked: requires consentToUpload=true and privacySensitive=false"
            ))
            return
        }

        if (mode == "cloud") {
            val cloud = CloudStreamingTranscriber()
            cloud.setCallback(callback)
            cloud.start(streamConfig)
            activeStreamingTranscriber = cloud
        } else {
            // Local mode: use the existing whisper engine via streaming transcriber
            val localConfig = WhisperConfig(
                modelPath = streamConfig.modelPath,
                language = streamConfig.language,
                numThreads = streamConfig.numThreads
            )
            val local = LocalStreamingTranscriber(localConfig)
            local.setCallback(callback)
            local.start(streamConfig)
            activeStreamingTranscriber = local
        }

        // Start audio capture for streaming
        startStreamingAudio(activeStreamingTranscriber!!)
    }

    fun stopStreaming() {
        stopStreamingAudio()
        activeStreamingTranscriber?.stop()
        activeStreamingTranscriber = null
    }

    private var activeStreamingTranscriber: StreamingTranscriber? = null
    private var streamingAudioThread: Thread? = null
    private var streamingAudioRecord: android.media.AudioRecord? = null
    @Volatile
    private var isStreamingAudioActive = false

    private fun startStreamingAudio(transcriber: StreamingTranscriber) {
        isStreamingAudioActive = true
        streamingAudioThread = Thread({
            try {
                val bufferSize = android.media.AudioRecord.getMinBufferSize(
                    16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )
                streamingAudioRecord = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    16000,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (streamingAudioRecord?.state == android.media.AudioRecord.STATE_INITIALIZED) {
                    streamingAudioRecord?.startRecording()
                    val buffer = ShortArray(bufferSize / 2)
                    val byteBuffer = ByteArray(bufferSize)

                    while (isStreamingAudioActive) {
                        val read = streamingAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            for (i in 0 until read) {
                                val sv = buffer[i]
                                byteBuffer[i * 2] = (sv.toInt() and 0xff).toByte()
                                byteBuffer[i * 2 + 1] = ((sv.toInt() shr 8) and 0xff).toByte()
                            }
                            transcriber.emit(byteBuffer.copyOfRange(0, read * 2))
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VelaTranscriber", "Streaming audio error", e)
            }
        }, "VelaStreamingAudio")
        streamingAudioThread?.start()
    }

    private fun stopStreamingAudio() {
        isStreamingAudioActive = false
        try {
            streamingAudioRecord?.stop()
            streamingAudioRecord?.release()
            streamingAudioRecord = null
            streamingAudioThread?.join(2000)
            streamingAudioThread = null
        } catch (e: Exception) {
            android.util.Log.e("VelaTranscriber", "Error stopping streaming audio", e)
        }
    }

    fun release() {
        stopStreaming()
        whisperEngine.free()
        audioRecorder.release()
    }
}
