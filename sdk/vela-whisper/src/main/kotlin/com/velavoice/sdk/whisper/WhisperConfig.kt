package com.velavoice.sdk.whisper

data class WhisperConfig(
    val modelPath: String,
    val language: String = "en",
    val numThreads: Int = 4
)
