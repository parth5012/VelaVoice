package com.velavoice.sdk.sherpa

import com.velavoice.sdk.ModelIntegrity
import java.io.File

/**
 * Configuration for the Sherpa-ONNX transcription engine.
 *
 * Mirrors [com.velavoice.sdk.whisper.WhisperConfig] hardening contracts:
 * canonical-path containment via [allowedModelRoots] and optional SHA-256
 * verification via [expectedHash] (single-file) — bundle-level integrity
 * lands with Ticket 804.
 */
data class SherpaConfig private constructor(
    val modelPath: String,
    val language: String,
    val numThreads: Int,
    val allowedModelRoots: List<File>,
    val expectedHash: String?,
    @Suppress("UNUSED_PARAMETER") private val marker: Boolean
) {
    @JvmOverloads
    constructor(
        modelPath: String,
        language: String = "en",
        numThreads: Int = DEFAULT_THREADS,
        allowedModelRoots: List<File> = emptyList(),
        expectedHash: String? = null
    ) : this(
        modelPath = modelPath,
        language = language,
        numThreads = clampThreads(numThreads),
        allowedModelRoots = allowedModelRoots,
        expectedHash = expectedHash,
        marker = true
    )

    init {
        // language validation kept permissive for sherpa (multilingual)
    }

    companion object {
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_THREADS = 4
        const val MAX_THREADS_CAP = 8

        fun clampThreads(threads: Int): Int {
            val hw = Runtime.getRuntime().availableProcessors()
            val maxThreads = maxOf(1, minOf(MAX_THREADS_CAP, hw))
            return threads.coerceIn(1, maxThreads)
        }
    }
}
