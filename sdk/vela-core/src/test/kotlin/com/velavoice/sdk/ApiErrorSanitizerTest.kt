package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ticket #238 / Finding F37:
 * Provider error bodies surfaced to UI/toasts/logs capped to <= 200 sanitized chars.
 */
class ApiErrorSanitizerTest {

    @Test
    fun `10KB error body surfaces as at most 200 sanitized chars`() {
        val large10KbBody = "Error: Database connection failed. ".repeat(300) // ~10,500 chars
        assertTrue(large10KbBody.length > 10000)

        val sanitized = sanitizeApiErrorBody(large10KbBody)

        assertTrue(
            "Sanitized error body must be <= 200 chars, was ${sanitized.length}",
            sanitized.length <= 200
        )
        assertTrue(sanitized.startsWith("Error: Database connection failed."))
    }

    @Test
    fun `strips non-printable and control characters`() {
        val dirty = "Error\u0000code:\u001B[31m500\u001B[0m\r\n\tMessage with \u0007 bell"
        val sanitized = sanitizeApiErrorBody(dirty)

        assertTrue("Must not contain NUL byte", !sanitized.contains('\u0000'))
        assertTrue("Must not contain ESC byte", !sanitized.contains('\u001B'))
        assertTrue("Must not contain BEL byte", !sanitized.contains('\u0007'))
        assertTrue("Must not contain raw newlines", !sanitized.contains('\r') && !sanitized.contains('\n'))
        assertTrue("Sanitized error must be <= 200 chars", sanitized.length <= 200)
    }

    @Test
    fun `handles null or blank error body`() {
        assertEquals("Unknown error", sanitizeApiErrorBody(null))
        assertEquals("Unknown error", sanitizeApiErrorBody(""))
        assertEquals("Unknown error", sanitizeApiErrorBody("   \t\n  "))
    }

    @Test
    fun `preserves valid printable characters and unicode`() {
        val normal = "Bad Request: model 'whisper-large' is not supported"
        assertEquals(normal, sanitizeApiErrorBody(normal))

        val unicode = "Erreur: clé d'API invalide ou expirée"
        assertEquals(unicode, sanitizeApiErrorBody(unicode))
    }

    @Test
    fun `VAS transcribeWithApi uses sanitizeApiErrorBody`() {
        val root = findRepoRoot()
        val vasFile = File(root, "velavoice app/src/native/VoiceAccessibilityService.kt")
        assertTrue("VoiceAccessibilityService.kt must exist", vasFile.isFile)
        val vasSource = vasFile.readText()

        assertTrue(
            "VAS must import or use sanitizeApiErrorBody in transcribeWithApi error path",
            vasSource.contains("sanitizeApiErrorBody")
        )
    }

    private fun findRepoRoot(): File {
        var dir: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (File(dir, "velavoice app").isDirectory && File(dir, "sdk/vela-core").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return dir
        }
        throw IllegalStateException("repo root not found above user.dir")
    }
}
