package com.velavoice.sdk.whisper

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    // ---------- Concurrency contract tests ----------
    // These tests verify the locking, idempotency, and AutoCloseable
    // semantics at the Kotlin level. They use WhisperEngineHarness —
    // a test-only subclass that stubs the native calls — so no JNI
    // library or model file is required.

    @Test
    fun `free is idempotent - double free does not throw`() {
        val engine = WhisperEngineHarness()
        engine.free()
        engine.free() // must not throw or double-free
    }

    @Test
    fun `transcribe after free throws IllegalStateException`() {
        val engine = WhisperEngineHarness()
        engine.free()
        assertThrows(IllegalStateException::class.java) {
            engine.transcribe(ByteArray(320))
        }
    }

    @Test
    fun `close is an alias for free and is idempotent`() {
        val engine = WhisperEngineHarness()
        engine.close() // AutoCloseable.close()
        engine.close() // must not throw
        assertThrows(IllegalStateException::class.java) {
            engine.transcribe(ByteArray(320))
        }
    }

    @Test
    fun `use block calls close automatically`() {
        val engine = WhisperEngineHarness()
        engine.use {
            it.transcribe(ByteArray(320)) // should succeed
        }
        // After use{}, engine is closed
        assertThrows(IllegalStateException::class.java) {
            engine.transcribe(ByteArray(320))
        }
    }

    @Test
    fun `free blocks until in-flight transcribe completes`() {
        // Verify that free() waits for a transcribe() that is already
        // holding the read lock. WhisperEngineHarness lets us inject
        // a latch so transcribe blocks inside the lock.
        val engine = WhisperEngineHarness()
        val transcribeStarted = CountDownLatch(1)
        val transcribeRelease = CountDownLatch(1)
        engine.onTranscribe = {
            transcribeStarted.countDown()
            transcribeRelease.await() // hold read lock
        }

        val freeCompleted = AtomicBoolean(false)
        val transcribeThread = Thread {
            engine.transcribe(ByteArray(320))
        }
        transcribeThread.start()
        transcribeStarted.await() // transcribe is inside the lock

        val freeThread = Thread {
            engine.free() // must block until transcribe finishes
            freeCompleted.set(true)
        }
        freeThread.start()

        Thread.sleep(100) // give free() time to attempt the lock
        // free should still be blocked because transcribe holds the read lock
        assertTrue("free() should block while transcribe is in-flight", !freeCompleted.get())

        transcribeRelease.countDown() // let transcribe finish
        transcribeThread.join(2000)
        freeThread.join(2000)
        assertTrue("free() should complete after transcribe finishes", freeCompleted.get())
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
}

/**
 * Test-only subclass that stubs the JNI native calls and the init
 * path so tests can exercise the locking contract without a whisper
 * shared library or model file.
 *
 * The [onTranscribe] hook is called while the read lock is held,
 * letting tests inject delays to provoke the race.
 */
private class WhisperEngineHarness : WhisperEngine(stubInit = true) {

    /** Called inside transcribeGuarded, while the read lock is held. */
    var onTranscribe: (() -> Unit)? = null

    init {
        // Simulate a valid context pointer
        setContextPtrForTest(0xDEAD_BEEF)
    }

    override fun doNativeTranscribe(
        ptr: Long,
        floatAudio: FloatArray,
        language: String,
        threads: Int,
        initialPrompt: String?
    ): String {
        onTranscribe?.invoke()
        return "stub"
    }

    override fun doNativeFree(ptr: Long) {
        // no-op: nothing native to free
    }
}
