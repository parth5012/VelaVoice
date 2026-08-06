package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class VelaRecordingCallbackTest {

    @Test
    fun `interface can be implemented`() {
        val callback = object : VelaRecordingCallback {
            var lastAmplitude = 0f
            var lastResult: TranscriptionResult? = null
            var lastError: VelaException? = null

            override fun onAmplitude(normalized: Float) {
                lastAmplitude = normalized
            }

            override fun onResult(result: TranscriptionResult) {
                lastResult = result
            }

            override fun onError(error: VelaException) {
                lastError = error
            }
        }

        callback.onAmplitude(0.5f)
        assertEquals(0.5f, callback.lastAmplitude, 0.0001f)

        val result = TranscriptionResult("raw", "clean", 100L)
        callback.onResult(result)
        assertEquals(result, callback.lastResult)

        val error = WhisperError("fail")
        callback.onError(error)
        assertEquals(error, callback.lastError)
    }
}
