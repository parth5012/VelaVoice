package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * F14 (adversarial review 2026-10-27, ticket #229): `isRecording` is the
 * capture-loop guard (`while (isRecording && audioRecord === record)`) in
 * [AudioRecorder]'s capture thread but is written from other threads (start/
 * stop/cancel/release). A plain `Boolean` gives no happens-before guarantee,
 * so the capture thread may never observe the stop: the thread keeps
 * recording, the mic leaks, and join() calls block for their full timeouts.
 *
 * Correctness at this level cannot be proven in a unit test, so this test
 * locks the contract mechanically: the guard field must be declared
 * `@Volatile` (JVM modifier bit `VOLATILE`), which fails red before the fix
 * and passes after.
 */
class AudioRecorderVolatileGuardTest {

    @Test
    fun `isRecording guard field is @Volatile for cross-thread visibility`() {
        val field = AudioRecorder::class.java.getDeclaredField("isRecording")
        assertTrue(
            "AudioRecorder.isRecording is written from start/stop/cancel/release " +
                "threads and read by the capture loop; it must be @Volatile so the " +
                "capture thread reliably observes the stop (F14, ticket #229)",
            Modifier.isVolatile(field.modifiers),
        )
    }
}
