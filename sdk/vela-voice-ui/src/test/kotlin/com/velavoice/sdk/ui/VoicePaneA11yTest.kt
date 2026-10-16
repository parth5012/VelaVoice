package com.velavoice.sdk.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Map #105 ticket #115: voice-pane accessibility & i18n.
 *
 * - Every interactive pane element carries a correct contentDescription and is
 *   reachable in a sensible focus order (Stop Clean -> Stop Raw -> Cancel).
 * - The waveform is decorative (screen-reader noise out); the status line is a
 *   polite live region mirroring its text as its description.
 * - Every [VoiceStatus] resolves to a `strings.xml` entry — no hardcoded
 *   user-visible status text.
 */
@RunWith(RobolectricTestRunner::class)
class VoicePaneA11yTest {

    private fun attachedPane(): Triple<Activity, FrameLayout, VoiceRecordingPane> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        val pane = VoiceRecordingPane(activity)
        container.addView(pane)
        return Triple(activity, container, pane)
    }

    private fun drainMain() =
        shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `every pane button has a spoken description`() {
        val (_, _, pane) = attachedPane()
        for ((name, button) in listOf(
            "stopClean" to pane.stopCleanButton,
            "stopRaw" to pane.stopRawButton,
            "cancel" to pane.cancelButton
        )) {
            val cd = button.contentDescription?.toString().orEmpty()
            assertTrue("$name button must have a non-blank contentDescription", cd.isNotBlank())
        }
    }

    @Test
    fun `pane buttons form a stop-clean-first focus chain`() {
        val (_, _, pane) = attachedPane()
        assertTrue("stopClean must be focusable", pane.stopCleanButton.isFocusable)
        assertTrue("stopRaw must be focusable", pane.stopRawButton.isFocusable)
        assertTrue("cancel must be focusable", pane.cancelButton.isFocusable)
        assertEquals(
            "focus must move Stop Clean -> Stop Raw",
            pane.stopRawButton.id, pane.stopCleanButton.nextFocusForwardId
        )
        assertEquals(
            "focus must move Stop Raw -> Cancel",
            pane.cancelButton.id, pane.stopRawButton.nextFocusForwardId
        )
    }

    @Test
    fun `waveform is decorative and status is a polite live region`() {
        val (_, _, pane) = attachedPane()
        assertEquals(
            "waveform carries no semantic content; the status line announces state",
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            pane.waveformView.importantForAccessibility
        )
        assertEquals(
            View.ACCESSIBILITY_LIVE_REGION_POLITE,
            pane.statusText.accessibilityLiveRegion
        )
    }

    @Test
    fun `every voice status resolves to a string resource`() {
        val (activity, _, pane) = attachedPane()
        for (status in VoiceStatus.entries) {
            pane.setStatus(status)
            drainMain()
            val expected = activity.getString(status.stringResId)
            assertTrue(
                "$status must resolve to a non-blank strings.xml entry",
                expected.isNotBlank()
            )
            assertEquals("$status pane text must come from resources", expected, pane.statusText.text.toString())
            assertEquals(
                "$status description must mirror the announced status",
                expected, pane.statusText.contentDescription?.toString()
            )
        }
    }

    @Test
    fun `pane button labels come from string resources`() {
        val (activity, _, pane) = attachedPane()
        assertEquals(activity.getString(R.string.vela_voice_stop_clean), pane.stopCleanButton.text.toString())
        assertEquals(activity.getString(R.string.vela_voice_stop_raw), pane.stopRawButton.text.toString())
        assertEquals(activity.getString(R.string.vela_voice_cancel), pane.cancelButton.text.toString())
    }

    @Test
    fun `no hardcoded user-visible literals remain in the pane`() {
        val root = findRepoRoot()
        val f = File(root, "sdk/vela-voice-ui/src/main/kotlin/com/velavoice/sdk/ui/VoiceRecordingPane.kt")
        val code = f.readText()
        // Button labels and the initial status must reference R.string, not literals.
        val literals = Regex("""text\s*=\s*"(Stop Clean|Stop Raw|Cancel|Listening\.\.\.)"""")
            .findAll(code).toList()
        assertTrue(
            "pane user-visible text must come from strings.xml, found literals: " +
                literals.map { it.value },
            literals.isEmpty()
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
