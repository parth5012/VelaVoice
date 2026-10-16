package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import java.io.File

/**
 * Map #105 ticket #109: harden accessibility text insertion.
 *
 * Source-contract guards (services cannot be instantiated in unit tests):
 * RED while the VAS insert paths pass transcribed text through unvalidated
 * and write to whatever node handle they hold, GREEN once:
 * - every VAS insert boundary sanitizes via [sanitizeForInsertion] (NUL /
 *   lone-surrogate defence in depth, cross-ref 4.5 upstream);
 * - every VAS write re-validates the target (refresh() freshness +
 *   session-start node-id comparison) and stale/disappeared nodes are a
 *   clean no-op;
 * - the action support is verified before acting, and ACTION_SET_TEXT is
 *   only ever dispatched with explicit splice-replace semantics through the
 *   shared [TextCommitter] owned span (there is no ACTION_INSERT_TEXT on
 *   [android.view.accessibility.AccessibilityNodeInfo] — SET_TEXT, verified
 *   above, is the only direct-write action; PASTE stays the cursor-insert
 *   path for non-sensitive batch text and never touches sensitive text);
 * - the VIMS single commit-text owner sanitizes too (trivial shared wiring).
 */
class A11yInsertionHardeningTest {

    private data class ServiceSources(val vas: String, val vims: String)

    private fun serviceSources(): ServiceSources {
        val root = findRepoRoot()
        val vas = File(root, "velavoice app/src/native/VoiceAccessibilityService.kt")
        val vims = File(root, "velavoice app/src/native/VoiceInputMethodService.kt")
        if (!vas.isFile) fail("VoiceAccessibilityService.kt not found under $root")
        if (!vims.isFile) fail("VoiceInputMethodService.kt not found under $root")
        return ServiceSources(vas.readText(), vims.readText())
    }

    private fun findRepoRoot(): File {
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

    // ── Sanitizer at every insert boundary ──

    @Test
    fun `VAS batch insert sanitizes before acting`() {
        val (vas, _) = serviceSources()
        val body = codeOnly(extractFunBody(vas, "insertText"))
        assertTrue(
            "VAS insertText must sanitize the transcript (sanitizeForInsertion) before any insert",
            body.contains("sanitizeForInsertion")
        )
    }

    @Test
    fun `VAS streaming inserts sanitize before composing`() {
        val (vas, _) = serviceSources()
        val partial = codeOnly(extractFunBody(vas, "insertStreamingPartial"))
        assertTrue(
            "VAS insertStreamingPartial must sanitize the marker text before composing",
            partial.contains("sanitizeForInsertion")
        )
        val final = codeOnly(extractFunBody(vas, "insertStreamingFinal"))
        assertTrue(
            "VAS insertStreamingFinal must sanitize the final text before composing",
            final.contains("sanitizeForInsertion")
        )
    }

    @Test
    fun `VIMS single committer sanitizes too`() {
        val (_, vims) = serviceSources()
        val owner = codeOnly(extractFunBody(vims, "insertTranscribedText"))
        assertTrue(
            "VIMS insertTranscribedText must sanitize (shared boundary, trivial wiring)",
            owner.contains("sanitizeForInsertion")
        )
    }

    // ── Target revalidation: stale/disappeared nodes are a clean no-op ──

    @Test
    fun `VAS captures the session target at start`() {
        val (vas, _) = serviceSources()
        val code = codeOnly(vas)
        assertTrue(
            "VAS must capture the session insertion target (captureSessionTarget / sessionTarget)",
            code.contains("captureSessionTarget(") || code.contains("sessionTarget")
        )
        val startRec = codeOnly(extractFunBody(vas, "startRecording"))
        assertTrue(
            "VAS startRecording must capture the session target",
            startRec.contains("captureSessionTarget(")
        )
        val startStream = codeOnly(extractFunBody(vas, "startStreamingTranscription"))
        assertTrue(
            "VAS startStreamingTranscription must capture the session target",
            startStream.contains("captureSessionTarget(")
        )
    }

    @Test
    fun `VAS revalidates the target immediately before every write`() {
        val (vas, _) = serviceSources()
        for (funName in listOf("insertText", "insertStreamingPartial", "insertStreamingFinal")) {
            val body = codeOnly(extractFunBody(vas, funName))
            assertTrue(
                "VAS $funName must re-resolve + freshness-check the target before acting " +
                    "(resolveFreshInsertionTarget with refresh())",
                body.contains("resolveFreshInsertionTarget(")
            )
        }
        val resolver = codeOnly(extractFunBody(vas, "resolveFreshInsertionTarget"))
        assertTrue(
            "VAS resolveFreshInsertionTarget must freshness-check via refresh() " +
                "(stale/disappeared nodes are a clean no-op)",
            resolver.contains(".refresh()")
        )
        assertTrue(
            "VAS resolveFreshInsertionTarget must compare against the session-start " +
                "node id (shouldInsertIntoTarget)",
            resolver.contains("shouldInsertIntoTarget")
        )
    }

    // ── Action support verified; SET_TEXT only with explicit replace semantics ──

    @Test
    fun `VAS verifies SET_TEXT support before splice writes`() {
        val (vas, _) = serviceSources()
        val code = codeOnly(vas)
        assertTrue(
            "VAS must verify ACTION_SET_TEXT support via the node's action list " +
                "before dispatching (no blind performAction)",
            code.contains("ACTION_SET_TEXT") && code.contains("actionList")
        )
    }

    @Test
    fun `VAS streaming paths stay clipboard-free`() {
        val (vas, _) = serviceSources()
        val partial = codeOnly(extractFunBody(vas, "insertStreamingPartial"))
        assertTrue(
            "VAS insertStreamingPartial must never touch the clipboard",
            !partial.contains("ClipboardManager") && !partial.contains("setPrimaryClip")
        )
        val final = codeOnly(extractFunBody(vas, "insertStreamingFinal"))
        assertTrue(
            "VAS insertStreamingFinal must never touch the clipboard",
            !final.contains("ClipboardManager") && !final.contains("setPrimaryClip")
        )
    }

    @Test
    fun `VAS SET_TEXT writes keep explicit splice-replace semantics`() {
        val (vas, _) = serviceSources()
        val partial = codeOnly(extractFunBody(vas, "insertStreamingPartial"))
        assertTrue(
            "VAS insertStreamingPartial SET_TEXT must stay committer-owned " +
                "(textCommitter.applyPartial splice, #107 intact)",
            partial.contains("textCommitter.applyPartial")
        )
        val final = codeOnly(extractFunBody(vas, "insertStreamingFinal"))
        assertTrue(
            "VAS insertStreamingFinal SET_TEXT must stay committer-owned " +
                "(textCommitter.commitFinal splice, #107 intact)",
            final.contains("textCommitter.commitFinal")
        )
    }
}
