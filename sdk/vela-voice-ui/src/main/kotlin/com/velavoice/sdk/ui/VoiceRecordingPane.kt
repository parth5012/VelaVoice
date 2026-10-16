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
        val colors = VelaColors.forTheme(isDark)

        val sansSerifMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val sansSerifLight = Typeface.create("sans-serif-light", Typeface.NORMAL)

        setBackgroundColor(colors.baseBg)
        setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), 0)

        // --- Top row: Status + Timer ---
        val topRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        statusText = TextView(context).apply {
            text = resources.getString(R.string.vela_voice_status_listening)
            contentDescription = text
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            textSize = 13f
            typeface = sansSerifLight
            setTextColor(colors.mainText)
            gravity = Gravity.START
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        timerText = TextView(context).apply {
            text = "0:00"
            textSize = 13f
            typeface = sansSerifMedium
            setTextColor(colors.subText)
            gravity = Gravity.END
            visibility = View.GONE
        }

        topRow.addView(statusText)
        topRow.addView(timerText)
        addView(topRow)

        // --- Waveform (taller for more pronounced amplitude visualization) ---
        waveformView = WaveformView(context).apply {
            // Decorative: amplitude visuals are noise for screen readers (ticket #115).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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
            setTextColor(colors.subText)
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
            setBackgroundColor(colors.surface)
            setTextColor(colors.subText)
        }
        addView(transcriptPreview)

        // --- Action buttons ---
        val buttonContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(colors.crust)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        stopCleanButton = makeActionButton(
            resources.getString(R.string.vela_voice_stop_clean),
            colors.stopClean, colors.buttonText, density, sansSerifMedium
        ) {
            onStopCleanListener?.invoke()
        }.also {
            it.id = View.generateViewId()
            it.contentDescription = resources.getString(R.string.vela_voice_cd_stop_clean)
        }

        stopRawButton = makeActionButton(
            resources.getString(R.string.vela_voice_stop_raw),
            colors.stopRaw, colors.buttonText, density, sansSerifMedium
        ) {
            onStopRawListener?.invoke()
        }.also {
            it.id = View.generateViewId()
            it.contentDescription = resources.getString(R.string.vela_voice_cd_stop_raw)
        }

        cancelButton = makeActionButton(
            resources.getString(R.string.vela_voice_cancel),
            colors.cancel, colors.buttonText, density, sansSerifMedium
        ) {
            onCancelListener?.invoke()
        }.also {
            it.id = View.generateViewId()
            it.contentDescription = resources.getString(R.string.vela_voice_cd_cancel)
        }

        // Deterministic focus order: Stop Clean -> Stop Raw -> Cancel (ticket #115).
        stopCleanButton.nextFocusForwardId = stopRawButton.id
        stopRawButton.nextFocusForwardId = cancelButton.id

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

    /**
     * Resource-backed status transition (ticket #115): resolves [status]
     * through `strings.xml`, mirrors it as the spoken description and
     * announces the change once for screen readers.
     */
    fun setStatus(status: VoiceStatus) {
        setStatusText(context.getString(status.stringResId), announce = true)
    }

    /**
     * Dynamic status text (engine errors, live partials). Silent by default so
     * per-tick streaming updates never spam the screen reader; pass
     * `announce = true` for one-shot spoken delivery.
     */
    fun setStatusText(text: String, announce: Boolean = false) {
        mainHandler.post {
            statusText.text = text
            statusText.contentDescription = text
            if (announce) {
                statusText.announceForAccessibility(text)
            }
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

    /**
     * Matched teardown for every listener/handler registration above (map #105
     * ticket #114): drops pending handler runnables, clears button click
     * listeners and listener lambdas so a recycled pane can neither fire into
     * a dead session nor hold the IME context alive.
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        stopCleanButton.setOnClickListener(null)
        stopRawButton.setOnClickListener(null)
        cancelButton.setOnClickListener(null)
        onStopCleanListener = null
        onStopRawListener = null
        onCancelListener = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    /** Resolved theme palette (Catppuccin-inspired) for the recording pane. */
    private data class VelaColors(
        val baseBg: Int,
        val crust: Int,
        val surface: Int,
        val mainText: Int,
        val subText: Int,
        val stopClean: Int,
        val stopRaw: Int,
        val cancel: Int,
        val buttonText: Int
    ) {
        companion object {
            fun forTheme(isDark: Boolean): VelaColors {
                fun color(light: String, dark: String): Int =
                    Color.parseColor(if (isDark) dark else light)
                // All three action buttons share one button-text color per theme.
                val buttonText = Color.parseColor(if (isDark) "#11111b" else "#eff1f5")
                return VelaColors(
                    baseBg = color("#eff1f5", "#1e1e2e"),
                    crust = color("#dce0e8", "#11111b"),
                    surface = color("#e6e9ef", "#181825"),
                    mainText = color("#4c4f69", "#cdd6f4"),
                    subText = color("#5c5f77", "#bac2de"),
                    stopClean = color("#40a02b", "#a6e3a1"),
                    stopRaw = color("#df8e1d", "#fab387"),
                    cancel = color("#d20f39", "#f38ba8"),
                    buttonText = buttonText
                )
            }
        }
    }

    private fun makeActionButton(
        text: String,
        bgColor: Int,
        textColor: Int,
        density: Float,
        typeface: Typeface,
        onClick: () -> Unit
    ): Button {
        return Button(context).apply {
            styleButton(this, text, bgColor, textColor, density, typeface)
            layoutParams = LayoutParams(0, (42 * density).toInt(), 1f).apply {
                leftMargin = (4 * density).toInt()
                rightMargin = (4 * density).toInt()
            }
            setOnClickListener { onClick() }
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
