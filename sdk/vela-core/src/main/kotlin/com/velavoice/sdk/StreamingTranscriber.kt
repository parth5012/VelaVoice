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
 *
 * **Cloud upload contract (map #72 ticket #77):** raw audio may only leave the
 * device when [consentToUpload] is `true` AND [privacySensitive] is `false`.
 * Both [StreamingPipeline] and [VelaTranscriber.startStreaming] enforce this
 * before any transcriber is started or any chunk is emitted: a cloud request
 * that fails the check is refused (error) or force-falls-back to the local
 * model when one is available. Cloud upload therefore requires explicit
 * opt-in via [consentToUpload]; there is no implicit consent.
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
    val vadThreshold: Float = 0.02f,
    /** True when the current session is dictating into a privacy-sensitive editor. */
    val privacySensitive: Boolean = false,
    /** Explicit opt-in required before any audio may be uploaded to a cloud API. */
    val consentToUpload: Boolean = false
) {
    /** Single predicate for the cloud-upload contract documented on [StreamConfig]. */
    fun allowsCloudUpload(): Boolean = consentToUpload && !privacySensitive
}
