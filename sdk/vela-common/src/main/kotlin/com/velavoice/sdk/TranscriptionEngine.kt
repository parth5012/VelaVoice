package com.velavoice.sdk

/**
 * Streaming-capable transcription engine abstraction.
 *
 * Ticket 801 (map #290): replaces the hard-coded whisper-only local path.
 * Engines are resolved via [ModelRegistry] + [EngineKind] instead of being
 * hard-coded. Cloud routing (Groq/Gemini/OpenAI) is unaffected.
 *
 * Implementations: `WhisperEngine` (vela-whisper), `SherpaEngine` (vela-sherpa,
 * lands in ticket #802).
 */
interface TranscriptionEngine : AutoCloseable {
    val engine: EngineKind
    val supportsStreaming: Boolean

    fun transcribe(audio: AudioSegment, lang: String? = null): TranscriptionResult

    /**
     * Opens a streaming session. Throws [UnsupportedOperationException] when
     * [supportsStreaming] is false.
     */
    fun openStream(lang: String?, callback: StreamCallback): StreamHandle

    fun cancel()
}
