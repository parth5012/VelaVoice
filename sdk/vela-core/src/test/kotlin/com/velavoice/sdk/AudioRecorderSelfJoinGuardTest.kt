package com.velavoice.sdk

import com.velavoice.sdk.whisper.WhisperEngine
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Ticket #230 (F21): the duration-limit callback fires ON the capture thread
 * (AudioRecorder contract #104); when the callback owner runs the normal
 * stop/cancel path synchronously from that thread, releaseAudioRecord()
 * joined recordingThread — a thread joining ITSELF, which can never
 * complete and burned the full CANCEL_JOIN_TIMEOUT_MS (2 s) on the capture
 * thread. The join must be skipped whenever the caller IS the recording
 * thread (still stops/releases the recorder; the thread exits its own loop).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowFakeAudioRecord::class])
class AudioRecorderSelfJoinGuardTest {

    @Before
    fun setUp() {
        ShadowFakeAudioRecord.resetHooks()
    }

    @After
    fun tearDown() {
        ShadowFakeAudioRecord.releaseBlockedReads()
        ShadowFakeAudioRecord.resetHooks()
    }

    private class StubEngine : WhisperEngine(stubInit = true) {
        init {
            setContextPtrForTest(0x1234_5678L)
        }
        override fun doNativeTranscribe(
            ptr: Long,
            floatAudio: FloatArray,
            language: String,
            threads: Int,
            initialPrompt: String?,
            translate: Boolean
        ): String = "stub transcript"
        override fun doNativeFree(ptr: Long) {}
        override fun doNativeCancel(ptr: Long) {}
    }

    private fun releasesAtTestStart(): Int = ShadowFakeAudioRecord.releaseCount

    /**
     * Regression for the self-join: stop() invoked synchronously from the
     * duration-limit callback (running on the capture thread) must return
     * well under CANCEL_JOIN_TIMEOUT_MS — joining the current thread can
     * only complete by burning the whole timeout.
     */
    @Test(timeout = 10000)
    fun `stop from the duration-limit callback does not self-join the capture thread`() {
        val capMs = 120L
        val selfStop = CountDownLatch(1)
        val elapsed = AtomicLong(-1)
        val recorder = AudioRecorder(capMs)
        val callback = object : VelaRecordingCallback {
            override fun onAmplitude(normalized: Float) {}
            override fun onResult(result: TranscriptionResult) {}
            override fun onError(error: VelaException) {}
            override fun onDurationLimitReached() {
                val startedAt = System.currentTimeMillis()
                recorder.stop(clean = false)
                elapsed.set(System.currentTimeMillis() - startedAt)
                selfStop.countDown()
            }
        }
        val releasesBefore = releasesAtTestStart()

        recorder.start(StubEngine(), null, callback)

        assertTrue(
            "duration-limit callback must fire on the capture thread",
            selfStop.await(5, TimeUnit.SECONDS)
        )
        val ms = elapsed.get()
        assertTrue(
            "stop() from the capture thread took ${ms}ms — releaseAudioRecord must skip " +
                "join when the caller IS the recording thread (self-join burns " +
                "${AudioRecorder.CANCEL_JOIN_TIMEOUT_MS}ms)",
            ms in 0 until AudioRecorder.CANCEL_JOIN_TIMEOUT_MS
        )
        assertFalse("stop() must end the recording state", recorder.isRecording())
        assertTrue(
            "stop() from the capture thread must still release the AudioRecord",
            ShadowFakeAudioRecord.releaseCount > releasesBefore
        )
        recorder.release()
    }

    /** Same regression through the cancel path. */
    @Test(timeout = 10000)
    fun `cancel from the duration-limit callback does not self-join the capture thread`() {
        val capMs = 120L
        val selfCancel = CountDownLatch(1)
        val elapsed = AtomicLong(-1)
        val recorder = AudioRecorder(capMs)
        val callback = object : VelaRecordingCallback {
            override fun onAmplitude(normalized: Float) {}
            override fun onResult(result: TranscriptionResult) {}
            override fun onError(error: VelaException) {}
            override fun onDurationLimitReached() {
                val startedAt = System.currentTimeMillis()
                recorder.cancel()
                elapsed.set(System.currentTimeMillis() - startedAt)
                selfCancel.countDown()
            }
        }

        recorder.start(StubEngine(), null, callback)

        assertTrue(
            "duration-limit callback must fire on the capture thread",
            selfCancel.await(5, TimeUnit.SECONDS)
        )
        val ms = elapsed.get()
        assertTrue(
            "cancel() from the capture thread took ${ms}ms — must skip joining itself",
            ms in 0 until AudioRecorder.CANCEL_JOIN_TIMEOUT_MS
        )
        assertFalse(recorder.isRecording())
        recorder.release()
    }
}
