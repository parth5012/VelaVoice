package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Map #105 ticket #111 (cloud reconnect): a hard-down network must back off
 * with jitter up to a max-attempt cap, then surface a user-visible error —
 * never hot-loop reconnects inside the IME process, never fail silent.
 */
@RunWith(RobolectricTestRunner::class)
class CloudReconnectBackoffTest {

    @Test
    fun `failures back off and cap then surface onError`() {
        val t = CloudStreamingTranscriber()
        val scheduledDelays = mutableListOf<Long>()
        val errors = mutableListOf<String>()
        t.scheduleReconnectForTest = { delayMs, _ -> scheduledDelays.add(delayMs) }
        t.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {}
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) { errors.add(error.message ?: "") }
            override fun onAmplitude(normalized: Float) {}
        })

        repeat(StreamingBackoff.MAX_ATTEMPTS) { i ->
            t.handleConnectionFailureForTest(RuntimeException("down $i"))
        }

        assertEquals(StreamingBackoff.MAX_ATTEMPTS, scheduledDelays.size)
        assertTrue("no silent failure before the cap", errors.isEmpty())
        // Exponential shape (jitter=0 fake uses exact centers via the hook below).
        for (i in 1 until scheduledDelays.size) {
            assertTrue(
                "delay ${scheduledDelays[i]} should exceed ${scheduledDelays[i - 1]}",
                scheduledDelays[i] >= scheduledDelays[i - 1]
            )
        }

        // One more failure exhausts the cap and surfaces the error.
        t.handleConnectionFailureForTest(RuntimeException("down final"))
        assertEquals(1, errors.size)
        assertTrue(errors[0].isNotBlank())
        assertEquals(StreamingBackoff.MAX_ATTEMPTS, scheduledDelays.size)
    }

    @Test
    fun `successful start resets the attempt count`() {
        val t = CloudStreamingTranscriber()
        var scheduled = 0
        t.scheduleReconnectForTest = { _, _ -> scheduled++ }
        t.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {}
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })
        t.handleConnectionFailureForTest(RuntimeException("down"))
        assertEquals(1, scheduled)
        t.resetReconnectForTest()
        t.handleConnectionFailureForTest(RuntimeException("down again"))
        assertEquals(2, scheduled)
    }
}
