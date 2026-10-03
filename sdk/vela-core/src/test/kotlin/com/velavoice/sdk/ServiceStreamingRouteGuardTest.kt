package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Map #130 ticket #132: VAS/VIMS streaming must go through the single gated
 * emit path ([StreamingPipeline.start] + `dispatchEmit`) instead of raw
 * `transcriber.emit` calls that bypass the #77 start-gate/refusal-once
 * contract — and must not duplicate the [StreamConfig.allowsCloudUpload]
 * check at direct emit sites (one choke point).
 *
 * These are source-contract guards over the two native services (which cannot
 * be instantiated in a unit test): RED while raw `emit(` calls are present in
 * either streaming path, GREEN once both services stream via
 * `StreamingPipeline` in local mode with a final [TextCleaner] pass (VAS)
 * composed through the [StreamingFieldComposer] baseline.
 */
class ServiceStreamingRouteGuardTest {

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

    /** Extract the first `override fun onFinal` block (streaming onFinal). */
    private fun extractFirstOnFinal(source: String): String {
        val idx = source.indexOf("override fun onFinal(")
        if (idx < 0) fail("override fun onFinal not found")
        val open = source.indexOf('{', idx)
        if (open < 0) fail("opening brace for onFinal not found")
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
        fail("unbalanced braces for onFinal")
        throw IllegalStateException("unreachable")
    }

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    @Test
    fun `VAS streaming path contains no raw transcriber emit`() {
        val (vas, _) = serviceSources()
        assertNoMatch(
            Regex("""\.emit\s*\("""),
            vas,
            "VAS must stream via StreamingPipeline.dispatchEmit, not raw transcriber.emit"
        )
    }

    @Test
    fun `VIMS streaming path contains no raw transcriber emit`() {
        val (_, vims) = serviceSources()
        assertNoMatch(
            Regex("""\.emit\s*\("""),
            vims,
            "VIMS must stream via StreamingPipeline.dispatchEmit, not raw transcriber.emit"
        )
    }

    @Test
    fun `neither service duplicates the cloud-upload check`() {
        val (vas, vims) = serviceSources()
        assertNoMatch(
            Regex("allowsCloudUpload"),
            vas,
            "VAS must not duplicate the upload check — StreamingPipeline is the single choke point"
        )
        assertNoMatch(
            Regex("allowsCloudUpload"),
            vims,
            "VIMS must not duplicate the upload check — StreamingPipeline is the single choke point"
        )
    }

    @Test
    fun `both services start streaming through StreamingPipeline in local mode`() {
        val (vas, vims) = serviceSources()
        for ((name, src) in listOf("VAS" to vas, "VIMS" to vims)) {
            assertTrue(
                "$name must build its streaming path on StreamingPipeline",
                src.contains("StreamingPipeline.Builder")
            )
            assertTrue(
                "$name must stay local-only (no cloud mode introduced)",
                Regex("""\.start\(\s*"local"""").containsMatchIn(src)
            )
        }
    }

    @Test
    fun `VAS streaming final passes through TextCleaner on the composer baseline`() {
        val (vas, _) = serviceSources()
        assertTrue(
            "VAS streaming onFinal must run the final TextCleaner pass",
            vas.contains("cleanStreamingFinalText")
        )
        assertTrue(
            "VAS final write must keep the StreamingFieldComposer baseline semantics",
            vas.contains("finalWriteText")
        )
    }

    @Test
    fun `VIMS streaming onFinal keeps the privacy-gated save`() {
        val (_, vims) = serviceSources()
        assertTrue(
            "VIMS must keep gating streaming saves on !sessionPrivacySensitive",
            vims.contains("if (!sessionPrivacySensitive)")
        )
    }

    // ── Map #132-fix: duplication + leak guards (red on buggy form) ──

    @Test
    fun `VAS streaming final uses composer baseline with SET_TEXT uniformly, never clipboard paste`() {
        val (vas, _) = serviceSources()
        val body = codeOnly(extractFunBody(vas, "insertStreamingText"))
        assertTrue(
            "VAS streaming final must compose via StreamingFieldComposer.finalWriteText (baseline+final)",
            body.contains("finalWriteText")
        )
        assertTrue(
            "VAS streaming final must write via ACTION_SET_TEXT uniformly",
            body.contains("ACTION_SET_TEXT")
        )
        assertNoMatch(
            Regex("ACTION_PASTE"),
            body,
            "VAS streaming insert must never ACTION_PASTE on top of streamed partials (baseline+final via SET_TEXT only)"
        )
        assertNoMatch(
            Regex("ClipboardManager|setPrimaryClip|ClipData"),
            body,
            "VAS streaming insert must never touch the clipboard (duplicates partials, leaks text)"
        )
    }

    @Test
    fun `VAS stop does not pre-insert final - single canonical final via pipeline onFinal`() {
        val (vas, _) = serviceSources()
        val body = codeOnly(extractFunBody(vas, "stopStreamingTranscription"))
        assertNoMatch(
            Regex("insertStreamingText"),
            body,
            "VAS stop must not insert remaining text — pipeline.stop() -> onFinal does the single canonical clean+write"
        )
        assertNoMatch(
            Regex("streamingBuffer|streamingCommittedLength"),
            body,
            "VAS stop must not touch the streaming buffer — onFinal owns the final write"
        )
        assertTrue(
            "VAS stop must still drive the pipeline to its canonical onFinal",
            body.contains("streamingPipeline?.stop()")
        )
    }

    @Test
    fun `VIMS streaming onFinal does not commit - single commit in stopStreaming`() {
        val (_, vims) = serviceSources()
        val onFinal = codeOnly(extractFirstOnFinal(vims))
        assertNoMatch(
            Regex("commitText"),
            onFinal,
            "VIMS onFinal must not commitText — stopStreaming already committed remaining (single-commit)"
        )
        assertNoMatch(
            Regex("finishComposingText|currentInputConnection"),
            onFinal,
            "VIMS onFinal must not touch the InputConnection — only status, gated save, showKeyboardView"
        )
        assertTrue(
            "VIMS onFinal must keep the privacy-gated save",
            onFinal.contains("if (!sessionPrivacySensitive)")
        )
        assertTrue(
            "VIMS onFinal must restore the keyboard view",
            onFinal.contains("showKeyboardView()")
        )
    }

    @Test
    fun `VIMS start failure releases pipeline - no leak`() {
        val (_, vims) = serviceSources()
        val body = extractFunBody(vims, "startStreaming")
        assertTrue(
            "VIMS startStreaming failure path must release the pipeline (no leak when start() throws)",
            body.contains("streamingPipeline?.release()")
        )
        assertTrue(
            "VIMS startStreaming failure path must nullify the pipeline",
            body.contains("streamingPipeline = null")
        )
    }

    @Test
    fun `VAS streaming final cleanup runs on single-thread executor, not raw Thread per final`() {
        val (vas, _) = serviceSources()
        assertTrue(
            "VAS must own a single-thread executor for streaming final cleanup (no thread churn)",
            vas.contains("newSingleThreadExecutor") || vas.contains("SingleThreadExecutor")
        )
        val cb = codeOnly(extractFunBody(vas, "createStreamingCallback"))
        assertNoMatch(
            Regex("""Thread\s*\("""),
            cb,
            "VAS onFinal must not spawn a raw Thread per final — use the single-thread executor"
        )
        assertTrue(
            "VAS onFinal must dispatch cleanup through the executor",
            cb.contains("streamingCleanupExecutor")
        )
    }
}
