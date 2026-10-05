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

    fun start(transcriber: StreamingTranscriber) {
        isActive = true
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
                    synchronized(audioRecordLock) {
                        audioRecord = record
                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            record.startRecording()
                        }
                    }

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        val buffer = ShortArray(bufferSize / 2)
                        while (isActive) {
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
        isActive = false
        try {
            synchronized(audioRecordLock) {
                audioRecord?.stop()
            }
            audioThread?.join(2000)
            audioThread = null
        } catch (e: Exception) {
            Log.e("StreamingAudioCapturer", "Error stopping streaming audio", e)
        }
    }
}
