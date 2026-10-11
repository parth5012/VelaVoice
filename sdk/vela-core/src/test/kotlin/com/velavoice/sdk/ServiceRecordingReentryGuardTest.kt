package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Wayfinder map #97 ticket #98: the IME native services cannot be instantiated
 * in a unit test, so this is a source-contract guard (same pattern as
 * ServiceStreamingSessionBindingTest) over the recording re-entry gate:
 *
 * 1. VIMS (`VoiceInputMethodService`): `RecordingSessionGate` (IDLE /
 *    RECORDING / PROCESSING) is the ONLY gate on the batch recording path —
 *    startRecording is a no-op unless IDLE, stopRecording/cancelRecording are
 *    gated and idempotent, the async transcriber build is discarded when the
 *    session was canceled/stopped/replaced meanwhile, and callbacks settle the
 *    gate. Before this ticket startRecording had no guard at all: a double-tap
 *    or an onStartInputView re-entry queued a second bg build that started a
 *    second VelaTranscriber/AudioRecord over the live one.
 * 2. VAS (`VoiceAccessibilityService`): its own isRecording guards on
 *    startRecording/stopRecording/cancelRecording are regression-locked (they
 *    already existed; the audit's RECORD_AUDIO_GRANTED pane claim was stale —
 *    that path lives in velaboard LatinIME, not VAS).
 */
class ServiceRecordingReentryGuardTest {

    private fun serviceSource(name: String): String {
        var dir: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "velavoice app/src/native/$name")
            if (file.isFile) return file.readText()
            dir = dir.parentFile ?: dir
        }
        fail("$name not found above user.dir")
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

    /** Extract the body of the LAST `fun <name>(` with balanced braces. */
    private fun extractLastFunBody(source: String, funName: String): String {
        val idx = source.lastIndexOf("fun $funName(")
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

    // ════════════════════════════════════════════════════════════════════
    // VIMS — the gate is the only recording-state owner
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS startRecording is a no-op unless the gate is IDLE`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        assertTrue(
            "VIMS must own a RecordingSessionGate instead of a bare isRecording flag",
            vims.contains("private val recordingGate = RecordingSessionGate()")
        )
        assertTrue(
            "VIMS must no longer own an independent isRecording AtomicBoolean " +
                "(the gate is the single source of truth)",
            !vims.contains("private val isRecording = AtomicBoolean")
        )
        val startBody = codeOnly(extractFunBody(vims, "startRecording"))
        assertTrue(
            "VIMS startRecording must begin with the gate claim " +
                "(if (!recordingGate.tryStart()) return) so a double-tap or " +
                "onStartInputView re-entry can never start a second session",
            startBody.contains("if (!recordingGate.tryStart()) return")
        )
        assertTrue(
            "VIMS startRecording must no longer set a live recording flag by hand",
            !startBody.contains("isRecording.set(")
        )
    }

    @Test
    fun `VIMS startRecording discards a build whose session ended meanwhile`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val startBody = codeOnly(extractFunBody(vims, "startRecording"))
        assertTrue(
            "VIMS startRecording must capture a session generation at start",
            startBody.contains("val recordingGeneration = ++streamingSessionGeneration")
        )
        assertTrue(
            "VIMS startRecording's publish post must reject a stale generation " +
                "before touching the transcriber (cancel/stop/onFinishInput during " +
                "the bg build must abort the pending start)",
            startBody.contains("if (recordingGeneration != streamingSessionGeneration)")
        )
        assertTrue(
            "VIMS startRecording's publish post must also require the gate to " +
                "still be RECORDING (a stop landing in the init window moves the " +
                "gate to PROCESSING with nothing to process)",
            startBody.contains("|| !recordingGate.isRecording()")
        )
        assertTrue(
            "VIMS startRecording must release a discarded built transcriber " +
                "(no AudioRecord/engine leak on the stale path)",
            startBody.contains("builtTranscriber.release()")
        )
    }

    @Test
    fun `VIMS stopRecording and cancelRecording are gated and idempotent`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val stopBody = codeOnly(extractFunBody(vims, "stopRecording"))
        assertTrue(
            "VIMS stopRecording must early-return unless the gate is RECORDING " +
                "(double-invoke, or stop with no session, must not re-run teardown " +
                "or show a phantom Processing state)",
            stopBody.contains("if (!recordingGate.tryStop()) return")
        )
        val cancelBody = codeOnly(extractFunBody(vims, "cancelRecording"))
        assertTrue(
            "VIMS cancelRecording must early-return unless the gate is RECORDING " +
                "(double-invoke and cancel-after-stop must be no-ops)",
            cancelBody.contains("if (!recordingGate.tryCancel()) return")
        )
        assertTrue(
            "VIMS cancelRecording must bump the session generation so a pending " +
                "bg start is invalidated (mirror of cancelStreaming)",
            cancelBody.contains("streamingSessionGeneration++")
        )
    }

    @Test
    fun `VIMS callbacks and lifecycle settle the gate`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val onResult = codeOnly(extractFunBody(vims, "onResult"))
        assertTrue(
            "VIMS onResult must settle the gate (PROCESSING -> IDLE) after the " +
                "stale-generation early return",
            onResult.contains("recordingGate.finish()")
        )
        assertTrue(
            "VIMS onResult must keep its stale-generation reject BEFORE the settle",
            onResult.contains("if (recordingGeneration != streamingSessionGeneration) return") &&
                onResult.indexOf("if (recordingGeneration != streamingSessionGeneration) return") <
                onResult.indexOf("recordingGate.finish()")
        )
        // VIMS declares two onError overrides (streaming callback + recording
        // callback); the recording one is last in the file.
        val onError = codeOnly(extractLastFunBody(vims, "onError"))
        assertTrue(
            "VIMS recording onError must settle the gate (RECORDING/PROCESSING -> IDLE)",
            onError.contains("recordingGate.finish()")
        )
        val onDestroy = codeOnly(extractFunBody(vims, "onDestroy"))
        assertTrue(
            "VIMS onDestroy must reset the gate so a rebuilt view cannot observe " +
                "a phantom session",
            onDestroy.contains("recordingGate.reset()")
        )
        assertTrue(
            "VIMS onDestroy must no longer poke a bare isRecording flag",
            !onDestroy.contains("isRecording.set(")
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // VAS — existing guards regression-locked
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `VAS startRecording stopRecording and cancelRecording keep their isRecording guards`() {
        val vas = codeOnly(serviceSource("VoiceAccessibilityService.kt"))
        val startBody = codeOnly(extractFunBody(vas, "startRecording"))
        assertTrue(
            "VAS startRecording must keep its re-entry guard (if (isRecording) return)",
            startBody.contains("if (isRecording) return")
        )
        val stopBody = codeOnly(extractFunBody(vas, "stopRecording"))
        assertTrue(
            "VAS stopRecording must keep its idempotence guard (if (!isRecording) return)",
            stopBody.contains("if (!isRecording) return")
        )
        val cancelBody = codeOnly(extractFunBody(vas, "cancelRecording"))
        assertTrue(
            "VAS cancelRecording must keep its idempotence guard (if (!isRecording) return)",
            cancelBody.contains("if (!isRecording) return")
        )
    }
}
