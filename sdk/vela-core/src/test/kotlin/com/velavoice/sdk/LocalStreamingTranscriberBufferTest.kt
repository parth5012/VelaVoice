package com.velavoice.sdk

import com.velavoice.sdk.whisper.WhisperConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStreamingTranscriberBufferTest {

    // --- Area 7: Buffer bounding ---

    private fun makeTranscriber(windowSamples: Int, chunkSamples: Int): LocalStreamingTranscriber {
        val t = LocalStreamingTranscriber(WhisperConfig("/dummy/model.bin", "en", 4))
        t.isRunning = true
        t.windowSamples = windowSamples
        t.chunkSamples = chunkSamples
        t.stepSamples = windowSamples
        t.overlapSamples = 0
        return t
    }

    @Test
    fun `emitting far more than window does not grow buffer unbounded`() {
        // Window = 1 second of mono 16-bit 16kHz = 32000 bytes
        val windowSamples = 16000
        val chunkSamples = 1600
        val t = makeTranscriber(windowSamples, chunkSamples)

        val chunk = ByteArray(chunkSamples * 2) // 3200 bytes per chunk
        // Emit 60 seconds of audio = 60 chunks = 192000 bytes, way over 32000 window
        for (i in 0 until 60) {
            t.emit(chunk)
        }

        // Buffer must be capped at window size (with some slack for the chunk-grain trim)
        val maxExpected = windowSamples * 2 + chunk.size
        assertTrue(
            "Buffer grew unbounded: bufferedBytes=${t.bufferedBytes}, maxExpected=$maxExpected",
            t.bufferedBytes <= maxExpected
        )
    }

    @Test
    fun `extractWindow returns at most window size`() {
        val windowSamples = 16000
        val chunkSamples = 1600
        val t = makeTranscriber(windowSamples, chunkSamples)

        val chunk = ByteArray(chunkSamples * 2)
        for (i in 0 until 30) {
            t.emit(chunk)
        }

        val window = t.extractWindow()
        val windowBytes = windowSamples * 2
        assertTrue(
            "extractWindow returned ${window.size} bytes, expected <= $windowBytes",
            window.size <= windowBytes
        )
    }

    @Test
    fun `extractWindow returns empty when buffer below chunk threshold`() {
        val t = makeTranscriber(16000, 1600)
        // Emit less than chunkSamples * 2 bytes
        val tinyChunk = ByteArray(100)
        t.emit(tinyChunk)
        val window = t.extractWindow()
        assertTrue("Should return empty when buffer < chunkSamples*2", window.isEmpty())
    }

    @Test
    fun `buffer stays bounded across many small emissions`() {
        val t = makeTranscriber(8000, 800)
        // 1000 emissions of 800 bytes each = 800000 bytes total
        val chunk = ByteArray(800)
        for (i in 0 until 1000) {
            t.emit(chunk)
        }
        val maxExpected = 8000 * 2 + chunk.size
        assertTrue(
            "Buffer leaked across many emissions: bufferedBytes=${t.bufferedBytes}",
            t.bufferedBytes <= maxExpected
        )
    }

    @Test
    fun `hard pending cap bounds buffer under a synthetic five-minute feed with a pathological window`() {
        // Ticket #104: window-trim alone cannot bound this — the window is
        // configured a hundred times larger than the whole synthetic session,
        // so only the absolute drop-oldest ceiling can. Five minutes of
        // 16 kHz 16-bit mono = 300 s * 32000 B/s = 9.6 MB of pending audio
        // with no consumer draining it.
        val t = makeTranscriber(windowSamples = 50_000_000, chunkSamples = 1600)
        val chunk = ByteArray(32000) // 1 second of PCM per emit
        for (i in 0 until 300) {
            t.emit(chunk)
        }
        assertTrue(
            "pending buffer ${t.bufferedBytes} exceeds hard cap " +
                "${LocalStreamingTranscriber.MAX_PENDING_BYTES} + one chunk",
            t.bufferedBytes <= LocalStreamingTranscriber.MAX_PENDING_BYTES + chunk.size
        )
        // Drop-oldest keeps a usable tail: the newest window is still there.
        assertTrue(t.extractWindow().isNotEmpty())
    }
}
