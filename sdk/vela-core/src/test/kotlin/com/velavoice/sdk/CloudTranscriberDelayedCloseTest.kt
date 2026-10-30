package com.velavoice.sdk

import okhttp3.WebSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenMock

/**
 * Wayfinder map #209 ticket #225 (F29 MEDIUM): `stop()` used to fire an
 * unmanaged `Thread { sleep(2000); webSocket?.close(...) }` that read the
 * shared field — a stop + restart within the 2 s window closed the NEW
 * session's socket. The delayed close must (a) locally capture the socket it
 * intends to close, and (b) be tied to sessionGen so a newer session is never
 * touched by a stale delayed close.
 */
@RunWith(RobolectricTestRunner::class)
class CloudTranscriberDelayedCloseTest {

    private class CollectedCloseSchedule {
        var delayMs: Long = -1
        var task: (() -> Unit)? = null
        fun run() { task?.invoke() ?: error("no delayed-close task scheduled") }
    }

    @Test
    fun `stopped session schedules exactly one delayed close of its own socket`() {
        val t = CloudStreamingTranscriber()
        val schedule = CollectedCloseSchedule()
        t.scheduleCloseForTest = { delayMs, task -> schedule.delayMs = delayMs; schedule.task = task }

        val socket = mock(WebSocket::class.java)
        whenMock(socket.send(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
        t.webSocket = socket
        t.isRunningInternalForTest = true

        t.stop()

        assertEquals("stop must schedule the close with the 2s completion delay", 2000L, schedule.delayMs)
        // Commit goes out on stop; nothing closed synchronously — the ONLY
        // close must be the delayed one of the locally captured socket.
        schedule.run()
        verify(socket).close(1000, "Session complete")
        schedule.delayMs = -1
    }

    @Test
    fun `restart within the close-delay keeps the NEW socket open`() {
        val t = CloudStreamingTranscriber()
        val schedule = CollectedCloseSchedule()
        t.scheduleCloseForTest = { _, task -> schedule.task = task }

        val oldSocket = mock(WebSocket::class.java)
        whenMock(oldSocket.send(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
        t.webSocket = oldSocket
        t.isRunningInternalForTest = true
        t.stop()

        // User stops and immediately restarts: a new socket exists and the
        // session has moved on (bumped generation + fresh terminal flag).
        val newSocket = mock(WebSocket::class.java)
        whenMock(newSocket.send(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
        t.webSocket = newSocket
        t.sessionGen++
        t.terminalStopRequested = false

        schedule.run()

        verify(newSocket, never()).close(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString())
        // The stale delayed task is a no-op — it must not even close the OLD
        // socket again (that socket's lifecycle belongs to its own close).
        verify(oldSocket, never()).close(1000, "Session complete")
        assertTrue("no-op task must not throw", true)
    }

    @Test
    fun `stale delayed close still fires when no restart happened`() {
        val t = CloudStreamingTranscriber()
        val schedule = CollectedCloseSchedule()
        t.scheduleCloseForTest = { _, task -> schedule.task = task }

        val socket = mock(WebSocket::class.java)
        whenMock(socket.send(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
        t.webSocket = socket
        t.isRunningInternalForTest = true
        t.stop()

        // No restart: socket still the same and generation unchanged.
        schedule.run()
        verify(socket).close(1000, "Session complete")
    }

    @Test
    fun `release cancels the pending delayed close`() {
        val t = CloudStreamingTranscriber()
        val schedule = CollectedCloseSchedule()
        t.scheduleCloseForTest = { _, task -> schedule.task = task }

        val socket = mock(WebSocket::class.java)
        whenMock(socket.send(org.mockito.ArgumentMatchers.anyString())).thenReturn(true)
        t.webSocket = socket
        t.isRunningInternalForTest = true
        t.stop()
        t.release()

        // After release the scheduler must be gone: running a captured task
        // (belt-and-suspenders against executor races) must be a no-op guard.
        if (schedule.task != null) schedule.run()
        verify(socket, never()).close(1000, "Session complete")
    }
}
