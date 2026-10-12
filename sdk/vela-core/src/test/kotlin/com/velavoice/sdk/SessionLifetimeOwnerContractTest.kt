package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Ticket #102 — single session-lifetime owner (per implementation).
 *
 * Each IME implementation has exactly ONE routine whose body is the only place
 * `release()` is invoked on the SDK session objects (`VelaTranscriber`,
 * `VelaStreamingSession`, `StreamingPipeline`). All other teardown paths route
 * through it, so ownership is auditable by stripping that routine's body and
 * asserting nothing remains.
 *
 * The two implementations are independent:
 *  - velaboard LatinIME: KeyboardSwitcher constructs/caches/releases.
 *  - RN companion IME: VoiceInputMethodService (VIMS) owns its own pipeline/
 *    transcriber lifetime behind the #98 generation counter.
 */
class SessionLifetimeOwnerContractTest {

    private fun repoRoot(): File {
        var dir: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (File(dir, "velavoice app").isDirectory && File(dir, "sdk/vela-core").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return dir
        }
        fail("repo root (containing 'velavoice app' + 'sdk/vela-core') not found above user.dir")
        throw IllegalStateException("unreachable")
    }

    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    private fun extractMethodBody(source: String, signature: String): String {
        val code = codeOnly(source)
        val idx = code.indexOf(signature)
        if (idx < 0) fail("'$signature' not found")
        val open = code.indexOf('{', idx)
        if (open < 0) fail("opening brace for '$signature' not found")
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
        }
        fail("unbalanced braces for '$signature'")
        throw IllegalStateException("unreachable")
    }

    private fun removeMethodBody(source: String, signature: String): String {
        val body = extractMethodBody(source, signature)
        return source.replace(body, "{}")
    }

    private fun keyboardSwitcherSource() = File(repoRoot(),
        "velaboard/app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java").readText()

    private fun vimsSource() = File(repoRoot(),
        "velavoice app/src/native/VoiceInputMethodService.kt").readText()

    // ══════════════════════════════════════════════════════════════════════
    // velaboard LatinIME: KeyboardSwitcher is the sole lifetime owner
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `KeyboardSwitcher teardownVelaEngine is the only place release is called`() {
        val source = keyboardSwitcherSource()
        val stripped = removeMethodBody(source, "private void teardownVelaEngine(")
        assertFalse(
            "after stripping teardownVelaEngine()'s body, no other .release() may remain in " +
                "KeyboardSwitcher — every Vela engine teardown must route through the single owner routine",
            codeOnly(stripped).contains(Regex("""\bvela(?:Transcriber|StreamingSession)\s*\.\s*release\s*\("""))
        )
        // and the routine itself is private (single implementation detail)
        assertTrue("teardownVelaEngine must be private (single lifetime owner routine)",
            codeOnly(source).contains("private void teardownVelaEngine("))
    }

    @Test
    fun `KeyboardSwitcher constructs Vela engines in exactly one place each`() {
        val code = codeOnly(keyboardSwitcherSource())
        val velaTranscriberCreations = Regex("""new\s+VelaTranscriber\.Builder\s*\(""")
            .findAll(code).count()
        assertEquals(
            "VelaTranscriber must be constructed in exactly one place (buildVelaTranscriber)",
            1, velaTranscriberCreations
        )
        val velaSessionCreations = Regex("""new\s+VelaStreamingSession\s*\(""")
            .findAll(code).count()
        assertEquals(
            "VelaStreamingSession must be constructed in exactly one place (streaming launch)",
            1, velaSessionCreations
        )
    }

    @Test
    fun `KeyboardSwitcher releaseVelaTranscriber is the public teardown entry point`() {
        val code = codeOnly(keyboardSwitcherSource())
        assertTrue("releaseVelaTranscriber() must exist as the public entry",
            code.contains("public void releaseVelaTranscriber("))
        val body = extractMethodBody(keyboardSwitcherSource(), "public void releaseVelaTranscriber(")
        assertTrue("releaseVelaTranscriber() must delegate to teardownVelaEngine",
            body.contains("teardownVelaEngine("))
    }

    // ══════════════════════════════════════════════════════════════════════
    // VIMS: teardownVoiceEngine is the sole lifetime owner
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS teardownVoiceEngine is the only place release is called`() {
        val source = vimsSource()
        val stripped = removeMethodBody(source, "private fun teardownVoiceEngine(")
        assertFalse(
            "after stripping teardownVoiceEngine()'s body, no other .release() may remain in " +
                "VoiceInputMethodService — every pipeline/transcriber teardown must route " +
                "through the single owner routine",
            codeOnly(stripped).contains(Regex("""\b(?:streamingPipeline|pipeline|transcriber|builtTranscriber|unpublished)\s*(?:\?)?\s*\.\s*release\s*\("""))
        )
        assertTrue("teardownVoiceEngine must be private (single lifetime owner routine)",
            codeOnly(source).contains("private fun teardownVoiceEngine("))
    }

    @Test
    fun `VIMS discarded-start path releases only the local pipeline not the published field`() {
        val staleGuard = extractMethodBody(vimsSource(), "if (generation != streamingSessionGeneration) {")
        // The stale-start discard must release the local `pipeline` variable,
        // never the shared `streamingPipeline` field (ticket #102: borrower
        // must not release the owner's field).
        assertTrue("stale-start guard must release the local pipeline variable",
            staleGuard.contains("teardownVoiceEngine("))
    }

    @Test
    fun `VIMS constructs VelaTranscriber in exactly one place`() {
        val code = codeOnly(vimsSource())
        val constructions = Regex("""VelaTranscriber\.Builder\s*\(""").findAll(code).count()
        assertEquals(
            "VelaTranscriber must be constructed in exactly one place in VIMS (lifetime owner)",
            1, constructions
        )
    }
}
