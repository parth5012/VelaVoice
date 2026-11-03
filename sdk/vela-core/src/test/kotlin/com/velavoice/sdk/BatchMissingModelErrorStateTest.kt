// SPDX-License-Identifier: GPL-3.0-only
package com.velavoice.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * F13 (map #212 ticket #244): the VAS batch path used to fabricate canned
 * demo strings ('Hello, testing Vela Voice floating transcription overlay.'
 * / 'Thank you for choosing Vela Voice. A longer offline transcription...')
 * into the user's editor AND save them as real transcriptions whenever no
 * local model was available. The right behavior is the streaming path's
 * missing-model error state: insert nothing, persist nothing, surface the
 * error message.
 *
 * Source-contract guard over the native services, which cannot be
 * instantiated in unit tests (same style as ServiceQuarantinePr138BindingTest).
 */
class BatchMissingModelErrorStateTest {

    private fun vasSource(): String {
        val root = findRepoRoot()
        val vas = File(root, "velavoice app/src/native/VoiceAccessibilityService.kt")
        if (!vas.isFile) fail("VoiceAccessibilityService.kt not found under $root")
        return vas.readText()
    }

    private fun findRepoRoot(): File {
        var dir: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var depth = 0
        while (depth++ < 8) {
            if (File(dir, "velavoice app").isDirectory && File(dir, "sdk/vela-core").isDirectory) {
                return dir
            }
            val parent = dir.parentFile ?: break
            dir = parent
        }
        fail("repo root (containing 'velavoice app' + 'sdk/vela-core') not found above user.dir")
        throw IllegalStateException("unreachable")
    }

    @Test
    fun batchPathHasNoCannedTranscripts() {
        val vas = vasSource()
        assertFalse(
            "VAS must never fabricate the 1-2 second fallback transcript",
            vas.contains("Hello, testing Vela Voice floating transcription overlay.")
        )
        assertFalse(
            "VAS must never fabricate the longer fallback transcript",
            vas.contains("Thank you for choosing Vela Voice. A longer offline transcription")
        )
    }

    @Test
    fun batchPathSurfacesMissingModelErrorState() {
        val vas = vasSource()
        assertTrue(
            "missing-model batch path must surface the 'Model missing' error state",
            vas.contains("Model missing — open Vela settings")
        )
    }

    @Test
    fun batchErrorStateRoutesThroughTheGatedStatusPath() {
        val vas = vasSource()
        // The no-model branch reaches the user ONLY through the errorMessage →
        // statusText gate (nothing inserted/saved while rawTranscript is empty).
        val missingModelIdx = vas.indexOf("Model missing — open Vela settings")
        if (missingModelIdx < 0) fail("missing-model error state not present")
        assertTrue(
            "the error string must be assigned to errorMessage, not injected as transcript text",
            vas.lineSequence().any {
                it.contains("Model missing — open Vela settings") && it.contains("errorMessage")
            }
        )
        assertTrue(
            "statusText error gate must exist after the transcription block",
            vas.substring(missingModelIdx).contains("statusText.text = errorMessage")
        )
        assertTrue(
            "insert must remain gated on non-empty transcript",
            vas.contains("if (finalTranscript.isNotEmpty()) {")
        )
        assertTrue(
            "save must remain gated on non-empty transcript",
            vas.contains("rawTranscript.isNotEmpty()")
        )
    }
}
