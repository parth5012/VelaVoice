package com.velavoice.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ticket #102 — StreamingAudioCapturer double-start guard: a second start()
 * while already active must not spawn a second capture thread/second
 * AudioRecord (two readers on the same mic). stop()-then-start() must still
 * work, and every constructed AudioRecord must be released exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowFakeAudioRecord::class])
class StreamingAudioCapturerStartGuardTest {

    private val quietTranscriber = object : StreamingTranscriber {
        override fun emit(audioChunk: ByteArray) {}
        override fun setCallback(callback: StreamingTranscriptionCallback) {}
        override fun start(config: StreamConfig) {}
        override fun stop() {}
        override fun release() {}
    }

    @Before
    fun setUp() {
        ShadowFakeAudioRecord.resetHooks()
    }

    @After
    fun tearDown() {
        ShadowFakeAudioRecord.releaseBlockedReads()
        ShadowFakeAudioRecord.resetHooks()
    }

    @Test
    fun `second start while active does not construct a second AudioRecord`() {
        val capturer = StreamingAudioCapturer()
        ShadowFakeAudioRecord.readResult = 2
        capturer.start(quietTranscriber)
        // give the first worker time to construct AudioRecord #0
        waitUntil { ShadowFakeAudioRecord.constructorCount >= 1 }

        // second start while still active (same session double-toggle)
        capturer.start(quietTranscriber)
        Thread.sleep(200)

        assertEquals(
            "double start() must not allocate a second AudioRecord (ticket #102)",
            1,
            ShadowFakeAudioRecord.constructorCount
        )
        capturer.stop()
    }

    @Test
    fun `stop then start constructs a fresh AudioRecord and releases the old one`() {
        val capturer = StreamingAudioCapturer()
        ShadowFakeAudioRecord.readResult = 2
        capturer.start(quietTranscriber)
        waitUntil { ShadowFakeAudioRecord.constructorCount >= 1 }
        capturer.stop()
        // after stop, old instance must be released
        waitUntil { ShadowFakeAudioRecord.releaseCount >= 1 }

        capturer.start(quietTranscriber)
        waitUntil { ShadowFakeAudioRecord.constructorCount >= 2 }
        Thread.sleep(200)
        capturer.stop()
        waitUntil { ShadowFakeAudioRecord.releaseCount >= 2 }

        assertEquals(
            "stop()-then-start() must construct exactly one AudioRecord per start",
            ShadowFakeAudioRecord.constructorCount,
            ShadowFakeAudioRecord.releaseCount
        )
        assertTrue(ShadowFakeAudioRecord.constructorCount >= 2)
    }

    @Test
    fun `stop when idle is a no-op and does not construct or release`() {
        val capturer = StreamingAudioCapturer()
        capturer.stop()
        capturer.stop()
        assertEquals(0, ShadowFakeAudioRecord.constructorCount)
        assertEquals(0, ShadowFakeAudioRecord.releaseCount)
    }

    private fun waitUntil(timeoutMs: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }
}
