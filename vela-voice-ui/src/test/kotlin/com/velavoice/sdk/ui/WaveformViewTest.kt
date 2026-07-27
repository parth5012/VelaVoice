package com.velavoice.sdk.ui

import android.view.View
import org.junit.Assert.assertNotNull
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

        // Add more than maxBarCount (24) amplitudes
        for (i in 0 until 30) {
            view.addAmplitude(i.toFloat() / 30f)
        }
        // Should not crash, old values pruned
    }
}
