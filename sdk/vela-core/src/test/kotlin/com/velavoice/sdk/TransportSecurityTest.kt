package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 88 (map #81): Lock down transport.
 *
 * Only encrypted transports are accepted for transcription endpoints:
 * - `wss://` and `https://` are accepted (plus blank = built-in default).
 * - `ws://` and `http://` are rejected with a clear error (fail closed),
 *   so a user-supplied cleartext endpoint can never silently carry API keys.
 */
@RunWith(RobolectricTestRunner::class)
class TransportSecurityTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `ws cleartext endpoint is rejected with clear error`() {
        try {
            StreamingPipeline.Builder(context)
                .apiKey("key-123")
                .endpoint("ws://custom.example.com/ws")
                .build()
            fail("Expected IllegalArgumentException for ws:// endpoint")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error must name the accepted schemes, got: ${e.message}",
                e.message?.contains("wss://") == true && e.message?.contains("https://") == true
            )
        }
    }

    @Test
    fun `http cleartext endpoint is rejected with clear error`() {
        try {
            StreamingPipeline.Builder(context)
                .apiKey("key-123")
                .endpoint("http://custom.example.com/v1/audio/transcriptions")
                .build()
            fail("Expected IllegalArgumentException for http:// endpoint")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Error must name the accepted schemes, got: ${e.message}",
                e.message?.contains("wss://") == true && e.message?.contains("https://") == true
            )
        }
    }

    @Test
    fun `wss endpoint is accepted`() {
        val config = StreamingPipeline.Builder(context)
            .apiKey("key-123")
            .endpoint("wss://custom.example.com/ws")
            .build()
            .config()
        assertEquals("wss://custom.example.com/ws", config.endpoint)
    }

    @Test
    fun `https endpoint is accepted`() {
        val config = StreamingPipeline.Builder(context)
            .apiKey("key-123")
            .endpoint("https://custom.example.com/v1/audio/transcriptions")
            .model("whisper-1")
            .build()
            .config()
        assertEquals("https://custom.example.com/v1/audio/transcriptions", config.endpoint)
    }

    @Test
    fun `blank endpoint falls back to secure default`() {
        val config = StreamingPipeline.Builder(context).build().config()
        val endpoint = config.endpoint
        assertTrue(
            "Default endpoint must be encrypted transport, got: $endpoint",
            endpoint.startsWith("wss://") || endpoint.startsWith("https://")
        )
    }

    @Test(timeout = 30000)
    fun `start rejects http endpoint override with error callback`() {
        val errors = mutableListOf<String>()
        val pipeline = StreamingPipeline.Builder(context)
            .apiKey("key-123")
            .build()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {}
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {
                errors += (error.message ?: "")
            }
            override fun onAmplitude(normalized: Float) {}
        })

        pipeline.start(
            "cloud",
            StreamConfig(endpoint = "http://evil.example.com/x", consentToUpload = true)
        )

        assertTrue(
            "Insecure start-time endpoint override must report wss/https error, got: $errors",
            errors.any { it.contains("wss://") && it.contains("https://") }
        )
    }

    @Test(timeout = 30000)
    fun `start rejects ws endpoint override with error callback`() {
        val errors = mutableListOf<String>()
        val pipeline = StreamingPipeline.Builder(context)
            .apiKey("key-123")
            .build()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {}
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {
                errors += (error.message ?: "")
            }
            override fun onAmplitude(normalized: Float) {}
        })

        pipeline.start(
            "cloud",
            StreamConfig(endpoint = "ws://evil.example.com/ws", consentToUpload = true)
        )

        assertTrue(
            "Insecure start-time endpoint override must report wss/https error, got: $errors",
            errors.any { it.contains("wss://") && it.contains("https://") }
        )
    }
}
