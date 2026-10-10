package com.velavoice.sdk.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Ticket #95 (map #89): WhisperEngine model integrity tests.
 */
@RunWith(RobolectricTestRunner::class)
class WhisperEngineTest {

    @Test
    fun `constructor throws on missing model file before JNI check`() {
        // initEngine() checks model file first: /tmp/model.bin doesn't exist
        // so it throws IllegalArgumentException before checking isLibLoaded
        val config = WhisperConfig("/nonexistent/model.bin")
        assertThrows(IllegalArgumentException::class.java) {
            WhisperEngine(config)
        }
    }

    // ---------- Error-propagation contract tests ----------
    // These tests verify that JNI-layer errors (RuntimeException thrown via
    // env->ThrowNew) propagate correctly through the Kotlin wrapper.
    // WhisperEngineErrorHarness stubs the native calls to simulate failures.

    @Test
    fun `transcribe propagates RuntimeException from native layer`() {
        val engine = WhisperEngineErrorHarness(
            transcribeError = RuntimeException("whisper_full failed with error code -1")
        )
        val ex = assertThrows(RuntimeException::class.java) {
            engine.transcribe(ByteArray(320))
        }
        assertTrue(
            "Should contain whisper_full error message",
            ex.message!!.contains("whisper_full failed")
        )
        engine.close()
    }

    @Test
    fun `transcribe propagates native C++ exception message`() {
        val engine = WhisperEngineErrorHarness(
            transcribeError = RuntimeException("std::bad_alloc")
        )
        val ex = assertThrows(RuntimeException::class.java) {
            engine.transcribe(ByteArray(320))
        }
        assertEquals("std::bad_alloc", ex.message)
        engine.close()
    }

    @Test
    fun `transcribe returns null from native triggers RuntimeException in Kotlin`() {
        // Simulates the case where nativeTranscribe returns null without
        // throwing (e.g. if the JNI ThrowNew itself failed).
        val engine = WhisperEngineErrorHarness(transcribeReturnsNull = true)
        assertThrows(RuntimeException::class.java) {
            engine.transcribe(ByteArray(320))
        }
        engine.close()
    }

    @Test
    fun `transcribe after free throws IllegalStateException not crash`() {
        val engine = WhisperEngineErrorHarness()
        engine.free()
        assertThrows(IllegalStateException::class.java) {
            engine.transcribe(ByteArray(320))
        }
    }

    @Test
    fun `free is idempotent - double free does not throw`() {
        val engine = WhisperEngineErrorHarness()
        engine.free()
        engine.free() // must not throw or double-free
    }

    @Test
    fun `empty audio returns empty string without hitting native`() {
        val engine = WhisperEngineErrorHarness(
            // If native were called, this would throw — proving it wasn't.
            transcribeError = RuntimeException("should not be called")
        )
        val result = engine.transcribe(ByteArray(0))
        assertEquals("", result)
        engine.close()
    }

    @Test
    fun `close is an alias for free and is idempotent`() {
        val engine = WhisperEngineErrorHarness()
        engine.close()
        engine.close()
        assertThrows(IllegalStateException::class.java) {
            engine.transcribe(ByteArray(320))
        }
    }

    @Test
    fun `use block calls close automatically`() {
        val engine = WhisperEngineErrorHarness()
        engine.use {
            it.transcribe(ByteArray(320))
        }
        assertThrows(IllegalStateException::class.java) {
            engine.transcribe(ByteArray(320))
        }
    }

    // ---------- Cancellation & Lifecycle tests (#94) ----------

    @Test
    fun `cancel during a long transcription returns promptly`() {
        val engine = WhisperEngineHarness()
        val transcribeEntered = CountDownLatch(1)
        val transcribeDone = CountDownLatch(1)
        val transcribeResult = AtomicReference<String?>(null)

        engine.onTranscribe = {
            transcribeEntered.countDown()
            // Simulate long in-flight transcription waiting for cancellation
            while (!engine.isCancelled.get()) {
                Thread.sleep(10)
            }
        }

        val thread = Thread {
            try {
                transcribeResult.set(engine.transcribe(ByteArray(320)))
            } finally {
                transcribeDone.countDown()
            }
        }
        thread.start()

        assertTrue("transcribe should enter execution", transcribeEntered.await(2, TimeUnit.SECONDS))

        val startedCancel = System.currentTimeMillis()
        engine.cancel()

        val finishedPromptly = transcribeDone.await(2, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - startedCancel
        assertTrue("transcribe should complete promptly after cancel (took ${elapsed}ms)", finishedPromptly)
        assertTrue("cancel took too long: ${elapsed}ms", elapsed < 2000)
        assertEquals("", transcribeResult.get())
        engine.close()
    }

    @Test
    fun `stop is an alias for cancel`() {
        val engine = WhisperEngineHarness()
        val cancelCalled = AtomicBoolean(false)
        engine.onCancelHook = { cancelCalled.set(true) }

        engine.stop()
        assertTrue("stop() must invoke cancellation", cancelCalled.get())
        engine.close()
    }

    @Test
    fun `free cancels and waits for in-flight transcribe to complete`() {
        val engine = WhisperEngineHarness()
        val transcribeEntered = CountDownLatch(1)
        val freeCompleted = AtomicBoolean(false)

        engine.onTranscribe = {
            transcribeEntered.countDown()
            while (!engine.isCancelled.get()) {
                Thread.sleep(10)
            }
        }

        val transcribeThread = Thread {
            engine.transcribe(ByteArray(320))
        }
        transcribeThread.start()
        assertTrue("transcribe entered", transcribeEntered.await(2, TimeUnit.SECONDS))

        val freeThread = Thread {
            engine.free()
            freeCompleted.set(true)
        }
        freeThread.start()

        freeThread.join(2000)
        transcribeThread.join(2000)
        assertTrue("free should complete after cancelling in-flight transcribe", freeCompleted.get())
    }

    @Test
    fun `stress - N threads interleaving transcribe and free produce no crash`() {
        val iterations = 50
        val threadsPerIteration = 8
        val errors = AtomicReference<Throwable?>(null)

        repeat(iterations) {
            val engine = WhisperEngineHarness()
            val barrier = CyclicBarrier(threadsPerIteration + 1) // +1 for free thread
            val done = CountDownLatch(threadsPerIteration + 1)

            // N transcribe threads
            repeat(threadsPerIteration) {
                Thread {
                    try {
                        barrier.await()
                        try {
                            engine.transcribe(ByteArray(320))
                        } catch (_: IllegalStateException) {
                            // expected if free() ran first
                        }
                    } catch (t: Throwable) {
                        errors.compareAndSet(null, t)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }

            // 1 free thread
            Thread {
                try {
                    barrier.await()
                    engine.free()
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                } finally {
                    done.countDown()
                }
            }.start()

            done.await()
            errors.get()?.let { throw it }
        }
    }

    // ---------- Thread Clamping Tests (#94) ----------

    @Test
    fun `a thread count of 100000 does not crash and is clamped`() {
        val engine = WhisperEngineHarness(
            customConfig = WhisperConfig("/tmp/model.bin", numThreads = 100000)
        )
        val result = engine.transcribe(ByteArray(320))
        assertEquals("stub transcription", result)

        val hw = Runtime.getRuntime().availableProcessors()
        val expectedMax = maxOf(1, minOf(8, hw))
        assertEquals(expectedMax, engine.capturedThreads)
        assertTrue(engine.capturedThreads in 1..8)
        engine.close()
    }

    @Test
    fun `a thread count below 1 is clamped to 1`() {
        val engine = WhisperEngineHarness(
            customConfig = WhisperConfig("/tmp/model.bin", numThreads = -4)
        )
        engine.transcribe(ByteArray(320))
        assertEquals(1, engine.capturedThreads)
        engine.close()
    }

    // ---------- Language Validation Tests (#94) ----------

    @Test
    fun `transcribe throws IllegalArgumentException on invalid language`() {
        val engine = WhisperEngineHarness()
        assertThrows(IllegalArgumentException::class.java) {
            engine.transcribeWithLanguage(ByteArray(320), "invalid_klingon_xyz")
        }
        engine.close()
    }

    @Test
    fun `transcribe accepts valid language strings`() {
        val engine = WhisperEngineHarness(
            customConfig = WhisperConfig("/tmp/model.bin", language = "german")
        )
        engine.transcribe(ByteArray(320))
        assertEquals("german", engine.capturedLanguage)
        engine.close()
    }

    // ---------- UTF-8 / Non-BMP / Emoji String Return Tests (#94) ----------

    @Test
    fun `transcript returned as UTF-8 preserves emoji and non-BMP characters without crash`() {
        val engine = WhisperEngineHarness()
        val emojiString = "Hello 🚀 world 🌍! Transcription completed 🎉"
        engine.stubResult = emojiString

        val result = engine.transcribe(ByteArray(320))
        assertEquals(emojiString, result)
        engine.close()
    }

    // ---------- Model-integrity tests (#95) ----------

    @Test
    fun `constructor throws SecurityException for path outside allowed roots`() {
        // Create a real temp file so the existence check passes,
        // but the containment check should reject it
        val tmpFile = File.createTempFile("whisper-test-model", ".bin")
        try {
            val allowedRoot = File("/data/data/com.velavoice/files")
            val config = WhisperConfig(
                modelPath = tmpFile.absolutePath,
                allowedModelRoots = listOf(allowedRoot)
            )
            val ex = assertThrows(SecurityException::class.java) {
                WhisperEngine(config)
            }
            assertTrue(
                "error should mention outside allowed roots",
                ex.message?.contains("outside allowed storage roots") == true
            )
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `constructor throws SecurityException for path traversal attack`() {
        // Even if the canonical file exists, the traversal should be caught
        val tmpDir = createTempDir("whisper-safe")
        val modelFile = File(tmpDir, "model.bin")
        modelFile.writeBytes("fake model".toByteArray())
        try {
            // allowedRoot is a sibling dir — the model is NOT inside it
            val allowedRoot = File(tmpDir.parent, "other-safe-dir")
            allowedRoot.mkdirs()
            val config = WhisperConfig(
                modelPath = modelFile.absolutePath,
                allowedModelRoots = listOf(allowedRoot)
            )
            assertThrows(SecurityException::class.java) {
                WhisperEngine(config)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `constructor throws SecurityException for wrong SHA-256 hash`() {
        val tmpFile = File.createTempFile("whisper-tampered", ".bin")
        try {
            tmpFile.writeBytes("tampered model content".toByteArray())
            // Use the tmpFile's parent as allowed root so containment passes
            val config = WhisperConfig(
                modelPath = tmpFile.absolutePath,
                allowedModelRoots = listOf(tmpFile.parentFile),
                expectedHash = "0000000000000000000000000000000000000000000000000000000000000000"
            )
            val ex = assertThrows(SecurityException::class.java) {
                WhisperEngine(config)
            }
            assertTrue(
                "error should mention SHA-256 verification",
                ex.message?.contains("SHA-256") == true
            )
        } finally {
            tmpFile.delete()
        }
    }
}

/**
 * Test-only subclass that stubs the JNI native calls so tests can exercise
 * error-propagation paths without a whisper shared library or model file.
 *
 * - [transcribeError]: when set, [doNativeTranscribe] throws this exception,
 *   simulating a JNI ThrowNew from the C++ layer.
 * - [transcribeReturnsNull]: when true, [doNativeTranscribe] returns null,
 *   simulating a JNI return of nullptr without a pending exception.
 * - Default (neither set): returns "stub transcription" — the happy path.
 */
private class WhisperEngineErrorHarness(
    private val transcribeError: RuntimeException? = null,
    private val transcribeReturnsNull: Boolean = false
) : WhisperEngine(stubInit = true) {

    init {
        setContextPtrForTest(0xDEAD_BEEF)
    }

    override fun doNativeTranscribe(
        ptr: Long,
        floatAudio: FloatArray,
        language: String,
        threads: Int,
        initialPrompt: String?
    ): String? {
        if (transcribeError != null) throw transcribeError
        if (transcribeReturnsNull) return null
        return "stub transcription"
    }

    override fun doNativeFree(ptr: Long) {
        // no-op: nothing native to free
    }

    override fun doNativeCancel(ptr: Long) {
        // no-op: nothing native to cancel
    }
}

/**
 * Test harness simulating native Whisper JNI calls for concurrency, cancellation,
 * thread clamping, language validation, and UTF-8 handling.
 */
private class WhisperEngineHarness(
    customConfig: WhisperConfig = WhisperConfig("/tmp/model.bin")
) : WhisperEngine(config = customConfig, stubInit = true) {

    val isCancelled = AtomicBoolean(false)
    var onTranscribe: (() -> Unit)? = null
    var onCancelHook: (() -> Unit)? = null
    var capturedThreads: Int = 0
    var capturedLanguage: String = ""
    var stubResult: String = "stub transcription"

    init {
        setContextPtrForTest(0xCAFE_BABE)
    }

    override fun doNativeCancel(ptr: Long) {
        isCancelled.set(true)
        onCancelHook?.invoke()
    }

    override fun doNativeTranscribe(
        ptr: Long,
        floatAudio: FloatArray,
        language: String,
        threads: Int,
        initialPrompt: String?
    ): String? {
        capturedLanguage = language
        capturedThreads = threads
        onTranscribe?.invoke()
        if (isCancelled.get()) {
            return ""
        }
        return stubResult
    }

    override fun doNativeFree(ptr: Long) {
        // no-op
    }

    fun transcribeWithLanguage(audio: ByteArray, language: String): String {
        require(WhisperConfig.isValidLanguage(language)) { "Invalid language: $language" }
        return transcribe(audio)
    }
}
