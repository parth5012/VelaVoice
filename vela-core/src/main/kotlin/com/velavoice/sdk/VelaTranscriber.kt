package com.velavoice.sdk

import android.content.Context
import com.velavoice.sdk.cleaner.CleanerConfig
import com.velavoice.sdk.cleaner.PersonalDictionary
import com.velavoice.sdk.cleaner.TextCleaner
import com.velavoice.sdk.whisper.WhisperConfig
import com.velavoice.sdk.whisper.WhisperEngine

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

        fun whisperModel(path: String) = apply { this.whisperModelPath = path }
        fun language(lang: String) = apply { this.language = lang }
        fun threads(n: Int) = apply { this.threads = n }
        fun useLlmCleaner(enable: Boolean, modelPath: String? = null) = apply {
            this.useLlmCleaner = enable
            this.llmModelPath = modelPath
        }
        fun personalDictionary(dict: PersonalDictionary) = apply { this.personalDictionary = dict }
        fun customFillers(fillers: List<String>) = apply { this.customFillers = fillers }

        fun build(): VelaTranscriber {
            val modelPath = whisperModelPath ?: throw IllegalStateException("whisperModel() required")
            val whisperConfig = WhisperConfig(modelPath, language, threads)
            val engine = WhisperEngine(whisperConfig)
            val cleaner = if (personalDictionary != null || customFillers != null || useLlmCleaner) {
                TextCleaner(
                    CleanerConfig(
                        useLlm = useLlmCleaner,
                        llmModelPath = llmModelPath,
                        personalDictionary = personalDictionary,
                        customFillers = customFillers
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
    fun transcribe(audioBytes: ByteArray): TranscriptionResult {
        val raw = whisperEngine.transcribe(audioBytes)
        val cleaned = textCleaner?.clean(raw) ?: raw
        val durationMs = ((audioBytes.size / 2) / 16L) // 16 samples/ms
        return TranscriptionResult(raw, cleaned, durationMs)
    }

    /** Start recording and transcribe live */
    fun startRecording(callback: VelaRecordingCallback) {
        audioRecorder.start(whisperEngine, textCleaner, callback)
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
