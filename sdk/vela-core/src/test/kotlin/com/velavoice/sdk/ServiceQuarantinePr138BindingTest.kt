package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * PR #138 review follow-ups (map #130 PR2 quarantine paths):
 * - Fix 3 (r4175362799): streaming onFinal saves must pass the CAPTURED
 *   verdict (sensitiveAtFinal), not the live service flag. Both services
 *   already gate on sensitiveAtFinal — only the save() argument was live.
 * - Fix 4 (Major, r4177382324): batch recording callbacks lack a generation
 *   check. AudioRecorder.stop() delivers onResult asynchronously; a later
 *   session can reset the privacy flag before the old callback runs, so a
 *   sensitive transcript passes the live-flag gate into shared storage.
 *   Mirror the streaming generation machinery for the batch path.
 *
 * Source-contract guards (same style as ServiceStreamingSessionBindingTest)
 * over the two native services, which cannot be instantiated in unit tests.
 */
class ServiceQuarantinePr138BindingTest {

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

    private fun extractOnFinal(source: String): String = extractFunBody(source, "onFinal")

    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    // ══════════════════════════════════════════════════════════════════════
    // Fix 3 — streaming onFinal save() must pass the captured verdict
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS streaming onFinal save passes the captured verdict`() {
        val (_, vims) = serviceSources()
        val onFinal = codeOnly(extractOnFinal(vims))
        assertTrue(
            "VIMS streaming onFinal save must pass the captured verdict: privacySensitive = sensitiveAtFinal",
            onFinal.contains("privacySensitive = sensitiveAtFinal")
        )
        assertTrue(
            "VIMS streaming onFinal save must not pass the live flag (a newer session resets it)",
            !onFinal.contains("privacySensitive = sessionStartedPrivacySensitive")
        )
    }

    @Test
    fun `VAS streaming onFinal save passes the captured verdict`() {
        val (vas, _) = serviceSources()
        val onFinal = codeOnly(extractOnFinal(vas))
        assertTrue(
            "VAS streaming onFinal save must pass the captured verdict: privacySensitive = sensitiveAtFinal",
            onFinal.contains("privacySensitive = sensitiveAtFinal")
        )
        assertTrue(
            "VAS streaming onFinal save must not pass the live flag (a newer session resets it)",
            !onFinal.contains("privacySensitive = sessionPrivacySensitive")
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Fix 4 — batch recording callbacks are generation-bound
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS batch recording captures the session generation at start`() {
        val (_, vims) = serviceSources()
        val start = codeOnly(extractFunBody(vims, "startRecording"))
        assertTrue(
            "VIMS startRecording must claim a generation for the batch session (advance on recording start)",
            start.contains("++streamingSessionGeneration")
        )
        assertTrue(
            "VIMS startRecording must capture it for the batch callback (e.g. val recordingGeneration = ...)",
            start.contains("recordingGeneration")
        )
    }

    @Test
    fun `VIMS batch onResult rejects a stale generation before save and commit`() {
        val (_, vims) = serviceSources()
        val start = codeOnly(extractFunBody(vims, "startRecording"))
        // onResult lives inside startRecording's transcriber callback.
        val resultIdx = start.indexOf("override fun onResult")
        assertTrue("VIMS batch onResult must still exist", resultIdx >= 0)
        val tail = start.substring(resultIdx)
        val guardIdx = tail.indexOf("if (recordingGeneration != streamingSessionGeneration) return")
        assertTrue(
            "VIMS batch onResult must reject a stale generation (old async result under a newer session)",
            guardIdx >= 0
        )
        val saveIdx = tail.indexOf("TranscriptionStorage.save")
        assertTrue("VIMS batch onResult must still own its gated save", saveIdx >= 0)
        assertTrue(
            "VIMS batch generation guard must run BEFORE the save (never persist a dead session's transcript)",
            guardIdx < saveIdx
        )
        val commitIdx = tail.indexOf("commitText")
        assertTrue("VIMS batch onResult must still own its commit", commitIdx >= 0)
        assertTrue(
            "VIMS batch generation guard must run BEFORE the commit (never commit a dead session's text)",
            guardIdx < commitIdx
        )
        // Cancel semantics preserved: cancel emits no callback, never plain stop.
        val cancel = codeOnly(extractFunBody(vims, "cancelRecording"))
        assertTrue(
            "VIMS cancelRecording must keep cancel semantics (transcriber?.cancelRecording, never stopRecording)",
            cancel.contains("transcriber?.cancelRecording()")
        )
    }

    @Test
    fun `VAS batch recording is generation-bound like streaming`() {
        val (vas, _) = serviceSources()
        val start = codeOnly(extractFunBody(vas, "startRecording"))
        assertTrue(
            "VAS startRecording (batch) must advance the session id so a later session invalidates pending batch work",
            start.contains("streamingSessionId++")
        )
        val stop = codeOnly(extractFunBody(vas, "stopRecording"))
        assertTrue(
            "VAS stopRecording must capture the session id for its async transcription (e.g. val sessionIdAtStop = streamingSessionId)",
            stop.contains("sessionIdAtStop") && stop.contains("streamingSessionId")
        )
        assertTrue(
            "VAS batch transcription must reject a stale session before touching shared storage",
            stop.contains("sessionIdAtStop != streamingSessionId")
        )
        val saveIdx = stop.indexOf("TranscriptionStorage.save")
        assertTrue("VAS batch path must still own its gated save", saveIdx >= 0)
        assertTrue(
            "VAS batch stale-session guard must run BEFORE the save",
            stop.indexOf("sessionIdAtStop != streamingSessionId") < saveIdx
        )
    }
}
