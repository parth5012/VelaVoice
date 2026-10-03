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
}
