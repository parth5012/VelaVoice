package com.velavoice.sdk.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun `config clamps numThreads to hardware concurrency capped at 8`() {
        val config = WhisperConfig("/tmp/model.bin", numThreads = 100000)
        val hw = Runtime.getRuntime().availableProcessors()
        val expectedMax = maxOf(1, minOf(8, hw))
        assertEquals(expectedMax, config.numThreads)
        assertTrue(config.numThreads in 1..8)
    }

    @Test
    fun `config clamps numThreads below 1 to 1`() {
        val configZero = WhisperConfig("/tmp/model.bin", numThreads = 0)
        assertEquals(1, configZero.numThreads)

        val configNegative = WhisperConfig("/tmp/model.bin", numThreads = -5)
        assertEquals(1, configNegative.numThreads)
    }

    @Test
    fun `config throws IllegalArgumentException on invalid language`() {
        assertThrows(IllegalArgumentException::class.java) {
            WhisperConfig("/tmp/model.bin", language = "invalid_lang_xyz")
        }
    }

    @Test
    fun `config accepts valid languages including short code full name and auto`() {
        val c1 = WhisperConfig("/tmp/model.bin", language = "en")
        assertEquals("en", c1.language)

        val c2 = WhisperConfig("/tmp/model.bin", language = "english")
        assertEquals("english", c2.language)

        val c3 = WhisperConfig("/tmp/model.bin", language = "auto")
        assertEquals("auto", c3.language)

        val c4 = WhisperConfig("/tmp/model.bin", language = "fr")
        assertEquals("fr", c4.language)
    }
}
