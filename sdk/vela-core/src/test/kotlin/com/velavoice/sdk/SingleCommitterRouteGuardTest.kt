package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import java.io.File

/**
 * Map #105 ticket #107: a single commit-text owner (no double insertion).
 *
 * Partial results and finals used to call `commitText`/`setComposingText` with
 * no shared deduplication, so a final repeating text already inserted as the
 * last partial duplicated it in the field. The contract pinned here:
 * - VIMS owns exactly one `commitText` call site, inside
 *   `insertTranscribedText()`, gated by the shared #106 blank no-op gate;
 *   streaming partials/finals and the batch path all route through it, and
 *   streaming revisions flow through the shared owned-range committer (#108
 *   APIs) so finals replace the trailing partial instead of appending it.
 * - VAS routes its streaming inserts through the same committer
 *   ([TextCommitter.applyPartial]/[TextCommitter.commitFinal]) when a field
 *   is focused, and its cancel path discards the owned range without touching
 *   the field.
 * - The keyboard streaming session ([VelaStreamingSession]) dedups markers by
 *   revision id (#108) and funnels its editor commits through one helper.
 */
class SingleCommitterRouteGuardTest {

    private data class ServiceSources(val vas: String, val vims: String, val session: String)

    private fun serviceSources(): ServiceSources {
        val root = findRepoRoot()
        val vas = File(root, "velavoice app/src/native/VoiceAccessibilityService.kt")
        val vims = File(root, "velavoice app/src/native/VoiceInputMethodService.kt")
        val session = File(
            root,
            "velaboard/app/src/main/java/helium314/keyboard/keyboard/VelaStreamingSession.java"
        )
        if (!vas.isFile) fail("VoiceAccessibilityService.kt not found under $root")
        if (!vims.isFile) fail("VoiceInputMethodService.kt not found under $root")
        if (!session.isFile) fail("VelaStreamingSession.java not found under $root")
        return ServiceSources(vas.readText(), vims.readText(), session.readText())
    }

    private fun findRepoRoot(): File {
        var dir: File = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "velavoice app").isDirectory && File(dir, "sdk/vela-core").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return dir
        }
        fail("repo root (containing 'velavoice app' + 'sdk/vela-core') not found above user.dir")
        throw IllegalStateException("unreachable")
    }

    private fun assertNoMatch(pattern: Regex, source: String, message: String) {
        val hit = pattern.find(source)
        assertTrue("$message — found: '${hit?.value}'", hit == null)
    }

    /** Extract the body of `fun <name>(` with balanced braces (first match). */
    private fun extractFunBody(source: String, funName: String): String {
        val idx = source.indexOf("fun $funName(")
        if (idx < 0) fail("fun $funName( not found")
        val open = source.indexOf('{', idx)
        if (open < 0) fail("opening brace for $funName not found")
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        fail("unbalanced braces for $funName")
        throw IllegalStateException("unreachable")
    }

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    // ── VIMS: one commit-text owner ──

    @Test
    fun `VIMS owns exactly one commitText site inside insertTranscribedText`() {
        val (_, vims, _) = serviceSources()
        val code = codeOnly(vims)
        val hits = Regex("""commitText\s*\(""").findAll(code).toList()
        assertEquals(
            "VIMS must own exactly one commitText call (inside insertTranscribedText); " +
                "stop/final/batch paths must route through it, found ${hits.size}",
            1,
            hits.size
        )
        val owner = codeOnly(extractFunBody(vims, "insertTranscribedText"))
        assertTrue(
            "the single commitText call must live in insertTranscribedText",
            owner.contains("commitText(")
        )
    }

    @Test
    fun `VIMS single committer keeps the shared blank no-op gate`() {
        val (_, vims, _) = serviceSources()
        val owner = codeOnly(extractFunBody(vims, "insertTranscribedText"))
        assertTrue(
            "insertTranscribedText must keep the #106 gate (shouldCommitTranscript) so " +
                "blank transcripts stay a no-op",
            owner.contains("shouldCommitTranscript")
        )
    }

    @Test
    fun `VIMS streaming partials route through the shared committer`() {
        val (_, vims, _) = serviceSources()
        val code = codeOnly(vims)
        assertTrue(
            "VIMS must hold the shared TextCommitter (same owner as the a11y path)",
            code.contains("TextCommitter")
        )
        assertTrue(
            "VIMS streaming revisions must flow through the owned-range replace " +
                "(finals replace the trailing partial, never append it)",
            code.contains("textCommitter.applyPartial")
        )
    }

    // ── VAS: same committer when a field is focused ──

    @Test
    fun `VAS routes streaming inserts through the same committer`() {
        val (vas, _, _) = serviceSources()
        val code = codeOnly(vas)
        assertTrue(
            "VAS must hold the shared TextCommitter (same owner as the IME path)",
            code.contains("TextCommitter")
        )
        assertTrue(
            "VAS partials must replace via the owned range, never append",
            code.contains("textCommitter.applyPartial")
        )
        assertTrue(
            "VAS finals must replace the trailing partial via the owned range",
            code.contains("textCommitter.commitFinal")
        )
    }

    @Test
    fun `VAS cancel discards the owned range without touching the field`() {
        val (vas, _, _) = serviceSources()
        val cancel = codeOnly(extractFunBody(vas, "cancelStreamingTranscription"))
        assertTrue(
            "VAS streaming cancel must forget the owned span (late markers stay stale)",
            cancel.contains("discardOwnedRange")
        )
        assertNoMatch(
            Regex("insertStreamingPartial\\s*\\(|insertStreamingFinal\\s*\\("),
            cancel,
            "VAS streaming cancel must never insert text (no UI tail)"
        )
    }

    // ── Keyboard session: revision dedup + one commit helper ──

    @Test
    fun `keyboard streaming session dedups by revision and funnels commits`() {
        val (_, _, session) = serviceSources()
        val code = codeOnly(session)
        assertTrue(
            "VelaStreamingSession must ignore stale/duplicate markers by revision id (#108)",
            code.contains("getRevisionId")
        )
        val hits = Regex("""\.commitText\s*\(""").findAll(code).toList()
        assertEquals(
            "VelaStreamingSession must own exactly one editor commit site (single helper), " +
                "found ${hits.size}",
            1,
            hits.size
        )
        assertTrue(
            "the single editor commit must go through the shared helper",
            code.contains("commitCleanedText")
        )
    }
}
