// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for the WaveformFeed EMA smoothing + ~30fps rate gate
 * (ticket #160).
 */
class WaveformFeedTest {

    private fun tol(x: Float, y: Float): Float = kotlin.math.abs(x - y)

    @Test
    fun firstSampleAlwaysDelivered() {
        val feed = WaveformFeed()
        val delivered = feed.feed(1f, 0L)
        assertTrue(delivered != null, "first sample is delivered immediately")
        assertTrue(tol(delivered!!, 0.3f) < 0.01f, "EMA seeded at alpha*value")
    }

    @Test
    fun firstSampleDeliveredAtRealisticUptime() {
        val feed = WaveformFeed()
        // Real Android elapsedRealtime: phone has been up for 1 hour.
        // Regression for F03 (map #213): Long.MIN_VALUE + 1 init overflowed
        // nowMs - lastDeliveredMs negative at any real uptime, so the rate
        // gate blocked forever and the waveform never drew on devices.
        val delivered = feed.feed(1f, 3_600_000L)
        assertTrue(delivered != null, "first sample must deliver at realistic device uptime")
        assertTrue(tol(delivered!!, 0.3f) < 0.01f, "EMA seeded at alpha*value")
    }

    @Test
    fun firstSampleDeliveredAfterResetAtRealisticUptime() {
        val feed = WaveformFeed()
        assertTrue(feed.feed(1f, 3_600_000L) != null)
        feed.reset() // new recording starts — same realistic uptime clock
        val delivered = feed.feed(0.5f, 3_600_005L)
        assertTrue(delivered != null, "first sample after reset() must deliver at realistic uptime")
    }

    @Test
    fun rateGateFoldsSamplesBetweenDeliveries() {
        val feed = WaveformFeed()
        assertTrue(feed.feed(0f, 0L) != null, "t=0 delivered")
        assertNull(feed.feed(1f, 16L), "inside window: folded, not delivered")
        assertNull(feed.feed(1f, 20L), "inside window: folded, not delivered")
        val resumed = feed.feed(1f, 33L)
        assertTrue(resumed != null && resumed > 0f,
            "delivery resumes after the window and the folded samples raised the EMA")
    }

    @Test
    fun emaConvergesTowardConstantLoudSignal() {
        val feed = WaveformFeed()
        assertTrue(feed.feed(1f, 0L) != null)
        var last = 0f
        var t = 33L
        for (i in 1..200) {
            val delivered = feed.feed(1f, t)
            if (delivered != null) last = delivered
            t += 33L
            if (last > 0.95f) break
        }
        assertTrue(last > 0.95f, "EMA must converge high for constant loud signal (last=$last)")
    }

    @Test
    fun emaStaysLowForQuietSignal() {
        val feed = WaveformFeed()
        var last = 0f
        var t = 0L
        for (i in 0..100) {
            val delivered = feed.feed(0.01f, t)
            if (delivered != null) last = delivered
            t += 33L
        }
        assertTrue(last < 0.15f, "quiet signal must remain visually quiet (last=$last)")
    }

    @Test
    fun outOfRangeInputsClamped() {
        val feed = WaveformFeed()
        val high = feed.feed(5f, 0L)
        assertTrue(high != null && tol(high, 0.3f) < 0.01f, "input 5 clamped to 1.0 before smoothing")
        val low = feed.feed(-3f, 33L)
        assertTrue(low != null && low < 1f, "input -3 clamped to 0.0 before smoothing")
    }
}
