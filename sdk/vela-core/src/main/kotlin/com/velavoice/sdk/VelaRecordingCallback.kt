package com.velavoice.sdk

interface VelaRecordingCallback {
    fun onAmplitude(normalized: Float)
    fun onResult(result: TranscriptionResult)
    fun onError(error: VelaException)

    /**
     * The recorder hit its hard duration cap ([AudioRecorder.MAX_RECORDING_DURATION_MS])
     * and stopped accumulating audio (map #97, ticket #104). Delivered on the
     * capture thread (like [onAmplitude]); the IME must post to its main thread
     * and run its normal stop path so session state settles and the transcript
     * of what was captured still lands via [onResult].
     */
    fun onDurationLimitReached() {}
}
