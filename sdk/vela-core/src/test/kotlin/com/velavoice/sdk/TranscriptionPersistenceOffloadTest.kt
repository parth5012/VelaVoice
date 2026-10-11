package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Map #97 ticket #100: transcription persistence must leave the IME main thread.
 *
 * `TranscriptionStorage.save()` is synchronous JSON encoding + file/WAV writes.
 * The IME commit paths used to call it inline from main-thread callbacks:
 * - `AudioRecorder` posts `VelaRecordingCallback.onResult` to the main looper,
 *   so the keyboard batch save ran on the IME main thread (with the PCM WAV of
 *   the whole recording).
 * - `VelaStreamingSession.completeFinalize` runs on `LatinIME.mHandler`, so the
 *   streamed save ran there too.
 * - `VoiceInputMethodService` batch `onResult` and posted streaming `onFinal`
 *   are both main-thread code doing the same.
 *
 * Contract: every one of those call sites dispatches through a dedicated
 * single-thread `persistAsync` seam (FIFO, failures contained so a storage
 * error can never reach the insertion path), and both `TranscriptionStorage`
 * copies own that seam. The velaboard copy additionally gets a behavioural
 * Robolectric test (`TranscriptionPersistenceAsyncBehaviorTest`) proving the
 * seam never runs on the caller thread — together they are the honest proxy
 * for a StrictMode `detectDiskWrites` assertion, which Robolectric does not
 * enforce and the velavoice-native IME has no test harness for at all.
 *
 * RED while save() is still called inline on the IME paths.
 */
class TranscriptionPersistenceOffloadTest {

    private fun repoRoot(): File {
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

    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    /** Body of the first method whose signature starts with [signature], balanced braces. */
    private fun extractMethodBody(source: String, signature: String): String {
        val code = codeOnly(source)
        val idx = code.indexOf(signature)
        if (idx < 0) fail("'$signature' not found")
        val open = code.indexOf('{', idx)
        if (open < 0) fail("opening brace for '$signature' not found")
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
        }
        fail("unbalanced braces for '$signature'")
        throw IllegalStateException("unreachable")
    }

    /** Balanced-paren argument text of every `persistAsync(` call. */
    private fun persistAsyncArgs(source: String): List<String> {
        val code = codeOnly(source)
        val args = mutableListOf<String>()
        var idx = code.indexOf("persistAsync(")
        while (idx >= 0) {
            val open = code.indexOf('(', idx)
            var depth = 0
            var end = -1
            for (i in open until code.length) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
            }
            if (end < 0) fail("unbalanced parens for persistAsync at $idx")
            args += code.substring(open + 1, end)
            idx = code.indexOf("persistAsync(", end)
        }
        return args
    }

    private fun countSaves(text: String): Int =
        codeOnly(text).split("TranscriptionStorage.save(").size - 1

    private fun imeSaveCallerFiles(): Map<String, File> {
        val root = repoRoot()
        return mapOf(
            "VIMS" to File(root, "velavoice app/src/native/VoiceInputMethodService.kt"),
            "KeyboardSwitcher" to File(root,
                "velaboard/app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java"),
            "VelaStreamingSession" to File(root,
                "velaboard/app/src/main/java/helium314/keyboard/keyboard/VelaStreamingSession.java")
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // The seam itself — both TranscriptionStorage copies
    // ══════════════════════════════════════════════════════════════════════

    private fun assertSeamExists(source: String, label: String) {
        val code = codeOnly(source)
        assertTrue(
            "$label must own a dedicated single-thread persistence executor",
            code.contains("private val persistenceExecutor = Executors.newSingleThreadExecutor")
        )
        val seam = extractMethodBody(code, "fun persistAsync(")
        assertTrue(
            "$label persistAsync must submit the task to that executor",
            seam.contains("persistenceExecutor.execute")
        )
        assertTrue(
            "$label persistAsync must contain task failures so a storage error " +
                "can never reach the insertion path",
            seam.contains("catch")
        )
    }

    @Test
    fun `velavoice storage owns a single-thread persistAsync seam`() {
        val source = File(repoRoot(), "velavoice app/src/native/TranscriptionStorage.kt").readText()
        assertSeamExists(source, "velavoice")
    }

    @Test
    fun `velaboard storage owns a single-thread persistAsync seam`() {
        val source = File(repoRoot(),
            "velaboard/app/src/main/java/helium314/keyboard/settings/TranscriptionStorage.kt").readText()
        assertSeamExists(source, "velaboard")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Every IME save call site is executor-bound
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS batch onResult and streaming onFinal save inside persistAsync`() {
        val source = imeSaveCallerFiles().getValue("VIMS").readText()
        val start = codeOnly(extractMethodBody(source, "fun startRecording("))
        val resultIdx = start.indexOf("override fun onResult")
        assertTrue("VIMS batch onResult must still exist", resultIdx >= 0)
        val onResult = start.substring(resultIdx)
        assertTrue(
            "VIMS batch onResult must keep its volatile privacy gate",
            onResult.contains("if (!sessionStartedPrivacySensitive)")
        )
        assertTrue(
            "VIMS batch save must be dispatched through persistAsync, never inline on the " +
                "main looper (AudioRecorder posts onResult to main)",
            persistAsyncArgs(onResult).any { it.contains("TranscriptionStorage.save(") }
        )
        assertTrue(
            "VIMS batch privacy gate must still run BEFORE the save is enqueued",
            onResult.indexOf("if (!sessionStartedPrivacySensitive)") < onResult.indexOf("persistAsync(")
        )

        val onFinal = codeOnly(extractMethodBody(source, "fun onFinal("))
        assertTrue(
            "VIMS streaming onFinal save must be dispatched through persistAsync, never " +
                "inline on the main handler",
            persistAsyncArgs(onFinal).any { it.contains("TranscriptionStorage.save(") }
        )
        assertTrue(
            "VIMS streaming save gate must still run BEFORE the save is enqueued",
            onFinal.indexOf("if (sessionGeneration == streamingSessionGeneration && !sensitiveAtFinal) {") in
                0 until onFinal.indexOf("persistAsync(")
        )
    }

    @Test
    fun `keyboard batch and streamed saves run inside persistAsync`() {
        val callers = imeSaveCallerFiles()
        val switcher = callers.getValue("KeyboardSwitcher").readText()
        val onResult = codeOnly(extractMethodBody(switcher, "void onResult("))
        assertTrue(
            "KeyboardSwitcher onResult save must be dispatched through persistAsync, never " +
                "inline on the main looper (AudioRecorder posts onResult to main)",
            persistAsyncArgs(onResult).any { it.contains("TranscriptionStorage.save(") }
        )

        val session = callers.getValue("VelaStreamingSession").readText()
        val completeFinalize = codeOnly(extractMethodBody(session, "void completeFinalize("))
        assertTrue(
            "VelaStreamingSession completeFinalize save must be dispatched through " +
                "persistAsync, never inline on LatinIME.mHandler",
            persistAsyncArgs(completeFinalize).any { it.contains("TranscriptionStorage.save(") }
        )
    }

    /**
     * Walk the whole production tree: every file that calls the gated save must
     * be a known IME caller whose saves are all executor-bound, or an explicitly
     * reviewed non-IME caller (VAS — the accessibility service, outside map #97
     * scope; a NEW caller forces a review here instead of silently landing on
     * whatever thread its author happened to be on).
     */
    @Test
    fun `no production save call escapes the persistence executor unreviewed`() {
        val root = repoRoot()
        val imeCallers = imeSaveCallerFiles().values.map { it.name }.toSet()
        val knownOutOfScope = setOf("VoiceAccessibilityService.kt")
        val found = mutableMapOf<String, File>()
        listOf(File(root, "velaboard/app/src/main"), File(root, "velavoice app/src")).forEach { tree ->
            tree.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .filter { countSaves(it.readText()) > 0 }
                .forEach { found[it.name] = it }
        }
        val unexpected = found.keys - imeCallers - knownOutOfScope
        assertTrue(
            "unexpected TranscriptionStorage.save caller needs threading review " +
                "(expected IME callers $imeCallers plus known $knownOutOfScope, found ${found.keys})",
            unexpected.isEmpty()
        )
        imeCallers.forEach { name ->
            val file = found[name]
            assertTrue("IME save caller $name no longer calls save", file != null)
            val source = file!!.readText()
            val bound = persistAsyncArgs(source).sumOf { countSaves(it) }
            assertEquals(
                "$name: every TranscriptionStorage.save( must sit inside a persistAsync " +
                    "argument (never inline on the IME main thread)",
                countSaves(source),
                bound
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Executable model of the seam's contract
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `persistAsync model - a failing save never reaches the caller and the next save still runs`() {
        val executor = Executors.newSingleThreadExecutor()
        val ran = mutableListOf<String>()
        try {
            executor.execute { throw RuntimeException("disk full") }
            executor.execute { ran.add("second save") }
            val drained = CountDownLatch(1)
            executor.execute { drained.countDown() }
            assertTrue("model executor must drain", drained.await(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
        assertEquals(
            "a throwing persistence task must not kill the single-thread executor " +
                "(subsequent saves still persist)",
            listOf("second save"),
            ran
        )
    }
}
