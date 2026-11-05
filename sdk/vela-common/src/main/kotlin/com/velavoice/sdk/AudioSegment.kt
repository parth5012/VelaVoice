package com.velavoice.sdk

/**
 * A chunk of PCM audio handed to a [TranscriptionEngine].
 *
 * [pcm] is 16-bit little-endian PCM at [sampleRate] Hz, mono.
 * Ticket 801 (map #290).
 */
data class AudioSegment(
    val pcm: ByteArray,
    val sampleRate: Int = 16000
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioSegment) return false
        return sampleRate == other.sampleRate && pcm.contentEquals(other.pcm)
    }

    override fun hashCode(): Int = 31 * pcm.contentHashCode() + sampleRate
}
