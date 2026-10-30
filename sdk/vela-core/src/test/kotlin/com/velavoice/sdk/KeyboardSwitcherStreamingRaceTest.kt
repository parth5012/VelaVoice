package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Wayfinder map #209 ticket #224 (F22 MEDIUM): a synchronous start failure in
 * `KeyboardSwitcher.startStreamingSession` (session.start() throws after the
 * session has already marked itself finished / delivered onError) must never
 * publish the dead session: the ready-post must check `session.isActive()`
 * before assigning velaStreamingSession / enabling Stop / showing
 * "Recording..." / starting the 1s timer; and `onStreamingFinished` must tear
 * down by session identity, not by field, so a stale session's callback can
 * never release a newer session's engine or restore the UI over it.
 *
 * Source-contract guard (velaboard module's own Robolectric suite covers the
 * behavioral halves it can reach; the pending→ready race needs main-looper
 * ordering this guard pins).
 */
class KeyboardSwitcherStreamingRaceTest {

    private fun switcherSource(): String {
        var dir: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir,
                "velaboard/app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java")
            if (file.isFile) return file.readText()
            dir = dir.parentFile ?: dir
        }
        fail("KeyboardSwitcher.java not found above user.dir")
        throw IllegalStateException("unreachable")
    }

    /** Extract the body of `fun <name>(` / `void <name>(` with balanced braces. */
    private fun extractFunBody(source: String, name: String): String {
        val idx = source.indexOf(name)
        if (idx < 0) fail("$name not found")
        val open = source.indexOf('{', idx)
        if (open < 0) fail("opening brace for $name not found")
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
        fail("unbalanced braces for $name")
        throw IllegalStateException("unreachable")
    }

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    @Test
    fun `ready-post publishes only an active session — sync start failure must not arm UI`() {
        val vims = codeOnly(switcherSource())
        // Pull the worker-try tail: everything between session.start() and
        // the catch, which contains the ready-post lambda.
        val fn = codeOnly(extractFunBody(vims, "private void startStreamingSession"))
        val tail = fn.substring(fn.indexOf("session.start();"))
        val ready = tail.substring(0, tail.indexOf("catch (final Exception e)"))
        // The ready-post must refuse an already-finished session: publishing
        // it re-enabled Stop, showed Recording... and started the eternal
        // 1 Hz timer on a detached dead session (F22/#224).
        assertTrue(
            "The ready-post must guard on session.isActive() (or equivalent " +
                "finished check) BEFORE assigning velaStreamingSession (F22)",
            ready.contains("session.isActive()")
        )
        assertTrue(
            "A dead session must be torn down in the ready-post, not published",
            ready.contains("teardownVelaEngine(null, session)")
        )
        // Order matters: the isActive check must come before the publish.
        assertTrue(
            "Guard must precede the field assign",
            ready.indexOf("session.isActive()") < ready.indexOf("velaStreamingSession = session")
        )
    }

    @Test
    fun `onStreamingFinished tears down by session identity not by field`() {
        val vims = codeOnly(switcherSource())
        val finished = codeOnly(extractFunBody(vims, "public void onStreamingFinished"))
        assertTrue(
            "onStreamingFinished must compare velaStreamingSession against the " +
                "callback's own session identity — a stale session's callback " +
                "must never release a newer session's engine (F22)",
            finished.contains("velaStreamingSession == session") ||
                finished.contains("velaStreamingSession != session") ||
                finished.contains("velaStreamingSession == selfRef.get()")
        )
        // The UI restore (keyboard / yaps stop) belongs inside the identity
        // guard too: a stale callback must not stomp a newer session's UI.
        val guardIdx = finished.indexOf("velaStreamingSession")
        val restoreIdx = finished.indexOf("showMainKeyboard")
        val yapsIdx = finished.indexOf("stopActiveRecording")
        if (restoreIdx >= 0 || yapsIdx >= 0) {
            assertTrue(
                "UI restore in onStreamingFinished must be guarded by the identity check",
                guardIdx in 0 until (restoreIdx.coerceAtLeast(yapsIdx))
            )
        }
    }

    @Test
    fun `start failure catch still recovers UI without publishing a session`() {
        val vims = codeOnly(switcherSource())
        // The catch INSIDE startStreamingSession (not another site's catch).
        val fn = codeOnly(extractFunBody(vims, "private void startStreamingSession"))
        val catchBody = codeOnly(fn.substring(fn.indexOf("catch (final Exception e)")))
        assertTrue(
            "The start-failure catch must keep clearing mIsVelaLoading",
            catchBody.contains("mIsVelaLoading = false")
        )
        assertTrue(
            "The start-failure catch must surface the error toast",
            catchBody.contains("Failed to start voice streaming")
        )
        assertTrue(
            "The start-failure catch must restore the main keyboard (or stop " +
                "the yaps recording on that surface)",
            catchBody.contains("showMainKeyboard(latinIME)") &&
                catchBody.contains("stopActiveRecording()")
        )
    }
}
