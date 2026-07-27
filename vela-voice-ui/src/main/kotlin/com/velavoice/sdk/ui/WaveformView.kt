package com.velavoice.sdk.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
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
    private val maxBarCount = 24

    fun addAmplitude(amp: Float) {
        amplitudes.add(amp)
        if (amplitudes.size > maxBarCount) {
            amplitudes.removeAt(0)
        }
        invalidate()
    }

    fun clear() {
        amplitudes.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val centerY = h / 2f
        val spacing = w / maxBarCount
        val barWidth = spacing * 0.6f
        paint.strokeWidth = barWidth

        // Catppuccin Waveform colors: Latte: #b4befe, Mocha: #89b4fa
        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        paint.color = Color.parseColor(if (isDark) "#89b4fa" else "#b4befe")

        // Draw symmetrically growing center
        val startIdx = (maxBarCount - amplitudes.size) / 2
        for (i in 0 until amplitudes.size) {
            val amp = amplitudes[i]
            val x = (startIdx + i) * spacing + spacing / 2f
            // Scale amplitude to fit view height (amp is between 0 and 1)
            val barHeight = Math.max(10f, amp * h * 0.8f)
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
        }
    }
}
