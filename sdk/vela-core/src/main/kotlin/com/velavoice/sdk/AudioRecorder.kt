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

    /**
     * Per-session audio buffer (ticket #102): each start() installs a fresh
     * instance so an orphaned capture thread from a previous session can never
     * write into the new session's buffer (cross-session PCM bleed).
     */
    @Volatile
    private var recordedAudioData = ByteArrayOutputStream()

    @Volatile
    private var transcribeThread: Thread? = null

    @Volatile
    private var currentTranscribeCancelled: java.util.concurrent.atomic.AtomicBoolean? = null

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
        // Never reconstruct over a live instance: tear down any leftover from a
        // failed prior session before allocating a new native buffer (ticket #99).
        releaseAudioRecord()
        isRecording = true
        currentWhisper = whisper
        currentCleaner = cleaner
        currentCallback = callback
        currentScribeInput = scribeInput
        currentInitialPrompt = initialPrompt
        // Fresh per-session buffer (ticket #102): never share bytes across sessions.
        recordedAudioData = ByteArrayOutputStream()

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
            audioRecord = record

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                isRecording = false
                releaseAudioRecord()
                callback.onError(AudioCaptureFailed("Microphone initialization failed"))
                return
            }

            record.startRecording()

            // Loop-local capture (ticket #102): the thread reads the AudioRecord
            // instance it created, not the shared field, and only while this
            // session still owns the field — an orphaned thread from a prior
            // session can never read a replacement session's recorder.
            val sessionBuffer = recordedAudioData
            recordingThread = Thread({
                val buffer = ShortArray(BUFFER_SIZE / 2)
                while (isRecording && audioRecord === record) {
                    try {
                        val readResult = record.read(buffer, 0, buffer.size)
                        if (readResult > 0 && audioRecord === record) {
                            sessionBuffer.write(AudioConverter.shortsToBytes(buffer, readResult))
                            callback.onAmplitude(AudioConverter.rmsNormalized(buffer, readResult))
                        }
                    } catch (e: Exception) {
                        // A blocked read() can throw when the recorder is released
                        // under it; the identity guard above already prevents
                        // cross-session bleed, so just exit the loop.
                        break
                    }
                }
            }, "VelaAudioRecorderThread")

            recordingThread?.start()
        } catch (e: SecurityException) {
            isRecording = false
            releaseAudioRecord()
            callback.onError(AudioCaptureFailed("Mic permission denied: " + e.message))
        } catch (e: Exception) {
            isRecording = false
            releaseAudioRecord()
            callback.onError(AudioCaptureFailed("Error starting recording: " + e.message))
        }
    }

    /**
     * Idempotent AudioRecord teardown (ticket #99): stop (IllegalStateException
     * tolerated — an uninitialized or already-stopped recorder throws by
     * contract), release unconditionally, null the field, then join the capture
     * thread with the ANR-bounded timeout. Every path that ends a capture
     * session — stop(), cancel(), release(), and start()'s error paths — must
     * funnel through here so a live native buffer is never orphaned.
     */
    private fun releaseAudioRecord() {
        val record = audioRecord ?: return
        audioRecord = null
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            // stop() throws when the recorder never initialized or was already
            // stopped; release() below still must run.
        }
        try {
            record.release()
        } catch (e: Exception) {
            currentCallback?.onError(AudioCaptureFailed("Error releasing recorder: " + e.message))
        }
        // Bounded join: a capture thread parked in a blocking read() must not
        // wedge the (often main/UI) thread calling cancel()/stop() — ANR risk.
        try {
            recordingThread?.join(CANCEL_JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordingThread = null
    }

    fun stop(clean: Boolean = true) {
        if (!isRecording) return
        // Snapshot this session's buffer BEFORE flipping the flag (ticket #102):
        // once isRecording is false a concurrent start() may reassign the
        // recordedAudioData field, so we must not read the field after the flip.
        val sessionBuffer = recordedAudioData
        isRecording = false
        releaseAudioRecord()

        val audioBytes = sessionBuffer.toByteArray()
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
            val sessionCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            currentTranscribeCancelled = sessionCancelled

            val thread = Thread({
                try {
                    if (sessionCancelled.get() || Thread.currentThread().isInterrupted) return@Thread
                    val rawTranscript = whisper.transcribe(audioBytes, initialPrompt)
                    if (sessionCancelled.get() || Thread.currentThread().isInterrupted) return@Thread
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
                    if (sessionCancelled.get() || Thread.currentThread().isInterrupted) return@Thread
                    val durationMs = ((audioBytes.size / 2) / 16L)
                    if (!sessionCancelled.get() && !Thread.currentThread().isInterrupted) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (!sessionCancelled.get()) {
                                callback.onResult(TranscriptionResult(rawTranscript, cleanedTranscript, durationMs, audioBytes))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!sessionCancelled.get() && !Thread.currentThread().isInterrupted) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (!sessionCancelled.get()) {
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
        currentTranscribeCancelled?.set(true)
        val thread = transcribeThread
        if (thread != null && thread.isAlive) {
            thread.interrupt()
            try {
                thread.join(timeoutMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (thread == null || !thread.isAlive) transcribeThread = null
    }

    /** Cancel recording immediately without transcription or callbacks */
    open fun cancel() {
        isRecording = false
        // Idempotent: releases only if a live instance is held (ticket #99).
        releaseAudioRecord()
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
        releaseAudioRecord()
        recordedAudioData.reset()
        currentWhisper = null
        currentCleaner = null
        currentCallback = null
        currentScribeInput = ScribeInput()
    }
}
