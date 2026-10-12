package com.velavoice.sdk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.velavoice.sdk.cleaner.TextCleaner
import com.velavoice.sdk.whisper.AudioConverter
import com.velavoice.sdk.whisper.WhisperEngine
import java.io.ByteArrayOutputStream

open class AudioRecorder(
    /**
     * Hard cap on one recording session (map #97, ticket #104). A stuck
     * session would otherwise grow the per-session PCM buffer without bound;
     * an OOM in a BIND_INPUT_METHOD process is a user-visible typing outage.
     * Tests inject a small cap to keep synthetic long-run tests fast.
     */
    private val maxRecordingDurationMs: Long = MAX_RECORDING_DURATION_MS
) {
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

        /**
         * Hard cap on one recording session (map #97, ticket #104). At the cap
         * the capture thread stops accumulating and signals the IME through
         * [VelaRecordingCallback.onDurationLimitReached]; the IME runs its
         * normal stop path, so the recorded audio is still transcribed. 60 s of
         * 16 kHz 16-bit mono is ~1.9 MB — small enough that a stuck session
         * can never OOM the IME process, long enough for normal dictation.
         */
        const val MAX_RECORDING_DURATION_MS = 60_000L

        /** PCM 16-bit mono @16 kHz: one second of audio in bytes. */
        const val PCM_BYTES_PER_SECOND = SAMPLE_RATE * 2L
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
            // Hard cap (ticket #104): the duration-implied byte budget bounds
            // the retained PCM; either the byte budget or the wall clock firing
            // ends accumulation and signals the IME for an auto-stop.
            val maxSessionBytes = maxRecordingDurationMs * PCM_BYTES_PER_SECOND / 1000
            val startNanos = System.nanoTime()
            recordingThread = Thread({
                val buffer = ShortArray(BUFFER_SIZE / 2)
                var sessionBytes = 0L
                var limitReached = false
                while (isRecording && audioRecord === record) {
                    try {
                        val readResult = record.read(buffer, 0, buffer.size)
                        if (readResult > 0 && audioRecord === record) {
                            // Clip the final read so the retained buffer can
                            // never exceed the byte budget, even before the
                            // IME's auto-stop lands.
                            val chunk = AudioConverter.shortsToBytes(buffer, readResult)
                            val remaining = maxSessionBytes - sessionBytes
                            val toWrite = if (chunk.size <= remaining) chunk
                                else chunk.copyOf(remaining.toInt().coerceAtLeast(0))
                            if (toWrite.isNotEmpty()) {
                                sessionBuffer.write(toWrite)
                                sessionBytes += toWrite.size
                                callback.onAmplitude(AudioConverter.rmsNormalized(buffer, readResult))
                            }
                        }
                        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                        if (sessionBytes >= maxSessionBytes || elapsedMs >= maxRecordingDurationMs) {
                            limitReached = true
                            break
                        }
                    } catch (e: Exception) {
                        // A blocked read() can throw when the recorder is released
                        // under it; the identity guard above already prevents
                        // cross-session bleed, so just exit the loop.
                        break
                    }
                }
                if (limitReached) {
                    // Auto-stop signal (ticket #104), delivered on the capture
                    // thread like onAmplitude: the IME owns the session state
                    // machine, so it runs the normal stop path (which still
                    // transcribes the capped audio via stop()). isRecording
                    // stays true until that stop lands, keeping the #98 gate
                    // transition (RECORDING -> PROCESSING -> IDLE) intact.
                    callback.onDurationLimitReached()
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
