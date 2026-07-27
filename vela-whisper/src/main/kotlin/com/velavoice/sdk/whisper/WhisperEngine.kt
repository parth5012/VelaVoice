package com.velavoice.sdk.whisper

import android.util.Log
import java.io.File

class WhisperEngine(private val config: WhisperConfig) {
    private var contextPtr: Long = 0
    private var isLibLoaded = false

    init {
        try {
            System.loadLibrary("whisper")
            isLibLoaded = true
            Log.d("WhisperEngine", "Successfully loaded whisper JNI library")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("WhisperEngine", "Could not load whisper JNI library", e)
        }
        initEngine()
    }

    private fun initEngine() {
        val modelFile = File(config.modelPath)
        if (!modelFile.exists()) {
            throw IllegalArgumentException("Model file not found at: ${config.modelPath}")
        }
        if (!isLibLoaded) {
            throw IllegalStateException("JNI library not loaded")
        }
        contextPtr = nativeInit(config.modelPath)
        if (contextPtr == 0L) {
            throw RuntimeException("Failed to initialize native Whisper context")
        }
    }

    fun transcribe(audioBytes: ByteArray): String {
        if (audioBytes.isEmpty()) return ""
        if (contextPtr == 0L) {
            throw IllegalStateException("Whisper context is not initialized")
        }
        val floatAudio = AudioConverter.convertPcmToFloat(audioBytes)
        return nativeTranscribe(contextPtr, floatAudio) ?: throw RuntimeException("Error during native transcription")
    }

    fun free() {
        if (contextPtr != 0L) {
            nativeFree(contextPtr)
            contextPtr = 0L
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(contextPtr: Long, audioData: FloatArray): String?
    private external fun nativeFree(contextPtr: Long)
}
