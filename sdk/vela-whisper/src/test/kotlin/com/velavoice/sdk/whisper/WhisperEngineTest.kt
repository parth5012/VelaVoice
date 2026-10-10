package com.velavoice.sdk.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
