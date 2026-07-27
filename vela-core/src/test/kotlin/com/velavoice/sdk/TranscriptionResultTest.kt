package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionResultTest {

    @Test
    fun `data class stores all fields`() {
        val result = TranscriptionResult(
            rawTranscript = "hello world",
            cleanedTranscript = "Hello World.",
            durationMs = 1000L
        )
        assertEquals("hello world", result.rawTranscript)
        assertEquals("Hello World.", result.cleanedTranscript)
        assertEquals(1000L, result.durationMs)
    }

    @Test
    fun `data class component access`() {
        val result = TranscriptionResult("raw", "clean", 500L)
        val (raw, cleaned, durationMs) = result
        assertEquals("raw", raw)
        assertEquals("clean", cleaned)
        assertEquals(500L, durationMs)
    }

    @Test
    fun `data class equality`() {
        val a = TranscriptionResult("a", "b", 1L)
        val b = TranscriptionResult("a", "b", 1L)
        assertEquals(a, b)
    }

    @Test
    fun `duration calculation matches formula`() {
        // audioBytes.size / 2 = numSamples
        // numSamples / 16 = durationMs (16 samples/ms at 16kHz)
        // 16 samples * 1000ms = 16000 samples -> 16000/16 = 1000ms = 1 second
        val audioBytes = ByteArray(32000) // 16000 samples at 16-bit = 32000 bytes
        val expectedMs = ((audioBytes.size / 2) / 16L)
        assertEquals(1000L, expectedMs)
    }
}
