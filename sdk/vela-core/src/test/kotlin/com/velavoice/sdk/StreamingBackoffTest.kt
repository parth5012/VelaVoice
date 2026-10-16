package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map #105 ticket #111 (streaming backoff): reconnects use exponential
 * backoff with jitter and a max-attempt cap instead of hot-looping inside
 * the IME process; exhaustion surfaces as an error, not silence.
 */
class StreamingBackoffTest {

    @Test
    fun `backoff sequence grows exponentially within jitter bounds`() {
        // Attempt n (1-based) centers on BASE * 2^(n-1), capped at MAX.
        val d1 = StreamingBackoff.delayForAttempt(1, jitterUnit = 0.0)
        val d2 = StreamingBackoff.delayForAttempt(2, jitterUnit = 0.0)
        val d3 = StreamingBackoff.delayForAttempt(3, jitterUnit = 0.0)
        assertEquals(StreamingBackoff.BASE_DELAY_MS, d1)
        assertEquals(StreamingBackoff.BASE_DELAY_MS * 2, d2)
        assertEquals(StreamingBackoff.BASE_DELAY_MS * 4, d3)

        // Full positive jitter stays within +JITTER_FRACTION.
        val j1 = StreamingBackoff.delayForAttempt(1, jitterUnit = 1.0)
        assertTrue(j1 in (d1 + 1)..(d1 + (d1 * StreamingBackoff.JITTER_FRACTION).toLong() + 1))
        // Full negative jitter stays within -JITTER_FRACTION.
        val jNeg = StreamingBackoff.delayForAttempt(2, jitterUnit = -1.0)
        assertTrue(jNeg in (d2 - (d2 * StreamingBackoff.JITTER_FRACTION).toLong() - 1)..(d2 - 1))
    }

    @Test
    fun `backoff delay respects the max cap`() {
        val huge = StreamingBackoff.delayForAttempt(100, jitterUnit = 1.0)
        assertTrue("delay $huge exceeds cap ${StreamingBackoff.MAX_DELAY_MS}", huge <= StreamingBackoff.MAX_DELAY_MS)
        val hugeNeg = StreamingBackoff.delayForAttempt(100, jitterUnit = -1.0)
        assertTrue(hugeNeg <= StreamingBackoff.MAX_DELAY_MS)
    }

    @Test
    fun `retry respects the max-attempt cap`() {
        assertTrue(StreamingBackoff.shouldRetry(1))
        assertTrue(StreamingBackoff.shouldRetry(StreamingBackoff.MAX_ATTEMPTS))
        assertFalse(StreamingBackoff.shouldRetry(StreamingBackoff.MAX_ATTEMPTS + 1))
        assertFalse(StreamingBackoff.shouldRetry(StreamingBackoff.MAX_ATTEMPTS + 100))
    }

    @Test
    fun `backoff constants are named and sane`() {
        assertTrue(StreamingBackoff.BASE_DELAY_MS > 0)
        assertTrue(StreamingBackoff.MAX_DELAY_MS >= StreamingBackoff.BASE_DELAY_MS)
        assertTrue(StreamingBackoff.MAX_ATTEMPTS in 1..10)
        assertTrue(StreamingBackoff.JITTER_FRACTION in 0.0..1.0)
    }
}
