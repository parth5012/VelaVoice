package com.velavoice.sdk.whisper

import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WhisperEngineTest {

    @Test
    fun `constructor throws on missing model file before JNI check`() {
        // initEngine() checks model file first: /tmp/model.bin doesn't exist
        // so it throws IllegalArgumentException before checking isLibLoaded
        val config = WhisperConfig("/nonexistent/model.bin")
        assertThrows(IllegalArgumentException::class.java) {
            WhisperEngine(config)
        }
    }
}
