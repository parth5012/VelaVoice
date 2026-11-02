package com.velavoice.sdk

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Ticket #236 / Finding F12:
 * VAS clipboard restore around paste.
 * Non-sensitive batch inserts go through ACTION_PASTE.
 * Prior clipboard content must be restored after transcript paste.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [android.os.Build.VERSION_CODES.P])
class VasClipboardRestoreTest {

    @Test
    fun `prior clipboard content restored after transcript paste`() {
        val context = RuntimeEnvironment.getApplication()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // User had copied a password
        val priorClip = ClipData.newPlainText("password", "secret-password-123")
        cm.setPrimaryClip(priorClip)
        assertEquals("secret-password-123", cm.primaryClip?.getItemAt(0)?.text?.toString())

        var pasteExecuted = false
        val tempClip = ClipData.newPlainText("transcription", "Hello world transcript")

        performWithClipboardRestore(cm, tempClip) {
            pasteExecuted = true
            // While pasteAction is executing, clipboard has the transcript
            assertEquals("Hello world transcript", cm.primaryClip?.getItemAt(0)?.text?.toString())
        }

        assertTrue("Paste action should have been called", pasteExecuted)
        // After pasteAction completes, prior clipboard is restored
        assertEquals("secret-password-123", cm.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `clipboard restored even if paste action throws`() {
        val context = RuntimeEnvironment.getApplication()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val priorClip = ClipData.newPlainText("link", "https://example.com")
        cm.setPrimaryClip(priorClip)

        val tempClip = ClipData.newPlainText("transcription", "Crash test")
        try {
            performWithClipboardRestore(cm, tempClip) {
                throw RuntimeException("Simulated paste failure")
            }
            fail("Expected exception was not thrown")
        } catch (e: RuntimeException) {
            assertEquals("Simulated paste failure", e.message)
        }

        assertEquals("https://example.com", cm.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `clipboard cleared when previously empty`() {
        val context = RuntimeEnvironment.getApplication()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.clearPrimaryClip()

        val tempClip = ClipData.newPlainText("transcription", "Temporary transcript")
        performWithClipboardRestore(cm, tempClip) {
            assertEquals("Temporary transcript", cm.primaryClip?.getItemAt(0)?.text?.toString())
        }

        val hasClip = try {
            cm.hasPrimaryClip() && (cm.primaryClip?.itemCount ?: 0) > 0
        } catch (e: Exception) {
            false
        }
        assertFalse("Clipboard should be cleared when previously empty", hasClip)
    }

    @Test
    fun `VAS insertText uses performWithClipboardRestore`() {
        val root = findRepoRoot()
        val vasFile = File(root, "velavoice app/src/native/VoiceAccessibilityService.kt")
        assertTrue("VoiceAccessibilityService.kt must exist", vasFile.isFile)
        val vasSource = vasFile.readText()

        assertTrue(
            "VAS must import or use performWithClipboardRestore",
            vasSource.contains("performWithClipboardRestore")
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
        fail("repo root (containing 'velavoice app' + 'sdk/vela-core') not found above user.dir")
        throw IllegalStateException("unreachable")
    }
}
