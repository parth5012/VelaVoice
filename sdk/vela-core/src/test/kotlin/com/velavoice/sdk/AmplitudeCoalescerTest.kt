package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map #105 ticket #113: amplitude UI posts must coalesce to ~15 Hz.
 *
 * The mic capture threads ([AudioRecorder], [StreamingPipeline]) invoke
 * `onAmplitude` per read buffer (~50/s). Each callback used to allocate a
 * `post {}` runnable on the main thread. [AmplitudeCoalescer] keeps the latest
 * sample and grants at most one main-thread post per [intervalMs], drops
 * offers while the pane is hidden, and never filters what the VAD observes
 * (VAD `observe()` stays on the capture thread, per-tick — see #111).
 */
class AmplitudeCoalescerTest {

    @Test
    fun `fifty hertz bursts coalesce to at most fifteen posts per second`() {
        val coalescer = AmplitudeCoalescer()
        var nowMs = 0L
        var posts = 0
        // 50 offers/s for 4 s (20 ms spacing), draining the shared runnable
        // on the "main thread" after every grant — the production shape.
        repeat(200) { i ->
            nowMs = i * 20L
            if (coalescer.offer(0.5f, nowMs, visible = true)) {
                posts++
                assertNotNull(coalescer.poll(nowMs))
            }
        }
        // 4 s at 15 Hz caps at 60 posts; allow one boundary grant of slack.
        assertTrue("50Hz input must coalesce to <=15Hz posts, got $posts", posts <= 61)
        // And it must not starve the meter either: ~12.5Hz here, so >= 40/4s.
        assertTrue("coalesced meter must still tick (~15Hz), got $posts", posts >= 40)
    }

    @Test
    fun `hidden pane offers post zero runnables`() {
        val coalescer = AmplitudeCoalescer()
        var posts = 0
        repeat(100) { i ->
            if (coalescer.offer(0.9f, i * 20L, visible = false)) posts++
        }
        assertEquals("hidden pane must drop every UI post", 0, posts)
    }

    @Test
    fun `at most one runnable is outstanding between drains`() {
        val coalescer = AmplitudeCoalescer()
        // Burst without draining (main thread stalled): only the first offer
        // may claim the single reusable runnable; the rest coalesce into it.
        var grants = 0
        repeat(50) { i ->
            if (coalescer.offer(0.1f + i * 0.01f, i * 20L, visible = true)) grants++
        }
        assertEquals("single reusable runnable: exactly one outstanding grant", 1, grants)
        // The drained value is the latest sample, not the first.
        val drained = coalescer.poll(10_000L)
        assertNotNull(drained)
        assertTrue("drain must deliver the latest coalesced sample", drained!! > 0.5f)
    }

    @Test
    fun `visible offers resume after a hidden stretch`() {
        val coalescer = AmplitudeCoalescer()
        repeat(10) { i ->
            coalescer.offer(0.4f, i * 20L, visible = false)
        }
        var posts = 0
        repeat(100) { i ->
            val now = 10_000L + i * 20L
            if (coalescer.offer(0.6f, now, visible = true)) {
                posts++
                coalescer.poll(now)
            }
        }
        assertTrue("meter must resume once visible, got $posts", posts >= 10)
    }

    @Test
    fun `reset clears the throttle so a new session ticks immediately`() {
        val coalescer = AmplitudeCoalescer()
        assertTrue(coalescer.offer(0.5f, 0L, visible = true))
        coalescer.poll(0L)
        coalescer.reset()
        // Immediately after reset the next offer must grant (no stale
        // lastEmitMs throttling the first bar of the new session).
        assertTrue(coalescer.offer(0.7f, 10L, visible = true))
        assertEquals(0.7f, coalescer.poll(10L)!!, 0.0001f)
    }

    @Test
    fun `poll with nothing pending returns null`() {
        assertNull(AmplitudeCoalescer().poll(0L))
    }
}
