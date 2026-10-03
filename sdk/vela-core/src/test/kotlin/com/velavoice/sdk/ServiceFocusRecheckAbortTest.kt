package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Map #130 ticket #133: VAS/VIMS mid-session privacy recheck + abort.
 *
 * Mirrors the #78 keyboard snapshot pattern (capture verdict at start,
 * rechecks may only TIGHTEN — abort non-sensitive→sensitive, never widen):
 * - VAS rechecks on accessibility focus change, guarded with is-session-active
 *   FIRST (hot path no-op, accessibility events are high-volume).
 * - VIMS wires onStartInput/onUpdateSelection → recheck, onFinishInput → stop
 *   (same wiring LatinIME got in #78).
 * - Abort reuses #74 cancel semantics: cancelRecording() → AudioRecorder.cancel()
 *   (no transcription, no flush, no save) — never plain stop.
 *
 * Source-contract guards (services cannot be instantiated in unit tests):
 * RED while recheck/abort wiring is absent, GREEN once both services abort
 * in-flight sessions on flip-to-sensitive and discard audio (no save, no UI tail).
 */
class ServiceFocusRecheckAbortTest {

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

    // ── VAS: session-active guard FIRST ──

    @Test
    fun `VAS recheck guards with is-session-active FIRST`() {
        val (vas, _) = serviceSources()
        val code = codeOnly(vas)
        assertTrue("VAS must own isSessionActive()", code.contains("fun isSessionActive("))
        assertTrue("VAS must own recheckSessionPrivacy()", code.contains("fun recheckSessionPrivacy("))
        val body = codeOnly(extractFunBody(vas, "recheckSessionPrivacy"))
        assertTrue("VAS recheck must consult isSessionActive()", body.contains("isSessionActive()"))
        assertTrue("VAS recheck must consult PrivacyGuard.isSensitiveAccessibilityNode", body.contains("isSensitiveAccessibilityNode"))
        val guardIdx = body.indexOf("isSessionActive()")
        val verdictIdx = body.indexOf("isSensitiveAccessibilityNode")
        assertTrue(
            "VAS recheck must guard is-session-active FIRST (hot path no-op before focus lookup) — like keyboard isVelaSessionActive gate",
            guardIdx >= 0 && verdictIdx >= 0 && guardIdx < verdictIdx
        )
        val activeBody = codeOnly(extractFunBody(vas, "isSessionActive"))
        assertTrue("VAS isSessionActive must cover batch recording", activeBody.contains("isRecording"))
        assertTrue("VAS isSessionActive must cover streaming", activeBody.contains("isStreaming"))
    }

    @Test
    fun `VAS recheck tightens only - never widens`() {
        val (vas, _) = serviceSources()
        val body = codeOnly(extractFunBody(vas, "recheckSessionPrivacy"))
        assertTrue(
            "VAS recheck must early-return when the session already started sensitive (never widen, never abort)",
            body.contains("sessionPrivacySensitive") && body.contains("return")
        )
        assertNoMatch(
            Regex("refreshSessionPrivacySensitive\\s*\\("),
            body,
            "VAS recheck must not overwrite the start snapshot via refreshSessionPrivacySensitive (that would widen sensitive->non-sensitive)"
        )
    }

    @Test
    fun `VAS flip-to-sensitive aborts via cancel, discarding audio`() {
        val (vas, _) = serviceSources()
        val recheck = codeOnly(extractFunBody(vas, "recheckSessionPrivacy"))
        assertTrue(
            "VAS recheck must abort via cancel (cancelRecording/cancelStreaming), not plain stop",
            recheck.contains("cancelRecording(") || recheck.contains("cancelStreaming")
        )
        assertNoMatch(
            Regex("stopRecording\\s*\\("),
            recheck,
            "VAS abort must never plain-stop batch recording (that would transcribe the tail) — use cancel"
        )
        // Batch cancel discards captured PCM and never saves/inserts.
        assertTrue("VAS must own cancelRecording()", codeOnly(vas).contains("fun cancelRecording("))
        val cancel = codeOnly(extractFunBody(vas, "cancelRecording"))
        assertTrue(
            "VAS cancelRecording must discard captured audio",
            cancel.contains("recordedAudioData.reset()")
        )
        assertNoMatch(
            Regex("TranscriptionStorage\\.save"),
            cancel,
            "VAS cancelRecording must never persist (no save of the tail)"
        )
        assertNoMatch(
            Regex("insertText\\s*\\("),
            cancel,
            "VAS cancelRecording must never insert text (no UI tail)"
        )
    }

    @Test
    fun `VAS streaming abort discards without commit or save`() {
        val (vas, _) = serviceSources()
        val code = codeOnly(vas)
        assertTrue(
            "VAS must own a streaming cancel path (cancelStreamingTranscription)",
            code.contains("fun cancelStreamingTranscription(")
        )
        val cancel = codeOnly(extractFunBody(vas, "cancelStreamingTranscription"))
        assertTrue(
            "VAS streaming cancel must still drive the pipeline down",
            cancel.contains("streamingPipeline?.stop()") || cancel.contains("streamingPipeline?.release()")
        )
        assertNoMatch(
            Regex("insertStreamingText\\s*\\("),
            cancel,
            "VAS streaming cancel must never insert remaining text (no UI tail)"
        )
        assertNoMatch(
            Regex("TranscriptionStorage\\.save"),
            cancel,
            "VAS streaming cancel must never persist"
        )
    }

    @Test
    fun `VAS accessibility focus change triggers recheck with hot-path guard`() {
        val (vas, _) = serviceSources()
        val handler = codeOnly(extractFunBody(vas, "onAccessibilityEvent"))
        assertTrue(
            "VAS must recheck on accessibility focus change (TYPE_VIEW_FOCUSED)",
            handler.contains("TYPE_VIEW_FOCUSED") && handler.contains("recheckSessionPrivacy")
        )
        assertTrue(
            "VAS focus handler must consult isSessionActive (high-volume events stay no-op)",
            handler.contains("isSessionActive()")
        )
        val guardIdx = handler.indexOf("isSessionActive()")
        val recheckIdx = handler.indexOf("recheckSessionPrivacy")
        assertTrue(
            "VAS focus handler must check is-session-active BEFORE rechecking",
            guardIdx >= 0 && recheckIdx >= 0 && guardIdx < recheckIdx
        )
    }

    // ── VIMS: snapshot + recheck + wiring ──

    @Test
    fun `VIMS captures start verdict snapshot`() {
        val (_, vims) = serviceSources()
        val code = codeOnly(vims)
        assertTrue(
            "VIMS must snapshot the start verdict (sessionStartedPrivacySensitive)",
            code.contains("sessionStartedPrivacySensitive")
        )
        val startRec = codeOnly(extractFunBody(vims, "startRecording"))
        assertTrue(
            "VIMS startRecording must capture the snapshot",
            startRec.contains("sessionStartedPrivacySensitive")
        )
        val startStream = codeOnly(extractFunBody(vims, "startStreaming"))
        assertTrue(
            "VIMS startStreaming must capture the snapshot",
            startStream.contains("sessionStartedPrivacySensitive")
        )
    }

    @Test
    fun `VIMS recheck guards with is-session-active FIRST and fails closed`() {
        val (_, vims) = serviceSources()
        val code = codeOnly(vims)
        assertTrue("VIMS must own isSessionActive()", code.contains("fun isSessionActive("))
        assertTrue("VIMS must own recheckSessionPrivacy()", code.contains("fun recheckSessionPrivacy("))
        val body = codeOnly(extractFunBody(vims, "recheckSessionPrivacy"))
        assertTrue("VIMS recheck must consult isSessionActive()", body.contains("isSessionActive()"))
        assertTrue(
            "VIMS recheck must consult PrivacyGuard.isPrivacySensitiveEditor",
            body.contains("isPrivacySensitiveEditor")
        )
        val guardIdx = body.indexOf("isSessionActive()")
        val verdictIdx = body.indexOf("isPrivacySensitiveEditor")
        assertTrue(
            "VIMS recheck must guard is-session-active FIRST (hot path no-op)",
            guardIdx >= 0 && verdictIdx >= 0 && guardIdx < verdictIdx
        )
        assertTrue(
            "VIMS recheck must fail closed on unknown editor (null => sensitive => abort, like keyboard failClosedWhenUnknown=true)",
            body.contains(", true") || body.contains("failClosed")
        )
        assertTrue(
            "VIMS recheck must early-return when the session already started sensitive (never widen)",
            body.contains("sessionStartedPrivacySensitive") && body.contains("return")
        )
        val activeBody = codeOnly(extractFunBody(vims, "isSessionActive"))
        assertTrue("VIMS isSessionActive must cover batch recording", activeBody.contains("isRecording"))
        assertTrue("VIMS isSessionActive must cover streaming", activeBody.contains("isStreaming"))
    }

    @Test
    fun `VIMS abort reuses cancel semantics - never plain stop`() {
        val (_, vims) = serviceSources()
        val recheck = codeOnly(extractFunBody(vims, "recheckSessionPrivacy"))
        assertTrue(
            "VIMS recheck must abort via cancel (cancelRecording/cancelStreaming)",
            recheck.contains("cancelRecording(") || recheck.contains("cancelStreaming(")
        )
        assertNoMatch(
            Regex("stopRecording\\s*\\("),
            recheck,
            "VIMS abort must never plain-stop batch recording (that would transcribe the tail) — use cancelRecording -> AudioRecorder.cancel"
        )
        assertNoMatch(
            Regex("stopStreaming\\s*\\("),
            recheck,
            "VIMS abort must never plain-stop streaming (that would commit remaining) — use cancelStreaming"
        )
        val cancel = codeOnly(extractFunBody(vims, "cancelRecording"))
        assertTrue(
            "VIMS cancelRecording must delegate to transcriber.cancelRecording() (#74 cancel chain -> AudioRecorder.cancel)",
            cancel.contains("cancelRecording()")
        )
    }

    @Test
    fun `VIMS streaming cancel discards without commit or save`() {
        val (_, vims) = serviceSources()
        val code = codeOnly(vims)
        assertTrue(
            "VIMS must own cancelStreaming() (discard path distinct from committing stopStreaming)",
            code.contains("fun cancelStreaming(")
        )
        val cancel = codeOnly(extractFunBody(vims, "cancelStreaming"))
        assertNoMatch(
            Regex("commitText"),
            cancel,
            "VIMS cancelStreaming must never commitText (no UI tail)"
        )
        assertNoMatch(
            Regex("TranscriptionStorage\\.save"),
            cancel,
            "VIMS cancelStreaming must never persist"
        )
        assertNoMatch(
            Regex("finishComposingText"),
            cancel,
            "VIMS cancelStreaming must not finish composing (discard, like keyboard cancel)"
        )
    }

    @Test
    fun `VIMS focus wiring mirrors LatinIME - start-update recheck, finish stops`() {
        val (_, vims) = serviceSources()
        val code = codeOnly(vims)
        assertTrue("VIMS must override onStartInput", code.contains("override fun onStartInput("))
        assertTrue("VIMS must override onUpdateSelection", code.contains("override fun onUpdateSelection("))
        assertTrue("VIMS must override onFinishInput", code.contains("override fun onFinishInput("))
        val onStart = codeOnly(extractFunBody(vims, "onStartInput"))
        assertTrue(
            "VIMS onStartInput must recheck (same wiring LatinIME got in #78)",
            onStart.contains("recheckSessionPrivacy")
        )
        val onUpdate = codeOnly(extractFunBody(vims, "onUpdateSelection"))
        assertTrue(
            "VIMS onUpdateSelection must recheck (same wiring LatinIME got in #78)",
            onUpdate.contains("recheckSessionPrivacy")
        )
        val onFinish = codeOnly(extractFunBody(vims, "onFinishInput"))
        assertTrue(
            "VIMS onFinishInput must stop the session (same wiring LatinIME got in #78: onFinishInput* -> stop)",
            onFinish.contains("stopStreaming(") || onFinish.contains("cancelRecording(")
        )
    }

    // ── #133-fix: flip + lifecycle hardening ──

    @Test
    fun `VIMS trailing saves use volatile flip snapshot, not frozen local`() {
        val (_, vims) = serviceSources()
        val code = codeOnly(vims)
        assertTrue(
            "VIMS trailing save gates (onFinal/onResult) must read the volatile sessionStartedPrivacySensitive flip so flip-to-sensitive blocks save",
            code.contains("if (!sessionStartedPrivacySensitive)")
        )
        assertNoMatch(
            Regex("if\\s*\\(\\s*!sessionPrivacySensitive\\s*\\)"),
            code,
            "VIMS must not gate trailing saves on the frozen local sessionPrivacySensitive (shadows the volatile flip)"
        )
    }

    @Test
    fun `VAS streaming final post rechecks cancelled before insert`() {
        val (vas, _) = serviceSources()
        val code = codeOnly(vas)
        assertTrue(
            "VAS streaming onFinal Handler post must recheck !streamingCancelled before insert (cleanup race: cancel may land during background clean)",
            code.contains("!streamingCancelled && cleaned.isNotBlank()")
        )
    }

    @Test
    fun `VIMS finish-input keeps no-session no-op`() {
        val (_, vims) = serviceSources()
        val onFinish = codeOnly(extractFunBody(vims, "onFinishInput"))
        assertTrue(
            "VIMS onFinishInput else branch must stay no-op without a session: else if (isRecording.get()) cancelRecording()",
            onFinish.contains("else if (isRecording.get())") && onFinish.contains("cancelRecording(")
        )
        val onFinishView = codeOnly(extractFunBody(vims, "onFinishInputView"))
        assertTrue(
            "VIMS onFinishInputView else branch must stay no-op without a session: else if (isRecording.get()) cancelRecording()",
            onFinishView.contains("else if (isRecording.get())") && onFinishView.contains("cancelRecording(")
        )
    }

    @Test
    fun `VIMS cancelRecording early-returns when idle`() {
        val (_, vims) = serviceSources()
        val cancel = codeOnly(extractFunBody(vims, "cancelRecording"))
        assertTrue(
            "VIMS cancelRecording must guard with if (!isRecording.get()) return so no-session calls are no-ops",
            cancel.contains("!isRecording.get()") && cancel.contains("return")
        )
    }

    @Test
    fun `VAS cancelRecording bounds thread join to avoid ANR`() {
        val (vas, _) = serviceSources()
        val cancel = codeOnly(extractFunBody(vas, "cancelRecording"))
        assertTrue(
            "VAS cancelRecording must bound recordingThread join with a timeout (AudioRecorder.CANCEL_JOIN_TIMEOUT_MS or 2000ms) to avoid ANR",
            cancel.contains("CANCEL_JOIN_TIMEOUT_MS") || cancel.contains("join(2000")
        )
        assertNoMatch(
            Regex("\\.join\\s*\\(\\s*\\)"),
            cancel,
            "VAS cancelRecording must not join unbounded (ANR risk on the calling thread)"
        )
    }
}
