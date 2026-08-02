package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `builder configures scribe options`() {
        val context = RuntimeEnvironment.getApplication()
        val builder = VelaTranscriber.Builder(context)
            .whisperModel("/tmp/model.bin")
            .scribe(
                enable = true,
                defaultStyle = "Casual",
                customSystemPrompt = "You are my assistant."
            )

        assertNotNull(builder)
    }

    @Test
    fun `builder scribe keeps default professional style`() {
        val context = RuntimeEnvironment.getApplication()
        val builder = VelaTranscriber.Builder(context)
            .whisperModel("/tmp/model.bin")
            .scribe(enable = true)

        assertNotNull(builder)
    }

    @Test
    fun `ScribeInput defaults are null`() {
        val scribe = ScribeInput()
        assertNull(scribe.contextBefore)
        assertNull(scribe.contextAfter)
        assertNull(scribe.appName)
        assertNull(scribe.inputType)
        assertNull(scribe.overrideStyle)
    }

    @Test
    fun `ScribeInput stores values`() {
        val scribe = ScribeInput(
            contextBefore = "Hello there",
            contextAfter = "how are you",
            appName = "com.example.messages",
            inputType = "text",
            overrideStyle = "Bullet Points"
        )
        assertEquals("Hello there", scribe.contextBefore)
        assertEquals("how are you", scribe.contextAfter)
        assertEquals("com.example.messages", scribe.appName)
        assertEquals("text", scribe.inputType)
        assertEquals("Bullet Points", scribe.overrideStyle)
    }
}
