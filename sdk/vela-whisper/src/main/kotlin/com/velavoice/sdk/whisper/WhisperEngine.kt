package com.velavoice.sdk.whisper

import android.util.Log
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thin JNI wrapper around whisper.cpp.
 *
 * Thread-safety contract:
 *  - Multiple concurrent [transcribe] calls are safe (read lock).
 *  - [free]/[close] acquires the write lock, so it blocks until every
 *    in-flight [transcribe] drains before releasing the native context.
 *  - Double-[free] is idempotent; post-free [transcribe] fails fast
 *    with [IllegalStateException].
 *
 * Implements [AutoCloseable] so the lifetime is expressible with `use {}`.
 */
open class WhisperEngine private constructor(
    private val config: WhisperConfig?,
    @Suppress("UNUSED_PARAMETER") stubInit: Boolean
) : AutoCloseable {

    /**
     * Primary production constructor — loads the JNI library and
     * initialises the native whisper context from [config].
     */
    constructor(config: WhisperConfig) : this(config, stubInit = false) {
        try {
            System.loadLibrary("whisper")
            isLibLoaded = true
            Log.d(TAG, "Successfully loaded whisper JNI library")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Could not load whisper JNI library", e)
        }
        initEngine()
    }

    /**
     * Test-only constructor — skips JNI loading and model-file checks.
     * Subclasses must call [setContextPtrForTest] to install a fake pointer
     * and override [doNativeTranscribe] / [doNativeFree].
     */
    protected constructor(stubInit: Boolean) : this(config = null, stubInit = true)

    private var isLibLoaded = false

    /** Guarded by [lock]. Marked @Volatile for safe reads outside the lock
     *  (e.g. the fast-path isEmpty check in [transcribe]). */
    @Volatile
    private var contextPtr: Long = 0L

    /** Mutual exclusion: serializes both in-flight transcribe calls (whisper_full is not
     *  thread-safe on the same context) and free. */
    private val lock = ReentrantLock()

    // -- init helpers -------------------------------------------------------

    private fun initEngine() {
        val cfg = config ?: throw IllegalStateException("No config provided")
        val modelFile = File(cfg.modelPath)
        if (!modelFile.exists()) {
            throw IllegalArgumentException("Model file not found at: ${cfg.modelPath}")
        }
        if (!isLibLoaded) {
            throw IllegalStateException("JNI library not loaded")
        }
        contextPtr = nativeInit(cfg.modelPath)
        if (contextPtr == 0L) {
            throw RuntimeException("Failed to initialize native Whisper context")
        }
    }

    /** Visible to test harness so it can install a fake pointer. */
    protected fun setContextPtrForTest(ptr: Long) {
        contextPtr = ptr
    }

    // -- public API ---------------------------------------------------------

    fun transcribe(audioBytes: ByteArray, initialPrompt: String? = null): String {
        if (audioBytes.isEmpty()) return ""
        val floatAudio = AudioConverter.convertPcmToFloat(audioBytes)

        lock.withLock {
            val ptr = contextPtr
            if (ptr == 0L) {
                throw IllegalStateException("Whisper context is not initialized or has been freed")
            }
            return doNativeTranscribe(
                ptr, floatAudio,
                config?.language ?: "en",
                config?.numThreads ?: 4,
                initialPrompt
            ) ?: throw RuntimeException("Error during native transcription")
        }
    }

    /**
     * Release the native whisper context.
     *
     * Blocks until every in-flight [transcribe] call has finished.
     * Idempotent — calling [free] (or [close]) more than once is safe.
     */
    fun free() {
        lock.withLock {
            val ptr = contextPtr
            if (ptr != 0L) {
                doNativeFree(ptr)
                contextPtr = 0L
            }
        }
    }

    /** [AutoCloseable] — delegates to [free]. */
    override fun close() = free()

    // -- native call points (open for test override) ------------------------

    /** Override in test harnesses to stub native transcription. */
    protected open fun doNativeTranscribe(
        ptr: Long,
        floatAudio: FloatArray,
        language: String,
        threads: Int,
        initialPrompt: String?
    ): String? = nativeTranscribe(ptr, floatAudio, language, threads, initialPrompt)

    /** Override in test harnesses to stub native free. */
    protected open fun doNativeFree(ptr: Long) = nativeFree(ptr)

    // -- JNI externals ------------------------------------------------------

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(
        contextPtr: Long, audioData: FloatArray,
        language: String, threads: Int, initialPrompt: String?
    ): String?
    private external fun nativeFree(contextPtr: Long)

    private companion object {
        const val TAG = "WhisperEngine"
    }
}
