package com.velavoice.sdk

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig
import com.k2fsa.sherpa.onnx.OnlineLMConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import com.velavoice.sdk.ModelIntegrity
import com.velavoice.sdk.sherpa.SherpaConfig
import com.velavoice.sdk.whisper.AudioConverter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * sherpa-onnx streaming backend (ticket 803, map #290).
 *
 * Wraps the vendored AAR's [OnlineRecognizer] streaming path:
 * - [emit] feeds PCM chunks into the [OnlineStream] and pumps each decode
 *   cycle's cumulative text as a revisable [RevisionMarker] partial.
 * - Native endpoint detection commits the current segment as a `commit`
 *   marker and resets the stream so segments stay disjoint.
 * - [stop] finalizes the stream, commits the remaining tail, and delivers
 *   the full text through [StreamingTranscriptionCallback.onFinal].
 *
 * Mirrors [com.velavoice.sdk.sherpa.SherpaEngine] hardening: model-path
 * containment via [SherpaConfig.allowedModelRoots], idempotent teardown,
 * fail-fast behavior after free. All native call points are protected-open
 * so Robolectric unit tests drive the state machine without the JNI .so
 * (stub constructor path — same pattern as SherpaEngine).
 *
 * Logcat hygiene: only counts and status transitions are logged — never
 * transcription content.
 */
open class SherpaStreamEngine protected constructor(
    private val config: SherpaConfig?,
    @Suppress("UNUSED_PARAMETER") stubInit: Boolean
) : StreamingTranscriber {

    constructor(config: SherpaConfig) : this(config, stubInit = false)

    protected constructor(stubInit: Boolean) : this(config = null, stubInit = true)

    // ── native handles (real path only; stay null on the stub path) ──
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

    private var callback: StreamingTranscriptionCallback? = null
    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val startedOnce = AtomicBoolean(false)
    private val finalDelivered = AtomicBoolean(false)

    // Session state
    private var language = SherpaConfig.DEFAULT_LANGUAGE
    private var numThreads = SherpaConfig.DEFAULT_THREADS

    /**
     * Text committed via endpoint/stop. [lastPartialText] holds the current
     * uncommitted segment's cumulative text. Segment ranges in emitted
     * markers index into this composed text (+1 separator between segments).
     */
    private var committedText = ""
    private var lastPartialText = ""
    private var nextRevisionId = 0L

    override fun setCallback(callback: StreamingTranscriptionCallback) {
        this.callback = callback
    }

    override fun start(streamConfig: StreamConfig) {
        if (released.get()) {
            Log.w(TAG, "start ignored: engine already released")
            return
        }
        if (!running.compareAndSet(false, true)) return

        // Session-first convention (as in LocalStreamingTranscriber):
        // StreamConfig wins, constructor SherpaConfig is the fallback.
        val modelPath = streamConfig.modelPath.ifBlank { config?.modelPath ?: "" }
        language = streamConfig.language.ifBlank { SherpaConfig.DEFAULT_LANGUAGE }
        numThreads = if (streamConfig.numThreads > 0) {
            SherpaConfig.clampThreads(streamConfig.numThreads)
        } else {
            SherpaConfig.DEFAULT_THREADS
        }

        if (modelPath.isBlank() || !File(modelPath).exists()) {
            fail("Sherpa streaming model not found: $modelPath")
            return
        }
        val roots = config?.allowedModelRoots ?: emptyList()
        if (roots.isNotEmpty() && !ModelIntegrity.isInsideAllowedRoots(modelPath, roots)) {
            fail("Model path outside allowed storage roots")
            return
        }

        val stub = config == null
        if (!stub && !nativeAllocate(modelPath, language, numThreads)) {
            fail("Failed to initialize sherpa streaming recognizer")
            return
        }

        committedText = ""
        lastPartialText = ""
        startedOnce.set(true)
        finalDelivered.set(false)
        Log.d(TAG, "stream started (model=${File(modelPath).name}, threads=$numThreads)")
    }

    override fun emit(audioChunk: ByteArray) {
        if (!running.get() || audioChunk.isEmpty()) return

        val floatAudio = try {
            AudioConverter.convertPcmToFloat(audioChunk)
        } catch (e: Throwable) {
            Log.e(TAG, "PCM conversion failed", e)
            callback?.onError(VelaError("Audio conversion failed: ${e.message}"))
            return
        }
        nativeFeed(floatAudio)

        pumpToMarker()

        // Native endpoint merges the current segment into the committed
        // text and resets the stream, so later segments get disjoint ranges.
        if (nativeEndpointCheck()) {
            commitCurrentSegment()
            nativeResetStream()
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) {
            // Not running: still guarantee a single onFinal for idempotence
            // only if we were started at some point and never delivered one.
            if (!finalDelivered.getAndSet(true) && startedOnce.get()) {
                callback?.onFinal(lastCombinedText())
            }
            return
        }
        if (finalDelivered.getAndSet(true)) return

        val drained = if (config == null) null else nativeFinalize()

        // Commit the remaining uncommitted tail (if any)
        val tail = lastPartialText
        if (tail.isNotBlank()) {
            commitCurrentSegment()
        }

        // Merge drained text (usually equals the last segment)
        var text = committedText
        if (!drained.isNullOrBlank() && !text.contains(drained)) {
            text = combined(text, drained)
        }
        callback?.onFinal(text)
        nativeClose()
        Log.d(TAG, "stream stopped")
    }

    override fun release() {
        if (!released.getAndSet(true)) {
            stop()
        }
    }

    internal val isRunningForTest: Boolean get() = running.get()

    internal val lastCombinedText: String get() = lastCombinedText()

    // ── revision emission ───────────────────────────────────────

    private fun pumpToMarker() {
        val pumped = nativePump() ?: return
        if (pumped == lastPartialText) return

        val start = committedText.length + if (committedText.isEmpty()) 0 else 1
        callback?.onRevisionMarker(
            RevisionMarker(
                type = "partial",
                text = pumped,
                range = start until start + pumped.length,
                revisionId = ++nextRevisionId
            )
        )
        lastPartialText = pumped
    }

    private fun commitCurrentSegment() {
        val segment = lastPartialText
        if (segment.isNotBlank()) {
            val start = committedText.length + if (committedText.isEmpty()) 0 else 1
            callback?.onRevisionMarker(
                RevisionMarker(
                    type = "commit",
                    text = segment,
                    range = start until start + segment.length,
                    revisionId = nextRevisionId++
                )
            )
            committedText = combined(committedText, segment)
        }
        lastPartialText = ""
    }

    private fun combined(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a $b"
    }

    private fun lastCombinedText(): String = combined(committedText, lastPartialText)

    private fun fail(message: String) {
        running.set(false)
        released.set(true)
        Log.e(TAG, message)
        callback?.onError(VelaError(message))
    }

    // ── native call points (real path uses the vendored AAR) ────

    /**
     * Allocate the streaming recognizer + initial stream. Returns false on
     * failure (JNI missing, model files unreadable). Stub engines (test
     * path, `config == null`) never reach this.
     */
    protected open fun nativeAllocate(modelPath: String, language: String, threads: Int): Boolean {
        val modelDir = File(modelPath)
        val transducer = OnlineTransducerModelConfig(
            encoder = File(modelDir, "encoder.onnx").absolutePath,
            decoder = File(modelDir, "decoder.onnx").absolutePath,
            joiner = File(modelDir, "joiner.onnx").takeIf { it.exists() }?.absolutePath
                ?: File(modelDir, "joint.onnx").absolutePath,
            qnnConfig = QnnConfig("", "", "")
        )
        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = File(modelDir, "tokens.txt").takeIf { it.exists() }?.absolutePath ?: "",
            numThreads = threads,
            debug = false,
            provider = "cpu",
            modelType = "nemo-transducer",
            modelingUnit = "",
            bpeVocab = ""
        )
        val recognizerConfig = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = modelConfig,
            lmConfig = OnlineLMConfig(),
            ctcFstDecoderConfig = OnlineCtcFstDecoderConfig(),
            hr = HomophoneReplacerConfig(),
            decodingMethod = "greedy_search",
            enableEndpoint = true
        )
        return try {
            recognizer = OnlineRecognizer(assetManager = null, config = recognizerConfig)
            val created = recognizer!!.createStream() ?: return false
            stream = created
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "sherpa JNI library not available", e)
            false
        } catch (e: Throwable) {
            Log.e(TAG, "failed to create streaming recognizer", e)
            false
        }
    }

    protected open fun nativeFeed(floatAudio: FloatArray) {
        stream?.acceptWaveform(floatAudio, SAMPLE_RATE)
    }

    /**
     * Decode-ready cycles and return the cumulative stream text, or null
     * when nothing was decoded.
     */
    protected open fun nativePump(): String? {
        val rec = recognizer ?: return null
        val st = stream ?: return null
        var text: String? = null
        while (rec.isReady(st)) {
            rec.decode(st)
            text = rec.getResult(st).text
        }
        return text
    }

    protected open fun nativeEndpointCheck(): Boolean {
        val rec = recognizer ?: return false
        val st = stream ?: return false
        return rec.isEndpoint(st)
    }

    protected open fun nativeResetStream() {
        recognizer?.reset(stream ?: return)
    }

    /**
     * Finalize: mark the stream input finished and drain remaining decodes.
     * Returns the final cumulative text of the current segment, or null.
     */
    protected open fun nativeFinalize(): String? {
        val rec = recognizer ?: return null
        val st = stream ?: return null
        return try {
            st.inputFinished()
            var text: String? = null
            while (rec.isReady(st)) {
                rec.decode(st)
                text = rec.getResult(st).text
            }
            text
        } catch (e: Throwable) {
            Log.e(TAG, "finalize failed", e)
            null
        }
    }

    protected open fun nativeClose() {
        try {
            stream?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "stream release failed", e)
        }
        try {
            recognizer?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "recognizer release failed", e)
        }
        stream = null
        recognizer = null
    }

    private companion object {
        const val TAG = "SherpaStreamEngine"
        const val SAMPLE_RATE = 16000
        const val FEATURE_DIM = 80
    }
}
