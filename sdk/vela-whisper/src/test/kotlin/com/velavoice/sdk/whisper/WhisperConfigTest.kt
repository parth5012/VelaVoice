package com.velavoice.sdk.whisper

import org.junit.Assert.assertEquals
import org.junit.Test

class WhisperConfigTest {

    @Test
    fun `config uses defaults when only modelPath provided`() {
        val config = WhisperConfig("/tmp/model.bin")
        assertEquals("/tmp/model.bin", config.modelPath)
        assertEquals("en", config.language)
        assertEquals(4, config.numThreads)
    }

    @Test
    fun `config accepts custom values`() {
        val config = WhisperConfig("/tmp/model.bin", language = "fr", numThreads = 2)
        assertEquals("/tmp/model.bin", config.modelPath)
        assertEquals("fr", config.language)
        assertEquals(2, config.numThreads)
    }
}
