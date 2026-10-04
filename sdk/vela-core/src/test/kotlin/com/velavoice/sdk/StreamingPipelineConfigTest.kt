package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StreamingPipelineConfigTest {

    private val context = RuntimeEnvironment.getApplication()

    // --- Area 1: Builder config propagation ---

    @Test
    fun `build propagates all 12 fields into StreamConfig`() {
        val pipeline = StreamingPipeline.Builder(context)
            .whisperModelPath("/data/model.bin")
            .apiKey("key-123")
            .endpoint("wss://custom.example.com/ws")
            .model("whisper-large-v3")
            .language("fr")
            .threads(8)
            .chunkDurationMs(2000)
            .windowDurationMs(20000)
            .overlapMs(1000)
            .resetIntervalMs(60000L)
            .useVad(false)
            .vadThreshold(0.05f)
            .build()

        val config = pipeline.config()
        assertEquals("/data/model.bin", config.modelPath)
        assertEquals("key-123", config.apiKey)
        assertEquals("wss://custom.example.com/ws", config.endpoint)
        assertEquals("whisper-large-v3", config.model)
        assertEquals("fr", config.language)
        assertEquals(8, config.numThreads)
        assertEquals(2000, config.chunkDurationMs)
        assertEquals(20000, config.windowDurationMs)
        assertEquals(1000, config.overlapMs)
        assertEquals(60000L, config.resetIntervalMs)
        assertEquals(false, config.useVad)
        assertEquals(0.05f, config.vadThreshold, 0.0001f)
    }

    @Test
    fun `modelPath is not empty when whisperModelPath was set`() {
        val pipeline = StreamingPipeline.Builder(context)
            .whisperModelPath("/sdcard/whisper.bin")
            .build()
        assertTrue("modelPath must not be empty after whisperModelPath()", pipeline.config().modelPath.isNotEmpty())
        assertEquals("/sdcard/whisper.bin", pipeline.config().modelPath)
    }

    @Test
    fun `config exposes defaults when builder methods not called`() {
        val config = StreamingPipeline.Builder(context).build().config()
        assertEquals("", config.modelPath)
        assertEquals("en", config.language)
        assertEquals(4, config.numThreads)
        assertEquals("wss://api.openai.com/v1/realtime/transcription_sessions", config.endpoint)
        assertEquals("gpt-live-transcribe", config.model)
        assertEquals(3000, config.chunkDurationMs)
        assertEquals(15000, config.windowDurationMs)
        assertEquals(1500, config.overlapMs)
        assertEquals(30000L, config.resetIntervalMs)
        assertEquals(true, config.useVad)
        assertEquals(0.02f, config.vadThreshold, 0.0001f)
    }

    // --- Area 2: start(mode, config) reconciliation ---

    @Test
    fun `reconcile fills blank modelPath from builder default`() {
        val pipeline = StreamingPipeline.Builder(context)
            .whisperModelPath("/builder/model.bin")
            .build()
        val resolved = pipeline.reconcile(StreamConfig())
        assertEquals("/builder/model.bin", resolved.modelPath)
    }

    @Test
    fun `reconcile preserves explicit modelPath over builder default`() {
        val pipeline = StreamingPipeline.Builder(context)
            .whisperModelPath("/builder/model.bin")
            .build()
        val resolved = pipeline.reconcile(StreamConfig(modelPath = "/explicit/model.bin"))
        assertEquals("/explicit/model.bin", resolved.modelPath)
    }

    @Test
    fun `reconcile fills blank apiKey from builder default`() {
        val pipeline = StreamingPipeline.Builder(context)
            .apiKey("builder-key")
            .build()
        val resolved = pipeline.reconcile(StreamConfig())
        assertEquals("builder-key", resolved.apiKey)
    }

    @Test
    fun `reconcile cannot restore builder endpoint because StreamConfig default is non-blank`() {
        val pipeline = StreamingPipeline.Builder(context)
            .endpoint("wss://builder.example.com/ws")
            .build()
        val resolved = pipeline.reconcile(StreamConfig())
        assertEquals(
            "KNOWN TRAP: reconcile() uses ifBlank, but StreamConfig.endpoint defaults to a non-blank " +
                "value, so an explicit StreamConfig() silently overrides the Builder. Use start(mode).",
            StreamConfig().endpoint,
            resolved.endpoint
        )
    }

    @Test
    fun `reconcile falls back to builder numThreads when config has zero`() {
        val pipeline = StreamingPipeline.Builder(context)
            .threads(7)
            .build()
        val resolved = pipeline.reconcile(StreamConfig(numThreads = 0))
        assertEquals(7, resolved.numThreads)
    }

    @Test
    fun `reconcile falls back to builder numThreads when config has negative`() {
        val pipeline = StreamingPipeline.Builder(context)
            .threads(7)
            .build()
        val resolved = pipeline.reconcile(StreamConfig(numThreads = -1))
        assertEquals(7, resolved.numThreads)
    }

    @Test
    fun `reconcile keeps explicit numThreads when positive`() {
        val pipeline = StreamingPipeline.Builder(context)
            .threads(2)
            .build()
        val resolved = pipeline.reconcile(StreamConfig(numThreads = 6))
        assertEquals(6, resolved.numThreads)
    }

    @Test
    fun `reconcile cannot restore builder language because StreamConfig default is non-blank`() {
        val pipeline = StreamingPipeline.Builder(context)
            .language("de")
            .build()
        val resolved = pipeline.reconcile(StreamConfig())
        assertEquals(
            "KNOWN TRAP: StreamConfig.language defaults to \"en\", which is not blank, so " +
                "reconcile() keeps it and the Builder's language is lost. Use start(mode).",
            StreamConfig().language,
            resolved.language
        )
    }

    @Test
    fun `reconcile cannot restore builder model because StreamConfig default is non-blank`() {
        val pipeline = StreamingPipeline.Builder(context)
            .model("gpt-4o-transcribe")
            .build()
        val resolved = pipeline.reconcile(StreamConfig())
        assertEquals(
            "KNOWN TRAP: StreamConfig.model defaults to \"gpt-live-transcribe\", which is not blank, " +
                "so reconcile() keeps it and the Builder's model is lost. Use start(mode).",
            StreamConfig().model,
            resolved.model
        )
    }

    @Test
    fun `reconcile preserves all explicit values when none blank`() {
        val pipeline = StreamingPipeline.Builder(context)
            .whisperModelPath("/builder/model.bin")
            .apiKey("builder-key")
            .endpoint("wss://builder.example.com/ws")
            .model("builder-model")
            .language("es")
            .threads(3)
            .build()
        val explicit = StreamConfig(
            modelPath = "/explicit/model.bin",
            apiKey = "explicit-key",
            endpoint = "wss://explicit.example.com/ws",
            model = "explicit-model",
            language = "it",
            numThreads = 9
        )
        val resolved = pipeline.reconcile(explicit)
        assertEquals("/explicit/model.bin", resolved.modelPath)
        assertEquals("explicit-key", resolved.apiKey)
        assertEquals("wss://explicit.example.com/ws", resolved.endpoint)
        assertEquals("explicit-model", resolved.model)
        assertEquals("it", resolved.language)
        assertEquals(9, resolved.numThreads)
    }

    @Test
    fun `builder rejects untrusted gemini endpoint when allowCustomEndpoint is false`() {
        try {
            StreamingPipeline.Builder(context)
                .apiKey("gemini-test-key")
                .model("gemini-2.0-flash")
                .endpoint("https://untrusted-domain.com/v1beta/models/gemini:generateContent")
                .build()
            org.junit.Assert.fail("Expected IllegalArgumentException for untrusted gemini endpoint")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("allowlist") == true)
        }
    }

    @Test
    fun `builder allows untrusted gemini endpoint when allowCustomEndpoint is explicitly enabled`() {
        val pipeline = StreamingPipeline.Builder(context)
            .apiKey("gemini-test-key")
            .model("gemini-2.0-flash")
            .endpoint("https://custom-proxy.internal.net/v1beta/models/gemini:generateContent")
            .allowCustomEndpoint(true)
            .build()
        assertEquals("https://custom-proxy.internal.net/v1beta/models/gemini:generateContent", pipeline.config().endpoint)
    }

    @Test
    fun `builder allows allowlisted generativelanguage endpoint by default`() {
        val pipeline = StreamingPipeline.Builder(context)
            .apiKey("gemini-test-key")
            .model("gemini-2.0-flash")
            .endpoint("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent")
            .build()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent", pipeline.config().endpoint)
    }
}
