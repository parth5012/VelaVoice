package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun `find returns definition for known id`() {
        val def = ModelRegistry.find("whisper-tiny-en")
        assertNotNull(def)
        assertEquals("whisper-tiny-en", def!!.id)
        assertEquals(EngineKind.WHISPER, def.engine)
    }

    @Test
    fun `find returns null for unknown id`() {
        assertNull(ModelRegistry.find("does-not-exist"))
    }

    @Test
    fun `byEngine filters by engine kind`() {
        val whisper = ModelRegistry.byEngine(EngineKind.WHISPER)
        val sherpa = ModelRegistry.byEngine(EngineKind.SHERPA)
        assertTrue(whisper.isNotEmpty())
        assertTrue(sherpa.isNotEmpty())
        assertTrue(whisper.all { it.engine == EngineKind.WHISPER })
        assertTrue(sherpa.all { it.engine == EngineKind.SHERPA })
    }

    @Test
    fun `whisper-tiny-en has pinned hash and streaming false`() {
        val def = ModelRegistry.find("whisper-tiny-en")!!
        assertEquals("921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f", def.expectedSha256)
        assertFalse(def.supportsStreaming)
    }

    @Test
    fun `registry contains first-batch whisper rows`() {
        val ids = ModelRegistry.ALL.map { it.id }
        assertTrue(ids.contains("whisper-tiny-en"))
        assertTrue(ids.contains("whisper-tiny-en-q5_0"))
        assertTrue(ids.contains("whisper-base-en"))
        assertTrue(ids.contains("whisper-small-en"))
    }

    @Test
    fun `registry contains sherpa rows`() {
        val ids = ModelRegistry.ALL.map { it.id }
        assertTrue(ids.contains("fluid-speech-mini"))
        assertTrue(ids.contains("parakeet-tdt-v3"))
        assertTrue(ids.contains("moonshine-tiny"))
    }

    @Test
    fun `whisperModelIds lists every whisper row in registry order`() {
        // CodeRabbit round-1 finding (806 follow-up): native services must not
        // hard-code 'whisper-tiny-en' — they query completed rows by candidate
        // ids resolved from this helper, so every whisper variant a user
        // downloads stays discoverable.
        val ids = ModelRegistry.whisperModelIds()
        assertTrue(ids.containsAll(listOf("whisper-tiny-en", "whisper-tiny-en-q5_0", "whisper-base-en", "whisper-small-en")))
        assertTrue(ids.none { it.startsWith("fluid") || it.startsWith("parakeet") || it.startsWith("moonshine") })
        // Registry order preserved: tiny first, small later.
        assertTrue(ids.indexOf("whisper-tiny-en") < ids.indexOf("whisper-small-en"))
    }

    @Test
    fun `streamingCapable filters by flag`() {
        // Ticket 803 Phase B: exactly one streaming row so far — the streamed
        // Nemotron-3.5-ASR-Streaming-0.6b transducer (supportsStreaming=true).
        val ids = ModelRegistry.streamingCapable().map { it.id }
        assertEquals(listOf("nemotron-streaming-en"), ids)
    }

    @Test
    fun `catalog contains all four newly requested models`() {
        val ids = ModelRegistry.ALL.map { it.id }
        assertTrue(ids.containsAll(listOf("whisper-turbo-q5_0", "parakeet-redux", "phonon-2", "whistle")))
    }

    @Test
    fun `whisper turbo q5_0 is pinned and has a per-model size cap above its artifact`() {
        val def = ModelRegistry.find("whisper-turbo-q5_0")!!
        assertEquals(EngineKind.WHISPER, def.engine)
        assertEquals("ggml-large-v3-turbo-q5_0.bin", def.filename)
        assertEquals("394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", def.expectedSha256)
        assertTrue(def.url.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"))
        assertEquals(600L * 1024 * 1024, def.maxDownloadBytes)
        // The pinned q5_0 artifact is 574,041,195 bytes — larger than the global
        // cap, so the per-model override must clear both bars.
        assertTrue(def.effectiveMaxBytes() > 574_041_195L)
        assertTrue(def.effectiveMaxBytes() > ModelIntegrity.MAX_MODEL_BYTES)
    }

    @Test
    fun `rows without an override keep the global size cap`() {
        assertEquals(ModelIntegrity.MAX_MODEL_BYTES, ModelRegistry.find("whisper-tiny-en")!!.effectiveMaxBytes())
        assertEquals(ModelIntegrity.MAX_MODEL_BYTES, ModelRegistry.find("parakeet-redux")!!.effectiveMaxBytes())
    }

    @Test
    fun `parakeet redux carries four pinned sherpa bundle parts within the global cap`() {
        val def = ModelRegistry.find("parakeet-redux")!!
        assertEquals(EngineKind.SHERPA, def.engine)
        // A bundle has no single-file URL — the UI must keep it non-selectable
        // until bundle download wiring lands (catalog-only pass).
        assertEquals("", def.url)
        assertEquals(4, def.bundleParts.size)
        assertEquals(
            setOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"),
            def.bundleParts.map { it.name }.toSet()
        )
        assertTrue(def.bundleParts.all {
            it.url.startsWith("https://huggingface.co/cohearo/sherpa-onnx-nemo-parakeet-redux/resolve/main/")
        })
        assertTrue(def.bundleParts.all { it.expectedSha256.length == 64 })
        assertEquals(416_471_283L, def.bundleParts.map { it.sizeBytes }.sum())
        assertTrue(def.bundleParts.map { it.sizeBytes }.sum() < ModelIntegrity.MAX_MODEL_BYTES)
    }

    @Test
    fun `no-runtime catalog rows carry their source and runtime note`() {
        val phonon = ModelRegistry.find("phonon-2")!!
        val whistle = ModelRegistry.find("whistle")!!
        assertEquals("", phonon.url)
        assertEquals("", whistle.url)
        assertTrue(phonon.catalogNote!!.contains("FermionResearch/Phonon-2"))
        assertTrue(whistle.catalogNote!!.contains("Cactus-Compute/whistle"))
        assertTrue(phonon.bundleParts.isEmpty())
        assertTrue(whistle.bundleParts.isEmpty())
    }
}