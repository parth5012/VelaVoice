package com.velavoice.sdk.ui

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoiceRecordingPaneTest {

    @Test
    fun `pane constructs with all child views`() {
        val context = RuntimeEnvironment.getApplication()
        val pane = VoiceRecordingPane(context)
        assertNotNull(pane)
        assertNotNull(pane.statusText)
        assertNotNull(pane.waveformView)
        assertNotNull(pane.stopCleanButton)
        assertNotNull(pane.stopRawButton)
        assertNotNull(pane.cancelButton)
    }

    @Test
    fun `pane initial status text is set`() {
        val context = RuntimeEnvironment.getApplication()
        val pane = VoiceRecordingPane(context)
        assertNotNull(pane.statusText)
    }

    @Test
    fun `stop clean button click triggers listener`() {
        val context = RuntimeEnvironment.getApplication()
        val pane = VoiceRecordingPane(context)
        var clicked = false
        pane.onStopCleanListener = { clicked = true }
        pane.stopCleanButton.performClick()
        assert(clicked)
    }

    @Test
    fun `stop raw button click triggers listener`() {
        val context = RuntimeEnvironment.getApplication()
        val pane = VoiceRecordingPane(context)
        var clicked = false
        pane.onStopRawListener = { clicked = true }
        pane.stopRawButton.performClick()
        assert(clicked)
    }

    @Test
    fun `cancel button click triggers listener`() {
        val context = RuntimeEnvironment.getApplication()
        val pane = VoiceRecordingPane(context)
        var clicked = false
        pane.onCancelListener = { clicked = true }
        pane.cancelButton.performClick()
        assert(clicked)
    }
}
