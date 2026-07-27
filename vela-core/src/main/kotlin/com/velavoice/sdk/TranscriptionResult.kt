package com.velavoice.sdk

data class TranscriptionResult(
    val rawTranscript: String,
    val cleanedTranscript: String,
    val durationMs: Long
)
