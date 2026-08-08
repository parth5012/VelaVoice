package com.velavoice.sdk

/**
 * A revision marker emitted by the streaming pipeline.
 * - [type]: "partial" for revisable text, "commit" for finalized text
 * - [text]: the transcription text for this segment
 * - [range]: the character range within the full output that this text occupies
 */
data class RevisionMarker(
    val type: String,  // "partial" or "commit"
    val text: String,
    val range: IntRange
)

/**
 * Callback for streaming transcription events.
 */
interface StreamingTranscriptionCallback {
    /** Emitted for each revision marker (partial or commit) */
    fun onRevisionMarker(marker: RevisionMarker)
    /** Emitted when the final transcription is complete */
    fun onFinal(text: String)
    /** Emitted on error */
    fun onError(error: VelaException)
    /** Emitted periodically with RMS amplitude (0.0 - 1.0) for waveform display */
    fun onAmplitude(normalized: Float)
}

/**
 * Common interface for streaming transcription backends.
 * Implementations handle either local (whisper.cpp) or cloud (OpenAI WebSocket) paths.
 */
interface StreamingTranscriber {
    /** Start a streaming session with the given configuration */
    fun start(config: StreamConfig)
    /** Emit a chunk of PCM audio data (16-bit, 16kHz, mono) */
    fun emit(audioChunk: ByteArray)
    /** Stop the streaming session and finalize transcription */
    fun stop()
    /** Set the callback for revision markers and events */
    fun setCallback(callback: StreamingTranscriptionCallback)
    /** Release resources */
    fun release()
}

/**
 * Configuration for a streaming transcription session.
 */
data class StreamConfig(
    val modelPath: String = "",
    val language: String = "en",
    val numThreads: Int = 4,
    val apiKey: String = "",
    val endpoint: String = "wss://api.openai.com/v1/realtime/transcription_sessions",
    val model: String = "gpt-live-transcribe",
    val chunkDurationMs: Int = 3000,
    val windowDurationMs: Int = 15000,
    val overlapMs: Int = 1500,
    val resetIntervalMs: Long = 30000,
    val useVad: Boolean = true,
    val vadThreshold: Float = 0.02f
)
