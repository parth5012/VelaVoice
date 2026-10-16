package com.velavoice.sdk.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.File

/**
 * Map #105 ticket #115 (source contracts): the Expo-shell IME service has no
 * local resources, so every user-visible string it shows must flow through the
 * SDK pane's resource-backed status API — never a hardcoded literal assigned
 * straight to the status view.
 */
@RunWith(JUnit4::class)
class VimsStatusStringsTest {

    private fun vimsSource(): String {
        val root = findRepoRoot()
        val f = File(root, "velavoice app/src/native/VoiceInputMethodService.kt")
        if (!f.isFile) throw IllegalStateException("VoiceInputMethodService.kt not found under $root")
        return f.readText()
    }

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    @Test
    fun `VIMS assigns no hardcoded literal to the status view`() {
        val code = codeOnly(vimsSource())
        val hits = Regex("""statusText\.text\s*=\s*"""").findAll(code).toList()
        assertTrue(
            "VIMS must route status text through setStatus()/setStatusText() " +
                "(strings.xml-backed); hardcoded assignments found: ${hits.map { it.value }}",
            hits.isEmpty()
        )
    }

    @Test
    fun `VIMS routes state changes through the resource-backed status API`() {
        val code = codeOnly(vimsSource())
        assertTrue(
            "VIMS must call setStatus() for state changes",
            code.contains("setStatus(")
        )
        assertTrue(
            "VIMS streaming partials must use setStatusText() (silent, no per-tick announce)",
            code.contains("setStatusText(")
        )
    }

    @Test
    fun `VIMS mic button exposes a spoken description`() {
        val code = codeOnly(vimsSource())
        assertTrue(
            "voiceButton must set a contentDescription (emoji label alone misreads)",
            code.contains("voiceButton") && code.contains("contentDescription")
        )
    }

    private fun findRepoRoot(): File {
        var dir: File = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "velavoice app").isDirectory && File(dir, "sdk/vela-voice-ui").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return dir
        }
        throw IllegalStateException("repo root not found above ${System.getProperty("user.dir")}")
    }
}
