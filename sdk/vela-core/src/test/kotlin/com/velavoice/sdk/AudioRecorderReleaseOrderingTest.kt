package com.velavoice.sdk

import com.velavoice.sdk.whisper.WhisperEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowFakeAudioRecord::class])
class AudioRecorderReleaseOrderingTest {

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

    private fun getTranscribeThread(recorder: AudioRecorder): Thread? {
        val field = AudioRecorder::class.java.getDeclaredField("transcribeThread")
        field.isAccessible = true
        return field.get(recorder) as? Thread
    }

    @Test
    fun `VelaTranscriber release calls audioRecorder release before whisperEngine free`() {
        val recorderReleased = AtomicBoolean(false)
        val recorderReleasedBeforeEngineFree = AtomicBoolean(false)

        val recorder = object : AudioRecorder() {
            override fun release() {
                recorderReleased.set(true)
                super.release()
            }
        }
        val engine = object : TestWhisperEngine() {
            override fun doNativeFree(ptr: Long) {
                if (recorderReleased.get()) {
                    recorderReleasedBeforeEngineFree.set(true)
                }
                super.doNativeFree(ptr)
            }
        }

        val transcriber = VelaTranscriber(engine, null, recorder)
        transcriber.release()

        assertTrue(
            "audioRecorder.release() must be called before whisperEngine.free()",
            recorderReleasedBeforeEngineFree.get()
        )
    }

    @Test
    fun `VelaTranscriber release drains in-flight transcribe before calling whisperEngine free`() {
        ShadowFakeAudioRecord.readResult = 2

        val engine = TestWhisperEngine()
        val recorder = AudioRecorder()
        val transcriber = VelaTranscriber(engine, null, recorder)
        val callback = mock(VelaRecordingCallback::class.java)

        val transcribeStarted = CountDownLatch(1)
        val transcribeRelease = CountDownLatch(1)
        val freeStarted = CountDownLatch(1)
        val freeCompleted = AtomicBoolean(false)

        engine.onTranscribe = {
            transcribeStarted.countDown()
            transcribeRelease.await(5, TimeUnit.SECONDS)
        }

        transcriber.startRecording(callback)
        val deadline = System.currentTimeMillis() + 3000
        while (recordedBufferSize(recorder) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        transcriber.stopRecording(clean = false)

        assertTrue(
            "transcribe thread never entered transcribe",
            transcribeStarted.await(5, TimeUnit.SECONDS)
        )

        val releaseThread = Thread {
            freeStarted.countDown()
            transcriber.release()
            freeCompleted.set(true)
        }
        releaseThread.start()
        assertTrue(freeStarted.await(2, TimeUnit.SECONDS))

        Thread.sleep(100)
        assertFalse(
            "engine should not be freed while in-flight transcribe is still executing",
            engine.isFreed
        )
        assertFalse(
            "transcriber.release() should not finish while transcribe is in-flight",
            freeCompleted.get()
        )

        transcribeRelease.countDown()
        releaseThread.join(5000)

        assertTrue("transcriber.release() should complete after transcribe finishes", freeCompleted.get())
        assertTrue("engine should be freed after release() completes", engine.isFreed)
        assertEquals(
            "No native transcribe call should happen after free",
            0,
            engine.nativeCallsAfterFree.get()
        )
    }

    @Test
    fun `AudioRecorder stop stores transcribe thread and drains on release`() {
        ShadowFakeAudioRecord.readResult = 2

        val engine = TestWhisperEngine()
        val recorder = AudioRecorder()
        val callback = mock(VelaRecordingCallback::class.java)

        val transcribeStarted = CountDownLatch(1)
        val transcribeRelease = CountDownLatch(1)
        engine.onTranscribe = {
            transcribeStarted.countDown()
            transcribeRelease.await(5, TimeUnit.SECONDS)
        }

        recorder.start(engine, null, callback)
        val deadline = System.currentTimeMillis() + 3000
        while (recordedBufferSize(recorder) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        recorder.stop(clean = false)
        assertTrue(transcribeStarted.await(5, TimeUnit.SECONDS))

        val transcribeThread = getTranscribeThread(recorder)
        assertNotNull("AudioRecorder must store transcribe thread in a field", transcribeThread)
        assertTrue("transcribe thread should be alive", transcribeThread!!.isAlive)

        val releaseThread = Thread {
            recorder.release()
        }
        releaseThread.start()
        Thread.sleep(100)
        assertTrue("release() must wait for transcribe thread to drain", releaseThread.isAlive)

        transcribeRelease.countDown()
        releaseThread.join(5000)
        assertFalse("releaseThread should have finished", releaseThread.isAlive)
        assertFalse("transcribeThread should have finished", transcribeThread.isAlive)
    }

    @Test
    fun `cancel during processing cancels in-flight transcribe and terminates deterministically`() {
        ShadowFakeAudioRecord.readResult = 2

        val engine = TestWhisperEngine()
        val recorder = AudioRecorder()
        val callback = mock(VelaRecordingCallback::class.java)

        val transcribeStarted = CountDownLatch(1)
        val allowTranscribe = CountDownLatch(1)
        engine.onTranscribe = {
            transcribeStarted.countDown()
            allowTranscribe.await(5, TimeUnit.SECONDS)
        }

        recorder.start(engine, null, callback)
        val deadline = System.currentTimeMillis() + 3000
        while (recordedBufferSize(recorder) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        recorder.stop(clean = false)
        assertTrue(transcribeStarted.await(5, TimeUnit.SECONDS))

        val transcribeThread = getTranscribeThread(recorder)
        assertNotNull(transcribeThread)

        recorder.cancel()
        allowTranscribe.countDown()

        assertFalse("cancel() must join transcribe thread so it is not orphaned", transcribeThread!!.isAlive)
        engine.free()
        assertEquals("No native transcribe call should happen after free", 0, engine.nativeCallsAfterFree.get())
    }

    @Test
    fun `cancel during processing asserts no native call happens after free`() {
        ShadowFakeAudioRecord.readResult = 2

        val engine = TestWhisperEngine()
        val recorder = AudioRecorder()
        val callback = mock(VelaRecordingCallback::class.java)

        val transcribeEntered = CountDownLatch(1)
        val allowTranscribe = CountDownLatch(1)
        engine.onTranscribe = {
            transcribeEntered.countDown()
            allowTranscribe.await(5, TimeUnit.SECONDS)
        }

        recorder.start(engine, null, callback)
        val deadline = System.currentTimeMillis() + 3000
        while (recordedBufferSize(recorder) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue("Capture thread should have recorded audio", recordedBufferSize(recorder) > 0)

        recorder.stop(clean = false)

        assertTrue(
            "Transcribe thread never entered transcribe",
            transcribeEntered.await(5, TimeUnit.SECONDS)
        )

        recorder.cancel()
        allowTranscribe.countDown()
        engine.free()

        assertEquals(
            "No native transcribe call should happen after free",
            0,
            engine.nativeCallsAfterFree.get()
        )
    }

    @Test
    fun `cancel before transcribe prevents any native transcribe call`() {
        ShadowFakeAudioRecord.readResult = 2

        val engine = TestWhisperEngine()
        val recorder = AudioRecorder()
        val callback = mock(VelaRecordingCallback::class.java)

        recorder.start(engine, null, callback)
        val deadline = System.currentTimeMillis() + 3000
        while (recordedBufferSize(recorder) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        recorder.stop(clean = false)
        recorder.cancel()
        engine.free()

        assertEquals("No native transcribe should be called after cancel and free", 0, engine.nativeCallsAfterFree.get())
    }

    @Test
    fun `drainTranscribeThread cancels and terminates thread if join times out`() {
        ShadowFakeAudioRecord.readResult = 2

        val engine = TestWhisperEngine()
        val recorder = AudioRecorder()
        val callback = mock(VelaRecordingCallback::class.java)

        val transcribeStarted = CountDownLatch(1)
        val transcribeBlocked = CountDownLatch(1)
        engine.onTranscribe = {
            transcribeStarted.countDown()
            try {
                transcribeBlocked.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        recorder.start(engine, null, callback)
        val deadline = System.currentTimeMillis() + 3000
        while (recordedBufferSize(recorder) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        recorder.stop(clean = false)
        assertTrue(transcribeStarted.await(5, TimeUnit.SECONDS))

        val drained = recorder.drainTranscribeThread(50L)
        assertFalse("drainTranscribeThread should report false on timeout", drained)

        val thread = getTranscribeThread(recorder)
        assertNull("transcribeThread reference should be cleared after timeout cancel", thread)

        engine.free()
        assertEquals(0, engine.nativeCallsAfterFree.get())
    }
}

private open class TestWhisperEngine : WhisperEngine(stubInit = true) {
    val nativeTranscribeCalls = AtomicInteger(0)
    val nativeCallsAfterFree = AtomicInteger(0)
    val freeCalls = AtomicInteger(0)
    @Volatile var isFreed = false
    var onTranscribe: (() -> Unit)? = null

    init {
        setContextPtrForTest(0x1234_5678L)
    }

    override fun doNativeTranscribe(
        ptr: Long,
        floatAudio: FloatArray,
        language: String,
        threads: Int,
        initialPrompt: String?
    ): String {
        nativeTranscribeCalls.incrementAndGet()
        if (isFreed) {
            nativeCallsAfterFree.incrementAndGet()
        }
        onTranscribe?.invoke()
        return "transcription result"
    }

    override fun doNativeFree(ptr: Long) {
        freeCalls.incrementAndGet()
        isFreed = true
    }
}
