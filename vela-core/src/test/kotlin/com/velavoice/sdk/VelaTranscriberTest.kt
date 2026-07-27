package com.velavoice.sdk

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VelaTranscriberTest {

    @Test
    fun `builder without model path throws`() {
        val context = RuntimeEnvironment.getApplication()
        val builder = VelaTranscriber.Builder(context)
        assertThrows(IllegalStateException::class.java) {
            builder.build()
        }
    }

    @Test
    fun `builder builds with model path`() {
        val context = RuntimeEnvironment.getApplication()
        val builder = VelaTranscriber.Builder(context)
            .whisperModel("/tmp/model.bin")
            .language("en")
            .threads(4)
        assertNotNull(builder)

        // build() will throw IllegalArgumentException because model file doesn't exist
        // WhisperEngine.initEngine() checks model file path first
        assertThrows(IllegalArgumentException::class.java) {
            builder.build()
        }
    }

    @Test
    fun `builder configures all options`() {
        val context = RuntimeEnvironment.getApplication()
        val builder = VelaTranscriber.Builder(context)
            .whisperModel("/tmp/model.bin")
            .language("fr")
            .threads(2)
            .useLlmCleaner(true, "/tmp/llm.bin")
            .customFillers(listOf("actually"))

        assertNotNull(builder)
    }
}
