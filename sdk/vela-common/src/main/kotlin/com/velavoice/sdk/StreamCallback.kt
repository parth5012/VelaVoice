package com.velavoice.sdk

/**
 * Callback for streaming transcription events emitted by a [TranscriptionEngine].
 *
 * Mirrors the existing `StreamingTranscriber` contract (`onPartial` / `onFinal` /
 * `onError`) so `LocalStreamingTranscriber` dedup/VAD bookkeeping stays reusable.
 * Ticket 801 (map #290).
 */
interface StreamCallback {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(error: Throwable)
}

/**
 * Handle returned by [TranscriptionEngine.openStream]. Closing it cancels the
 * stream. Ticket 801 (map #290).
 */
interface StreamHandle : AutoCloseable {
    fun cancel()
}
