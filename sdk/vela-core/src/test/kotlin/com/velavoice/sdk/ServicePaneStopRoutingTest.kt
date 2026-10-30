package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Wayfinder map #209 ticket #222 (grilling #220 F01): the pane Stop / Stop-raw
 * / Cancel buttons must work in EVERY streaming mode — the terminal signal is
 * an explicit stop, so these are the user's only stop paths.
 *
 * The recording gate is a BATCH-path state machine: `startStreaming()` never
 * claims it (only `startRecording()` does, via tryStart), so pane buttons that
 * route to the gate-guarded `stopRecording()`/`cancelRecording()` are NO-OPS in
 * streamed mode — the gate is IDLE, mic stays open until onFinishInput. The
 * locked decision (#222): pane listeners route a streamed session around the
 * gate to `stopStreaming()`/`cancelStreaming()` (the same routing `onFinishInput`
 * already uses) instead of claiming the gate from streaming. The gate cannot
 * just transport streamed sessions: `stopRecording()` would still drive the
 * null batch transcriber, so gate-claim-only would set PROCESSING and leave
 * the pipeline recording.
 *
 * The native IME service cannot be instantiated in a JVM test, so this is a
 * source-contract guard (same pattern as ServiceRecordingReentryGuardTest).
 */
class ServicePaneStopRoutingTest {

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

    /**
     * Extract the `voiceRecordingPane.apply` configuration block (where the
     * pane's stop/stop-raw/cancel listeners are wired) with balanced braces.
     */
    private fun extractPaneWiring(source: String): String {
        val idx = source.indexOf("voiceRecordingPane = VoiceRecordingPane(context).apply")
        if (idx < 0) fail("voiceRecordingPane apply block not found")
        val open = source.indexOf('{', idx)
        if (open < 0) fail("opening brace for voiceRecordingPane apply not found")
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
        fail("unbalanced braces for voiceRecordingPane apply")
        throw IllegalStateException("unreachable")
    }

    /** Listener body for `name = { ... }` inside the pane wiring block. */
    private fun listenerBody(wiring: String, name: String): String {
        val idx = wiring.indexOf("$name =")
        if (idx < 0) fail("$name is not wired in the voiceRecordingPane block")
        val open = wiring.indexOf('{', idx)
        if (open < 0) fail("opening brace for $name not found")
        var depth = 0
        for (i in open until wiring.length) {
            when (wiring[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return wiring.substring(open + 1, i)
                }
            }
        }
        fail("unbalanced braces for $name")
        throw IllegalStateException("unreachable")
    }

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    // ════════════════════════════════════════════════════════════════════
    // The stop path: an explicit stop must end a streamed session
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS pane Stop routes streamed sessions to stopStreaming not the batch gate`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val wiring = codeOnly(extractPaneWiring(vims))
        val stopClean = codeOnly(listenerBody(wiring, "onStopCleanListener"))
        val stopRaw = codeOnly(listenerBody(wiring, "onStopRawListener"))
        for (body in listOf("onStopCleanListener" to stopClean, "onStopRawListener" to stopRaw)) {
            assertTrue(
                "VIMS $body.first must route an actively-streaming session to " +
                    "stopStreaming() — stopRecording()'s recordingGate.tryStop() " +
                    "is a no-op while the gate is IDLE (startStreaming never claims " +
                    "it), which kept the mic open when the user pressed Stop (#222)",
                body.second.contains("isStreaming.get()") && body.second.contains("stopStreaming()")
            )
            assertTrue(
                "VIMS $body.first must still run the batch path when not streaming",
                body.second.contains("stopRecording(")
            )
        }
    }

    @Test
    fun `VIMS pane Stop routes a queued streaming start to cancelStreaming not the batch gate`() {
        // CodeRabbit review finding on #262 (Major): with streamingStartPending
        // (queued pipeline build) and isStreaming still false, both Stop
        // listeners fell through to stopRecording() — the gated batch path —
        // whose gate is IDLE in streamed mode, so the queued pipeline went on
        // to START recording after the user pressed Stop.
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val wiring = codeOnly(extractPaneWiring(vims))
        val stopClean = codeOnly(listenerBody(wiring, "onStopCleanListener"))
        val stopRaw = codeOnly(listenerBody(wiring, "onStopRawListener"))
        for (listener in listOf("onStopCleanListener" to stopClean, "onStopRawListener" to stopRaw)) {
            assertTrue(
                "VIMS ${listener.first} must route an active streamed session to stopStreaming() (#222)",
                listener.second.contains("isStreaming.get()")
            )
            assertTrue(
                "VIMS ${listener.first} must handle a queued-but-unstarted start " +
                    "(streamingStartPending) — dropping it into stopRecording()'s " +
                    "batch gate lets the pending pipeline start after Stop",
                listener.second.contains("streamingStartPending")
            )
            assertTrue(
                "VIMS ${listener.first} must abort the queued start through the " +
                    "streaming path (cancelStreaming: abort queued start + restore keyboard)",
                listener.second.contains("cancelStreaming()")
            )
            // Order: active-session, pending-start, then batch fallback —
            // pending must be caught BEFORE the batch branch.
            val pendingIdx = listener.second.indexOf("streamingStartPending")
            val batchIdx = listener.second.indexOf("stopRecording(")
            assertTrue(
                "VIMS ${listener.first}: the pending-start branch must come before the batch stopRecording fallback",
                pendingIdx in 0 until batchIdx
            )
        }
    }

    @Test
    fun `VIMS pane Cancel routes streamed sessions to cancelStreaming not the batch gate`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val wiring = codeOnly(extractPaneWiring(vims))
        val cancel = codeOnly(listenerBody(wiring, "onCancelListener"))
        assertTrue(
            "VIMS onCancelListener must route an actively-streaming session to " +
                "cancelStreaming() — cancelRecording()'s recordingGate.tryCancel() " +
                "is a no-op while the gate is IDLE (startStreaming never claims " +
                "it), which kept the mic open when the user pressed Cancel (#222)",
            cancel.contains("cancelStreaming()")
        )
        assertTrue(
            "VIMS onCancelListener must cover streamingStartPending too — a queued " +
                "streaming start was cancelable at gate level only via the batch path",
            cancel.contains("streamingStartPending")
        )
        assertTrue(
            "VIMS onCancelListener must still run the batch path when no session is streaming",
            cancel.contains("cancelRecording()")
        )
    }

    @Test
    fun `VIMS streaming stop and cancel do not touch the batch gate`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        // The routing (not a gate claim) is the locked fix: startStreaming()
        // must never claim the gate, and the streaming teardowns must never
        // settle it — the gate stays a pure batch-path state machine, so
        // mixing states across the two modes can never wedge it.
        val startStreamingBody = codeOnly(extractFunBody(vims, "startStreaming"))
        assertTrue(
            "VIMS startStreaming must NOT claim the RecordingSessionGate " +
                "(recordingGate.tryStart) — the gate is the batch path only; " +
                "streamed sessions route pane stops around it (#222)",
            !startStreamingBody.contains("recordingGate.tryStart")
        )
        val stopStreamingBody = codeOnly(extractFunBody(vims, "stopStreaming"))
        assertTrue(
            "VIMS stopStreaming must NOT settle the batch gate (no " +
                "recordingGate.tryStop/tryCancel/finish/reset — it was never claimed)",
            listOf("recordingGate.tryStop", "recordingGate.tryCancel", "recordingGate.finish", "recordingGate.reset")
                .none { stopStreamingBody.contains(it) }
        )
        val cancelStreamingBody = codeOnly(extractFunBody(vims, "cancelStreaming"))
        assertTrue(
            "VIMS cancelStreaming must NOT settle the batch gate (no " +
                "recordingGate.tryStop/tryCancel/finish/reset — it was never claimed)",
            listOf("recordingGate.tryStop", "recordingGate.tryCancel", "recordingGate.finish", "recordingGate.reset")
                .none { cancelStreamingBody.contains(it) }
        )
    }

    @Test
    fun `VIMS batch stopRecording and cancelRecording stay gate-guarded`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val stopBody = codeOnly(extractFunBody(vims, "stopRecording"))
        val cancelBody = codeOnly(extractFunBody(vims, "cancelRecording"))
        assertTrue(
            "VIMS stopRecording must keep its gate guard — the routing fix must " +
                "not weaken the batch path's idempotence",
            stopBody.contains("if (!recordingGate.tryStop()) return")
        )
        assertTrue(
            "VIMS cancelRecording must keep its gate guard — the routing fix must " +
                "not weaken the batch path's idempotence",
            cancelBody.contains("if (!recordingGate.tryCancel()) return")
        )
    }
}
