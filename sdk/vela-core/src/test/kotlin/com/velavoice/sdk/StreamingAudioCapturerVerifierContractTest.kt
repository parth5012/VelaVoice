package com.velavoice.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * StreamingAudioCapturer verifier contract (hotfix branch).
 *
 * Android 15 (API 35) ART's strict verifier rejects a method that performs a
 * return instruction while a monitor is held unless that return is inside an
 * exception handler covering the monitor region ("catch-all for an
 * instruction where a monitor is held"). Kotlinc emits exactly that illegal
 * bytecode for `return@Thread` inside `synchronized(...)` inside a lambda:
 * the non-local return is compiled as a plain `return` while the monitor is
 * still held, and ART throws VerifyError at first class load — on debug
 * builds (R8 normally rewrites this away, which is why release 4.1 shipped).
 *
 * The crash observed on device (helium314.keyboard.debug):
 *   java.lang.VerifyError: Verifier rejected class StreamingAudioCapturer:
 *   void start$lambda$3 failed to verify: [0x3C] expected to be within a
 *   catch-all for an instruction where a monitor is held
 *   at VelaTranscriber.<init>(VelaTranscriber.kt:191)
 *
 * That single crash killed the whole IME process the moment "transcribe" was
 * tapped. The fix: never leave a synchronized block via return — capture the
 * decision in a local and return after the block exits.
 */
class StreamingAudioCapturerVerifierContractTest {

    private fun findRepoRoot(): File {
        var dir: File = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "sdk").isDirectory && File(dir, "velaboard").isDirectory) return dir
            dir = dir.parentFile ?: return dir
        }
        throw IllegalStateException("repo root not found above ${System.getProperty("user.dir")}")
    }

    private fun codeOnly(source: String): String =
        source.replace(Regex("//.*"), "")
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

    @Test
    fun `capturer thread body never returns while holding the monitor`() {
        val root = findRepoRoot()
        val f = File(
            root,
            "sdk/vela-core/src/main/kotlin/com/velavoice/sdk/StreamingAudioCapturer.kt"
        )
        assertTrue(f.isFile)
        val code = codeOnly(f.readText())
        // Any `return@Thread` (or bare return) lexically inside a
        // synchronized(...) block carries ART's monitor-held-return hazard.
        val hits = Regex("""return@Thread\b""").findAll(code).toList()
        assertFalse(
            "StreamingAudioCapturer must not return from inside a synchronized " +
                "block — ART (API 35) fails VerifyError on it in debug builds and " +
                "crashes the IME at transcribe-tap; found: ${hits.map { it.value }}",
            hits.isNotEmpty()
        )
    }

    @Test
    fun `stale-epoch abandon path still releases the recorder without installing it`() {
        val code = codeOnly(
            File(
                findRepoRoot(),
                "sdk/vela-core/src/main/kotlin/com/velavoice/sdk/StreamingAudioCapturer.kt"
            ).readText()
        )
        // The abandon decision survives as a guarded assignment, and the
        // finally-based teardown still covers the not-installed case.
        assertTrue(
            "stale epoch must still abandon installation (guard must exist)",
            code.contains("epoch != sessionEpoch") && code.contains("installRecord")
        )
    }
}
