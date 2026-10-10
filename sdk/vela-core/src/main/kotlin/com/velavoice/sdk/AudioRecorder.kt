package com.velavoice.sdk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.velavoice.sdk.cleaner.TextCleaner
import com.velavoice.sdk.whisper.AudioConverter
import com.velavoice.sdk.whisper.WhisperEngine
import java.io.ByteArrayOutputStream

open class AudioRecorder {
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val recordedAudioData = ByteArrayOutputStream()

    @Volatile
    private var transcribeThread: Thread? = null

    @Volatile
    private var isTranscribeCancelled = false

    private var currentWhisper: WhisperEngine? = null
    private var currentCleaner: TextCleaner? = null
    private var currentCallback: VelaRecordingCallback? = null
    private var currentScribeInput: ScribeInput = ScribeInput()
    private var currentInitialPrompt: String? = null

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        /** Max time cancel() waits for the capture thread to exit (ANR budget). */
        const val CANCEL_JOIN_TIMEOUT_MS = 2000L

        /** Max time to wait for the transcribe thread to drain during teardown. */
        const val TRANSCRIBE_JOIN_TIMEOUT_MS = 5000L
    }

    fun isRecording(): Boolean = isRecording

    fun start(whisper: WhisperEngine, cleaner: TextCleaner?, callback: VelaRecordingCallback) {
        start(whisper, cleaner, callback, ScribeInput(), null)
    }

    fun start(whisper: WhisperEngine, cleaner: TextCleaner?, callback: VelaRecordingCallback, scribeInput: ScribeInput, initialPrompt: String? = null) {
        if (isRecording) return
        isRecording = true
        currentWhisper = whisper
        currentCleaner = cleaner
        currentCallback = callback
        currentScribeInput = scribeInput
        currentInitialPrompt = initialPrompt
        recordedAudioData.reset()

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback.onError(AudioCaptureFailed("Microphone initialization failed"))
                isRecording = false
                return
            }

            audioRecord?.startRecording()

            recordingThread = Thread({
                val buffer = ShortArray(BUFFER_SIZE / 2)
                while (isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult > 0) {
                        recordedAudioData.write(AudioConverter.shortsToBytes(buffer, readResult))
                        callback.onAmplitude(AudioConverter.rmsNormalized(buffer, readResult))
                    }
                }
            }, "VelaAudioRecorderThread")

            recordingThread?.start()
        } catch (e: SecurityException) {
            isRecording = false
            callback.onError(AudioCaptureFailed("Mic permission denied: " + e.message))
        } catch (e: Exception) {
            isRecording = false
            callback.onError(AudioCaptureFailed("Error starting recording: " + e.message))
        }
    }

    fun stop(clean: Boolean = true) {
        if (!isRecording) return
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread?.join()
            recordingThread = null
        } catch (e: Exception) {
            currentCallback?.onError(AudioCaptureFailed("Error stopping recording: " + e.message))
            return
        }

        val audioBytes = recordedAudioData.toByteArray()
        val whisper = currentWhisper
        val callback = currentCallback
        // Snapshot session state before spawning: a concurrent start() may overwrite
        // currentScribeInput/currentCleaner/currentInitialPrompt while this transcript
        // is still being cleaned (TOCTOU — ticket #75), which would apply a later
        // field's privacy flag to this field's text.
        val cleaner = currentCleaner
        val scribeInput = currentScribeInput
        val initialPrompt = currentInitialPrompt

        if (whisper != null && callback != null) {
            drainTranscribeThread(CANCEL_JOIN_TIMEOUT_MS)
            isTranscribeCancelled = false

            val thread = Thread({
                try {
                    if (isTranscribeCancelled || Thread.currentThread().isInterrupted) return@Thread
                    val rawTranscript = whisper.transcribe(audioBytes, initialPrompt)
                    if (isTranscribeCancelled || Thread.currentThread().isInterrupted) return@Thread
                    val cleanedTranscript = if (clean) {
                        cleaner?.clean(
                            rawTranscript,
                            contextBefore = scribeInput.contextBefore,
                            contextAfter = scribeInput.contextAfter,
                            appName = scribeInput.appName,
                            inputType = scribeInput.inputType,
                            overrideStyle = scribeInput.overrideStyle,
                            privacySensitive = scribeInput.privacySensitive
                        ) ?: rawTranscript
                    } else {
                        rawTranscript
                    }
                    if (isTranscribeCancelled || Thread.currentThread().isInterrupted) return@Thread
                    val durationMs = ((audioBytes.size / 2) / 16L)
                    if (!isTranscribeCancelled && !Thread.currentThread().isInterrupted) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (!isTranscribeCancelled) {
                                callback.onResult(TranscriptionResult(rawTranscript, cleanedTranscript, durationMs, audioBytes))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!isTranscribeCancelled && !Thread.currentThread().isInterrupted) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (!isTranscribeCancelled) {
                                callback.onError(WhisperError("Transcription failed: " + e.message))
                            }
                        }
                    }
                } finally {
                    if (transcribeThread === Thread.currentThread()) {
                        transcribeThread = null
                    }
                }
            }, "VelaTranscribeThread")
            transcribeThread = thread
            thread.start()
        }
    }

    /**
     * Drain the in-flight transcribe thread, waiting up to [timeoutMs] for completion.
     * Bounded by [timeoutMs]. If timeout expires, cancels and interrupts the thread
     * so it cannot run past teardown.
     * Returns true if drained, false if timed out.
     */
    fun drainTranscribeThread(timeoutMs: Long = TRANSCRIBE_JOIN_TIMEOUT_MS): Boolean {
        val thread = transcribeThread ?: return true
        try {
            thread.join(timeoutMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) {
            cancelTranscribe(CANCEL_JOIN_TIMEOUT_MS)
            return false
        }
        transcribeThread = null
        return true
    }

    /** Convenience alias for [drainTranscribeThread]. */
    fun joinTranscribeThread(timeoutMs: Long = TRANSCRIBE_JOIN_TIMEOUT_MS): Boolean =
        drainTranscribeThread(timeoutMs)

    /**
     * Cancel any in-flight transcription deterministically.
     * Sets the cancellation flag, interrupts the thread, and waits up to [timeoutMs] to join.
     */
    fun cancelTranscribe(timeoutMs: Long = CANCEL_JOIN_TIMEOUT_MS) {
        isTranscribeCancelled = true
        val thread = transcribeThread
        if (thread != null && thread.isAlive) {
            thread.interrupt()
            try {
                thread.join(timeoutMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        transcribeThread = null
    }

    /** Cancel recording immediately without transcription or callbacks */
    open fun cancel() {
        if (isRecording) {
            isRecording = false
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                // Bounded join: a capture thread parked in a blocking read() must not
                // wedge the (often main/UI) thread calling cancel() — ANR risk (OCR finding).
                recordingThread?.join(CANCEL_JOIN_TIMEOUT_MS)
                recordingThread = null
            } catch (e: Exception) {
                // ignore during cancel
            }
        }
        cancelTranscribe()
        recordedAudioData.reset()
    }

    open fun release() {
        if (isRecording) {
            cancel()
        } else {
            drainTranscribeThread()
        }
        // cancel() early-returns when not recording (e.g. after a completed stop()),
        // so release() must free the captured audio buffer unconditionally.
        recordedAudioData.reset()
        currentWhisper = null
        currentCleaner = null
        currentCallback = null
        currentScribeInput = ScribeInput()
    }
}
