package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Map #130 ticket #134: fail-closed privacy gate inside TranscriptionStorage.save().
 *
 * Both `TranscriptionStorage` copies were passive with no privacy parameter — every
 * gate was a caller-side `if (!sensitive)` skip, so any future direct `save()` caller
 * bypassed silently. `save()` must take a REQUIRED `privacySensitive: Boolean` (no
 * default — mirror of the #76 `TextCleaner.clean` overload removal) and, on `true`,
 * write nothing, return the failure signal, and log counts only (never names/paths
 * with content). Existing caller-side skips stay as the second layer.
 *
 * Source-contract guards (app-module storage cannot be instantiated in SDK unit
 * tests; the velavoice-app copy has no Gradle test harness at all — the velaboard
 * copy gets a direct Robolectric behavior test alongside this contract):
 * RED while the gate is absent, GREEN once both copies refuse sensitive writes.
 */
class TranscriptionStoragePrivacyGateTest {

    private data class StorageSources(val velaboard: String, val velavoice: String)

    private fun storageSources(): StorageSources {
        val root = findRepoRoot()
        val velaboard = File(root, "velaboard/app/src/main/java/helium314/keyboard/settings/TranscriptionStorage.kt")
        val velavoice = File(root, "velavoice app/src/native/TranscriptionStorage.kt")
        if (!velaboard.isFile) fail("velaboard TranscriptionStorage.kt not found under $root")
        if (!velavoice.isFile) fail("velavoice TranscriptionStorage.kt not found under $root")
        return StorageSources(velaboard.readText(), velavoice.readText())
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

    /** Extract the body of `fun save(` with balanced braces (first match). */
    private fun extractSaveBody(source: String): String {
        val idx = source.indexOf("fun save(")
        if (idx < 0) fail("fun save( not found")
        val open = source.indexOf('{', idx)
        if (open < 0) fail("opening brace for save not found")
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
        fail("unbalanced braces for save")
        throw IllegalStateException("unreachable")
    }

    /** Extract the `fun save(...)` parameter list (between the parens). */
    private fun extractSaveParams(source: String): String {
        val idx = source.indexOf("fun save(")
        if (idx < 0) fail("fun save( not found")
        val open = source.indexOf('(', idx)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        fail("unbalanced parens for save params")
        throw IllegalStateException("unreachable")
    }

    /** Index of the first disk write inside a save() body; fails when save writes nothing. */
    private fun firstWriteIndex(body: String, label: String): Int {
        val hits = listOf("mkdirs()", "writeText(", "saveWav(")
            .map { body.indexOf(it) }
            .filter { it >= 0 }
        assertTrue("$label save() performs no write — gate has nothing to guard", hits.isNotEmpty())
        return hits.minOrNull() ?: -1
    }

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    // ── Required param, no default (mirror of #76 clean overload removal) ──

    @Test
    fun `velaboard save requires privacySensitive with no default`() {
        val (velaboard, _) = storageSources()
        val params = codeOnly(extractSaveParams(velaboard))
        assertTrue(
            "velaboard save() must take a required privacySensitive: Boolean param",
            params.contains("privacySensitive: Boolean")
        )
        assertTrue(
            "velaboard save() privacySensitive must have NO default (explicit verdict at every call site)",
            !params.contains(Regex("privacySensitive\\s*:\\s*Boolean\\s*="))
        )
    }

    @Test
    fun `velavoice save requires privacySensitive with no default`() {
        val (_, velavoice) = storageSources()
        val params = codeOnly(extractSaveParams(velavoice))
        assertTrue(
            "velavoice save() must take a required privacySensitive: Boolean param",
            params.contains("privacySensitive: Boolean")
        )
        assertTrue(
            "velavoice save() privacySensitive must have NO default (explicit verdict at every call site)",
            !params.contains(Regex("privacySensitive\\s*:\\s*Boolean\\s*="))
        )
    }

    // ── Fail-closed gate: refuse BEFORE any write ──

    @Test
    fun `velaboard save refuses sensitive writes before touching disk`() {
        val (velaboard, _) = storageSources()
        val body = codeOnly(extractSaveBody(velaboard))
        assertTrue(
            "velaboard save() must branch on the privacy verdict",
            body.contains("if (privacySensitive)")
        )
        val gateIdx = body.indexOf("if (privacySensitive)")
        val firstWriteIdx = firstWriteIndex(body, "velaboard")
        assertTrue(
            "velaboard save() gate must run BEFORE any disk write (fail closed)",
            gateIdx >= 0 && gateIdx < firstWriteIdx
        )
        val gateBlock = body.substring(gateIdx, firstWriteIdx)
        assertTrue(
            "velaboard save() gate must return the failure signal without writing",
            gateBlock.contains("return null")
        )
    }

    @Test
    fun `velavoice save refuses sensitive writes before touching disk`() {
        val (_, velavoice) = storageSources()
        val body = codeOnly(extractSaveBody(velavoice))
        assertTrue(
            "velavoice save() must branch on the privacy verdict",
            body.contains("if (privacySensitive)")
        )
        val gateIdx = body.indexOf("if (privacySensitive)")
        val firstWriteIdx = firstWriteIndex(body, "velavoice")
        assertTrue(
            "velavoice save() gate must run BEFORE any disk write (fail closed)",
            gateIdx >= 0 && gateIdx < firstWriteIdx
        )
        val gateBlock = body.substring(gateIdx, firstWriteIdx)
        assertTrue(
            "velavoice save() gate must return the failure signal without writing",
            gateBlock.contains("return null")
        )
    }

    // ── Count-only refusal logs (never names/paths/content, cf. #79) ──

    @Test
    fun `velaboard refusal log carries counts only - never content or paths`() {
        val (velaboard, _) = storageSources()
        val body = codeOnly(extractSaveBody(velaboard))
        val gateIdx = body.indexOf("if (privacySensitive)")
        assertTrue("velaboard save() must branch on the privacy verdict", gateIdx >= 0)
        val firstWriteIdx = firstWriteIndex(body, "velaboard")
        val gateRegion = body.substring(gateIdx, firstWriteIdx)
        assertTrue(
            "velaboard save() gate must log the refusal (observability without content)",
            gateRegion.contains("Log.")
        )
        assertTrue(
            "velaboard refusal gate interpolates transcript content or file paths",
            !gateRegion.contains("\$raw") && !gateRegion.contains("\$cleaned") &&
                !gateRegion.contains("baseName") && !gateRegion.contains(".wav") &&
                !gateRegion.contains(".json")
        )
    }

    @Test
    fun `velavoice refusal log carries counts only - never content or paths`() {
        val (_, velavoice) = storageSources()
        val body = codeOnly(extractSaveBody(velavoice))
        val gateIdx = body.indexOf("if (privacySensitive)")
        assertTrue("velavoice save() must branch on the privacy verdict", gateIdx >= 0)
        val firstWriteIdx = firstWriteIndex(body, "velavoice")
        val gateRegion = body.substring(gateIdx, firstWriteIdx)
        assertTrue(
            "velavoice save() gate must log the refusal (observability without content)",
            gateRegion.contains("Log.")
        )
        assertTrue(
            "velavoice refusal gate interpolates transcript content or file paths",
            !gateRegion.contains("\$raw") && !gateRegion.contains("\$cleaned") &&
                !gateRegion.contains("baseName") && !gateRegion.contains(".wav") &&
                !gateRegion.contains(".json")
        )
    }

    // ── Every call site passes an explicit verdict (no default-arg call compiles) ──

    private fun assertAllSaveCallsPassFlag(file: File, label: String) {
        val code = codeOnly(file.readText())
        var idx = code.indexOf("TranscriptionStorage.save(")
        if (idx < 0) fail("$label: no TranscriptionStorage.save( call found")
        var count = 0
        while (idx >= 0) {
            count++
            // Balance parens from the call to capture the full argument list.
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
            if (end < 0) fail("$label: unbalanced parens in save call #$count")
            val args = code.substring(open, end + 1)
            assertTrue(
                "$label: save call #$count must pass an explicit privacySensitive verdict (no default-arg call)",
                args.contains("privacySensitive", ignoreCase = true)
            )
            idx = code.indexOf("TranscriptionStorage.save(", end)
        }
    }

    @Test
    fun `keyboard callbacks pass explicit verdict to velaboard save`() {
        val root = findRepoRoot()
        val main = File(root, "velaboard/app/src/main")
        val callers = main.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.readText().contains("TranscriptionStorage.save(") }
            .toList()
        val names = callers.map { it.name }.toSet()
        assertTrue(
            "velaboard batch + streaming callbacks must both route through the gated save " +
                "(expected KeyboardSwitcher + VelaStreamingSession, found $names)",
            names.contains("KeyboardSwitcher.java") && names.contains("VelaStreamingSession.java")
        )
        callers.forEach { assertAllSaveCallsPassFlag(it, "velaboard/${it.name}") }
    }

    @Test
    fun `VIMS trailing saves pass explicit verdict to velavoice save`() {
        val root = findRepoRoot()
        assertAllSaveCallsPassFlag(
            File(root, "velavoice app/src/native/VoiceInputMethodService.kt"),
            "VIMS"
        )
    }

    @Test
    fun `VAS batch and streaming saves pass explicit verdict to velavoice save`() {
        val root = findRepoRoot()
        assertAllSaveCallsPassFlag(
            File(root, "velavoice app/src/native/VoiceAccessibilityService.kt"),
            "VAS"
        )
    }

    @Test
    fun `no other production caller bypasses the gated save`() {
        val root = findRepoRoot()
        val expected = setOf(
            "KeyboardSwitcher.java",
            "VelaStreamingSession.java",
            "VoiceInputMethodService.kt",
            "VoiceAccessibilityService.kt"
        )
        val actual = mutableSetOf<String>()
        listOf(
            File(root, "velaboard/app/src/main"),
            File(root, "velavoice app/src")
        ).forEach { tree ->
            tree.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    if (codeOnly(file.readText()).contains("TranscriptionStorage.save(")) {
                        actual.add(file.name)
                    }
                }
        }
        assertTrue(
            "unexpected TranscriptionStorage.save caller would bypass review " +
                "(expected $expected, found $actual)",
            actual == expected
        )
    }
}
