package com.velavoice.sdk.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class VoiceRecordingPane(context: Context) : LinearLayout(context) {

    val statusText: TextView
    val waveformView: WaveformView
    val stopCleanButton: Button
    val stopRawButton: Button
    val cancelButton: Button

    var onStopCleanListener: (() -> Unit)? = null
    var onStopRawListener: (() -> Unit)? = null
    var onCancelListener: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        val density = context.resources.displayMetrics.density

        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val baseBgColor = Color.parseColor(if (isDark) "#1e1e2e" else "#eff1f5")
        val crustColor = Color.parseColor(if (isDark) "#11111b" else "#dce0e8")
        val mainTextColor = Color.parseColor(if (isDark) "#cdd6f4" else "#4c4f69")
        val stopCleanColor = Color.parseColor(if (isDark) "#a6e3a1" else "#40a02b")
        val stopRawColor = Color.parseColor(if (isDark) "#fab387" else "#df8e1d")
        val cancelColor = Color.parseColor(if (isDark) "#f38ba8" else "#d20f39")

        val stopCleanTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")
        val stopRawTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")
        val cancelTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")

        val sansSerifMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val sansSerifLight = Typeface.create("sans-serif-light", Typeface.NORMAL)

        setBackgroundColor(baseBgColor)
        setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), 0)

        statusText = TextView(context).apply {
            text = "Listening..."
            textSize = 14f
            typeface = sansSerifLight
            setTextColor(mainTextColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        waveformView = WaveformView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (100 * density).toInt()).apply {
                bottomMargin = (16 * density).toInt()
            }
        }

        val buttonContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            setBackgroundColor(crustColor)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val btnLayoutParams = LayoutParams(0, (48 * density).toInt(), 1f).apply {
            leftMargin = (4 * density).toInt()
            rightMargin = (4 * density).toInt()
        }

        stopCleanButton = Button(context).apply {
            styleButton(this, "Stop Clean", stopCleanColor, stopCleanTextColor, density, sansSerifMedium)
            layoutParams = btnLayoutParams
            setOnClickListener { onStopCleanListener?.invoke() }
        }

        stopRawButton = Button(context).apply {
            styleButton(this, "Stop Raw", stopRawColor, stopRawTextColor, density, sansSerifMedium)
            layoutParams = btnLayoutParams
            setOnClickListener { onStopRawListener?.invoke() }
        }

        cancelButton = Button(context).apply {
            styleButton(this, "Cancel", cancelColor, cancelTextColor, density, sansSerifMedium)
            layoutParams = btnLayoutParams
            setOnClickListener { onCancelListener?.invoke() }
        }

        buttonContainer.addView(stopCleanButton)
        buttonContainer.addView(stopRawButton)
        buttonContainer.addView(cancelButton)

        addView(statusText)
        addView(waveformView)
        addView(buttonContainer)
    }

    private fun styleButton(button: Button, text: String, bgColor: Int, textColor: Int, density: Float, typeface: Typeface) {
        button.apply {
            this.text = text
            this.typeface = typeface
            this.setTextColor(textColor)
            this.isAllCaps = false
            this.background = createCapsuleDrawable(bgColor, 100f * density)
            this.gravity = Gravity.CENTER
        }
    }

    private fun createCapsuleDrawable(backgroundColor: Int, cornerRadius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(backgroundColor)
            setCornerRadius(cornerRadius)
        }
    }
}
