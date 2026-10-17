package com.velavoice.sdk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.velavoice.sdk.whisper.AudioConverter

/**
 * Owns the microphone capture thread feeding a streaming transcriber.
 *
 * Extracted from [VelaTranscriber] so the facade stays a thin delegation layer.
 * [stop] is safe to call when idle (mirrors the original null-safe teardown).
 *
 * The worker keeps its recorder in a local reference and releases it in a
 * `finally` block, so [stop] can never miss a recorder the worker is about
 * to start, nor release one out from under a blocking `read()`.
 */
class StreamingAudioCapturer {
    @Volatile
    private var isActive = false
    private var audioThread: Thread? = null
    private val audioRecordLock = Any()
    private var audioRecord: AudioRecord? = null

    /**
     * Monotonic session epoch (ticket #102): bumped on every stop() so an
     * orphaned worker whose start() outlived its stop can tell it is stale and
     * exit instead of reading a replacement session's AudioRecord.
     */
    @Volatile
    private var sessionEpoch = 0

    fun start(transcriber: StreamingTranscriber) {
        // Double-start guard (ticket #102): a second start() while already
        // active must not spawn a second capture thread / second AudioRecord
        // (two readers on one mic). stop()-then-start() still works because
        // stop() clears isActive before joining.
        synchronized(audioRecordLock) {
            if (isActive) return
            isActive = true
        }
        val epoch = sessionEpoch
        audioThread = Thread({
            try {
                val bufferSize = AudioRecord.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                try {
                    // Hotfix: a `return@Thread` from inside synchronized(...)
                    // compiles to a monitor-held return, which Android 15's
                    // strict verifier rejects (VerifyError at class load,
                    // crashing the whole IME on debug builds; R8 masked it in
                    // release 4.1). The decision is captured in a local and
                    // acted on after the block has exited instead.
                    var installRecord = true
                    synchronized(audioRecordLock) {
                        // A stop() between our isActive check and this lock may
                        // have bumped the epoch; if so, do not install the
                        // recorder (ticket #102).
                        if (epoch != sessionEpoch) {
                            installRecord = false
                        } else {
                            audioRecord = record
                            if (record.state == AudioRecord.STATE_INITIALIZED) {
                                record.startRecording()
                            }
                        }
                    }

                    if (installRecord && record.state == AudioRecord.STATE_INITIALIZED) {
                        val buffer = ShortArray(bufferSize / 2)
                        while (isActive && epoch == sessionEpoch) {
                            val read = record.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                transcriber.emit(AudioConverter.shortsToBytes(buffer, read))
                            }
                        }
                    }
                } finally {
                    synchronized(audioRecordLock) {
                        if (audioRecord === record) audioRecord = null
                        record.release()
                    }
                }
            } catch (e: Exception) {
                Log.e("StreamingAudioCapturer", "Streaming audio error", e)
            }
        }, "VelaStreamingAudio")
        audioThread?.start()
    }

    fun stop() {
        // Bump the epoch first so any in-flight worker that has not yet
        // installed its AudioRecord can detect staleness and exit (ticket #102).
        sessionEpoch++
        isActive = false
        try {
            synchronized(audioRecordLock) {
                audioRecord?.stop()
            }
            // Join OUTSIDE the lock: the worker's finally block needs the lock
            // to release its recorder, so joining under the lock would deadlock.
            audioThread?.join(2000)
            audioThread = null
        } catch (e: Exception) {
            Log.e("StreamingAudioCapturer", "Error stopping streaming audio", e)
        }
    }
}
