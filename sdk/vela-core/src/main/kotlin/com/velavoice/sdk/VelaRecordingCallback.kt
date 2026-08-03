package com.velavoice.sdk

interface VelaRecordingCallback {
    fun onAmplitude(normalized: Float)
    fun onResult(result: TranscriptionResult)
    fun onError(error: VelaException)
}
