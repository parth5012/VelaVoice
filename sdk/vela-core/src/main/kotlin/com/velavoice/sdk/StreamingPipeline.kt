package com.velavoice.sdk

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.velavoice.sdk.whisper.WhisperConfig
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * High-level streaming transcription pipeline.
 *
 * Orchestrates:
 * - Audio capture with chunked buffering
 * - Backend selection (local whisper.cpp or cloud OpenAI)
 * - Commit boundary detection (VAD pause, sentence boundary, max time)
 * - Final full-pass cleanup on stop
 * - Revision marker emission to the UI
 *
 * Architecture: single-threaded sequential processing.
 */
class StreamingPipeline private constructor(
    private val localTranscriber: LocalStreamingTranscriber?,
    private val cloudTranscriber: CloudStreamingTranscriber?
) {
    private var streamConfig: StreamConfig = StreamConfig()
    private var callback: StreamingTranscriptionCallback? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val audioBuffer = ConcurrentLinkedQueue<Byte>()
    private val bufferLock = Any()

    // Commit boundary tracking
    private var lastActivityTime = 0L
    private var lastCommitTime = 0L
    private var currentSegmentStart = 0
    private var committedText = ""
    private var isSessionLocal = true

    // Audio constants
    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        // Commit boundary defaults
        const val VAD_PAUSE_MS = 500L
        const val MAX_SEGMENT_MS = 8000L
        const val FINAL_PASS_SECONDS = 5
    }

    class Builder(private val context: Context) {
        private var whisperModelPath: String = ""
        private var apiKey: String = ""
        private var endpoint: String = ""
        private var model: String = "gpt-live-transcribe"
        private var language: String = "en"
        private var numThreads: Int = 4
        private var chunkDurationMs: Int = 3000
        private var windowDurationMs: Int = 15000
        private var overlapMs: Int = 1500
        private var resetIntervalMs: Long = 30000

        fun whisperModelPath(path: String) = apply { this.whisperModelPath = path }
        fun apiKey(key: String) = apply { this.apiKey = key }
        fun endpoint(url: String) = apply { this.endpoint = url }
        fun model(model: String) = apply { this.model = model }
        fun language(lang: String) = apply { this.language = lang }
        fun threads(n: Int) = apply { this.numThreads = n }
        fun chunkDurationMs(ms: Int) = apply { this.chunkDurationMs = ms }
        fun windowDurationMs(ms: Int) = apply { this.windowDurationMs = ms }
        fun overlapMs(ms: Int) = apply { this.overlapMs = ms }
        fun resetIntervalMs(ms: Long) = apply { this.resetIntervalMs = ms }

        fun build(): StreamingPipeline {
            val localTranscriber = if (whisperModelPath.isNotBlank()) {
                LocalStreamingTranscriber(WhisperConfig(whisperModelPath, language, numThreads))
            } else null

            val cloudTranscriber = if (apiKey.isNotBlank()) {
                CloudStreamingTranscriber()
            } else null

            return StreamingPipeline(localTranscriber, cloudTranscriber)
        }
    }

    fun setCallback(callback: StreamingTranscriptionCallback) {
        this.callback = callback
    }

    /**
     * Start streaming transcription.
     * @param mode "local" for whisper.cpp, "cloud" for OpenAI WebSocket
     * @param config stream configuration
     */
    fun start(mode: String, config: StreamConfig = StreamConfig()) {
        if (isRecording.get()) return
        this.streamConfig = config
        this.isSessionLocal = mode == "local"
        this.committedText = ""
        this.currentSegmentStart = 0
        this.lastCommitTime = System.currentTimeMillis()
        this.lastActivityTime = System.currentTimeMillis()

        // Start the appropriate transcriber
        val transcriber = if (isSessionLocal) localTranscriber else cloudTranscriber
        if (transcriber == null) {
            callback?.onError(VelaError("No transcriber available for mode: $mode"))
            return
        }

        transcriber.setCallback(createTranscriberCallback())
        transcriber.start(config)

        // Start audio capture
        startAudioCapture(transcriber)
        isRecording.set(true)

        Log.d("StreamingPipeline", "Started in $mode mode")
    }

    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)

        // Stop audio capture
        stopAudioCapture()

        // Final cleanup: commit remaining partials
        commitRemaining()

        // Stop transcriber
        val transcriber = if (isSessionLocal) localTranscriber else cloudTranscriber
        transcriber?.stop()

        Log.d("StreamingPipeline", "Stopped")
    }

    fun release() {
        stop()
        localTranscriber?.release()
        cloudTranscriber?.release()
    }

    private fun startAudioCapture(transcriber: StreamingTranscriber) {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback?.onError(VelaError("Microphone initialization failed"))
                return
            }

            audioRecord?.startRecording()

            recordingThread = Thread({
                val buffer = ShortArray(BUFFER_SIZE / 2)
                val byteBuffer = ByteArray(BUFFER_SIZE)
                var lastVadCheck = System.currentTimeMillis()

                while (isRecording.get()) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult > 0) {
                        var sumSquares = 0.0
                        for (i in 0 until readResult) {
                            val shortVal = buffer[i]
                            sumSquares += shortVal * shortVal
                            byteBuffer[i * 2] = (shortVal.toInt() and 0xff).toByte()
                            byteBuffer[i * 2 + 1] = ((shortVal.toInt() shr 8) and 0xff).toByte()
                        }

                        val audioBytes = byteBuffer.copyOfRange(0, readResult * 2)

                        // Buffer for final pass
                        synchronized(bufferLock) {
                            for (b in audioBytes) {
                                audioBuffer.add(b)
                            }
                        }

                        // Emit to transcriber
                        transcriber.emit(audioBytes)

                        // Amplitude callback
                        val rms = sqrt(sumSquares / readResult)
                        val normalized = (rms / 32768.0).toFloat()
                        callback?.onAmplitude(normalized)

                        // VAD-based commit boundary detection
                        val now = System.currentTimeMillis()
                        if (now - lastVadCheck > 100) {
                            checkCommitBoundary(normalized, now)
                            lastVadCheck = now
                        }

                        // Update activity time if above threshold
                        if (normalized > (streamConfig.vadThreshold)) {
                            lastActivityTime = now
                        }
                    }
                }
            }, "StreamingAudioCapture")
            recordingThread?.start()

        } catch (e: SecurityException) {
            callback?.onError(VelaError("Mic permission denied"))
        } catch (e: Exception) {
            callback?.onError(VelaError("Audio capture error: ${e.message}"))
        }
    }

    private fun stopAudioCapture() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread?.join(2000)
            recordingThread = null
        } catch (e: Exception) {
            Log.e("StreamingPipeline", "Error stopping audio", e)
        }
    }

    private fun checkCommitBoundary(amplitude: Float, now: Long) {
        val timeSinceLastCommit = now - lastCommitTime
        val timeSinceLastActivity = now - lastActivityTime

        // Commit if: VAD pause detected OR max segment time reached
        val shouldCommit = (timeSinceLastActivity > VAD_PAUSE_MS && amplitude < streamConfig.vadThreshold) ||
                timeSinceLastCommit > MAX_SEGMENT_MS

        if (shouldCommit && currentSegmentStart < committedText.length) {
            // Commit the current segment
            val segmentText = committedText.substring(currentSegmentStart)
            if (segmentText.isNotBlank()) {
                callback?.onRevisionMarker(
                    RevisionMarker(
                        type = "commit",
                        text = segmentText.trim(),
                        range = currentSegmentStart until committedText.length
                    )
                )
                currentSegmentStart = committedText.length
            }
            lastCommitTime = now
        }
    }

    private fun commitRemaining() {
        if (committedText.isNotBlank() && currentSegmentStart < committedText.length) {
            val remaining = committedText.substring(currentSegmentStart).trim()
            if (remaining.isNotEmpty()) {
                callback?.onRevisionMarker(
                    RevisionMarker(
                        type = "commit",
                        text = remaining,
                        range = currentSegmentStart until committedText.length
                    )
                )
            }
        }
    }

    private fun createTranscriberCallback(): StreamingTranscriptionCallback {
        return object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {
                when (marker.type) {
                    "partial" -> {
                        // Append to committed text buffer
                        committedText += marker.text + " "
                        callback?.onRevisionMarker(marker)
                    }
                    "commit" -> {
                        callback?.onRevisionMarker(marker)
                    }
                }
            }

            override fun onFinal(text: String) {
                callback?.onFinal(text)
            }

            override fun onError(error: VelaException) {
                callback?.onError(error)
            }

            override fun onAmplitude(normalized: Float) {
                // Amplitude handled by audio capture thread
            }
        }
    }
}
