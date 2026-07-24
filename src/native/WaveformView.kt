package com.velavoice.app

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
        color = Color.parseColor("#007bff") // Modern blue
        style = Paint.Style.FILL
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val amplitudes = ArrayList<Float>()
    private val maxBarCount = 40

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

        val startIdx = maxBarCount - amplitudes.size
        for (i in 0 until amplitudes.size) {
            val amp = amplitudes[i]
            val x = (startIdx + i) * spacing + spacing / 2f
            // Scale amplitude to fit view height (amp is between 0 and 1)
            val barHeight = Math.max(10f, amp * h * 0.8f)
            canvas.drawLine(x, centerY - barHeight / 2f, x, centerY + barHeight / 2f, paint)
        }
    }
}
