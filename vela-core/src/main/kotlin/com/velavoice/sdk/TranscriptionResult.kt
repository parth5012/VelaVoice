package com.velavoice.sdk

import java.util.Arrays

data class TranscriptionResult(
    val rawTranscript: String,
    val cleanedTranscript: String,
    val durationMs: Long,
    val audioBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TranscriptionResult) return false
        return rawTranscript == other.rawTranscript &&
                cleanedTranscript == other.cleanedTranscript &&
                durationMs == other.durationMs &&
                audioBytes.contentEquals(other.audioBytes)
    }

    override fun hashCode(): Int {
        var result = rawTranscript.hashCode()
        result = 31 * result + cleanedTranscript.hashCode()
        result = 31 * result + durationMs.hashCode()
        result = 31 * result + (audioBytes?.contentHashCode() ?: 0)
        return result
    }
}
