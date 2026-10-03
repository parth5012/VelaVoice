package com.velavoice.sdk

import com.velavoice.sdk.cleaner.CleanerConfig
import com.velavoice.sdk.cleaner.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Map #130 ticket #131: VIMS/VAS must not construct/load the LLM cleaner for
 * privacy-sensitive sessions, mirroring
 * `KeyboardSwitcher.buildVelaTranscriber` (`useLlm && !privacySensitive`).
 *
 * Both branches collapse to rule-based output (the `clean(..., privacySensitive)`
 * second layer stays), so these tests assert the CONSTRUCTION flag — what
 * `useLlm` value reaches `Builder.useLlmCleaner` (VIMS) / `CleanerConfig`
 * (VAS) — not output text (cf. the #80 TextCleaner lesson).
 */
@RunWith(RobolectricTestRunner::class)
class LlmCleanerPrivacyGateTest {

    // ── Shared gate (mirrors keyboard `useLlm && !privacySensitive`) ──

    @Test
    fun `gate disables LLM cleaner for sensitive session even when pref on`() {
        assertFalse(
            PrivacyGuard.shouldEnableLlmCleaner(useLlmPref = true, privacySensitive = true)
        )
    }

    @Test
    fun `gate keeps LLM cleaner for non-sensitive session when pref on`() {
        assertTrue(
            PrivacyGuard.shouldEnableLlmCleaner(useLlmPref = true, privacySensitive = false)
        )
    }

    @Test
    fun `gate disables LLM cleaner when pref off regardless of sensitivity`() {
        assertFalse(PrivacyGuard.shouldEnableLlmCleaner(false, false))
        assertFalse(PrivacyGuard.shouldEnableLlmCleaner(false, true))
    }

    // ── VIMS shape: Builder.useLlmCleaner gated on pref + model + !sensitive ──

    /** Mirrors VIMS `startRecording`: `if (gate && cachedLlmPath != null) builder.useLlmCleaner(...)`. */
    private fun vimsShouldBuildLlm(useLlmPref: Boolean, llmPath: String?, sensitive: Boolean): Boolean =
        PrivacyGuard.shouldEnableLlmCleaner(useLlmPref, sensitive) && llmPath != null

    @Test
    fun `VIMS sensitive session never enables Builder LLM cleaner even with model present`() {
        assertFalse(vimsShouldBuildLlm(true, "/models/cleaner.onnx", true))
    }

    @Test
    fun `VIMS non-sensitive session enables Builder LLM cleaner when pref on and model present`() {
        assertTrue(vimsShouldBuildLlm(true, "/models/cleaner.onnx", false))
    }

    @Test
    fun `VIMS non-sensitive session without model still builds no LLM cleaner`() {
        assertFalse(vimsShouldBuildLlm(true, null, false))
    }

    // ── VAS shape: CleanerConfig.useLlm gated on pref + !sensitive ──

    /** Construction spy: records the CleanerConfig that VAS would hand to TextCleaner. */
    private fun vasBuildConfig(
        useLlmPref: Boolean,
        sensitive: Boolean,
        factory: (CleanerConfig) -> TextCleaner = ::TextCleaner
    ): Pair<CleanerConfig, TextCleaner> {
        val config = CleanerConfig(
            useLlm = PrivacyGuard.shouldEnableLlmCleaner(useLlmPref, sensitive),
            llmModelPath = "/models/cleaner.onnx"
        )
        return config to factory(config)
    }

    @Test
    fun `VAS sensitive session constructs TextCleaner with useLlm false`() {
        val seen = mutableListOf<CleanerConfig>()
        vasBuildConfig(true, true) { config ->
            seen.add(config)
            TextCleaner(config)
        }
        assertEquals(1, seen.size)
        assertFalse(
            "sensitive VAS session must never construct an LLM-backed cleaner",
            seen.single().useLlm
        )
    }

    @Test
    fun `VAS non-sensitive session constructs TextCleaner with useLlm true when pref on`() {
        val seen = mutableListOf<CleanerConfig>()
        vasBuildConfig(true, false) { config ->
            seen.add(config)
            TextCleaner(config)
        }
        assertEquals(1, seen.size)
        assertTrue(
            "non-sensitive VAS session must keep the LLM cleaner when pref is on",
            seen.single().useLlm
        )
    }
}
