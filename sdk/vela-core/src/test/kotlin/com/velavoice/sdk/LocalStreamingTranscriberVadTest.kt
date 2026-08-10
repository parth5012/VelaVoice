package com.velavoice.sdk

import com.velavoice.sdk.whisper.WhisperConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStreamingTranscriberVadTest {

    private val transcriber = LocalStreamingTranscriber(WhisperConfig("/dummy/model.bin", "en", 4))

    // --- Area 6: isSilent / RMS VAD ---

    private fun pcmFromShorts(vararg samples: Short): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            bytes[i * 2] = (samples[i].toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xff).toByte()
        }
        return bytes
    }

    private fun constantPcm(count: Int, value: Int): ByteArray =
        pcmFromShorts(*ShortArray(count) { value.toShort() })

    @Test
    fun `isSilent empty array returns true`() {
        assertTrue(transcriber.isSilent(ByteArray(0), 0.02f))
    }

    @Test
    fun `isSilent all-zero PCM is silent`() {
        val pcm = constantPcm(1000, 0)
        assertTrue(transcriber.isSilent(pcm, 0.02f))
    }

    @Test
    fun `isSilent full-scale PCM is not silent`() {
        val pcm = constantPcm(1000, 32767)
        assertFalse(transcriber.isSilent(pcm, 0.02f))
    }

    @Test
    fun `isSilent negative full-scale PCM is not silent`() {
        val pcm = constantPcm(1000, -32768)
        assertFalse(transcriber.isSilent(pcm, 0.02f))
    }

    @Test
    fun `isSilent does not throw at full-scale amplitude (no overflow)`() {
        // The overflow bug: sumSquares as Int overflowed near ±32767.
        // With Double widening, this must compute cleanly and not throw.
        val pcm = constantPcm(4000, 32767)
        // Must not throw
        val result = transcriber.isSilent(pcm, 0.02f)
        assertFalse("Full-scale 32767 PCM must not be silent", result)
    }

    @Test
    fun `isSilent threshold boundary just below`() {
        // RMS of a constant value v = v (since all samples identical)
        // normalized = v / 32768. For threshold 0.02, silent if v < 0.02 * 32768 = 655.36
        // So v=655 is silent
        val v = 655
        val pcm = constantPcm(1000, v)
        assertTrue("RMS 655 should be below threshold 0.02", transcriber.isSilent(pcm, 0.02f))
    }

    @Test
    fun `isSilent threshold boundary just above`() {
        val v = 656
        val pcm = constantPcm(1000, v)
        assertFalse("RMS 656 should be above threshold 0.02", transcriber.isSilent(pcm, 0.02f))
    }

    @Test
    fun `isSilent small amplitude below threshold`() {
        val pcm = constantPcm(1000, 100)
        assertTrue(transcriber.isSilent(pcm, 0.02f))
    }

    @Test
    fun `isSilent odd byte count does not crash`() {
        // audioData with odd size: samples = size/2, last byte ignored
        val pcm = pcmFromShorts(1000, 1000, 1000) + ByteArray(1) { 0x7f }
        // Must not throw
        transcriber.isSilent(pcm, 0.02f)
    }
}
