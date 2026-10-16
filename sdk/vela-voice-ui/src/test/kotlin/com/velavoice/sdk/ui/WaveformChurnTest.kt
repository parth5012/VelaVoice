package com.velavoice.sdk.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Map #105 ticket #112: stop the waveform/redraw churn.
 *
 * - Hot path ([WaveformView.addAmplitude], driven per mic-capture chunk) must not
 *   schedule redraws while the pane is hidden (parent GONE / detached): hidden
 *   invalidate count == 0.
 * - `onDraw` must allocate nothing (reusable [Paint] field, no per-frame objects).
 * - Session-end [WaveformView.clear] must drop the per-session amplitude cache
 *   (audit correction: no precomputed `Bitmap` cache exists on this view — the
 *   `amplitudes` list IS the per-session cache, so `clear()` is the drop site).
 * - Detach must drop queued amplitude posts so a dead pane never redraws.
 */
@RunWith(RobolectricTestRunner::class)
class WaveformChurnTest {

    /** Counts framework redraw requests without changing draw behavior. */
    private class CountingWaveformView(context: android.content.Context) : WaveformView(context) {
        var invalidateCount = 0
        override fun invalidate() {
            invalidateCount++
            super.invalidate()
        }
    }

    private fun attachedView(): Triple<Activity, FrameLayout, CountingWaveformView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        val view = CountingWaveformView(activity)
        parent.addView(view, FrameLayout.LayoutParams(360, 100))
        activity.setContentView(parent)
        return Triple(activity, parent, view)
    }

    private fun amplitudesOf(view: WaveformView): ArrayList<*> {
        val field = WaveformView::class.java.getDeclaredField("amplitudes")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(view) as ArrayList<*>
    }

    @Test
    fun `hidden pane addAmplitude schedules zero invalidates`() {
        val (_, parent, view) = attachedView()
        parent.visibility = View.GONE
        view.addAmplitude(0.5f)
        view.addAmplitude(0.8f)
        shadowOf(RuntimeEnvironment.getApplication().mainLooper).idle()
        assertEquals(0, view.invalidateCount)
    }

    @Test
    fun `detached pane addAmplitude schedules zero invalidates`() {
        val context = RuntimeEnvironment.getApplication()
        val view = CountingWaveformView(context)
        view.addAmplitude(0.5f)
        assertEquals(0, view.invalidateCount)
    }

    @Test
    fun `visible pane addAmplitude still schedules redraws`() {
        val (_, _, view) = attachedView()
        assertTrue(view.isShown)
        view.addAmplitude(0.5f)
        assertTrue(view.invalidateCount >= 1)
    }

    @Test
    fun `onDraw reuses field objects and allocates nothing per frame`() {
        val context = RuntimeEnvironment.getApplication()
        val view = WaveformView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 360, 100)
        repeat(36) { view.addAmplitude(0.5f) }

        val paintField = WaveformView::class.java.getDeclaredField("paint")
        paintField.isAccessible = true
        val paintBefore = paintField.get(view) as Paint
        val amplitudesBefore = amplitudesOf(view)

        // ~10 s of recording at 60 fps.
        val bitmap = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        repeat(600) { view.draw(canvas) }

        assertSame(paintBefore, paintField.get(view))
        assertSame(amplitudesBefore, amplitudesOf(view))
        assertEquals(36, amplitudesOf(view).size)
        bitmap.recycle()
    }

    @Test
    fun `clear drops the per-session amplitude cache`() {
        val context = RuntimeEnvironment.getApplication()
        val view = CountingWaveformView(context)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 360, 100)
        repeat(10) { view.addAmplitude(0.5f) }
        assertEquals(10, amplitudesOf(view).size)

        view.clear()

        assertTrue(amplitudesOf(view).isEmpty())
        // Drawing the dropped (empty) state must not crash.
        val bitmap = Bitmap.createBitmap(360, 100, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }

    @Test
    fun `detach drops queued amplitude posts`() {
        val (activity, parent, view) = attachedView()
        assertTrue(view.isShown)

        // Mic-thread style post queued while visible...
        view.post { view.addAmplitude(0.9f) }
        // ...but the pane detaches before the main thread runs it.
        parent.removeView(view)
        shadowOf(activity.mainLooper).idle()

        assertTrue(amplitudesOf(view).isEmpty())
        assertEquals(0, view.invalidateCount)
    }

    @Test
    fun `view holds no precomputed bitmap cache`() {
        val bitmapFields = WaveformView::class.java.declaredFields
            .filter { it.type == Bitmap::class.java }
        assertTrue(
            "WaveformView must not cache Bitmaps (none to recycle): found $bitmapFields",
            bitmapFields.isEmpty()
        )
    }
}
