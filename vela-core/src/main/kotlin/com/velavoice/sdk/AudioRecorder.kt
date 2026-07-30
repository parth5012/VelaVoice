package com.velavoice.sdk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.velavoice.sdk.cleaner.TextCleaner
import com.velavoice.sdk.whisper.WhisperEngine
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

class AudioRecorder {
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val recordedAudioData = ByteArrayOutputStream()

    private var currentWhisper: WhisperEngine? = null
    private var currentCleaner: TextCleaner? = null
    private var currentCallback: VelaRecordingCallback? = null

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    fun isRecording(): Boolean = isRecording

    fun start(whisper: WhisperEngine, cleaner: TextCleaner?, callback: VelaRecordingCallback) {
        if (isRecording) return
        isRecording = true
        currentWhisper = whisper
        currentCleaner = cleaner
        currentCallback = callback
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
                val byteBuffer = ByteArray(BUFFER_SIZE)
                while (isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult > 0) {
                        var sum = 0.0
                        for (i in 0 until readResult) {
                            val shortVal = buffer[i]
                            sum += shortVal * shortVal
                            byteBuffer[i * 2] = (shortVal.toInt() and 0xff).toByte()
                            byteBuffer[i * 2 + 1] = ((shortVal.toInt() shr 8) and 0xff).toByte()
                        }
                        recordedAudioData.write(byteBuffer, 0, readResult * 2)

                        val rms = sqrt(sum / readResult)
                        val normalized = (rms / 32768.0).toFloat()
                        callback.onAmplitude(normalized)
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

        if (whisper != null && callback != null) {
            Thread({
                try {
                    val rawTranscript = whisper.transcribe(audioBytes)
                    val cleanedTranscript = if (clean) {
                        currentCleaner?.clean(rawTranscript) ?: rawTranscript
                    } else {
                        rawTranscript
                    }
                    val durationMs = ((audioBytes.size / 2) / 16L)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        callback.onResult(TranscriptionResult(rawTranscript, cleanedTranscript, durationMs, audioBytes))
                    }
                } catch (e: Exception) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        callback.onError(WhisperError("Transcription failed: " + e.message))
                    }
                }
            }, "VelaTranscribeThread").start()
        }
    }

    fun release() {
        stop(false)
        currentWhisper = null
        currentCleaner = null
        currentCallback = null
    }
}
