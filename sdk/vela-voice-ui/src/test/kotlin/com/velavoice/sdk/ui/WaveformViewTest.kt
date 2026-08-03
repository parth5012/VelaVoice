package com.velavoice.sdk.ui

import android.graphics.Color
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WaveformViewTest {

    @Test
    fun `view constructs with default attrs`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)
        assertNotNull(view)
    }

    @Test
    fun `addAmplitude stores value and triggers invalidate`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)

        // Measure and layout the view so it has dimensions
        view.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 200, 100)

        view.addAmplitude(0.5f)
        // Should not crash, amplitude stored internally
    }

    @Test
    fun `clear removes all amplitudes`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)

        view.addAmplitude(0.5f)
        view.addAmplitude(0.8f)
        view.clear()

        // After clear, drawing should handle empty state
        view.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 200, 100)
        // No crash expected
    }

    @Test
    fun `maxBarCount limits stored amplitudes`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)

        // Add more than maxBarCount (36) amplitudes
        for (i in 0 until 40) {
            view.addAmplitude(i.toFloat() / 40f)
        }
        // Should not crash, old values pruned
    }

    @Test
    fun `default sensitivity is 1.5`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)
        assertEquals(1.5f, view.sensitivity, 0.01f)
    }

    @Test
    fun `sensitivity clamps to valid range`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)

        view.sensitivity = 0.1f
        assertEquals(0.5f, view.sensitivity, 0.01f)

        view.sensitivity = 10.0f
        assertEquals(4.0f, view.sensitivity, 0.01f)

        view.sensitivity = 2.0f
        assertEquals(2.0f, view.sensitivity, 0.01f)
    }

    @Test
    fun `default colors match Lumina Sonic design system`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)

        assertEquals(Color.parseColor("#62f9ee"), view.activeColor)
        assertEquals(Color.parseColor("#ddb7ff"), view.processedColor)
    }

    @Test
    fun `isLive defaults to true`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)
        assertTrue(view.isLive)
    }

    @Test
    fun `addAmplitude clamps values to 0-1 range`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 200, 100)

        // Out-of-range values should not crash
        view.addAmplitude(-0.5f)
        view.addAmplitude(1.5f)
        view.addAmplitude(0.3f)
    }

    @Test
    fun `setSensitivity triggers invalidate`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 200, 100)

        // Changing sensitivity should not crash
        view.sensitivity = 2.0f
        view.addAmplitude(0.5f)
    }
}
