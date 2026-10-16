package helium314.keyboard.keyboard

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Map #105 ticket #112: stop the waveform/redraw churn (velaboard Yaps pane).
 *
 * Robolectric honesty note: looper idle never runs a draw traversal, so
 * `onDraw` does NOT execute on its own here. Frame-loop scheduling is asserted
 * through invalidate counts (every scheduled frame eventually calls
 * `invalidate()`), while draw math is driven with explicit `view.draw(canvas)`
 * calls after measure/layout.
 *
 * - [YapsWaveformView.addAmplitude] (data path) must schedule nothing while hidden:
 *   hidden invalidate count == 0.
 * - Smoothing math must converge without new data (the condition that lets the
 *   loop stop rescheduling), and no frames are scheduled after detach/hide.
 * - [TranscribingDotsView.stopAnimating] must remove the pending frame (no trailing
 *   invalidate after stop) and [TranscribingDotsView.startAnimating] must be idempotent.
 *
 * NOTE: never use bare `idle()` while a loop is alive — a self-rescheduling chain
 * makes it hang. All waits below use bounded [ShadowLooper.idleFor].
 */
@RunWith(RobolectricTestRunner::class)
class YapsWaveformChurnTest {

    private open class DrawCountingWaveformView(context: android.content.Context) :
        YapsWaveformView(context) {
        var invalidateCount = 0
        var postCount = 0
        var drawCount = 0
        override fun invalidate() {
            invalidateCount++
            super.invalidate()
        }
        override fun postInvalidateDelayed(delayMilliseconds: Long) {
            postCount++
            super.postInvalidateDelayed(delayMilliseconds)
        }
        public override fun onDraw(canvas: Canvas) {
            drawCount++
            super.onDraw(canvas)
        }
    }

    private open class DrawCountingDotsView(context: android.content.Context) :
        TranscribingDotsView(context) {
        var invalidateCount = 0
        var postCount = 0
        override fun invalidate() {
            invalidateCount++
            super.invalidate()
        }
        override fun postInvalidateDelayed(delayMilliseconds: Long) {
            postCount++
            super.postInvalidateDelayed(delayMilliseconds)
        }
        public override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
        }
    }

    private fun idleForMs(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    /**
     * Drive one frame explicitly: traversal-driven draws do not run under
     * Robolectric, so loop rescheduling is observed by counting
     * `postInvalidateDelayed` schedules across a direct `draw(Canvas)` call.
     */
    private fun drawOnce(view: View) {
        val bitmap = Bitmap.createBitmap(360, 60, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    private fun attachedWaveform(): Triple<Activity, FrameLayout, DrawCountingWaveformView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        val view = DrawCountingWaveformView(activity)
        parent.addView(view, FrameLayout.LayoutParams(360, 60))
        activity.setContentView(parent)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 360, 60)
        return Triple(activity, parent, view)
    }

    private fun attachedDots(): Triple<Activity, FrameLayout, DrawCountingDotsView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity)
        val view = DrawCountingDotsView(activity)
        parent.addView(view, FrameLayout.LayoutParams(360, 60))
        activity.setContentView(parent)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, 360, 60)
        return Triple(activity, parent, view)
    }

    private fun floatArrayField(view: View, name: String): FloatArray {
        val field = view.javaClass.superclass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(view) as FloatArray
    }

    @Test
    fun `hidden waveform addAmplitude schedules zero invalidates`() {
        val context = RuntimeEnvironment.getApplication()
        val view = DrawCountingWaveformView(context)
        view.addAmplitude(0.5f)
        view.addAmplitude(0.8f)
        idleForMs(200)
        assertEquals(0, view.invalidateCount)
    }

    @Test
    fun `visible waveform addAmplitude still schedules redraws`() {
        val (_, _, view) = attachedWaveform()
        assertTrue(view.isShown)
        view.addAmplitude(0.5f)
        assertTrue(view.invalidateCount >= 1)
    }

    @Test
    fun `waveform smoothing converges and the loop goes quiet`() {
        val (_, _, view) = attachedWaveform()
        repeat(5) { view.addAmplitude(0.6f) }
        val bitmap = Bitmap.createBitmap(360, 60, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // ~2 s of frames at 60 fps, driven explicitly (no Robolectric traversal).
        repeat(120) {
            view.draw(canvas)
            idleForMs(16)
        }
        val bars = floatArrayField(view, "barHeights")
        val targets = floatArrayField(view, "targetHeights")
        val maxGap = bars.indices.maxOf { i -> Math.abs(targets[i] - bars[i]) }
        assertTrue(
            maxGap <= 0.5f,
            "smoothing must converge (|target-bar| <= 0.5, was $maxGap)"
        )
        // Converged: no further frames scheduled without new amplitudes.
        idleForMs(500)
        val settled = view.invalidateCount
        idleForMs(2000)
        assertEquals(settled, view.invalidateCount)
        bitmap.recycle()
    }

    @Test
    fun `detached waveform schedules nothing further`() {
        val (_, parent, view) = attachedWaveform()
        repeat(5) { view.addAmplitude(0.6f) }
        idleForMs(500)
        parent.removeView(view)
        idleForMs(200)
        val countAtDetach = view.invalidateCount
        idleForMs(1000)
        assertEquals(countAtDetach, view.invalidateCount)
    }

    @Test
    fun `dots start schedules frames while visible`() {
        val (_, _, view) = attachedDots()
        assertTrue(view.isShown)
        view.startAnimating()
        idleForMs(300)
        assertTrue(view.invalidateCount >= 1)
    }

    @Test
    fun `dots stop kills the pulse loop`() {
        val (_, _, view) = attachedDots()
        view.startAnimating()
        idleForMs(300)
        assertTrue(view.invalidateCount >= 1)

        view.stopAnimating()
        idleForMs(200)
        val countAtStop = view.invalidateCount
        idleForMs(500)
        assertEquals(countAtStop, view.invalidateCount)
    }

    @Test
    fun `dots double start does not stack pulse chains`() {
        val (_, _, view) = attachedDots()
        view.startAnimating()
        view.startAnimating()
        // Idempotent start: exactly one scheduled frame, not two (synchronous,
        // no traversal needed — deterministic pre/post discriminator).
        assertEquals(1, view.postCount)
        idleForMs(200)
        view.stopAnimating()
        idleForMs(200)
        val countAtStop = view.invalidateCount
        // A single stop must kill the animation: with stacked chains the second
        // chain would keep scheduling frames after the stop.
        idleForMs(500)
        assertEquals(countAtStop, view.invalidateCount)
    }

    @Test
    fun `dots restart after stop still animates`() {
        val (_, _, view) = attachedDots()
        view.startAnimating()
        idleForMs(200)
        view.stopAnimating()
        idleForMs(200)
        val countAfterStop = view.invalidateCount

        view.startAnimating()
        idleForMs(300)
        assertTrue(view.invalidateCount > countAfterStop)
    }

    @Test
    fun `dots hidden while animating schedules nothing further`() {
        val (_, parent, view) = attachedDots()
        view.startAnimating()
        idleForMs(300)
        assertTrue(view.invalidateCount >= 1)

        parent.visibility = View.GONE
        // Drain any frame already posted before the hide.
        idleForMs(200)
        val countAtHide = view.invalidateCount
        idleForMs(1000)
        assertEquals(countAtHide, view.invalidateCount)
    }

    @Test
    fun `hidden waveform draw schedules no follow-up frame`() {
        val (_, parent, view) = attachedWaveform()
        view.addAmplitude(0.6f)
        drawOnce(view)
        // Unconverged bars while visible: exactly one follow-up frame scheduled.
        assertEquals(1, view.postCount)

        parent.visibility = View.GONE
        drawOnce(view)
        // Hidden: the smoothing loop must not reschedule (deterministic —
        // traversals never run here, so only the guard can stop the re-post).
        assertEquals(1, view.postCount)
    }

    @Test
    fun `detached waveform draw schedules no follow-up frame`() {
        val (_, parent, view) = attachedWaveform()
        view.addAmplitude(0.6f)
        drawOnce(view)
        assertEquals(1, view.postCount)

        parent.removeView(view)
        drawOnce(view)
        assertEquals(1, view.postCount)
    }

    @Test
    fun `dots hidden draw schedules no follow-up frame`() {
        val (_, parent, view) = attachedDots()
        view.startAnimating()
        drawOnce(view)
        // Start scheduled one frame; the explicit draw scheduled the next.
        assertEquals(2, view.postCount)

        parent.visibility = View.GONE
        drawOnce(view)
        assertEquals(2, view.postCount)
    }
}
