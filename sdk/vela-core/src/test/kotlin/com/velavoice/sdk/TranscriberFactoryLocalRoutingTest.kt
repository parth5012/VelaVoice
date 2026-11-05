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
}