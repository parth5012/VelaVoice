package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Map #130 ticket #135: quarantine directory + Drive-sync hardening.
 *
 * Two layers (both copies of `TranscriptionStorage`, both sync modules):
 * 1. Sensitive sessions persist under a DEDICATED quarantine directory that the
 *    sync scanner never enters (structural exclusion — a sibling dir, not a
 *    naming convention or a subdirectory). `save(..., privacySensitive = true)`
 *    routes there (shared dir untouched, still returns null per #134) while
 *    `false` keeps the existing `transcriptions/` layout. Every save stamps its
 *    verdict (`privacySensitive`) into the JSON.
 * 2. Belt-and-braces on egress: `getUnsyncedFiles` stays scoped to the shared
 *    dir AND re-verifies the JSON verdict, skipping sensitive / unparseable /
 *    verdict-less files fail-closed with counts-only logs; both `syncToDrive`
 *    loops re-check per file pre-upload (TOCTOU guard) and count skips.
 *
 * RED while quarantine routing / verdict stamping / re-verify are absent,
 * GREEN once both storage copies and both sync modules enforce them.
 */
class TranscriptionStorageQuarantineSyncTest {

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

    private fun storageSources(): Pair<String, String> {
        val root = repoRoot()
        val velaboard = File(root, "velaboard/app/src/main/java/helium314/keyboard/settings/TranscriptionStorage.kt")
        val velavoice = File(root, "velavoice app/src/native/TranscriptionStorage.kt")
        if (!velaboard.isFile) fail("velaboard TranscriptionStorage.kt not found under $root")
        if (!velavoice.isFile) fail("velavoice TranscriptionStorage.kt not found under $root")
        return velaboard.readText() to velavoice.readText()
    }

    private fun syncSources(): Pair<String, String> {
        val root = repoRoot()
        val velaboard = File(root, "velaboard/app/src/main/java/helium314/keyboard/settings/GoogleDriveSync.kt")
        val velavoice = File(root, "velavoice app/src/native/GoogleDriveSyncModule.kt")
        if (!velaboard.isFile) fail("velaboard GoogleDriveSync.kt not found under $root")
        if (!velavoice.isFile) fail("velavoice GoogleDriveSyncModule.kt not found under $root")
        return velaboard.readText() to velavoice.readText()
    }

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    /** Extract the body of `fun <name>(` with balanced braces (first match). */
    private fun extractFunBody(source: String, signature: String): String {
        val idx = source.indexOf(signature)
        if (idx < 0) fail("$signature not found")
        val open = source.indexOf('{', idx)
        if (open < 0) fail("opening brace for $signature not found")
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
        fail("unbalanced braces for $signature")
        throw IllegalStateException("unreachable")
    }

    /** Value of `private const val <name> = "..."` (code only). */
    private fun constString(code: String, name: String): String {
        val m = Regex("""const val $name\s*=\s*"([^"]+)"""").find(code)
            ?: throw AssertionError("const val $name not found")
        return m.groupValues[1]
    }

    // ── Quarantine dir: dedicated sibling, never scanned ──

    @Test
    fun `velaboard defines a dedicated quarantine dir sibling to transcriptions`() {
        val (velaboard, _) = storageSources()
        val code = codeOnly(velaboard)
        val shared = constString(code, "TRANSCRIPTIONS_DIR")
        assertTrue("shared dir const must stay 'transcriptions'", shared == "transcriptions")
        val quarantine = constString(code, "QUARANTINE_DIR")
        assertTrue(
            "quarantine dir must be a dedicated sibling (not the shared dir, not nested under it): was '$quarantine'",
            quarantine != shared && !quarantine.startsWith("$shared/")
        )
    }

    @Test
    fun `velavoice defines a dedicated quarantine dir sibling to transcriptions`() {
        val (_, velavoice) = storageSources()
        val code = codeOnly(velavoice)
        val shared = constString(code, "TRANSCRIPTIONS_DIR")
        assertTrue("shared dir const must stay 'transcriptions'", shared == "transcriptions")
        val quarantine = constString(code, "QUARANTINE_DIR")
        assertTrue(
            "quarantine dir must be a dedicated sibling (not the shared dir, not nested under it): was '$quarantine'",
            quarantine != shared && !quarantine.startsWith("$shared/")
        )
    }

    @Test
    fun `velaboard scanner stays scoped to the shared dir and never enters quarantine`() {
        val (velaboard, _) = storageSources()
        val code = codeOnly(velaboard)
        val scanner = extractFunBody(code, "fun getUnsyncedFiles(")
        assertTrue(
            "scanner must list the shared transcriptions dir",
            scanner.contains("getTranscriptionsDir(")
        )
        assertTrue(
            "scanner must never enter the quarantine dir (structural exclusion, not naming)",
            !scanner.contains("uarantine")
        )
    }

    @Test
    fun `velavoice scanner stays scoped to the shared dir and never enters quarantine`() {
        val (_, velavoice) = storageSources()
        val code = codeOnly(velavoice)
        val scanner = extractFunBody(code, "fun getUnsyncedFiles(")
        assertTrue(
            "scanner must list the shared transcriptions dir",
            scanner.contains("getTranscriptionsDir(")
        )
        assertTrue(
            "scanner must never enter the quarantine dir (structural exclusion, not naming)",
            !scanner.contains("uarantine")
        )
    }

    // ── save routes sensitive sessions to quarantine (both copies) ──

    @Test
    fun `velaboard sensitive save routes to quarantine and keeps shared dir untouched`() {
        val (velaboard, _) = storageSources()
        val code = codeOnly(velaboard)
        val body = extractFunBody(code, "fun save(")
        val gateIdx = body.indexOf("if (privacySensitive)")
        assertTrue("velaboard save() must still branch on the privacy verdict", gateIdx >= 0)
        val gateRegion = body.substring(gateIdx)
        assertTrue(
            "velaboard save(true) must route the session to quarantine storage",
            gateRegion.contains("uarantine")
        )
        assertTrue(
            "velaboard save(true) must keep the #134 failure signal (shared-dir save refused)",
            gateRegion.contains("return null")
        )
    }

    @Test
    fun `velavoice sensitive save routes to quarantine and keeps shared dir untouched`() {
        val (_, velavoice) = storageSources()
        val code = codeOnly(velavoice)
        val body = extractFunBody(code, "fun save(")
        val gateIdx = body.indexOf("if (privacySensitive)")
        assertTrue("velavoice save() must still branch on the privacy verdict", gateIdx >= 0)
        val gateRegion = body.substring(gateIdx)
        assertTrue(
            "velavoice save(true) must route the session to quarantine storage",
            gateRegion.contains("uarantine")
        )
        assertTrue(
            "velavoice save(true) must keep the #134 failure signal (shared-dir save refused)",
            gateRegion.contains("return null")
        )
    }

    // ── Every save stamps its verdict into the JSON ──

    @Test
    fun `velaboard save stamps the privacy verdict into the JSON`() {
        val (velaboard, _) = storageSources()
        val body = codeOnly(extractFunBody(codeOnly(velaboard), "fun save("))
        assertTrue(
            "velaboard save() must stamp its verdict so sync can re-verify pre-upload",
            body.contains("privacySensitive")
        )
        assertTrue(
            "velaboard save() must persist the verdict field in the JSON payload",
            body.contains("\"privacySensitive\"")
        )
    }

    @Test
    fun `velavoice save stamps the privacy verdict into the JSON`() {
        val (_, velavoice) = storageSources()
        val body = codeOnly(extractFunBody(codeOnly(velavoice), "fun save("))
        assertTrue(
            "velavoice save() must stamp its verdict so sync can re-verify pre-upload",
            body.contains("privacySensitive")
        )
        assertTrue(
            "velavoice save() must persist the verdict field in the JSON payload",
            body.contains("\"privacySensitive\"")
        )
    }

    // ── Scanner re-verifies the verdict fail-closed ──

    @Test
    fun `velaboard scanner re-verifies the JSON verdict and skips fail-closed`() {
        val (velaboard, _) = storageSources()
        val code = codeOnly(velaboard)
        val scanner = extractFunBody(code, "fun getUnsyncedFiles(")
        assertTrue(
            "velaboard scanner must re-verify the stored privacy verdict pre-upload",
            scanner.contains("privacySensitive") || scanner.contains("sUploadable") ||
                scanner.contains("Uploadable(")
        )
    }

    @Test
    fun `velavoice scanner re-verifies the JSON verdict and skips fail-closed`() {
        val (_, velavoice) = storageSources()
        val code = codeOnly(velavoice)
        val scanner = extractFunBody(code, "fun getUnsyncedFiles(")
        assertTrue(
            "velavoice scanner must re-verify the stored privacy verdict pre-upload",
            scanner.contains("privacySensitive") || scanner.contains("sUploadable") ||
                scanner.contains("Uploadable(")
        )
    }

    // ── Both sync loops re-check per file pre-upload (TOCTOU guard) ──

    @Test
    fun `velaboard syncToDrive re-verifies each file pre-upload and counts skips`() {
        val (sync, _) = syncSources()
        val code = codeOnly(sync)
        val loop = extractFunBody(code, "fun syncToDrive(")
        assertTrue(
            "velaboard syncToDrive must re-verify the verdict per file pre-upload (TOCTOU guard)",
            loop.contains("privacySensitive") || loop.contains("sUploadable") || loop.contains("Uploadable(")
        )
        assertTrue(
            "velaboard syncToDrive must count verdict-skipped files (counts-only observability)",
            loop.contains("skipped", ignoreCase = true)
        )
    }

    @Test
    fun `velavoice syncToDrive re-verifies each file pre-upload and counts skips`() {
        val (_, sync) = syncSources()
        val code = codeOnly(sync)
        val loop = extractFunBody(code, "fun syncToDrive(")
        assertTrue(
            "velavoice syncToDrive must re-verify the verdict per file pre-upload (TOCTOU guard)",
            loop.contains("privacySensitive") || loop.contains("sUploadable") || loop.contains("Uploadable(")
        )
        assertTrue(
            "velavoice syncToDrive must count verdict-skipped files (counts-only observability)",
            loop.contains("skipped", ignoreCase = true)
        )
    }
}
