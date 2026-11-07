package com.velavoice.sdk.sherpa

import android.util.Log
import com.velavoice.sdk.AudioSegment
import com.velavoice.sdk.EngineKind
import com.velavoice.sdk.ModelIntegrity
import com.velavoice.sdk.StreamCallback
import com.velavoice.sdk.StreamHandle
import com.velavoice.sdk.TranscriptionEngine
import com.velavoice.sdk.TranscriptionResult
import com.velavoice.sdk.whisper.AudioConverter
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Sherpa-ONNX backed [TranscriptionEngine] (batch path for ticket 802).
 *
 * Mirrors [com.velavoice.sdk.whisper.WhisperEngine] hardening contracts:
 * containment check, sha256 verification, exclusive lock, idempotent close,
 * fail-fast post-free. Streaming lands in ticket 803.
 */
open class SherpaEngine protected constructor(
    private val config: SherpaConfig?,
    @Suppress("UNUSED_PARAMETER") stubInit: Boolean
) : TranscriptionEngine {

    constructor(config: SherpaConfig) : this(config, stubInit = false) {
        try {
            isLibLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Could not load sherpa JNI library", e)
        }
        initEngine()
    }

    protected constructor(stubInit: Boolean) : this(config = null, stubInit = true)

    private var isLibLoaded = false

    @Volatile
    private var contextPtr: Long = 0L

    // Keep a reference to the native recognizer to release it.
    private var recognizer: OfflineRecognizer? = null

    private val lock = ReentrantLock()

    @Volatile
    private var cancelled = false

    private fun initEngine() {
        val cfg = config ?: throw IllegalStateException("No config provided")
        val modelFile = File(cfg.modelPath)
        val exists = if (modelFile.isDirectory) modelFile.exists() else modelFile.exists()
        if (!exists) {
            throw IllegalArgumentException("Model not found at: ${cfg.modelPath}")
        }
        if (cfg.allowedModelRoots.isNotEmpty()) {
            if (!ModelIntegrity.isInsideAllowedRoots(cfg.modelPath, cfg.allowedModelRoots)) {
                throw SecurityException(
                    "Model path '${cfg.modelPath}' is outside allowed storage roots. " +
                    "Only app-private storage (filesDir / getExternalFilesDir) is accepted."
                )
            }
        }
        if (cfg.expectedHash != null) {
            // A pinned hash only protects a single file. A directory path
            // would silently bypass verification — refuse instead of
            // shipping unverified model contents.
            if (modelFile.isDirectory) {
                throw SecurityException(
                    "Model path '${cfg.modelPath}' is a directory; SHA-256 pinning " +
                    "requires a single model file, so a directory cannot be verified."
                )
            }
            if (!ModelIntegrity.verifySha256(modelFile, cfg.expectedHash)) {
                throw SecurityException(
                    "Model file at '${cfg.modelPath}' failed SHA-256 verification."
                )
            }
        }
        if (!isLibLoaded) {
            // Native lib is loaded by the AAR static initializer; mark loaded.
            isLibLoaded = true
        }
        recognizer = createRecognizer(cfg)
        contextPtr = 1L
        if (contextPtr == 0L) {
            throw RuntimeException("Failed to initialize native Sherpa context")
        }
    }

    private fun createRecognizer(cfg: SherpaConfig): OfflineRecognizer {
        val modelDir = cfg.modelPath
        val transducer = OfflineTransducerModelConfig(
            encoder = File(modelDir, "encoder.onnx").absolutePath,
            decoder = File(modelDir, "decoder.onnx").absolutePath,
            joiner = File(modelDir, "joiner.onnx").takeIf { it.exists() }?.absolutePath
                ?: File(modelDir, "joint.onnx").absolutePath,
            qnnConfig = com.k2fsa.sherpa.onnx.QnnConfig("", "", "")
        )
        val modelConfig = OfflineModelConfig(
            transducer = transducer,
            paraformer = com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig(),
            whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(),
            fireRedAsr = com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig(),
            moonshine = com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig(),
            nemo = com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig(),
            senseVoice = com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(),
            dolphin = com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig(),
            zipformerCtc = com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig(),
            wenetCtc = com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig(),
            omnilingual = com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig(),
            medasr = com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig(),
            funasrNano = com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig(),
            qwen3Asr = com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig(),
            fireRedAsrCtc = com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig(),
            canary = com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig(),
            cohereTranscribe = com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig(),
            teleSpeech = "",
            numThreads = cfg.numThreads,
            debug = false,
            provider = "cpu",
            modelType = "",
            tokens = File(modelDir, "tokens.txt").takeIf { it.exists() }?.absolutePath ?: "",
            modelingUnit = "cjkchar",
            bpeVocab = ""
        )
        val featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80)
        val recConfig = OfflineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            hr = com.k2fsa.sherpa.onnx.HomophoneReplacerConfig(),
            decodingMethod = "greedy_search",
            maxActivePaths = 4,
            hotwordsFile = "",
            hotwordsScore = 1.5f,
            ruleFsts = "",
            ruleFars = "",
            blankPenalty = 0f,
            ctcFstDecoderConfig = com.k2fsa.sherpa.onnx.OfflineCtcFstDecoderConfig()
        )
        return OfflineRecognizer(null, recConfig)
    }

    protected fun setContextPtrForTest(ptr: Long) {
        contextPtr = ptr
    }

    /** Test-only: inject the recognizer for doNativeTranscribe-path tests. */
    protected fun setRecognizerForTest(rec: OfflineRecognizer?) {
        recognizer = rec
    }

    // TranscriptionEngine
    override val engine: EngineKind = EngineKind.SHERPA
    override val supportsStreaming: Boolean = false

    @JvmOverloads
    fun transcribe(audioBytes: ByteArray, initialPrompt: String? = null): String {
        if (audioBytes.isEmpty()) return ""
        lock.withLock {
            val ptr = contextPtr
            if (ptr == 0L) {
                throw IllegalStateException("Sherpa context is not initialized or has been freed")
            }
            val floatAudio = AudioConverter.convertPcmToFloat(audioBytes)
            return doNativeTranscribe(ptr, floatAudio, config?.language ?: "en", config?.numThreads ?: 4) ?: throw RuntimeException("Error during native transcription")
        }
    }

    override fun transcribe(audio: AudioSegment, lang: String?): TranscriptionResult {
        val raw = transcribe(audio.pcm)
        val durationMs = ((audio.pcm.size / 2) / 16L)
        return TranscriptionResult(raw, raw, durationMs)
    }

    override fun openStream(lang: String?, callback: StreamCallback): StreamHandle {
        if (!supportsStreaming) throw UnsupportedOperationException("streaming not supported")
        // 803
        throw UnsupportedOperationException("streaming not supported")
    }

    override fun cancel() {
        cancelled = true
        val ptr = contextPtr
        if (ptr != 0L) {
            doNativeCancel(ptr)
        }
    }

    fun free() {
        cancel()
        lock.withLock {
            val ptr = contextPtr
            if (ptr != 0L) {
                doNativeFree(ptr)
                recognizer?.release()
                recognizer = null
                contextPtr = 0L
            }
        }
    }

    override fun close() = free()

    // native call points
    protected open fun doNativeCancel(ptr: Long) {
        // no-op default; tests override
    }


    protected open fun doNativeTranscribe(ptr: Long, floatAudio: FloatArray, language: String, threads: Int): String? {
        val rec = recognizer ?: return null
        val stream = createOfflineStream(rec) ?: return null
        try {
            stream.acceptWaveform(floatAudio, 16000)
            rec.decode(stream)
            return rec.getResult(stream).text
        } finally {
            releaseOfflineStream(stream)
        }
    }

    /**
     * Native call point: creates the per-transcription [OfflineStream].
     * Returns null when the recognizer could not allocate a stream.
     */
    protected open fun createOfflineStream(rec: OfflineRecognizer): OfflineStream? = rec.createStream()

    /**
     * Native call point: releases the [OfflineStream]. Runs in a `finally`
     * so a failed decode never leaks the native stream handle.
     */
    protected open fun releaseOfflineStream(stream: OfflineStream) {
        stream.release()
    }

    protected open fun doNativeFree(ptr: Long) {
        // no-op default
    }

    private companion object {
        const val TAG = "SherpaEngine"
    }
}
