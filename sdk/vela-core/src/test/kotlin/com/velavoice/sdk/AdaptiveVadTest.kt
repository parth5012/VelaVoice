package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map #105 ticket #111 (VAD): the stop threshold must adapt to the rolling
 * noise floor with a minimum hold time — quiet rooms trip the stop, noisy
 * rooms hold mid-word dips instead of cutting speech.
 */
class AdaptiveVadTest {

    @Test
    fun `quiet room trips stop after hold`() {
        val vad = AdaptiveVad(baseThreshold = 0.02f)
        // Quiet floor: observe near-silence so the floor settles low.
        var now = 0L
        repeat(40) {
            now += 100L
            vad.observe(0.002f, now)
        }
        // Effective threshold stays near the base in quiet air.
        val eff = vad.effectiveThreshold()
        assertTrue("quiet effective threshold near base, was $eff", eff <= 0.03f)
        // Sustained silence past the stop hold trips.
        assertTrue(vad.shouldStop(amplitude = 0.002f, nowMs = now + 700L, lastActivityTime = now - 700L))
    }

    @Test
    fun `noisy room holds a mid-word dip`() {
        val vad = AdaptiveVad(baseThreshold = 0.02f)
        // Noisy floor: observe loud room tone so the floor rises.
        var now = 0L
        repeat(60) {
            now += 100L
            vad.observe(0.05f, now)
        }
        val eff = vad.effectiveThreshold()
        assertTrue("noisy effective threshold rises above base, was $eff", eff > 0.02f)
        // Brief mid-word dip below the raised threshold must NOT trip:
        // the minimum hold time has not elapsed.
        assertFalse(
            vad.shouldStop(amplitude = 0.03f, nowMs = now + 100L, lastActivityTime = now)
        )
        // Sustained dip past the hold DOES trip.
        assertTrue(
            vad.shouldStop(amplitude = 0.03f, nowMs = now + 900L, lastActivityTime = now - 900L)
        )
    }

    @Test
    fun `effective threshold never leaves documented bounds`() {
        val vad = AdaptiveVad(baseThreshold = 0.02f)
        var now = 0L
        // Dead silence must not drag the floor below the minimum.
        repeat(200) {
            now += 50L
            vad.observe(0.0f, now)
        }
        assertTrue(vad.effectiveThreshold() >= AdaptiveVad.MIN_THRESHOLD)
        // Roaring noise must not push the threshold above the maximum.
        repeat(200) {
            now += 50L
            vad.observe(0.9f, now)
        }
        assertTrue(vad.effectiveThreshold() <= AdaptiveVad.MAX_THRESHOLD)
    }

    @Test
    fun `constants are named and sane`() {
        assertEquals(0.02f, AdaptiveVad.DEFAULT_BASE_THRESHOLD, 0.0001f)
        assertTrue(AdaptiveVad.MIN_THRESHOLD > 0f)
        assertTrue(AdaptiveVad.MIN_THRESHOLD <= AdaptiveVad.DEFAULT_BASE_THRESHOLD)
        assertTrue(AdaptiveVad.MAX_THRESHOLD > AdaptiveVad.DEFAULT_BASE_THRESHOLD)
        assertTrue(AdaptiveVad.STOP_HOLD_MS >= AdaptiveVad.MIN_SPEECH_HOLD_MS)
    }
}
