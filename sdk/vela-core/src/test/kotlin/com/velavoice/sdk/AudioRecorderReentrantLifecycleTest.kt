package com.velavoice.sdk

import android.media.AudioRecord
import com.velavoice.sdk.whisper.WhisperEngine
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wayfinder map #97 ticket #98: re-entrant recording and the hot-mic path.
 * The Done-when sequence start(); start(); stop(); stop(); cancel(); start()
 * must never throw and never leak an AudioRecord (asserted via the
 * ShadowFakeAudioRecord constructor/release counters — android-all has no
 * AudioRecord shadow, so every instance is accounted for by the double).
 * Also covers AudioRecorder.release() mid-stream resetting isRecording so a
 * fresh start() after release works (the audit's "Already recording" dead
 * button — the flag must not survive release()).
 * Ticket #99 adds the teardown-exactly-once contract: every path (stop, cancel,
 * failed init, startRecording throw) funnels through one release routine so
 * constructorCount == releaseCount always (zero held AudioRecords).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowFakeAudioRecord::class])
class AudioRecorderReentrantLifecycleTest {

    @Before
    fun setUp() {
        ShadowFakeAudioRecord.resetHooks()
    }

    @After
    fun tearDown() {
        ShadowFakeAudioRecord.releaseBlockedReads()
        ShadowFakeAudioRecord.resetHooks()
    }

    /**
     * Mockito's any() throws NPE when stubbing a Kotlin non-null parameter
     * (null-check in the generated matcher bridge). Declaring the helper in
     * Kotlin with a non-null return type suppresses that check (same pattern
     * as AudioRecorderScribeRaceTest).
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(type: Class<T>): T = ArgumentMatchers.any(type) as T

    private fun newRecorder(): Triple<AudioRecorder, WhisperEngine, VelaRecordingCallback> {
        val recorder = AudioRecorder()
        val whisper = mock(WhisperEngine::class.java)
        doReturn("ok").`when`(whisper).transcribe(anyArg(ByteArray::class.java), isNull())
        val callback = mock(VelaRecordingCallback::class.java)
        return Triple(recorder, whisper, callback)
    }

    @Test(timeout = 10000)
    fun `start start stop stop cancel start never throws and never leaks an AudioRecord`() {
        val (recorder, whisper, callback) = newRecorder()

        recorder.start(whisper, null, callback)
        assertTrue("first start must record", recorder.isRecording())
        recorder.start(whisper, null, callback)
        assertTrue("re-entrant start must not stop the live session", recorder.isRecording())
        assertTrue(
            "re-entrant start must not build a second AudioRecord " +
                "(leaks the first capture session), constructed=" +
                ShadowFakeAudioRecord.constructorCount,
            ShadowFakeAudioRecord.constructorCount == 1
        )

        recorder.stop(clean = false)
        assertFalse("first stop must end recording", recorder.isRecording())
        recorder.stop(clean = false)

        recorder.cancel()

        recorder.start(whisper, null, callback)
        assertTrue("fresh start after stop+cancel must work", recorder.isRecording())
        recorder.stop(clean = false)
        assertFalse(recorder.isRecording())

        assertTrue(
            "exactly two AudioRecords may have been constructed " +
                "(re-entrant start was a no-op), actual=" + ShadowFakeAudioRecord.constructorCount,
            ShadowFakeAudioRecord.constructorCount == 2
        )
        assertTrue(
            "every constructed AudioRecord must be released exactly once " +
                "(no leak, no double-release), released=" + ShadowFakeAudioRecord.releaseCount,
            ShadowFakeAudioRecord.releaseCount == 2
        )
    }

    @Test(timeout = 10000)
    fun `release mid-recording resets isRecording so a fresh start works`() {
        val (recorder, whisper, callback) = newRecorder()

        recorder.start(whisper, null, callback)
        assertTrue(recorder.isRecording())

        recorder.release()

        assertFalse(
            "release() must reset isRecording (else the next start() is a " +
                "permanently dead button until process restart)",
            recorder.isRecording()
        )
        assertTrue(
            "the AudioRecord captured by the canceled session must be released",
            ShadowFakeAudioRecord.releaseCount == 1
        )

        recorder.start(whisper, null, callback)
        assertTrue("start() after release() must work", recorder.isRecording())
        assertTrue(
            "start() after release() must build a fresh AudioRecord",
            ShadowFakeAudioRecord.constructorCount == 2
        )
        recorder.stop(clean = false)
        assertFalse(recorder.isRecording())
    }

    @Test(timeout = 10000)
    fun `cancel mid-recording then start works and releases the first AudioRecord`() {
        val (recorder, whisper, callback) = newRecorder()

        recorder.start(whisper, null, callback)
        recorder.cancel()
        assertFalse("cancel() must clear isRecording", recorder.isRecording())
        assertTrue(
            "cancel() must release the live AudioRecord",
            ShadowFakeAudioRecord.releaseCount == 1
        )

        recorder.start(whisper, null, callback)
        assertTrue("start() after cancel() must work", recorder.isRecording())
        assertTrue(ShadowFakeAudioRecord.constructorCount == 2)
        recorder.stop(clean = false)
        assertTrue(ShadowFakeAudioRecord.releaseCount == 2)
    }

    @Test(timeout = 10000)
    fun `start cancel start stop cancel leaves zero held AudioRecords`() {
        val (recorder, whisper, callback) = newRecorder()

        recorder.start(whisper, null, callback)
        recorder.cancel()
        recorder.start(whisper, null, callback)
        recorder.stop(clean = false)
        recorder.cancel()

        assertFalse(recorder.isRecording())
        assertTrue(
            "start/cancel/start/stop/cancel must construct exactly two AudioRecords " +
                "(one per start), actual=" + ShadowFakeAudioRecord.constructorCount,
            ShadowFakeAudioRecord.constructorCount == 2
        )
        assertTrue(
            "zero held AudioRecords at the end: every constructed instance released, " +
                "constructed=" + ShadowFakeAudioRecord.constructorCount +
                " released=" + ShadowFakeAudioRecord.releaseCount,
            ShadowFakeAudioRecord.releaseCount == ShadowFakeAudioRecord.constructorCount
        )
    }

    @Test(timeout = 10000)
    fun `uninitialized AudioRecord is released and a later start succeeds`() {
        ShadowFakeAudioRecord.stateResult = AudioRecord.STATE_UNINITIALIZED
        val (recorder, whisper, callback) = newRecorder()

        recorder.start(whisper, null, callback)

        assertFalse("failed init must not report recording", recorder.isRecording())
        assertTrue(
            "failed init still constructed one AudioRecord",
            ShadowFakeAudioRecord.constructorCount == 1
        )
        assertTrue(
            "the uninitialized AudioRecord must still be released (native buffer leak " +
                "otherwise), released=" + ShadowFakeAudioRecord.releaseCount,
            ShadowFakeAudioRecord.releaseCount == 1
        )

        ShadowFakeAudioRecord.stateResult = AudioRecord.STATE_INITIALIZED
        recorder.start(whisper, null, callback)
        assertTrue("start() after failed init must work", recorder.isRecording())
        assertTrue(ShadowFakeAudioRecord.constructorCount == 2)
        recorder.stop(clean = false)
        assertTrue(
            "second session's AudioRecord must be released too",
            ShadowFakeAudioRecord.releaseCount == 2
        )
    }

    @Test(timeout = 10000)
    fun `startRecording failure releases the constructed AudioRecord`() {
        ShadowFakeAudioRecord.throwOnStartRecording = true
        val (recorder, whisper, callback) = newRecorder()

        recorder.start(whisper, null, callback)

        assertFalse("startRecording() throwing must not report recording", recorder.isRecording())
        assertTrue(ShadowFakeAudioRecord.constructorCount == 1)
        assertTrue(
            "the AudioRecord constructed before startRecording() threw must be released, " +
                "released=" + ShadowFakeAudioRecord.releaseCount,
            ShadowFakeAudioRecord.releaseCount == 1
        )
        verify(callback).onError(anyArg(VelaException::class.java))
    }
}
