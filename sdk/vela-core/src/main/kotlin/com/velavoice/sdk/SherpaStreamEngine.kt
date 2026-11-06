package com.velavoice.sdk

import com.velavoice.sdk.sherpa.SherpaConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming transcription backend for sherpa-onnx models (ticket 803).
 *
 * Minimal scaffold: full OnlineRecognizer wiring lands with the research
 * verdict. Compiles against the shared [StreamingTranscriber] contract so
 * routing code can reference it.
 */
class SherpaStreamEngine(
    private val config: SherpaConfig
) : StreamingTranscriber {

    private var callback: StreamingTranscriptionCallback? = null
    private val running = AtomicBoolean(false)

    override fun setCallback(callback: StreamingTranscriptionCallback) {
        this.callback = callback
    }

    override fun start(config: StreamConfig) {
        running.set(true)
    }

    override fun emit(audioChunk: ByteArray) {
        // no-op scaffold
    }

    override fun stop() {
        running.set(false)
        callback?.onFinal("")
    }

    override fun release() {
        stop()
    }
}
