package com.velavoice.sdk

import android.util.Log
import com.velavoice.sdk.whisper.AudioConverter
import com.velavoice.sdk.whisper.WhisperConfig
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Local streaming transcription using whisper.cpp with persistent state.
 *
 * Implements the architecture decisions:
 * - Sliding window with overlapping chunks for continuity
 * - Persistent whisper context with periodic reset (30s default)
 * - RMS VAD pre-filter to skip silent chunks
 * - Timestamp-based deduplication for overlap regions
 * - Single-threaded sequential processing
 */
class LocalStreamingTranscriber(private val config: WhisperConfig) : StreamingTranscriber {
    private var contextPtr: Long = 0
    private var isLibLoaded = false
    private var isRunning = false
    private var callback: StreamingTranscriptionCallback? = null
    private var streamConfig: StreamConfig? = null

    // Sliding window buffer
    private val audioBuffer = ConcurrentLinkedQueue<Byte>()
    private val bufferLock = Any()

    // Processing thread
    private var processingThread: Thread? = null
    private val shouldStop = AtomicBoolean(false)

    // State management
    private val totalSamples = AtomicLong(0)
    private val lastResetTime = AtomicLong(0)
    private var lastCommittedText = ""
    private var partialText = ""
    private var committedLength = 0

    // Chunk timing
    private var chunkSamples = 0
    private var stepSamples = 0
    private var windowSamples = 0
    private var overlapSamples = 0

    init {
        try {
            System.loadLibrary("whisper")
            isLibLoaded = true
            Log.d("LocalStreamingTranscriber", "whisper JNI library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("LocalStreamingTranscriber", "Failed to load whisper JNI", e)
        }
    }

    override fun start(config: StreamConfig) {
        if (isRunning) return
        this.streamConfig = config

        val modelFile = File(config.modelPath)
        if (!modelFile.exists()) {
            callback?.onError(VelaError("Model file not found: ${config.modelPath}"))
            return
        }
        if (!isLibLoaded) {
            callback?.onError(VelaError("JNI library not loaded"))
            return
        }

        contextPtr = nativeInit(config.modelPath)
        if (contextPtr == 0L) {
            callback?.onError(VelaError("Failed to initialize whisper context"))
            return
        }

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
            audioBuffer.clear()
        }

        startProcessingThread()
        Log.d("LocalStreamingTranscriber", "Streaming started: window=${config.windowDurationMs}ms, step=${config.chunkDurationMs}ms, overlap=${config.overlapMs}ms")
    }

    override fun emit(audioChunk: ByteArray) {
        if (!isRunning) return
        synchronized(bufferLock) {
            for (b in audioChunk) {
                audioBuffer.add(b)
            }
        }
        totalSamples.addAndGet(audioChunk.size / 2L)
    }

    override fun stop() {
        if (!isRunning) return
        shouldStop.set(true)
        processingThread?.join(5000)
        processingThread = null

        // Final pass: transcribe remaining audio
        flushRemaining()

        isRunning = false

        if (contextPtr != 0L) {
            nativeFree(contextPtr)
            contextPtr = 0L
        }

        Log.d("LocalStreamingTranscriber", "Streaming stopped")
    }

    override fun setCallback(callback: StreamingTranscriptionCallback) {
        this.callback = callback
    }

    override fun release() {
        stop()
    }

    private fun startProcessingThread() {
        processingThread = Thread({
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
        }, "LocalStreamingProcessor")
        processingThread?.start()
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
            val floatAudio = AudioConverter.convertPcmToFloat(windowData)
            val result = nativeTranscribe(contextPtr, floatAudio)
            if (result != null) {
                handleTranscriptionResult(result)
            }
        } catch (e: Exception) {
            Log.e("LocalStreamingTranscriber", "Transcription failed", e)
        }
    }

    private fun extractWindow(): ByteArray {
        synchronized(bufferLock) {
            val totalBytes = audioBuffer.size
            val windowBytes = windowSamples * 2  // 2 bytes per sample

            if (totalBytes < windowBytes) {
                // Not enough data yet, return what we have
                if (totalBytes < chunkSamples * 2) return ByteArray(0)
                return audioBuffer.toByteArray().copyOfRange(0, totalBytes)
            }

            // Extract the last windowBytes from buffer
            val allData = audioBuffer.toByteArray()
            return allData.copyOfRange(allData.size - windowBytes, allData.size)
        }
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
                val startIdx = committedLength
                val endIdx = startIdx + newPortion.length

                // Emit as partial (revisable)
                val marker = RevisionMarker(
                    type = "partial",
                    text = newPortion,
                    range = startIdx until endIdx
                )
                callback?.onRevisionMarker(marker)

                partialText = newPortion
                committedLength = endIdx
                lastCommittedText = newTextTrimmed
            }
        } else {
            // No overlap — new text segment
            val separator = if (committedLength > 0) " " else ""
            val startIdx = committedLength + separator.length
            val endIdx = startIdx + newTextTrimmed.length

            val marker = RevisionMarker(
                type = "partial",
                text = newTextTrimmed,
                range = startIdx until endIdx
            )
            callback?.onRevisionMarker(marker)

            partialText = newTextTrimmed
            committedLength = endIdx
            lastCommittedText = newTextTrimmed
        }
    }

    /**
     * Find the length of overlapping text between the previous result and new result.
     * Uses suffix-prefix matching for deduplication.
     */
    private fun findOverlapLength(previous: String, current: String): Int {
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
        val remaining = extractWindow()
        if (remaining.isNotEmpty()) {
            try {
                val floatAudio = AudioConverter.convertPcmToFloat(remaining)
                val result = nativeTranscribe(contextPtr, floatAudio)
                if (result != null && result.isNotBlank()) {
                    val finalText = result.trim()
                    // Commit remaining partials
                    if (partialText.isNotEmpty()) {
                        callback?.onRevisionMarker(
                            RevisionMarker(
                                type = "commit",
                                text = partialText,
                                range = (committedLength - partialText.length) until committedLength
                            )
                        )
                    }
                    callback?.onFinal(finalText)
                } else {
                    // Commit whatever partials we have
                    if (partialText.isNotEmpty()) {
                        callback?.onRevisionMarker(
                            RevisionMarker(
                                type = "commit",
                                text = partialText,
                                range = (committedLength - partialText.length) until committedLength
                            )
                        )
                    }
                    callback?.onFinal(lastCommittedText)
                }
            } catch (e: Exception) {
                Log.e("LocalStreamingTranscriber", "Final pass failed", e)
                callback?.onFinal(lastCommittedText)
            }
        } else {
            // Commit partials
            if (partialText.isNotEmpty()) {
                callback?.onRevisionMarker(
                    RevisionMarker(
                        type = "commit",
                        text = partialText,
                        range = (committedLength - partialText.length) until committedLength
                    )
                )
            }
            callback?.onFinal(lastCommittedText)
        }
    }

    private fun resetContext() {
        if (contextPtr != 0L) {
            nativeFree(contextPtr)
        }
        contextPtr = nativeInit(config.modelPath)
        lastCommittedText = ""
        Log.d("LocalStreamingTranscriber", "Context reset after ${streamConfig?.resetIntervalMs}ms")
    }

    private fun isSilent(audioData: ByteArray, threshold: Float): Boolean {
        if (audioData.isEmpty()) return true
        val samples = audioData.size / 2
        var sumSquares = 0.0
        for (i in 0 until samples) {
            val low = audioData[i * 2].toInt() and 0xff
            val high = audioData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / samples)
        val normalized = rms / 32768.0
        return normalized < threshold
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(contextPtr: Long, audioData: FloatArray): String?
    private external fun nativeFree(contextPtr: Long)
}
