package com.velavoice.sdk

sealed class VelaException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ModelNotFound(modelPath: String) : VelaException("Model not found: $modelPath")
class WhisperError(msg: String) : VelaException(msg)
class AudioCaptureFailed(msg: String) : VelaException(msg)
class InvalidAudio(msg: String) : VelaException(msg)
