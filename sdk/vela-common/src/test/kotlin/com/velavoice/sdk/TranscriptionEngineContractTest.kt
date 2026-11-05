package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscriptionEngineContractTest {

    private class StubEngine(override val supportsStreaming: Boolean) : TranscriptionEngine {
        override val engine: EngineKind = EngineKind.WHISPER
        override fun transcribe(audio: AudioSegment, lang: String?): TranscriptionResult =
            TranscriptionResult("raw", "clean", 0L)
        override fun openStream(lang: String?, callback: StreamCallback): StreamHandle {
            if (!supportsStreaming) throw UnsupportedOperationException("streaming not supported")
            return object : StreamHandle {
                override fun cancel() {}
                override fun close() {}
            }
        }
        override fun cancel() {}
        override fun close() {}
    }

    @Test
    fun `non-streaming engine openStream throws UnsupportedOperationException`() {
        val e = StubEngine(false)
        try {
            e.openStream("en", object : StreamCallback {
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) {}
                override fun onError(error: Throwable) {}
            })
            fail("expected throw")
        } catch (ex: UnsupportedOperationException) {
        }
    }

    @Test
    fun `streaming engine returns handle`() {
        val e = StubEngine(true)
        val h = e.openStream("en", object : StreamCallback {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) {}
            override fun onError(error: Throwable) {}
        })
        assertTrue(h is StreamHandle)
        h.close()
    }

    @Test
    fun `transcribe returns result`() {
        val e = StubEngine(true)
        val r = e.transcribe(AudioSegment("x".toByteArray()))
        assertEquals("raw", r.rawTranscript)
    }
}