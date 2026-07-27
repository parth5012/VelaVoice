package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VelaExceptionTest {

    @Test
    fun `ModelNotFound has correct message`() {
        val ex = ModelNotFound("/path/to/model.bin")
        assertEquals("Model not found: /path/to/model.bin", ex.message)
    }

    @Test
    fun `WhisperError has correct message`() {
        val ex = WhisperError("transcription failed")
        assertEquals("transcription failed", ex.message)
    }

    @Test
    fun `AudioCaptureFailed has correct message`() {
        val ex = AudioCaptureFailed("mic not available")
        assertEquals("mic not available", ex.message)
    }

    @Test
    fun `InvalidAudio has correct message`() {
        val ex = InvalidAudio("unsupported format")
        assertEquals("unsupported format", ex.message)
    }

    @Test
    fun `all exception types are VelaException`() {
        assertTrue(ModelNotFound("") is VelaException)
        assertTrue(WhisperError("") is VelaException)
        assertTrue(AudioCaptureFailed("") is VelaException)
        assertTrue(InvalidAudio("") is VelaException)
    }
}
