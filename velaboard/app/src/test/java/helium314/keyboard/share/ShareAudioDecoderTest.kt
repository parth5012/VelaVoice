// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.share

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import org.robolectric.Shadows
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareAudioDecoderTest {

    private fun littleEndian(vararg shorts: Short): ByteArray {
        val buf = java.nio.ByteBuffer.allocate(shorts.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) buf.putShort(s)
        return buf.array()
    }

    /** Minimal WAV writer: canonical fmt chunk payload followed by any raw chunk layouts. */
    private fun wav(fmtPayload: ByteArray, data: ByteArray, extraChunks: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0).array())
        out.write("WAVE".toByteArray())
        for ((id, payload) in extraChunks) {
            out.write(id.toByteArray())
            out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array())
            out.write(payload)
            if (payload.size % 2 == 1) out.write(0)
        }
        out.write("fmt ".toByteArray())
        out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(fmtPayload.size).array())
        out.write(fmtPayload)
        if (fmtPayload.size % 2 == 1) out.write(0)
        out.write("data".toByteArray())
        out.write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
        out.write(data)
        return out.toByteArray()
    }

    private fun fmtChunk(audioFormat: Int, channels: Int, sampleRate: Int, bits: Int): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        return java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putShort(audioFormat.toShort())
            .putShort(channels.toShort())
            .putInt(sampleRate)
            .putInt(byteRate)
            .putShort(blockAlign.toShort())
            .putShort(bits.toShort())
            .array()
    }

    @Test fun canonicalWavAt16kMonoStripsHeaderAndPassesPcmThrough() {
        val payload = littleEndian(100, -200, 300, -400)
        val bytes = wav(fmtChunk(1, 1, 16000, 16), payload)
        assertEquals(payload.toList(), ShareAudioDecoder.decodeWav(bytes).toList())
        assertEquals(8, ShareAudioDecoder.decodeWav(bytes).size)
    }

    @Test fun chunksBeforeFmtAndDataAreSkipped() {
        val payload = littleEndian(5, 6, 7)
        val bytes = wav(fmtChunk(1, 1, 16000, 16), payload, extraChunks = listOf("LIST" to "INFOjunk!".toByteArray()))
        assertEquals(payload.toList(), ShareAudioDecoder.decodeWav(bytes).toList())
    }

    @Test fun stereo44k1IsDownmixedDownsampledTo16kMono() {
        // constant samples: DC passes the anti-alias filter (gain exactly 1),
        // and downmixing + resampling a constant yields the constant.
        val frames = 4410 // 0.1 s at 44.1 kHz
        val interleaved = ShortArray(frames * 2) { if (it % 2 == 0) 1000 else -500 }
        val bytes = wav(fmtChunk(1, 2, 44100, 16), littleEndian(*interleaved))
        val out = ShareAudioDecoder.decodeWav(bytes)
        assertEquals(1600 * 2, out.size) // 1600 mono frames at 16 kHz = 3200 bytes
        // the low-pass boots up from silence; DC converges (gain exactly 1) within
        // a few dozen samples, so check everything after the transient
        for (frame in 64 until 1600) {
            val lo = out[frame * 2].toInt() and 0xFF
            val hi = out[frame * 2 + 1].toInt()
            assertEquals(250, (hi shl 8) or lo, "frame $frame")
        }
    }

    @Test fun stereo32kIsDownmixedDownsampledTo16kMono() {
        // constant samples: DC passes the anti-alias filter (gain exactly 1),
        // and downmixing + resampling a constant yields the constant — sampled
        // far enough in that the filter's start transient has settled.
        val frames = 1000
        val interleaved = ShortArray(frames * 2) { if (it % 2 == 0) 200 else 100 }
        val bytes = wav(fmtChunk(1, 2, 32000, 16), littleEndian(*interleaved))
        val out = ShareAudioDecoder.decodeWav(bytes)
        assertEquals(frames * 16000 / 32000 * 2, out.size)
        for (frame in 64 until frames * 16000 / 32000) {
            val lo = out[frame * 2].toInt() and 0xFF
            val hi = out[frame * 2 + 1].toInt()
            assertEquals(150, (hi shl 8) or lo, "frame $frame")
        }
    }

    @Test fun mono8kSamplingUpsampledDoublyTo16k() {
        val payload = littleEndian(10, 20, 30, 40)
        val bytes = wav(fmtChunk(1, 1, 8000, 16), payload)
        val out = ShareAudioDecoder.decodeWav(bytes)
        assertEquals(16, out.size) // 8 shorts at 16 kHz = 16 bytes
        val outShorts = out.toList().chunked(2).map { ((it[1].toInt() shl 8) or (it[0].toInt() and 0xFF)).toShort() }
        assertEquals(listOf<Short>(10, 15, 20, 25, 30, 35, 40, 40), outShorts)
    }

    @Test fun mono8bitUnsignedIsCentred() {
        val data = byteArrayOf(128.toByte(), 228.toByte()) // centre 0, +100
        val bytes = wav(fmtChunk(1, 1, 16000, 8), data)
        val out = ShareAudioDecoder.decodeWav(bytes)
        assertEquals(4, out.size)
        // full-scale rescale: (228-128)=100, shifted up to 16-bit lands at 100 * 256
        assertEquals(0, (out[1].toInt() shl 8) or (0xFF and out[0].toInt()))
        assertEquals(25600, (out[3].toInt() shl 8) or (0xFF and out[2].toInt()))
    }

    @Test fun mono24bitIsConverted() {
        // 4,000,000 little-endian 24 bit (0x00 0x09 0x3D): full-scale rescale
        // shr 8 gives 15,625 — without it, ~-48 dBFS samples and louder clip
        val bytes = wav(fmtChunk(1, 1, 16000, 24), byteArrayOf(0x00, 0x09, 0x3D))
        val out = ShareAudioDecoder.decodeWav(bytes)
        assertEquals(15625, (out[1].toInt() shl 8) or (out[0].toInt() and 0xFF))
    }

    @Test fun mono32bitIntIsConverted() {
        // 16,777,216 little-endian 32 bit (0x00 0x00 0x00 0x01): full-scale
        // rescale shr 16 gives 256 — without it 32-bit int WAV hard-clips
        val bytes = wav(fmtChunk(1, 1, 16000, 32), byteArrayOf(0x00, 0x00, 0x00, 0x01))
        val out = ShareAudioDecoder.decodeWav(bytes)
        assertEquals(256, (out[1].toInt() shl 8) or (out[0].toInt() and 0xFF))
    }

    @Test fun float32WavIsConverted() {
        val buf = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(0.5f)
        val bytes = wav(fmtChunk(3, 1, 16000, 32), buf.array())
        val out = ShareAudioDecoder.decodeWav(bytes)
        assertEquals(16384, (out[1].toInt() shl 8) or (out[0].toInt() and 0xFF)) // 0.5 * 32767 rounds to 16384
    }

    @Test fun unsupportedWavEncodingFailsWithClearMessage() {
        val bytes = wav(fmtChunk(6, 1, 16000, 4), byteArrayOf(0))
        val e = assertFailsWith<IllegalArgumentException> { ShareAudioDecoder.decodeWav(bytes) }
        assertTrue(e.message!!.contains("Unsupported WAV"), e.message)
    }

    @Test fun truncatedOrNotWavHeaderFails() {
        assertFailsWith<IllegalArgumentException> { ShareAudioDecoder.decodeWav(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { ShareAudioDecoder.decodeWav("RIFFxxxx".toByteArray() + "WAVE".toByteArray()) }
    }

    @Test fun nonWavBytesAreRoutedToTheCompressedDecoder() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://x/audio.mp3")
        val audioBytes = byteArrayOf(1, 2, 3, 4)
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(audioBytes))
        val original = ShareAudioDecoder.compressedDecoder
        var seamCallCount = 0
        try {
            ShareAudioDecoder.compressedDecoder = { calledContext, calledUri ->
                seamCallCount++
                assertEquals(context, calledContext)
                assertEquals(uri, calledUri)
                littleEndian(7, 8)
            }
            assertEquals(littleEndian(7, 8).toList(), ShareAudioDecoder.decodeTo16kMono(context, uri).toList())
            assertEquals(1, seamCallCount)
        } finally {
            ShareAudioDecoder.compressedDecoder = original
        }
    }

    @Test fun compressedDecoderFailureSurfacesInsteadOfProducingGarbage() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.parse("content://x/bogus.mp3")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
        val original = ShareAudioDecoder.compressedDecoder
        try {
            ShareAudioDecoder.compressedDecoder = { _, _ -> throw IllegalStateException("decode failed") }
            val e = assertFailsWith<IllegalStateException> { ShareAudioDecoder.decodeTo16kMono(context, uri) }
            assertEquals("decode failed", e.message)
        } finally {
            ShareAudioDecoder.compressedDecoder = original
        }
    }
}
