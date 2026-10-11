package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wayfinder map #97 ticket #98: a single IDLE/RECORDING/PROCESSING state
 * machine is the only gate for the IME recording path.
 * - start is only legal from IDLE (double-tap / onStartInputView re-entry is a no-op)
 * - stop is only legal from RECORDING and idempotent
 * - cancel is only legal from RECORDING and idempotent
 * - double-invoke in any order never throws and never leaves the gate stuck
 */
class RecordingSessionGateTest {

    @Test
    fun `tryStart succeeds from IDLE and fails while RECORDING`() {
        val gate = RecordingSessionGate()
        assertEquals(RecordingSessionGate.State.IDLE, gate.state)
        assertTrue("first start must claim the session", gate.tryStart())
        assertEquals(RecordingSessionGate.State.RECORDING, gate.state)
        assertTrue(gate.isRecording())
        assertFalse("re-entrant start (double-tap) must be a no-op", gate.tryStart())
        assertEquals(RecordingSessionGate.State.RECORDING, gate.state)
    }

    @Test
    fun `tryStop succeeds once from RECORDING and is idempotent`() {
        val gate = RecordingSessionGate()
        assertFalse("stop while IDLE must be a no-op", gate.tryStop())
        gate.tryStart()
        assertTrue(gate.tryStop())
        assertEquals(RecordingSessionGate.State.PROCESSING, gate.state)
        assertFalse("second stop must not re-run teardown", gate.tryStop())
        assertEquals(RecordingSessionGate.State.PROCESSING, gate.state)
    }

    @Test
    fun `tryCancel succeeds from RECORDING and is a no-op elsewhere`() {
        val gate = RecordingSessionGate()
        assertFalse("cancel while IDLE must be a no-op", gate.tryCancel())
        gate.tryStart()
        assertTrue(gate.tryCancel())
        assertEquals(RecordingSessionGate.State.IDLE, gate.state)
        assertFalse("second cancel must be a no-op", gate.tryCancel())
    }

    @Test
    fun `cancel after stop is a no-op so stop owns the in-flight transcribe`() {
        val gate = RecordingSessionGate()
        gate.tryStart()
        assertTrue(gate.tryStop())
        assertFalse("cancel during PROCESSING must not discard stop's session", gate.tryCancel())
        assertEquals(RecordingSessionGate.State.PROCESSING, gate.state)
    }

    @Test
    fun `start start stop stop cancel start sequence settles deterministically`() {
        val gate = RecordingSessionGate()
        assertTrue(gate.tryStart())
        assertFalse("second start is a no-op", gate.tryStart())
        assertTrue(gate.tryStop())
        assertFalse("second stop is a no-op", gate.tryStop())
        assertFalse("cancel after stop is a no-op", gate.tryCancel())
        assertFalse("start is illegal while PROCESSING", gate.tryStart())
        assertTrue("callback completion settles the session", gate.finish())
        assertEquals(RecordingSessionGate.State.IDLE, gate.state)
        assertTrue("fresh start after settle must work", gate.tryStart())
        assertTrue(gate.tryCancel())
        assertTrue("fresh start after cancel must work", gate.tryStart())
        assertTrue(gate.finish())
    }

    @Test
    fun `finish settles RECORDING and PROCESSING but not IDLE`() {
        val gate = RecordingSessionGate()
        assertFalse("finish while IDLE is a no-op", gate.finish())
        gate.tryStart()
        assertTrue(gate.finish())
        assertEquals(RecordingSessionGate.State.IDLE, gate.state)
        gate.tryStart()
        assertTrue(gate.tryStop())
        assertTrue(gate.finish())
        assertEquals(RecordingSessionGate.State.IDLE, gate.state)
    }

    @Test
    fun `reset forces IDLE from every state`() {
        val gate = RecordingSessionGate()
        gate.tryStart()
        gate.reset()
        assertEquals(RecordingSessionGate.State.IDLE, gate.state)
        gate.tryStart()
        gate.tryStop()
        gate.reset()
        assertEquals(RecordingSessionGate.State.IDLE, gate.state)
        assertTrue(gate.tryStart())
    }

    @Test
    fun `isRecording is true only in RECORDING`() {
        val gate = RecordingSessionGate()
        assertFalse(gate.isRecording())
        gate.tryStart()
        assertTrue(gate.isRecording())
        gate.tryStop()
        assertFalse("PROCESSING is not recording", gate.isRecording())
        gate.finish()
        assertFalse(gate.isRecording())
    }

    @Test(timeout = 5000)
    fun `concurrent tryStart lets exactly one caller win`() {
        val gate = RecordingSessionGate()
        val winners = AtomicInteger(0)
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val threads = (0 until 8).map {
            Thread {
                ready.countDown()
                go.await()
                if (gate.tryStart()) winners.incrementAndGet()
            }.apply { start() }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        go.countDown()
        threads.forEach { it.join(2000) }
        assertEquals("exactly one concurrent start may claim the session", 1, winners.get())
        assertEquals(RecordingSessionGate.State.RECORDING, gate.state)
    }
}
