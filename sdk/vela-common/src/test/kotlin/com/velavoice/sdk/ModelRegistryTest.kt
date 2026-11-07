package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun `find returns definition for known id`() {
        val def = ModelRegistry.find("whisper-tiny-en")
        assertNotNull(def)
        assertEquals("whisper-tiny-en", def!!.id)
        assertEquals(EngineKind.WHISPER, def.engine)
    }

    @Test
    fun `find returns null for unknown id`() {
        assertNull(ModelRegistry.find("does-not-exist"))
    }

    @Test
    fun `byEngine filters by engine kind`() {
        val whisper = ModelRegistry.byEngine(EngineKind.WHISPER)
        val sherpa = ModelRegistry.byEngine(EngineKind.SHERPA)
        assertTrue(whisper.isNotEmpty())
        assertTrue(sherpa.isNotEmpty())
        assertTrue(whisper.all { it.engine == EngineKind.WHISPER })
        assertTrue(sherpa.all { it.engine == EngineKind.SHERPA })
    }

    @Test
    fun `whisper-tiny-en has pinned hash and streaming false`() {
        val def = ModelRegistry.find("whisper-tiny-en")!!
        assertEquals("921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f", def.expectedSha256)
        assertFalse(def.supportsStreaming)
    }

    @Test
    fun `registry contains first-batch whisper rows`() {
        val ids = ModelRegistry.ALL.map { it.id }
        assertTrue(ids.contains("whisper-tiny-en"))
        assertTrue(ids.contains("whisper-tiny-en-q5_0"))
        assertTrue(ids.contains("whisper-base-en"))
        assertTrue(ids.contains("whisper-small-en"))
    }

    @Test
    fun `registry contains sherpa rows`() {
        val ids = ModelRegistry.ALL.map { it.id }
        assertTrue(ids.contains("fluid-speech-mini"))
        assertTrue(ids.contains("parakeet-tdt-v3"))
        assertTrue(ids.contains("moonshine-tiny"))
    }

    @Test
    fun `streamingCapable filters by flag`() {
        // Ticket 803 Phase B: exactly one streaming row so far — the streamed
        // Nemotron-3.5-ASR-Streaming-0.6b transducer (supportsStreaming=true).
        val ids = ModelRegistry.streamingCapable().map { it.id }
        assertEquals(listOf("nemotron-streaming-en"), ids)
    }
}