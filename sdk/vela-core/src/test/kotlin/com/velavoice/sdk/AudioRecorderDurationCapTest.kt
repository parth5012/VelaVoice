package com.velavoice.sdk

import android.os.Looper
import com.velavoice.sdk.whisper.WhisperEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ticket #104: AudioRecorder's per-session PCM buffer is hard-capped by a
 * max recording duration. Synthetic reads stand in for a real microphone.
 *
 * What these tests prove (and what they do not):
 * - Proven: at the configured cap the capture thread stops accumulating,
 *   signals the IME exactly once via onDurationLimitReached, and the retained
 *   PCM (the recordedAudioData byte array) never exceeds the duration-implied
 *   byte budget — including under a sustained synthetic "five-minute" pump
 *   that keeps serving reads long after the cap fired.
 * - Not proven: real-microphone timing (Robolectric's AudioRecord is a fake;
 *   wall-clock and audio-byte caps are both exercised synthetically), and
 *   Java heap deltas (the byte-array length of the retained buffer is used as
 *   the memory proxy the ticket allows).
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowFakeAudioRecord::class])
class AudioRecorderDurationCapTest {

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

    /** Callback that records every event and counts down on the duration-limit signal. */
    private class RecordingCallback : VelaRecordingCallback {
        val limitSignal = CountDownLatch(1)
        val limitCount = AtomicInteger(0)
        val results = CopyOnWriteArrayList<TranscriptionResult>()
        val errors = CopyOnWriteArrayList<VelaException>()
        override fun onAmplitude(normalized: Float) {}
        override fun onResult(result: TranscriptionResult) { results.add(result) }
        override fun onError(error: VelaException) { errors.add(error) }
        override fun onDurationLimitReached() {
            limitCount.incrementAndGet()
            limitSignal.countDown()
        }
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

    /** Poll the paused main looper until [callback] sees a result (AudioRecorder posts onResult there). */
    private fun awaitResult(callback: RecordingCallback, timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (callback.results.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun capBytesFor(capMs: Long): Int = (capMs * AudioRecorder.PCM_BYTES_PER_SECOND / 1000).toInt()

    @Test
    fun `duration cap stops accumulation and signals the IME exactly once at the configured limit`() {
        val capMs = 250L
        val capBytes = capBytesFor(capMs)
        ShadowFakeAudioRecord.readResult = 2048 // 4096 bytes per synthetic read
        val recorder = AudioRecorder(capMs)
        val callback = RecordingCallback()

        recorder.start(StubEngine(), null, callback)

        assertTrue(
            "onDurationLimitReached must fire when the cap is hit",
            callback.limitSignal.await(5, TimeUnit.SECONDS)
        )
        // Give any duplicate-signal a chance to arrive before asserting exactly-once.
        Thread.sleep(100)
        assertEquals("limit signal must fire exactly once", 1, callback.limitCount.get())
        assertTrue(
            "retained PCM ${recordedBufferSize(recorder)} exceeds cap $capBytes bytes",
            recordedBufferSize(recorder) <= capBytes
        )
        // Sustained pumping past the cap must not grow the buffer: the capture
        // thread has exited, so further synthetic reads go nowhere.
        Thread.sleep(200)
        assertTrue(
            "retained PCM grew after the cap fired",
            recordedBufferSize(recorder) <= capBytes
        )

        // The IME's normal stop path still transcribes the capped session.
        recorder.stop(clean = false)
        awaitResult(callback)
        assertEquals("stop() after the cap must still deliver one result", 1, callback.results.size)
        val audioBytes = callback.results[0].audioBytes
        assertTrue(
            "transcribed audio ${audioBytes?.size ?: -1} exceeds cap $capBytes bytes",
            audioBytes != null && audioBytes.size <= capBytes
        )
        recorder.release()
    }

    @Test
    fun `synthetic five-minute recording stays under the byte cap`() {
        // Realtime 5 minutes of 16 kHz 16-bit mono = 300 s * 32000 B/s = 9.6 MB
        // of PCM if uncapped. The test configures a 500 ms cap (16000 bytes)
        // so the run stays fast; the shadow keeps serving synthetic reads for
        // the whole simulated session.
        val capMs = 500L
        val capBytes = capBytesFor(capMs)
        ShadowFakeAudioRecord.readResult = 2048
        val recorder = AudioRecorder(capMs)
        val callback = RecordingCallback()

        recorder.start(StubEngine(), null, callback)
        assertTrue(callback.limitSignal.await(5, TimeUnit.SECONDS))

        // Simulate the remainder of the long session: keep the recorder in its
        // (post-cap) running state while synthetic reads would keep arriving,
        // and assert the retained buffer never exceeds the cap at any point.
        val pumpUntil = System.currentTimeMillis() + 1000L
        while (System.currentTimeMillis() < pumpUntil) {
            assertTrue(
                "retained PCM ${recordedBufferSize(recorder)} exceeded cap $capBytes mid-session",
                recordedBufferSize(recorder) <= capBytes
            )
            Thread.sleep(20)
        }

        recorder.stop(clean = false)
        awaitResult(callback)
        assertEquals(1, callback.limitCount.get())
        val audioBytes = callback.results.firstOrNull()?.audioBytes
        assertTrue(
            "5-minute synthetic session retained ${audioBytes?.size ?: -1} bytes, " +
                "cap is $capBytes (uncapped would be 9600000)",
            audioBytes != null && audioBytes.size <= capBytes
        )
        assertTrue("errors must not fire on a capped session", callback.errors.isEmpty())
        recorder.release()
    }

    @Test
    fun `wall-clock cap fires even when reads return no data`() {
        // A muted/stuck mic returns 0 samples forever; the wall-clock cap must
        // still auto-stop the session so state and the mic are released.
        val capMs = 150L
        ShadowFakeAudioRecord.readResult = 0
        val recorder = AudioRecorder(capMs)
        val callback = RecordingCallback()

        recorder.start(StubEngine(), null, callback)

        assertTrue(
            "wall-clock cap must signal even with zero-sample reads",
            callback.limitSignal.await(5, TimeUnit.SECONDS)
        )
        assertEquals(0, recordedBufferSize(recorder))
        recorder.stop(clean = false)
        awaitResult(callback)
        recorder.release()
    }

    @Test
    fun `user stop before the cap does not signal the duration limit`() {
        val capMs = 60_000L // the production cap: a short session must never signal
        ShadowFakeAudioRecord.readResult = 2
        val recorder = AudioRecorder(capMs)
        val callback = RecordingCallback()

        recorder.start(StubEngine(), null, callback)
        val deadline = System.currentTimeMillis() + 3000
        while (recordedBufferSize(recorder) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        recorder.stop(clean = false)
        awaitResult(callback)

        assertEquals("a user stop must not emit a duration-limit signal", 0, callback.limitCount.get())
        assertEquals(1, callback.results.size)
        recorder.release()
    }
}
