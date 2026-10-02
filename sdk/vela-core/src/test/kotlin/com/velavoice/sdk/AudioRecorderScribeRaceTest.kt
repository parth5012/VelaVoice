package com.velavoice.sdk

import com.velavoice.sdk.cleaner.TextCleaner
import com.velavoice.sdk.whisper.WhisperEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression test for the currentScribeInput TOCTOU race (map #72, ticket #75):
 * stop() spawns the transcribe thread which reads currentScribeInput/cleaner/
 * initialPrompt *after* whisper.transcribe() returns (seconds later). A second
 * start() in that window overwrites those fields, so a password-field transcript
 * could be cleaned with a later non-sensitive ScribeInput.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowFakeAudioRecord::class])
class AudioRecorderScribeRaceTest {

    /**
     * Kotlin-declared wrapper: Mockito's `any(Class)` returns a Java-generic T
     * which the Kotlin compiler null-checks at the call site (checkNotNullExpressionValue),
     * throwing NPE for our non-null parameters. Declaring the helper in Kotlin with a
     * non-null return type suppresses that check; the returned value is only a placeholder
     * because a matcher is registered for the argument slot.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(type: Class<T>): T = ArgumentMatchers.any(type) as T

    @Test
    fun `in-flight transcribe keeps original scribeInput when start called again`() {
        val whisper = mock(WhisperEngine::class.java)
        val cleaner = mock(TextCleaner::class.java)
        val callbackA = mock(VelaRecordingCallback::class.java)
        val callbackB = mock(VelaRecordingCallback::class.java)

        val transcribeStarted = CountDownLatch(1)
        val releaseTranscribe = CountDownLatch(1)
        val cleanedLatch = CountDownLatch(1)
        val capturedPrivacy = AtomicReference<Boolean?>(null)
        val capturedPrompt = AtomicReference<String?>(null)

        doAnswer {
            transcribeStarted.countDown()
            releaseTranscribe.await(5, TimeUnit.SECONDS)
            "raw secret transcript"
        }.`when`(whisper).transcribe(anyArg(ByteArray::class.java), isNull())

        doAnswer { inv ->
            capturedPrivacy.set(inv.getArgument(6))
            capturedPrompt.set(inv.getArgument(5))
            cleanedLatch.countDown()
            "cleaned"
        }.`when`(cleaner).clean(
            anyArg(String::class.java),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            anyArg(String::class.java),
            anyBoolean()
        )

        val recorder = AudioRecorder()
        val sensitiveInput = ScribeInput(privacySensitive = true, overrideStyle = "password")
        recorder.start(whisper, cleaner, callbackA, sensitiveInput)
        recorder.stop(clean = true)

        assertTrue(
            "transcribe thread never reached whisper.transcribe",
            transcribeStarted.await(5, TimeUnit.SECONDS)
        )

        // Interleave: a new recording starts on a normal (non-sensitive) field
        // while the first transcript is still being cleaned.
        val normalInput = ScribeInput(privacySensitive = false, overrideStyle = null)
        recorder.start(whisper, cleaner, callbackB, normalInput)

        releaseTranscribe.countDown()

        assertTrue(
            "clean was never invoked for the in-flight transcript",
            cleanedLatch.await(5, TimeUnit.SECONDS)
        )
        assertEquals(
            "in-flight transcript must be cleaned with the ORIGINAL privacySensitive=true, " +
                "not the ScribeInput from the interleaved start()",
            true,
            capturedPrivacy.get()
        )
        assertEquals(
            "in-flight transcript must keep the ORIGINAL overrideStyle",
            "password",
            capturedPrompt.get()
        )

        // The interleaved session was never stopped, so exactly one clean() must have run.
        verify(cleaner, times(1)).clean(
            anyArg(String::class.java),
            isNull(),
            isNull(),
            isNull(),
            isNull(),
            anyArg(String::class.java),
            anyBoolean()
        )
        recorder.release()
    }
}
