package com.velavoice.sdk

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
}
