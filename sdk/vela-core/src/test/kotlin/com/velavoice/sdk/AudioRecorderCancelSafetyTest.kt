package com.velavoice.sdk

import com.velavoice.sdk.whisper.WhisperEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * OCR review findings on AudioRecorder (session a7d7065a):
 * - #3 medium: cancel() joins the recording thread without a timeout; if the thread
 *   is parked in a blocking AudioRecord.read() (capture driver stall), a main-thread
 *   cancel() blocks indefinitely -> ANR. Fix: join with a timeout (like StreamingPipeline).
 * - #4 low: release() after a completed stop() bypasses cancel()'s early return, so
 *   recordedAudioData is never reset and the captured audio stays retained in memory.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowFakeAudioRecord::class])
class AudioRecorderCancelSafetyTest {

    @Before
    fun setUp() {
        ShadowFakeAudioRecord.resetHooks()
    }

    @After
    fun tearDown() {
        ShadowFakeAudioRecord.releaseBlockedReads()
        ShadowFakeAudioRecord.resetHooks()
    }

    private fun recordedBufferSize(recorder: AudioRecorder): Int {
        val field = AudioRecorder::class.java.getDeclaredField("recordedAudioData")
        field.isAccessible = true
        return (field.get(recorder) as ByteArrayOutputStream).size()
    }

    @Test(timeout = 8000)
    fun `cancel returns promptly while recording thread is blocked in read`() {
        ShadowFakeAudioRecord.blockReads = true

        val recorder = AudioRecorder()
        val whisper = mock(WhisperEngine::class.java)
        val callback = mock(VelaRecordingCallback::class.java)
        recorder.start(whisper, null, callback, ScribeInput(privacySensitive = true))

        // Wait deterministically until the capture thread is inside the blocking read().
        assertTrue(
            "capture thread never entered the blocking read()",
            ShadowFakeAudioRecord.readEntered.await(3, java.util.concurrent.TimeUnit.SECONDS)
        )
        assertTrue("recorder should be recording", recorder.isRecording())

        val startedAt = System.currentTimeMillis()
        recorder.cancel()
        val elapsed = System.currentTimeMillis() - startedAt

        assertFalse("cancel() must clear the recording flag", recorder.isRecording())
        assertTrue(
            "cancel() took ${elapsed}ms with a blocked capture thread - " +
                "join() must use a timeout so the UI thread cannot be wedged (<=3500ms expected)",
            elapsed <= 3500
        )
    }

    @Test
    fun `release after stop clears recorded audio buffer`() {
        ShadowFakeAudioRecord.readResult = 2

        val recorder = AudioRecorder()
        val whisper = mock(WhisperEngine::class.java)
        val callback = mock(VelaRecordingCallback::class.java)
        recorder.start(whisper, null, callback, ScribeInput(privacySensitive = false))

        val deadline = System.currentTimeMillis() + 3000
        while (recordedBufferSize(recorder) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(
            "test invalid: capture thread never wrote audio bytes into the buffer",
            recordedBufferSize(recorder) > 0
        )

        recorder.stop(clean = false)
        val sizeAfterStop = recordedBufferSize(recorder)
        assertTrue("stop() should leave the buffer populated for transcription", sizeAfterStop > 0)

        recorder.release()

        assertEquals(
            "release() after a completed stop() must free the captured audio buffer " +
                "(cancel() early-returns when not recording, so release() has to reset it)",
            0,
            recordedBufferSize(recorder)
        )
    }
}
