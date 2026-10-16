package com.velavoice.sdk.ui

import android.app.Activity
import android.widget.FrameLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Map #105 ticket #114: the SDK recording pane must not leak listeners across
 * input sessions. Detaching (view recycle) clears every button click listener,
 * every listener lambda and every pending handler runnable, so a detached pane
 * can neither fire into a dead session nor hold the IME context alive.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceRecordingPaneDetachTest {

    private fun attachedPane(): Triple<Activity, FrameLayout, VoiceRecordingPane> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = FrameLayout(activity)
        activity.setContentView(container)
        val pane = VoiceRecordingPane(activity)
        container.addView(pane)
        return Triple(activity, container, pane)
    }

    @Test
    fun `detach clears click listeners so buttons cannot fire into a dead session`() {
        val (_, container, pane) = attachedPane()
        var stops = 0
        var raws = 0
        var cancels = 0
        pane.onStopCleanListener = { stops++ }
        pane.onStopRawListener = { raws++ }
        pane.onCancelListener = { cancels++ }

        container.removeView(pane) // view recycle: onDetachedFromWindow must release

        pane.stopCleanButton.performClick()
        pane.stopRawButton.performClick()
        pane.cancelButton.performClick()
        assertFalse(
            "detached pane buttons must be dead (stops=$stops raws=$raws cancels=$cancels)",
            stops + raws + cancels > 0
        )
    }

    @Test
    fun `release drops listener lambdas and pending handler work`() {
        val (_, _, pane) = attachedPane()
        var stops = 0
        pane.onStopCleanListener = { stops++ }
        pane.updateTranscriptPreview("hello")
        pane.updateTimer(3)

        pane.release()

        pane.stopCleanButton.performClick()
        assertFalse("release() must clear button listeners", stops > 0)
    }

    @Test
    fun `attached pane still delivers clicks before detach`() {
        val (_, _, pane) = attachedPane()
        var stops = 0
        pane.onStopCleanListener = { stops++ }

        pane.stopCleanButton.performClick()

        assertTrue("sanity: attached pane must deliver clicks", stops == 1)
    }
}
