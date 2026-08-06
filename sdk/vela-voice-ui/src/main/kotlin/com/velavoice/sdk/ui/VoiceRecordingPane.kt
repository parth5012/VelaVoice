package com.velavoice.sdk.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class VoiceRecordingPane(context: Context) : LinearLayout(context) {

    val statusText: TextView
    val waveformView: WaveformView
    val transcriptPreview: TextView
    val timerText: TextView
    val stopCleanButton: Button
    val stopRawButton: Button
    val cancelButton: Button

    var onStopCleanListener: (() -> Unit)? = null
    var onStopRawListener: (() -> Unit)? = null
    var onCancelListener: (() -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        orientation = VERTICAL
        val density = context.resources.displayMetrics.density

        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val baseBgColor = Color.parseColor(if (isDark) "#1e1e2e" else "#eff1f5")
        val crustColor = Color.parseColor(if (isDark) "#11111b" else "#dce0e8")
        val surfaceColor = Color.parseColor(if (isDark) "#181825" else "#e6e9ef")
        val mainTextColor = Color.parseColor(if (isDark) "#cdd6f4" else "#4c4f69")
        val subTextColor = Color.parseColor(if (isDark) "#bac2de" else "#5c5f77")
        val stopCleanColor = Color.parseColor(if (isDark) "#a6e3a1" else "#40a02b")
        val stopRawColor = Color.parseColor(if (isDark) "#fab387" else "#df8e1d")
        val cancelColor = Color.parseColor(if (isDark) "#f38ba8" else "#d20f39")

        val stopCleanTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")
        val stopRawTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")
        val cancelTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")

        val sansSerifMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val sansSerifLight = Typeface.create("sans-serif-light", Typeface.NORMAL)

        setBackgroundColor(baseBgColor)
        setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), 0)

        // --- Top row: Status + Timer ---
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        statusText = TextView(context).apply {
            text = "Listening..."
            textSize = 13f
            typeface = sansSerifLight
            setTextColor(mainTextColor)
            gravity = Gravity.START
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        timerText = TextView(context).apply {
            text = "0:00"
            textSize = 13f
            typeface = sansSerifMedium
            setTextColor(subTextColor)
            gravity = Gravity.END
            visibility = View.GONE
        }

        topRow.addView(statusText)
        topRow.addView(timerText)
        addView(topRow)

        // --- Waveform (taller for more pronounced amplitude visualization) ---
        waveformView = WaveformView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (100 * density).toInt()).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
        }
        addView(waveformView)

        // --- Live transcript preview ---
        transcriptPreview = TextView(context).apply {
            textSize = 12f
            typeface = sansSerifLight
            setTextColor(subTextColor)
            gravity = Gravity.START
            setLineSpacing(0f, 1.2f)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (6 * density).toInt()
                topMargin = (4 * density).toInt()
            }
            setPadding((4 * density).toInt(), (6 * density).toInt(),
                (4 * density).toInt(), (6 * density).toInt())
            setBackgroundColor(surfaceColor)
            setTextColor(subTextColor)
        }
        addView(transcriptPreview)

        // --- Action buttons ---
        val buttonContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(crustColor)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val btnLayoutParams = LayoutParams(0, (42 * density).toInt(), 1f).apply {
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

        addView(buttonContainer)
    }

    /** Update the live transcript preview text shown above the buttons */
    fun updateTranscriptPreview(text: String) {
        mainHandler.post {
            if (text.isNotEmpty()) {
                transcriptPreview.text = text
                transcriptPreview.visibility = View.VISIBLE
            } else {
                transcriptPreview.visibility = View.GONE
            }
        }
    }

    /** Update the recording timer display */
    fun updateTimer(seconds: Int) {
        mainHandler.post {
            val mins = seconds / 60
            val secs = seconds % 60
            timerText.text = String.format("%d:%02d", mins, secs)
            timerText.visibility = View.VISIBLE
        }
    }

    /** Hide the timer display */
    fun hideTimer() {
        mainHandler.post {
            timerText.visibility = View.GONE
        }
    }

    /** Reset all display fields to initial state */
    fun resetDisplay() {
        mainHandler.post {
            transcriptPreview.visibility = View.GONE
            transcriptPreview.text = ""
            timerText.visibility = View.GONE
            timerText.text = "0:00"
            waveformView.clear()
        }
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
