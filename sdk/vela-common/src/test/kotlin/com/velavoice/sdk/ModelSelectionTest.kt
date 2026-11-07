package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ticket 806 (map #290): registry-driven selected-model resolution.
 *
 * Fallback order defined by the ticket: selected id → any completed model of
 * the same engine → null (caller falls back to cloud voice input). The
 * resolver is pure so both velavoice app and velaboard keyboard share the
 * exact same contract.
 */
@RunWith(RobolectricTestRunner::class)
class ModelSelectionTest {

    private fun candidate(id: String, engine: EngineKind, path: String = "/models/$id") =
        ModelSelection.CompletedModel(id = id, engine = engine, path = path)

    @Test
    fun `selected id wins when completed`() {
        val done = listOf(
            candidate("whisper-tiny-en", EngineKind.WHISPER),
            candidate("moonshine-tiny", EngineKind.SHERPA)
        )
        val sel = ModelSelection.resolve("moonshine-tiny", done)
        assertEquals("moonshine-tiny", sel?.modelId)
        assertEquals(EngineKind.SHERPA, sel?.engine)
    }

    @Test
    fun `selected id that is not completed falls back to same-engine completed`() {
        val done = listOf(
            candidate("whisper-tiny-en", EngineKind.WHISPER),
            candidate("moonshine-tiny", EngineKind.SHERPA)
        )
        val sel = ModelSelection.resolve("parakeet-tdt-v3", done) // sherpa not downloaded
        assertEquals("moonshine-tiny", sel?.modelId)
        assertEquals(EngineKind.SHERPA, sel?.engine)
    }

    @Test
    fun `no selection resolves the first registry-ordered completed model`() {
        val done = listOf(
            candidate("moonshine-tiny", EngineKind.SHERPA),
            candidate("fluid-speech-mini", EngineKind.SHERPA)
        )
        val sel = ModelSelection.resolve(null, done)
        // Registry order (fluid-speech-mini is registered before moonshine-tiny)
        // wins over candidate input order for determinism.
        assertEquals("fluid-speech-mini", sel?.modelId)
    }

    @Test
    fun `no selection and no completed model resolves to null (cloud fallback)`() {
        assertNull(ModelSelection.resolve(null, emptyList()))
        assertNull(ModelSelection.resolve("whisper-tiny-en", emptyList()))
    }

    @Test
    fun `unregistered selected id falls back, never matches arbitrary rows by coincidence`() {
        val done = listOf(
            candidate("whisper-tiny-en", EngineKind.WHISPER),
            candidate("not-in-registry", EngineKind.WHISPER)
        )
        val sel = ModelSelection.resolve("not-in-registry", done)
        assertEquals(
            "selection must be a registry-recognized id: unregistered ids must fall back",
            "whisper-tiny-en",
            sel?.modelId
        )
    }

    @Test
    fun `single-model invariant - never yields two selections`() {
        val done = listOf(
            candidate("whisper-tiny-en", EngineKind.WHISPER),
            candidate("moonshine-tiny", EngineKind.SHERPA)
        )
        val sel = ModelSelection.resolve(null, done)
        // A second resolve with the same inputs yields the identical single
        // selection — the resolver cannot "hold" two engines.
        val again = ModelSelection.resolve(null, done)
        assertEquals(sel?.modelId, again?.modelId)
        assertEquals(sel?.engine, again?.engine)
    }
}
