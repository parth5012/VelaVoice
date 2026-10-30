package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Wayfinder map #209 ticket #223 (F06 HIGH): a streaming error must fully tear
 * the session down — the fix at #222 made the pane's Cancel depend on
 * `isStreaming`/`streamingStartPending`, and `onError` only flipped
 * `isStreaming` while leaving capture running and the pipeline leaked; a later
 * `cancelStreaming()` then hit its early-return and the pane could never
 * recover. The locked contract (grilling #220): errors are an abort/recovery
 * path — same teardown family as cancelStreaming, with buffer-discard
 * semantics, and the mic must be released.
 *
 * The native IME service cannot be instantiated in a JVM test, so this is a
 * source-contract guard (same pattern as ServicePaneStopRoutingTest).
 */
class ServiceStreamingErrorTeardownTest {

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
     * Extract the body of `override fun onError` inside a callback object
     * created by `<call>(` — first onError that appears after the marker.
     */
    private fun extractOnErrorAfter(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) fail("marker $marker not found")
        val idx = source.indexOf("override fun onError(", start)
        if (idx < 0) fail("onError not found after $marker")
        val open = source.indexOf('{', idx)
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
        fail("unbalanced braces for onError")
        throw IllegalStateException("unreachable")
    }

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    @Test
    fun `streaming onError runs the full streaming teardown not just a flag flip`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val onError = codeOnly(extractOnErrorAfter(vims, "pipeline.setCallback"))
        assertTrue(
            "VIMS streaming onError must dispatch the shared streaming teardown " +
                "— only flipping isStreaming left the mic open and the pane " +
                "stuck: a later cancelStreaming() hit its early-return (F06/#223)",
            onError.contains("teardownStreamingSession()")
        )
        assertTrue(
            "VIMS streaming onError must stay generation-bound at head AND at " +
                "effect time (map #130 PR #137 TOCTOU fix preserved)",
            Regex("streamingSessionGeneration").findAll(onError).count() >= 2
        )
        assertTrue(
            "VIMS streaming onError must show the error text on the pane",
            onError.contains("setStatusText(error.message)")
        )
        assertTrue(
            "VIMS streaming onError must drop the isStreaming state before the " +
                "teardown so later cancel/stop cannot double-teardown",
            onError.contains("isStreaming.set(false)")
        )
    }

    @Test
    fun `streaming teardown discards buffer and suppresses trailing callbacks`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val teardown = codeOnly(extractFunBody(vims, "teardownStreamingSession"))
        // Buffer-discard semantics of cancelStreaming (map #130 ticket #133):
        // nothing the error left in the buffer may be committed or saved.
        assertTrue(
            "streaming teardown must discard the buffered tail " +
                "(streamingBuffer.setLength(0))",
            teardown.contains("streamingBuffer.setLength(0)")
        )
        assertTrue(
            "streaming teardown must reset both buffer and shown lengths",
            teardown.contains("streamingCommittedLength = 0") &&
                teardown.contains("streamingShownLength = 0")
        )
        assertTrue(
            "streaming teardown must discard owned text ranges " +
                "(textCommitter.discardOwnedRange())",
            teardown.contains("textCommitter.discardOwnedRange()")
        )
        assertTrue(
            "streaming teardown must mark the session cancelled so a trailing " +
                "onFinal/onRevisionMarker after teardown commits or saves nothing",
            teardown.contains("streamingCancelled = true")
        )
        // Mic + engine lifecycle: stop the pipeline and release recording
        // resources, then restore the keyboard (restartable state).
        assertTrue(
            "streaming teardown must stop the pipeline (streamingPipeline?.stop())",
            teardown.contains("streamingPipeline?.stop()")
        )
        assertTrue(
            "streaming teardown must release the voice engine " +
                "(teardownVoiceEngine...) and null the pipeline",
            teardown.contains("teardownVoiceEngine") &&
                teardown.contains("streamingPipeline = null")
        )
        assertTrue(
            "streaming teardown must clear the waveform",
            teardown.contains("voiceRecordingPane.waveformView.clear()")
        )
        assertTrue(
            "streaming teardown must restore the keyboard view",
            teardown.contains("showKeyboardView()")
        )
    }

    @Test
    fun `cancelStreaming reuses the shared teardown — no duplicated state handling`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val cancelBody = codeOnly(extractFunBody(vims, "cancelStreaming"))
        assertTrue(
            "VIMS cancelStreaming must dispatch the shared streaming teardown " +
                "— one teardown path, one set of discard semantics (#223)",
            cancelBody.contains("teardownStreamingSession()")
        )
        val stopBody = codeOnly(extractFunBody(vims, "stopStreaming"))
        assertTrue(
            "stopStreaming keeps its own commit-semantics teardown (it commits " +
                "the tail and is NOT an abort) — it must not be merged into the " +
                "shared streaming teardown",
            stopBody.contains("finishComposingText()")
        )
        assertTrue(
            "stopStreaming keeps the recording pipeline release path",
            stopBody.contains("teardownVoiceEngine")
        )
    }

    @Test
    fun `subsequent cancel after error teardown is harmless`() {
        val vims = codeOnly(serviceSource("VoiceInputMethodService.kt"))
        val cancelBody = codeOnly(extractFunBody(vims, "cancelStreaming"))
        // After a teardown, isStreaming == false and streamingStartPending ==
        // false, so cancelStreaming() must early-return (idempotence guard).
        assertTrue(
            "cancelStreaming keeps its early-return guard so a cancel after " +
                "error-teardown is a no-op (F06 acceptance)",
            cancelBody.contains("if (!isStreaming.get() && !streamingStartPending) return")
        )
    }
}
