package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * F36 remnant (adversarial review 2026-10-27, map #210 ticket #254): the
 * orphan F36 fields that have no riding behavioral ticket get annotated with
 * @Volatile here. These are read/written from more than one thread (reconnect
 * scheduler vs. main for CloudStreamingTranscriber; startStreaming vs.
 * stopStreaming/release for VelaTranscriber) with no happens-before guarantee.
 *
 * Correctness of visibility cannot be proven in a unit test, so this test
 * locks the contract mechanically: each guard field must be declared
 * @Volatile (JVM modifier bit VOLATILE), which fails red before the fix and
 * passes after.
 */
class F36RemnantVolatileGuardTest {

    @Test
    fun `CloudStreamingTranscriber webSocket is @Volatile`() {
        val field = CloudStreamingTranscriber::class.java.getDeclaredField("webSocket")
        assertTrue(
            "CloudStreamingTranscriber.webSocket is published by start()/release() and read by " +
                "the reconnect scheduler and send paths — must be @Volatile (F36, ticket #254)",
            Modifier.isVolatile(field.modifiers),
        )
    }

    @Test
    fun `CloudStreamingTranscriber sessionGen is @Volatile`() {
        val field = CloudStreamingTranscriber::class.java.getDeclaredField("sessionGen")
        assertTrue(
            "CloudStreamingTranscriber.sessionGen is bumped by start()/stop()/release() and read " +
                "by the delayed-close scheduler — must be @Volatile (F36, ticket #254)",
            Modifier.isVolatile(field.modifiers),
        )
    }

    @Test
    fun `VelaTranscriber activeStreamingTranscriber is @Volatile`() {
        val field = VelaTranscriber::class.java.getDeclaredField("activeStreamingTranscriber")
        assertTrue(
            "VelaTranscriber.activeStreamingTranscriber is published by startStreaming and read by " +
                "stopStreaming/release — must be @Volatile (F36, ticket #254)",
            Modifier.isVolatile(field.modifiers),
        )
    }
}