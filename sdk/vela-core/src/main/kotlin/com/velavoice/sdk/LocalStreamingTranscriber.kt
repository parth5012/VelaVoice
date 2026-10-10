package com.velavoice.sdk

import android.util.Log
import com.velavoice.sdk.whisper.AudioConverter
import com.velavoice.sdk.whisper.WhisperConfig
import com.velavoice.sdk.whisper.WhisperEngine
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Local streaming transcription using whisper.cpp with persistent state.
 *
 * Implements the architecture decisions:
 * - Sliding window with overlapping chunks for continuity
 * - Persistent whisper context with periodic reset (30s default)
 * - RMS VAD pre-filter to skip silent chunks
 * - Timestamp-based deduplication for overlap regions
 * - Single-threaded sequential processing
 *
 * The native whisper context is owned by [WhisperEngine] from the vela-whisper
 * module; this class holds no JNI bindings of its own.
 */
class LocalStreamingTranscriber(
    private val config: WhisperConfig,
    private val engineFactory: (WhisperConfig) -> WhisperEngine = { WhisperEngine(it) }
) : StreamingTranscriber {
    private var engine: WhisperEngine? = null
    private var engineConfig: WhisperConfig = config
    @Volatile
    internal var isRunning = false
    private var callback: StreamingTranscriptionCallback? = null
    private var streamConfig: StreamConfig? = null

    // Sliding window buffer (bounded to the configured window size)
    private val pendingChunks = ArrayDeque<ByteArray>()
    internal var bufferedBytes = 0
    private val bufferLock = Any()

    // Processing thread
    @Volatile
    internal var processingThread: Thread? = null
    private val shouldStop = AtomicBoolean(false)

    @Volatile
    internal var teardownThread: Thread? = null

    // State management
    private val totalSamples = AtomicLong(0)
    private val lastResetTime = AtomicLong(0)
    private var lastCommittedText = ""
    private var partialText = ""
    internal var committedLength = 0

    // Chunk timing
    internal var chunkSamples = 0
    internal var stepSamples = 0
    internal var windowSamples = 0
    internal var overlapSamples = 0

    override fun start(config: StreamConfig) {
        // Drain any prior teardown so we don't race with previous session cleanup
        teardownThread?.let { td ->
            while (td.isAlive) {
                try {
                    td.join()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        teardownThread = null

        if (isRunning) return
        this.streamConfig = config

        // Single source of truth: the StreamConfig wins, the constructor
        // WhisperConfig is the fallback when the caller left fields blank.
        val modelPath = config.modelPath.ifBlank { this.config.modelPath }
        engineConfig = WhisperConfig(
            modelPath = modelPath,
            language = config.language.ifBlank { this.config.language },
            numThreads = if (config.numThreads > 0) config.numThreads else this.config.numThreads
        )

        if (modelPath.isBlank() || !File(modelPath).exists()) {
            callback?.onError(VelaError("Model file not found: $modelPath"))
            return
        }

        engine = try {
            engineFactory(engineConfig)
        } catch (e: Throwable) {
            callback?.onError(VelaError("Failed to initialize whisper context: ${e.message}"))
            null
        }
        if (engine == null) return

        // Calculate sample counts from durations
        val sampleRate = 16000
        chunkSamples = (config.chunkDurationMs * sampleRate / 1000)
        windowSamples = (config.windowDurationMs * sampleRate / 1000)
        overlapSamples = (config.overlapMs * sampleRate / 1000)
        stepSamples = windowSamples - overlapSamples

        // Ensure chunk alignment (16-bit = 2 bytes per sample)
        chunkSamples = (chunkSamples / 2) * 2
        windowSamples = (windowSamples / 2) * 2
        overlapSamples = (overlapSamples / 2) * 2
        stepSamples = (stepSamples / 2) * 2

        shouldStop.set(false)
        isRunning = true
        lastResetTime.set(System.currentTimeMillis())
        totalSamples.set(0)
        committedLength = 0
        lastCommittedText = ""
        partialText = ""

        // Clear buffer
        synchronized(bufferLock) {
            pendingChunks.clear()
            bufferedBytes = 0
        }

        startProcessingThread()
        Log.d("LocalStreamingTranscriber", "Streaming started: window=${config.windowDurationMs}ms, step=${config.chunkDurationMs}ms, overlap=${config.overlapMs}ms")
    }

    override fun emit(audioChunk: ByteArray) {
        if (!isRunning || shouldStop.get() || audioChunk.isEmpty()) return
        synchronized(bufferLock) {
            if (!isRunning || shouldStop.get()) return
            pendingChunks.addLast(audioChunk.copyOf())
            bufferedBytes += audioChunk.size
            trimBufferLocked()
        }
        totalSamples.addAndGet(audioChunk.size / 2L)
    }

    override fun stop() {
        if (!isRunning || shouldStop.getAndSet(true)) return
        isRunning = false

        val thread = processingThread
        thread?.interrupt()

        val td = Thread({
            // Join until the processing thread is actually dead: no timeout-then-free path
            if (thread != null) {
                while (thread.isAlive) {
                    try {
                        thread.join()
                    } catch (e: InterruptedException) {
                        // Keep looping until the processing thread is dead
                    }
                }
            }

            try {
                engine?.free()
            } catch (e: Throwable) {
                Log.e("LocalStreamingTranscriber", "Error freeing engine", e)
            } finally {
                engine = null
                processingThread = null
                synchronized(bufferLock) {
                    pendingChunks.clear()
                    bufferedBytes = 0
                }
            }

            Log.d("LocalStreamingTranscriber", "Streaming stopped")
        }, "LocalStreamingTeardown")

        teardownThread = td
        td.start()
    }

    /**
     * Test/lifecycle helper to await completion of background teardown.
     * Returns true if teardown finished within [timeoutMs], false otherwise.
     */
    internal fun awaitTeardown(timeoutMs: Long = 5000L): Boolean {
        val td = teardownThread ?: return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (td.isAlive && System.currentTimeMillis() < deadline) {
            try {
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
                td.join(remaining)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return !td.isAlive
    }

    override fun setCallback(callback: StreamingTranscriptionCallback) {
        this.callback = callback
    }

    override fun release() {
        stop()
    }

    private fun startProcessingThread() {
        val thread = Thread({
            try {
                while (!shouldStop.get()) {
                    try {
                        processNextChunk()
                        // Sleep for the step duration
                        val stepMs = streamConfig?.chunkDurationMs?.toLong() ?: 3000L
                        Thread.sleep(stepMs)
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Exception) {
                        Log.e("LocalStreamingTranscriber", "Processing error", e)
                        callback?.onError(VelaError("Processing error: ${e.message}"))
                    }
                }
            } finally {
                // Clear interrupted status so whisper inference is not interrupted
                Thread.interrupted()
                try {
                    flushRemaining()
                } catch (e: Exception) {
                    Log.e("LocalStreamingTranscriber", "Error during final flush", e)
                }
            }
        }, "LocalStreamingProcessor")
        processingThread = thread
        thread.start()
    }

    private fun processNextChunk() {
        val sc = streamConfig ?: return

        // Check if we need to reset context (periodic reset to prevent drift)
        val now = System.currentTimeMillis()
        if (now - lastResetTime.get() > sc.resetIntervalMs) {
            resetContext()
            lastResetTime.set(now)
        }

        // Extract window of audio from buffer
        val windowData = extractWindow()
        if (windowData.isEmpty()) return

        // VAD pre-filter: skip silent chunks
        if (sc.useVad && isSilent(windowData, sc.vadThreshold)) {
            return
        }

        // Transcribe the window
        try {
            val result = engine?.transcribe(windowData)
            if (result != null) {
                handleTranscriptionResult(result)
            }
        } catch (e: Exception) {
            Log.e("LocalStreamingTranscriber", "Transcription failed", e)
        }
    }

    private fun trimBufferLocked() {
        val maxBytes = if (windowSamples > 0) windowSamples * 2 else DEFAULT_WINDOW_BYTES
        while (pendingChunks.size > 1 && bufferedBytes - pendingChunks.first().size >= maxBytes) {
            bufferedBytes -= pendingChunks.removeFirst().size
        }
    }

    internal fun extractWindow(): ByteArray {
        synchronized(bufferLock) {
            if (bufferedBytes < chunkSamples * 2) return ByteArray(0)
            return copyBufferLocked()
        }
    }

    private fun extractAll(): ByteArray {
        synchronized(bufferLock) {
            if (bufferedBytes <= 0) return ByteArray(0)
            return copyBufferLocked()
        }
    }

    private fun copyBufferLocked(): ByteArray {
        val all = ByteArray(bufferedBytes)
        var offset = 0
        for (chunk in pendingChunks) {
            System.arraycopy(chunk, 0, all, offset, chunk.size)
            offset += chunk.size
        }
        val windowBytes = windowSamples * 2
        if (windowBytes <= 0 || all.size <= windowBytes) return all
        return all.copyOfRange(all.size - windowBytes, all.size)
    }

    private fun handleTranscriptionResult(newText: String) {
        if (newText.isBlank()) return

        val newTextTrimmed = newText.trim()

        // Timestamp-based dedup: find overlap with last committed text
        val overlapLength = findOverlapLength(lastCommittedText, newTextTrimmed)

        if (overlapLength > 0) {
            // Extract only the new portion
            val newPortion = newTextTrimmed.substring(overlapLength).trimStart()
            if (newPortion.isNotEmpty()) {
                emitPartial(newPortion, committedLength, newTextTrimmed)
            }
        } else {
            // No overlap — new text segment
            val separator = if (committedLength > 0) " " else ""
            emitPartial(newTextTrimmed, committedLength + separator.length, newTextTrimmed)
        }
    }

    /** Emits a revisable partial marker and advances the committed-text bookkeeping. */
    private fun emitPartial(text: String, startIdx: Int, fullText: String) {
        val endIdx = startIdx + text.length
        callback?.onRevisionMarker(
            RevisionMarker(
                type = "partial",
                text = text,
                range = startIdx until endIdx
            )
        )
        partialText = text
        committedLength = endIdx
        lastCommittedText = fullText
    }

    /**
     * Find the length of overlapping text between the previous result and new result.
     * Uses suffix-prefix matching for deduplication.
     */
    internal fun findOverlapLength(previous: String, current: String): Int {
        if (previous.isEmpty() || current.isEmpty()) return 0
        if (current == previous) return current.length

        // Check if current starts with previous (full overlap)
        if (current.startsWith(previous)) {
            return previous.length
        }

        // Find longest suffix of previous that is a prefix of current
        val words = previous.split(" ")
        val currentWords = current.split(" ")

        var maxOverlap = 0
        for (i in 1..minOf(words.size, currentWords.size)) {
            val suffix = words.takeLast(i).joinToString(" ")
            if (current.startsWith(suffix)) {
                maxOverlap = suffix.length
            }
        }
        return maxOverlap
    }

    private fun flushRemaining() {
        val remaining = extractAll()
        val result = if (remaining.isNotEmpty()) {
            try {
                engine?.transcribe(remaining)
            } catch (e: Exception) {
                Log.e("LocalStreamingTranscriber", "Final pass failed", e)
                null
            }
        } else null

        if (partialText.isNotEmpty()) {
            callback?.onRevisionMarker(
                RevisionMarker(
                    type = "commit",
                    text = partialText,
                    range = (committedLength - partialText.length).coerceAtLeast(0) until committedLength
                )
            )
        }

        if (result != null && result.isNotBlank()) {
            callback?.onFinal(result.trim())
        } else {
            callback?.onFinal(lastCommittedText)
        }
    }

    private fun resetContext() {
        try {
            engine?.free()
            engine = engineFactory(engineConfig)
            lastCommittedText = ""
            Log.d("LocalStreamingTranscriber", "Context reset after ${streamConfig?.resetIntervalMs}ms")
        } catch (e: Throwable) {
            engine = null
            Log.e("LocalStreamingTranscriber", "Context reset failed", e)
            callback?.onError(VelaError("Context reset failed: ${e.message}"))
        }
    }

    internal fun isSilent(audioData: ByteArray, threshold: Float): Boolean =
        AudioConverter.isSilent(audioData, threshold)

    private companion object {
        const val DEFAULT_WINDOW_BYTES = 16000 * 2 * 15
    }
}
