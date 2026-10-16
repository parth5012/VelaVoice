package com.velavoice.sdk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Live amplitude waveform (map #105 ticket #112 churn contract):
 *
 * - [paint] is a field initialized once; [onDraw] allocates nothing per frame.
 * - The hot path ([addAmplitude], driven per mic-capture chunk) only schedules a
 *   redraw while the view is actually shown ([isShown]: visible, attached, and all
 *   ancestors visible). Amplitudes posted from a torn-down session (pane GONE after
 *   `showKeyboardView`, or in-flight `post {}` runnables racing a detach) are still
 *   stored but never schedule hidden redraws — hidden invalidate count == 0.
 * - The per-session amplitude list IS the cached waveform data (audit correction:
 *   this view holds no precomputed `Bitmap`, so there is nothing to `recycle()`).
 *   [clear] drops it; it is idempotent, so session-end call sites cannot double-free.
 *
 * Design note: runnables posted from outside (`waveformView.post { addAmplitude }`
 * by the IME mic callback) cannot be enumerated through the public `View` API, so
 * detach does not attempt to cancel them — the [isShown] gate already strips their
 * only harmful effect (scheduling hidden redraws), and the stored value is capped
 * with the rest of the list.
 */
open class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val amplitudes = ArrayList<Float>()
    private val maxBarCount = 36

    /**
     * Sensitivity multiplier applied after the non-linear boost.
     * Higher values = taller, more responsive bars.
     * Default 1.5x provides a pronounced waveform for typical speech levels.
     */
    var sensitivity: Float = 1.5f
        set(value) {
            field = value.coerceIn(0.5f, 4.0f)
            invalidate()
        }

    /**
     * Color for live/active waveform bars.
     * Uses Lumina Sonic Neon Cyan (#62f9ee) per design system.
     */
    var activeColor: Int = Color.parseColor("#62f9ee")
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Color for processed/playback waveform bars.
     * Uses Lumina Sonic Electric Indigo (#ddb7ff) per design system.
     */
    var processedColor: Int = Color.parseColor("#ddb7ff")
        set(value) {
            field = value
            invalidate()
        }

    /** Whether this waveform is showing live (active) or processed audio */
    var isLive: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Set once the view has ever been attached: distinguishes a live pane torn
     * down mid-session (late mic posts must be dropped) from a view that was
     * never attached (unit-test / pre-attach staging, stores normally). */
    private var wasAttached = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wasAttached = true
    }

    fun addAmplitude(amp: Float) {
        // A pane detached mid-session is dead: drop late mic posts entirely
        // (removeCallbacks is best-effort — a runnable that already escaped
        // still lands here). Never-attached views store normally.
        if (wasAttached && !isAttachedToWindow) return
        amplitudes.add(amp.coerceIn(0f, 1f))
        if (amplitudes.size > maxBarCount) {
            amplitudes.removeAt(0)
        }
        requestRedraw()
    }

    fun clear() {
        amplitudes.clear()
        invalidate()
    }

    /**
     * Redraw only when the pane can actually be seen (ticket #112): a hidden view
     * stores the amplitude but schedules nothing. Rare one-shot paths ([clear],
     * property setters) still invalidate unconditionally so a re-shown pane can
     * never display stale bars.
     */
    private fun requestRedraw() {
        if (!isShown) return
        invalidate()
    }

    /**
     * Apply non-linear boost to make quiet sounds more visible.
     * Uses square-root curve: sqrt(0.01)=0.1, sqrt(0.1)=0.316, sqrt(0.5)=0.707
     * This dramatically amplifies low-level speech while preserving dynamics.
     */
    private fun boostAmplitude(raw: Float): Float {
        // Square root boost lifts quiet sounds
        val boosted = sqrt(raw.toDouble()).toFloat()
        // Apply sensitivity gain and clamp
        return min(1.0f, boosted * sensitivity)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val centerY = h / 2f
        val spacing = w / maxBarCount
        val barWidth = spacing * 0.65f
        paint.strokeWidth = barWidth

        // Use design system colors
        paint.color = if (isLive) activeColor else processedColor

        // Draw symmetrically growing center
        val startIdx = (maxBarCount - amplitudes.size) / 2
        for (i in 0 until amplitudes.size) {
            val amp = amplitudes[i]
            val x = (startIdx + i) * spacing + spacing / 2f

            // Apply non-linear boost for more visible amplitude
            val boostedAmp = boostAmplitude(amp)
            // Scale to fill 90% of view height with a higher minimum floor
            val barHeight = max(14f, boostedAmp * h * 0.9f)

            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
        }
    }
}
