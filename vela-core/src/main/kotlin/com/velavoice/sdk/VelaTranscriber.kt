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
    private val audioRecorder: AudioRecorder
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
            return VelaTranscriber(engine, cleaner, recorder)
        }
    }

    /** Transcribe pre-recorded PCM 16-bit 16kHz mono audio bytes */
    fun transcribe(audioBytes: ByteArray): TranscriptionResult = transcribe(audioBytes, ScribeInput())

    /**
     * Transcribe pre-recorded PCM audio with optional Scribe context. When Scribe is enabled
     * in the cleaner config, [ScribeInput] fields are injected into the rewrite prompt.
     */
    fun transcribe(audioBytes: ByteArray, scribeInput: ScribeInput): TranscriptionResult {
        val raw = whisperEngine.transcribe(audioBytes)
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

    /** Start recording and transcribe live */
    fun startRecording(callback: VelaRecordingCallback) {
        audioRecorder.start(whisperEngine, textCleaner, callback, ScribeInput())
    }

    /** Start recording with Scribe context (surrounding text / app metadata from the IME) */
    fun startRecording(callback: VelaRecordingCallback, scribeInput: ScribeInput) {
        audioRecorder.start(whisperEngine, textCleaner, callback, scribeInput)
    }

    /** Stop recording and commit transcription */
    fun stopRecording(clean: Boolean = true) {
        audioRecorder.stop(clean)
    }

    fun release() {
        whisperEngine.free()
        audioRecorder.release()
    }
}
