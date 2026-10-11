package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Map #97 ticket #101 — session-churn audit contracts.
 *
 * The RN-native services (VoiceAccessibilityService, VoiceInputMethodService)
 * have no gradle test module, so their lifetime/caching contracts are locked
 * here as source contracts (same approach as [TranscriptionPersistenceOffloadTest]).
 * The behavioural counterparts live in the velaboard Robolectric suite
 * (KeyboardTranscriberSessionReuseTest, TranscriptionStorageIncrementalSaveTest).
 *
 * Locked claims:
 *  - VAS constructs TextCleaner only via the #96 getOrCreate cache (audit claim:
 *    "TextCleaner constructed per capture" — already satisfied, regression lock).
 *  - VAS resolves the cross-app models.db paths once per service lifetime and
 *    caches the local WhisperEngine (audit claim: model path / whisper_init
 *    loaded per capture — fixed by ticket #101).
 *  - A null path resolution is NEVER served as a permanent cached answer: a
 *    model downloaded after the first query must be picked up by a later call
 *    (#101 review blocker 1: dead path lockout).
 *  - The cached engine's acquire AND free run under the same lock, so two
 *    concurrent captures can never both construct a ~40MB native engine and
 *    onDestroy can never free one mid-acquire (#101 review blocker 3).
 *  - VIMS already caches its model paths (audit ref P6; lock).
 *  - Both TranscriptionStorage twins append one file per save; no list
 *    re-encode on insert (audit claim: "list rebuilt from scratch" — stale,
 *    regression lock).
 */
class SessionChurnContractTest {

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

    private fun vasSource() = File(repoRoot(),
        "velavoice app/src/native/VoiceAccessibilityService.kt").readText()

    // ══════════════════════════════════════════════════════════════════════
    // VAS: TextCleaner lifetime (#96 lock) + model path/engine caching (#101)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VAS constructs TextCleaner only via the getOrCreate cache`() {
        val code = codeOnly(vasSource())
        assertFalse(
            "VoiceAccessibilityService must never construct TextCleaner directly — the #96 " +
                "getOrCreate cache owns the ONNX model lifetime",
            code.contains(Regex("""=\s*TextCleaner\s*\("""))
        )
        assertTrue("VAS must route cleaner construction through TextCleaner.getOrCreate",
            code.contains("TextCleaner.getOrCreate"))
    }

    @Test
    fun `VAS caches the cross-app model path resolutions for the service lifetime`() {
        val source = vasSource()
        val code = codeOnly(source)
        assertTrue("VAS must memoize the whisper model path resolution",
            code.contains("whisperModelPathResolved"))
        assertTrue("VAS must memoize the LLM model path resolution",
            code.contains("llmModelPathResolved"))
        val onDestroy = extractMethodBody(source, "override fun onDestroy(")
        assertTrue("onDestroy must invalidate the whisper path cache",
            onDestroy.contains("cachedWhisperModelPath = null"))
        assertTrue("onDestroy must invalidate the LLM path cache",
            onDestroy.contains("cachedLlmModelPath = null"))
    }

    @Test
    fun `VAS caches one local whisper engine per service lifetime and frees it once on destroy`() {
        val source = vasSource()
        val code = codeOnly(source)
        val acquire = extractMethodBody(source, "private fun acquireWhisperEngine(")
        assertTrue("the cached engine accessor must own the instance",
            acquire.contains("cachedWhisperEngine"))
        val constructions = Regex("""\bWhisperEngine\s*\(""").findAll(code).count()
        assertEquals(
            "WhisperEngine must be constructed in exactly one place (the cache accessor) — " +
                "never per capture",
            1, constructions
        )
        assertFalse("the batch path must not free the shared engine per capture",
            code.contains("whisper.free()"))
        val onDestroy = extractMethodBody(source, "override fun onDestroy(")
        assertTrue(
            "onDestroy must free the cached engine exactly once, via the synchronized helper",
            Regex("""freeWhisperEngine\(\)""").findAll(onDestroy).count() == 1
        )
    }

    @Test
    fun `VAS never permanently caches a null model path - a later download is re-queried`() {
        val source = vasSource()
        for ((label, fn) in listOf("whisper" to "getWhisperModelPath", "llm" to "getLlmModelPath")) {
            val body = extractMethodBody(source, "private fun $fn(")
            assertTrue(
                "$label path cache must only serve a NON-null cached path whose file still exists",
                body.contains("if (cached != null && File(cached).exists()) return cached")
            )
            assertFalse(
                "$label path cache must never serve a null resolution as a permanent answer " +
                    "(a model downloaded after the first query would be locked out)",
                body.contains("if (cached == null || File(cached).exists()) return cached")
            )
            assertTrue(
                "$label: a null query result must leave the cache unresolved so the next call re-queries",
                body.contains("${label}ModelPathResolved = path != null")
            )
            assertFalse(
                "$label: a null query result must not be marked resolved",
                body.contains("${label}ModelPathResolved = true")
            )
        }
    }

    @Test
    fun `VAS guards whisper engine acquire and free with the same lock`() {
        val source = vasSource()
        assertTrue(
            "acquireWhisperEngine must be @Synchronized — two concurrent captures must not " +
                "both construct a ~40MB native engine and overwrite one without free()",
            Regex("""@Synchronized\s+private fun acquireWhisperEngine\(""").containsMatchIn(source)
        )
        assertTrue(
            "the engine free must be @Synchronized on the same monitor as acquire",
            Regex("""@Synchronized\s+private fun freeWhisperEngine\(""").containsMatchIn(source)
        )
        val onDestroy = extractMethodBody(source, "override fun onDestroy(")
        assertTrue(
            "onDestroy must free the engine through the guarded helper, not inline",
            onDestroy.contains("freeWhisperEngine()")
        )
        assertFalse(
            "onDestroy must not touch the engine cache fields outside the shared lock",
            onDestroy.contains("cachedWhisperEngine")
        )
        val free = extractMethodBody(source, "private fun freeWhisperEngine(")
        assertTrue("the guarded helper owns the engine teardown", free.contains("cachedWhisperEngine"))
        assertTrue("the guarded helper performs the free", free.contains("engine.free()"))
    }

    // ══════════════════════════════════════════════════════════════════════
    // VIMS: model-path cache (already satisfied by map #130 work; lock)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS resolves model paths once and reuses them across recordings`() {
        val code = codeOnly(File(repoRoot(),
            "velavoice app/src/native/VoiceInputMethodService.kt").readText())
        assertTrue(
            "VIMS must keep its loadModelPaths early-return cache (no per-recording models.db query)",
            code.contains("if (cachedWhisperPath != null && cachedLlmPath != null) return")
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // TranscriptionStorage twins: incremental append, no list re-encode
    // ══════════════════════════════════════════════════════════════════════

    private fun assertIncrementalSave(source: String, label: String) {
        val body = extractMethodBody(source, "fun save(")
        assertTrue("$label save must write one new per-session JSON file",
            body.contains("File(dir, \"\$baseName.json\")"))
        assertTrue("$label save must encode only that one file",
            body.contains("jsonFile.writeText"))
        assertFalse("$label save must never list the store (no list rebuild per insert)",
            body.contains("listFiles"))
        assertFalse("$label save must never read earlier transcripts (no re-encode per insert)",
            body.contains("readText"))
    }

    @Test
    fun `velaboard transcript store appends incrementally`() {
        assertIncrementalSave(File(repoRoot(),
            "velaboard/app/src/main/java/helium314/keyboard/settings/TranscriptionStorage.kt")
            .readText(), "velaboard")
    }

    @Test
    fun `velavoice transcript store appends incrementally`() {
        assertIncrementalSave(File(repoRoot(),
            "velavoice app/src/native/TranscriptionStorage.kt").readText(), "velavoice")
    }
}
