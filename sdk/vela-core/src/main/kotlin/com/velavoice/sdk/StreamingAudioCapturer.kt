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
 */
class StreamingAudioCapturer {
    @Volatile
    private var isActive = false
    private var audioThread: Thread? = null
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
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                    val buffer = ShortArray(bufferSize / 2)

                    while (isActive) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            transcriber.emit(AudioConverter.shortsToBytes(buffer, read))
                        }
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
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            audioThread?.join(2000)
            audioThread = null
        } catch (e: Exception) {
            Log.e("StreamingAudioCapturer", "Error stopping streaming audio", e)
        }
    }
}
