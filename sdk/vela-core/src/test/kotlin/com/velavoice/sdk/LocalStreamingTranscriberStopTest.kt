package com.velavoice.sdk

import com.velavoice.sdk.whisper.WhisperConfig
import com.velavoice.sdk.whisper.WhisperEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class LocalStreamingTranscriberStopTest {

    private fun createTempModelFile(): File {
        val file = File.createTempFile("test_whisper_model", ".bin")
        file.deleteOnExit()
        return file
    }

    private fun createPcmChunk(samples: Int): ByteArray {
        val bytes = ByteArray(samples * 2)
        // fill with non-silent audio so VAD doesn't skip it
        for (i in 0 until samples) {
            val sampleVal: Short = if (i % 2 == 0) 10000 else -10000
            bytes[i * 2] = (sampleVal.toInt() and 0xff).toByte()
            bytes[i * 2 + 1] = ((sampleVal.toInt() shr 8) and 0xff).toByte()
        }
        return bytes
    }

    @Test
    fun `stop returns in bounded time on caller thread and does not block for long inference`() {
        val modelFile = createTempModelFile()
        val engine = StopTestWhisperEngine()

        val transcribeStarted = CountDownLatch(1)
        val transcribeBlock = CountDownLatch(1)
        engine.onTranscribe = {
            transcribeStarted.countDown()
            transcribeBlock.await(5, TimeUnit.SECONDS)
        }

        val transcriber = LocalStreamingTranscriber(
            WhisperConfig(modelFile.absolutePath, "en", 4)
        ) { engine }

        val streamConfig = StreamConfig(
            chunkDurationMs = 100,
            windowDurationMs = 200,
            overlapMs = 50,
            useVad = false,
            modelPath = modelFile.absolutePath
        )

        transcriber.start(streamConfig)

        // Emit audio to trigger processing
        val chunk = createPcmChunk(3200) // 200ms at 16kHz
        transcriber.emit(chunk)

        assertTrue(
            "Processor thread should enter transcribe",
            transcribeStarted.await(5, TimeUnit.SECONDS)
        )

        // Call stop on caller thread while transcribe is actively running
        val t0 = System.currentTimeMillis()
        transcriber.stop()
        val elapsed = System.currentTimeMillis() - t0

        assertTrue("stop() must return in bounded time (< 500ms), took ${elapsed}ms", elapsed < 500)
        assertFalse(
            "engine must NOT be freed while transcribe is in-flight on processing thread",
            engine.isFreed
        )

        // Release the transcribe block
        transcribeBlock.countDown()

        // Wait for teardown to complete
        assertTrue("teardown should finish cleanly", transcriber.awaitTeardown(5000))
        assertTrue("engine must be freed after processor thread terminates", engine.isFreed)
        assertEquals(
            "no native transcribe calls after free",
            0,
            engine.nativeCallsAfterFree.get()
        )
    }

    @Test
    fun `engine is never freed while processor thread is live even if processing exceeds old 5s timeout`() {
        val modelFile = createTempModelFile()
        val engine = StopTestWhisperEngine()

        val transcribeStarted = CountDownLatch(1)
        val transcribeBlock = CountDownLatch(1)
        engine.onTranscribe = {
            transcribeStarted.countDown()
            transcribeBlock.await(5, TimeUnit.SECONDS)
        }

        val transcriber = LocalStreamingTranscriber(
            WhisperConfig(modelFile.absolutePath, "en", 4)
        ) { engine }

        val streamConfig = StreamConfig(
            chunkDurationMs = 100,
            windowDurationMs = 200,
            overlapMs = 50,
            useVad = false,
            modelPath = modelFile.absolutePath
        )

        transcriber.start(streamConfig)
        transcriber.emit(createPcmChunk(3200))

        assertTrue(transcribeStarted.await(5, TimeUnit.SECONDS))

        transcriber.stop()

        // Wait a bit to ensure teardown thread is actively waiting on join
        Thread.sleep(150)

        // While transcribe is blocked, the engine must still not be freed
        assertFalse(
            "engine must not be freed while processor is alive",
            engine.isFreed
        )

        // Release transcribe
        transcribeBlock.countDown()

        assertTrue(transcriber.awaitTeardown(5000))
        assertTrue(engine.isFreed)
        assertEquals(0, engine.nativeCallsAfterFree.get())
    }

    @Test
    fun `flushRemaining and onFinal run on processing thread off caller thread`() {
        val modelFile = createTempModelFile()
        val engine = StopTestWhisperEngine()

        val transcriber = LocalStreamingTranscriber(
            WhisperConfig(modelFile.absolutePath, "en", 4)
        ) { engine }

        val finalCallbackThread = AtomicReference<Thread?>(null)
        val onFinalCalled = CountDownLatch(1)

        transcriber.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {}
            override fun onFinal(text: String) {
                finalCallbackThread.set(Thread.currentThread())
                onFinalCalled.countDown()
            }
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        val streamConfig = StreamConfig(
            chunkDurationMs = 500,
            windowDurationMs = 1000,
            overlapMs = 200,
            useVad = false,
            modelPath = modelFile.absolutePath
        )

        transcriber.start(streamConfig)
        transcriber.emit(createPcmChunk(16000)) // 1 second

        val callerThread = Thread.currentThread()
        transcriber.stop()

        assertTrue("onFinal should be called during stop flush", onFinalCalled.await(5, TimeUnit.SECONDS))
        assertTrue(transcriber.awaitTeardown(5000))

        val callbackThread = finalCallbackThread.get()
        assertNotNull("onFinal must have been called", callbackThread)
        assertNotEquals(
            "onFinal must NOT run on the caller thread",
            callerThread,
            callbackThread
        )
        assertTrue(
            "onFinal should run on LocalStreamingProcessor thread, was: ${callbackThread?.name}",
            callbackThread?.name?.contains("LocalStreamingProcessor") == true
        )
    }

    @Test
    fun `emit drops chunks after stop is called`() {
        val modelFile = createTempModelFile()
        val engine = StopTestWhisperEngine()

        val transcriber = LocalStreamingTranscriber(
            WhisperConfig(modelFile.absolutePath, "en", 4)
        ) { engine }

        val streamConfig = StreamConfig(
            chunkDurationMs = 500,
            windowDurationMs = 1000,
            overlapMs = 200,
            useVad = false,
            modelPath = modelFile.absolutePath
        )

        transcriber.start(streamConfig)
        assertTrue(transcriber.isRunning)

        transcriber.stop()
        assertFalse(transcriber.isRunning)

        // Try emitting after stop
        val chunk = createPcmChunk(3200)
        val prevBuffered = transcriber.bufferedBytes
        transcriber.emit(chunk)

        assertEquals("emit after stop should not increase bufferedBytes", prevBuffered, transcriber.bufferedBytes)
        transcriber.awaitTeardown(5000)
    }

    @Test
    fun `concurrent stop calls are thread-safe and idempotent`() {
        val modelFile = createTempModelFile()
        val engine = StopTestWhisperEngine()

        val transcriber = LocalStreamingTranscriber(
            WhisperConfig(modelFile.absolutePath, "en", 4)
        ) { engine }

        val streamConfig = StreamConfig(
            chunkDurationMs = 100,
            windowDurationMs = 200,
            overlapMs = 50,
            useVad = false,
            modelPath = modelFile.absolutePath
        )

        transcriber.start(streamConfig)

        val threadCount = 10
        val barrier = CyclicBarrier(threadCount)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) {
            Thread {
                barrier.await()
                transcriber.stop()
                done.countDown()
            }.start()
        }

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(transcriber.awaitTeardown(5000))
        assertTrue("engine must be freed", engine.isFreed)
        assertEquals("engine.free must be called once", 1, engine.freeCalls.get())
    }

    @Test
    fun `subsequent start after stop drains prior teardown cleanly`() {
        val modelFile = createTempModelFile()
        val engine1 = StopTestWhisperEngine()
        val engine2 = StopTestWhisperEngine()
        val engines = mutableListOf(engine1, engine2)

        val transcriber = LocalStreamingTranscriber(
            WhisperConfig(modelFile.absolutePath, "en", 4)
        ) { engines.removeAt(0) }

        val streamConfig = StreamConfig(
            chunkDurationMs = 100,
            windowDurationMs = 200,
            overlapMs = 50,
            useVad = false,
            modelPath = modelFile.absolutePath
        )

        // Session 1
        transcriber.start(streamConfig)
        transcriber.emit(createPcmChunk(3200))
        transcriber.stop()

        // Session 2 started immediately
        transcriber.start(streamConfig)
        assertTrue("Session 1 engine must be freed before/when Session 2 starts", engine1.isFreed)
        assertTrue(transcriber.isRunning)

        transcriber.stop()
        assertTrue(transcriber.awaitTeardown(5000))
        assertTrue("Session 2 engine must be freed after stop", engine2.isFreed)
    }
}

private class StopTestWhisperEngine : WhisperEngine(stubInit = true) {
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

    override fun doNativeCancel(ptr: Long) {
        // no-op: nothing native to cancel in tests
    }
}
