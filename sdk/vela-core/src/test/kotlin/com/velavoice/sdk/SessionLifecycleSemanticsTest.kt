package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ticket #221 (F15/F33): session-end semantics locked in grilling #220.
 *
 * 1. Terminal signal — explicit stop only. A session terminates solely on an
 *    explicit user stop (pane Stop/Cancel, tile stop, IME action). `onFinal`
 *    is emitted only on that signal; a server-initiated socket close is an
 *    abort/recovery path, never a graceful session end.
 * 2. Intermediate turn completions emit a commit marker only — a Server-VAD
 *    utterance end (`transcription.completed`) must NOT tear the session down
 *    at the first natural pause.
 * 3. Cold start (F33) — no commit boundary before first speech:
 *    `AdaptiveVad.shouldStop()` returns false until `everSawSpeech` is true.
 */
@RunWith(RobolectricTestRunner::class)
class SessionLifecycleSemanticsTest {

    private class CapturingCallback : StreamingTranscriptionCallback {
        val markers = mutableListOf<RevisionMarker>()
        var finalCalls = 0
        var lastFinal = ""
        override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
        override fun onFinal(text: String) { finalCalls++; lastFinal = text }
        override fun onError(error: VelaException) {}
        override fun onAmplitude(normalized: Float) {}
    }

    // --- F15: intermediate completions commit without ending the session ---

    @Test
    fun `intermediate transcription completion emits commit marker but no onFinal`() {
        val t = CloudStreamingTranscriber()
        val cb = CapturingCallback()
        t.setCallback(cb)

        t.handleDelta("Hello tolli ")
        t.handleServerMessage("""{"type":"transcription.completed","transcript":"Hello"}""")

        val commits = cb.markers.filter { it.type == "commit" }
        assertEquals("intermediate completion must still commit the segment", 1, commits.size)
        assertEquals("Hello", commits[0].text)
        assertEquals(
            "F15: intermediate Server-VAD completions must never emit the terminal onFinal",
            0,
            cb.finalCalls
        )
    }

    @Test
    fun `conversation item transcription completion behaves like an intermediate commit`() {
        val t = CloudStreamingTranscriber()
        val cb = CapturingCallback()
        t.setCallback(cb)

        t.handleServerMessage(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"hey"}"""
        )

        assertEquals(1, cb.markers.size)
        assertEquals("commit", cb.markers[0].type)
        assertEquals("hey", cb.markers[0].text)
        assertEquals("no onFinal before an explicit stop", 0, cb.finalCalls)
    }

    // --- F15: the terminal onFinal is reserved for explicit stop ---

    @Test
    fun `server completion after an explicit stop emits the terminal onFinal`() {
        val t = CloudStreamingTranscriber()
        val cb = CapturingCallback()
        t.setCallback(cb)

        // The user pressed Stop: the stop path arms the terminal signal
        // before the trailing commit goes out.
        t.armTerminalStop()
        t.handleCompleted("last words")

        val commits = cb.markers.filter { it.type == "commit" }
        assertEquals(1, commits.size)
        assertEquals("last words", commits[0].text)
        assertEquals("armed completion carries the single terminal onFinal", 1, cb.finalCalls)
        assertEquals("last words", cb.lastFinal)
    }

    @Test
    fun `arming resets with a fresh session start`() {
        val t = CloudStreamingTranscriber()
        val cb = CapturingCallback()
        t.setCallback(cb)
        t.armTerminalStop()
        t.start(StreamConfig(apiKey = "test-key"))

        // The socket connect is started but the arm flag must be cleared so a
        // new session's intermediate completions stay non-terminal.
        assertFalse(t.terminalStopRequested)
        t.release()
    }

    // --- F33: no commit boundary before first speech ---

    @Test
    fun `cold start silence never trips the VAD stop before first speech`() {
        val vad = AdaptiveVad(baseThreshold = 0.02f)
        var now = 0L
        repeat(40) {
            now += 100L
            vad.observe(0.002f, now)
        }
        // All stop conditions satisfied EXCEPT no speech was ever seen:
        // quiet amplitude under threshold, stop hold elapsed since the
        // session-start lastActivityTime.
        assertFalse(
            "F33: no commit boundary before first detected speech",
            vad.shouldStop(amplitude = 0.002f, nowMs = now + 700L, lastActivityTime = 0L)
        )
    }

    @Test
    fun `first speech arms the VAD stop for later silence`() {
        val vad = AdaptiveVad(baseThreshold = 0.02f)
        var now = 0L
        repeat(10) {
            now += 100L
            vad.observe(0.002f, now)
        }
        // User speaks.
        now += 100L
        vad.observe(0.1f, now)
        // Speech stops: silence past the stop hold now trips.
        assertTrue(
            vad.shouldStop(amplitude = 0.002f, nowMs = now + 700L, lastActivityTime = 0L)
        )
    }
}
