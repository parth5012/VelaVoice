package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriberFactoryLocalRoutingTest {

    @Test
    fun `resolveLocalModel returns definition for whisper id`() {
        val def = TranscriberFactory.resolveLocalModel("whisper-tiny-en")
        assertNotNull(def)
        assertEquals("whisper-tiny-en", def!!.id)
    }

    @Test
    fun `resolveLocalModel returns null for unknown id`() {
        assertNull(TranscriberFactory.resolveLocalModel("nope"))
    }

    @Test
    fun `localEngineKind whisper for whisper id`() {
        assertEquals(EngineKind.WHISPER, TranscriberFactory.localEngineKind("whisper-tiny-en"))
    }

    @Test
    fun `isWhisperLocalModel true for whisper`() {
        assertTrue(TranscriberFactory.isWhisperLocalModel("whisper-tiny-en"))
    }

    @Test
    fun `isWhisperLocalModel false for sherpa`() {
        assertFalse(TranscriberFactory.isWhisperLocalModel("fluid-speech-mini"))
    }

    // ── Ticket 803: streaming-capable routing ───────────────────

    @Test
    fun `streaming sherpa model routes to SherpaStreamEngine`() {
        val def = TranscriberFactory.resolveLocalModel("nemotron-streaming-en")
        assertNotNull(def)
        assertTrue(def!!.supportsStreaming)
        assertEquals(EngineKind.SHERPA, def.engine)

        val transcriber = TranscriberFactory.createLocalStreamingTranscriber(
            "nemotron-streaming-en", StreamConfig()
        )
        assertNotNull(transcriber)
        assertTrue(transcriber is SherpaStreamEngine)
    }

    @Test
    fun `non-streaming sherpa models route to null streaming transcriber`() {
        assertNull(TranscriberFactory.createLocalStreamingTranscriber("fluid-speech-mini", StreamConfig()))
        assertNull(TranscriberFactory.createLocalStreamingTranscriber("parakeet-tdt-v3", StreamConfig()))
        assertNull(TranscriberFactory.createLocalStreamingTranscriber("moonshine-tiny", StreamConfig()))
    }

    @Test
    fun `streamingCapable lists the sherpa streaming model`() {
        val ids = ModelRegistry.streamingCapable(EngineKind.SHERPA).map { it.id }
        assertTrue("nemotron-streaming-en" in ids)
    }
}