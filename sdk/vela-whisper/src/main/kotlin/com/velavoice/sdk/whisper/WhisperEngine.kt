package com.velavoice.sdk.whisper

import android.util.Log
import com.velavoice.sdk.ModelIntegrity
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thin JNI wrapper around whisper.cpp.
 *
 * Thread-safety contract:
 *  - [transcribe] and [free] are mutually exclusive (whisper_full is not
 *    thread-safe on the same context; WHISPER_LOCKING is disabled).
 *  - [cancel]/[stop] signals in-flight transcription to abort promptly.
 *  - [free]/[close] signals abort first, then acquires the exclusive lock,
 *    blocking until every in-flight [transcribe] drains before releasing
 *    the native context.
 *  - Double-[free] is idempotent; post-free [transcribe] fails fast
 *    with [IllegalStateException].
 *
 * Implements [AutoCloseable] so the lifetime is expressible with `use {}`.
 *
 * Ticket #95 (map #89): model load path hardened.
 * - Canonical-path containment check against [config.allowedModelRoots] before native init.
 * - Optional SHA-256 verification via [config.expectedHash].
 */
open class WhisperEngine protected constructor(
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
     * and override [doNativeTranscribe] / [doNativeFree] / [doNativeCancel].
     */
    protected constructor(stubInit: Boolean) : this(config = null, stubInit = true)

    private var isLibLoaded = false

    /** Guarded by [lock]. Marked @Volatile for safe reads outside the lock
     *  (e.g. the fast-path isEmpty check in [transcribe] and [cancel]). */
    @Volatile
    private var contextPtr: Long = 0L

    /** Exclusive lock: whisper_full is not thread-safe on the same context
     *  (WHISPER_LOCKING disabled), so transcribe and free must not overlap. */
    private val lock = ReentrantLock()

    // -- init helpers -------------------------------------------------------

    private fun initEngine() {
        val cfg = config ?: throw IllegalStateException("No config provided")
        val modelFile = File(cfg.modelPath)
        if (!modelFile.exists()) {
            throw IllegalArgumentException("Model file not found at: ${cfg.modelPath}")
        }
        // Ticket #95: containment check — refuse models outside app-private storage
        if (cfg.allowedModelRoots.isNotEmpty()) {
            if (!ModelIntegrity.isInsideAllowedRoots(cfg.modelPath, cfg.allowedModelRoots)) {
                throw SecurityException(
                    "Model path '${cfg.modelPath}' is outside allowed storage roots. " +
                    "Only app-private storage (filesDir / getExternalFilesDir) is accepted."
                )
            }
        }
        // Ticket #95: optional SHA-256 verification (closes TOCTOU window when combined
        // with containment — only the app can write to app-private storage)
        if (cfg.expectedHash != null) {
            if (!ModelIntegrity.verifySha256(modelFile, cfg.expectedHash)) {
                throw SecurityException(
                    "Model file at '${cfg.modelPath}' failed SHA-256 verification."
                )
            }
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

        lock.withLock {
            val ptr = contextPtr
            if (ptr == 0L) {
                throw IllegalStateException("Whisper context is not initialized or has been freed")
            }
            val lang = config?.language ?: WhisperConfig.DEFAULT_LANGUAGE
            require(WhisperConfig.isValidLanguage(lang)) { "Invalid language: $lang" }
            val threads = WhisperConfig.clampThreads(config?.numThreads ?: WhisperConfig.DEFAULT_THREADS)
            val floatAudio = AudioConverter.convertPcmToFloat(audioBytes)
            return doNativeTranscribe(
                ptr, floatAudio,
                lang,
                threads,
                initialPrompt
            ) ?: throw RuntimeException("Error during native transcription")
        }
    }

    /**
     * Cancel any in-flight transcription promptly.
     * Safe to call concurrently with [transcribe].
     */
    fun cancel() {
        val ptr = contextPtr
        if (ptr != 0L) {
            doNativeCancel(ptr)
        }
    }

    /** Alias for [cancel]. */
    fun stop() = cancel()

    /**
     * Release the native whisper context.
     *
     * Aborts any in-flight transcription and blocks until it has finished.
     * Idempotent — calling [free] (or [close]) more than once is safe.
     */
    fun free() {
        cancel()
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

    /** Override in test harnesses to stub native cancel. */
    protected open fun doNativeCancel(ptr: Long) = nativeCancel(ptr)

    /** Override in test harnesses to stub native transcription. */
    protected open fun doNativeTranscribe(
        ptr: Long,
        floatAudio: FloatArray,
        language: String,
        threads: Int,
        initialPrompt: String?
    ): String? {
        val bytes = nativeTranscribe(ptr, floatAudio, language, threads, initialPrompt) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /** Override in test harnesses to stub native free. */
    protected open fun doNativeFree(ptr: Long) = nativeFree(ptr)

    // -- JNI externals ------------------------------------------------------

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(
        contextPtr: Long, audioData: FloatArray,
        language: String, threads: Int, initialPrompt: String?
    ): ByteArray?
    private external fun nativeCancel(contextPtr: Long)
    private external fun nativeFree(contextPtr: Long)

    private companion object {
        const val TAG = "WhisperEngine"
    }
}
