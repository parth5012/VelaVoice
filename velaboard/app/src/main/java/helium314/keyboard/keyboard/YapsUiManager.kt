package helium314.keyboard.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode

class YapsUiManager private constructor() {

    companion object {
        @Volatile
        private var sInstance: YapsUiManager? = null

        @JvmStatic
        fun getInstance(): YapsUiManager {
            return sInstance ?: synchronized(this) {
                sInstance ?: YapsUiManager().also { sInstance = it }
            }
        }
    }

    private var mLatinIME: LatinIME? = null
    private var mKeyboardSwitcher: KeyboardSwitcher? = null
    private var mSuggestionStripView: View? = null
    private var mKeyboardViewWrapper: FrameLayout? = null

    // Views
    private var mAutoPill: LinearLayout? = null
    private var mActiveToolbar: LinearLayout? = null
    private var mWaveformView: YapsWaveformView? = null
    private var mLanguageText: TextView? = null
    private var mDimOverlay: View? = null
    private var mTranscribingDots: TranscribingDotsView? = null
    private var mConfirmBtn: ImageButton? = null
    private var mMiddleContainer: LinearLayout? = null

    private var mIsTranscribing = false

    private val mHandler = Handler(Looper.getMainLooper())

    fun init(latinIME: LatinIME, keyboardSwitcher: KeyboardSwitcher, suggestionStripView: View, keyboardViewWrapper: FrameLayout) {
        this.mLatinIME = latinIME
        this.mKeyboardSwitcher = keyboardSwitcher
        this.mSuggestionStripView = suggestionStripView
        this.mKeyboardViewWrapper = keyboardViewWrapper

        // Clean up any old views if re-initializing
        removeYapsViews()
    }

    private fun isYapsActive(): Boolean {
        val prefs = Settings.getInstance().prefs ?: return false
        return prefs.getString(Settings.PREF_VELA_UI_STYLE, Defaults.PREF_VELA_UI_STYLE) == "yaps"
    }

    /**
     * Called when the keyboard is shown or layout is loaded in standby state.
     * Sets up the Idle trigger ("AUTO" pill) on the toolbar.
     */
    fun setupIdleState() {
        if (!isYapsActive()) {
            removeYapsViews()
            return
        }

        val parent = mSuggestionStripView?.parent as? ViewGroup ?: return
        val context = mLatinIME ?: return
        val density = context.resources.displayMetrics.density

        // If pill already exists, just show it
        if (mAutoPill != null) {
            mAutoPill?.visibility = View.VISIBLE
            mAutoPill?.alpha = 1f
            return
        }

        // Create the AUTO Pill Trigger Button
        mAutoPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            
            // Capsule background with warm coral color
            background = GradientDrawable().apply {
                cornerRadius = 18f * density // Half of ~36dp height
                setColor(Color.parseColor("#E87A5D"))
            }

            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)

            // Minimalist Waveform Icon (3 vertical bars)
            val waveformIcon = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = (6 * density).toInt()
                }

                val heights = listOf(8, 16, 10)
                heights.forEach { h ->
                    val bar = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (2 * density).toInt(),
                            (h * density).toInt()
                        ).apply {
                            leftMargin = (1 * density).toInt()
                            rightMargin = (1 * density).toInt()
                        }
                        setBackgroundColor(Color.WHITE)
                    }
                    addView(bar)
                }
            }
            addView(waveformIcon)

            // Bold uppercase label
            val labelText = TextView(context).apply {
                text = "AUTO"
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(Color.WHITE)
                textSize = 12f
            }
            addView(labelText)

            // Position at the right end of utility bar
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                (34 * density).toInt(),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                rightMargin = (12 * density).toInt()
            }

            setOnClickListener {
                // Trigger voice input action directly
                mLatinIME?.onCodeInput(KeyCode.VOICE_INPUT, 0, 0, false)
            }
        }

        // Add to parent FrameLayout so it overlays on top
        parent.addView(mAutoPill)
    }

    /**
     * Called when active recording/transcription begins.
     * Replaces standard toolbar with morphing recording controls and dims alphanumeric key grid.
     */
    fun startActiveRecording(activeLanguage: String) {
        if (!isYapsActive()) return
        val context = mLatinIME ?: return
        val density = context.resources.displayMetrics.density
        val parent = mSuggestionStripView?.parent as? ViewGroup ?: return



        // 2. Hide "AUTO" Pill trigger button
        mAutoPill?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            mAutoPill?.visibility = View.GONE
        }?.start()

        // 3. Hide standard Suggestion Strip View
        mSuggestionStripView?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            mSuggestionStripView?.visibility = View.INVISIBLE
        }?.start()

        // 4. Create and Show Active Recording Toolbar
        if (mActiveToolbar == null) {
            mActiveToolbar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#0F0F0F")) // solid black/dark background
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)

                // Cancel Button (X icon)
                val cancelBtn = ImageButton(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (36 * density).toInt(),
                        (36 * density).toInt()
                    )
                    setBackgroundColor(Color.TRANSPARENT)
                    val cancelDrawable = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_close_clear_cancel)
                    cancelDrawable?.let { DrawableCompat.setTint(it, Color.parseColor("#CCCCCC")) }
                    setImageDrawable(cancelDrawable)
                    setOnClickListener {
                        // cancelVelaRecording already calls stopActiveRecording() internally
                        mKeyboardSwitcher?.cancelVelaRecording()
                    }
                }
                addView(cancelBtn)

                // Middle Container for visualizer & language indicator
                mMiddleContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

                    // Language label
                    mLanguageText = TextView(context).apply {
                        text = activeLanguage.uppercase()
                        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                        setTextColor(Color.parseColor("#E87A5D"))
                        textSize = 10f
                    }
                    addView(mLanguageText)

                    // Live Waveform Visualizer
                    mWaveformView = YapsWaveformView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (20 * density).toInt()
                        ).apply {
                            topMargin = (2 * density).toInt()
                        }
                    }
                    addView(mWaveformView)
                }
                addView(mMiddleContainer)

                // Confirm / Insert Action Button (checkmark)
                mConfirmBtn = ImageButton(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (36 * density).toInt(),
                        (36 * density).toInt()
                    ).apply {
                        leftMargin = (8 * density).toInt()
                    }
                    
                    // Circular coral background
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#E87A5D"))
                    }

                    // Checkmark icon
                    val checkDrawable = ContextCompat.getDrawable(context, android.R.drawable.checkbox_on_background)
                    checkDrawable?.let { DrawableCompat.setTint(it, Color.WHITE) }
                    setImageDrawable(checkDrawable)

                    setOnClickListener {
                        mIsTranscribing = true
                        showTranscribingState()
                        mKeyboardSwitcher?.confirmVelaRecording()
                    }
                }
                addView(mConfirmBtn)
            }

            val viewHeight = mSuggestionStripView?.height ?: 0
            val viewMeasuredHeight = mSuggestionStripView?.measuredHeight ?: 0
            val stripHeight = if (viewHeight > 0) {
                viewHeight
            } else if (viewMeasuredHeight > 0) {
                viewMeasuredHeight
            } else {
                (44f * density).toInt()
            }
            parent.addView(mActiveToolbar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                stripHeight,
                Gravity.TOP
            ))
        }

        mActiveToolbar?.visibility = View.VISIBLE
        mActiveToolbar?.alpha = 0f
        mActiveToolbar?.animate()?.alpha(1f)?.setDuration(150)?.start()
    }

    /**
     * Called when user confirms recording and transcription is processing.
     * Replaces waveform with animated pulsing dots and shows "TRANSCRIBING" label.
     */
    private fun showTranscribingState() {
        val context = mLatinIME ?: return
        val density = context.resources.displayMetrics.density
        val container = mMiddleContainer ?: return

        // Remove live waveform
        mWaveformView?.let { container.removeView(it) }
        mWaveformView = null

        // Update language label
        mLanguageText?.text = "TRANSCRIBING"
        mLanguageText?.setTextColor(Color.parseColor("#62f9ee"))

        // Disable confirm button with greyed appearance
        mConfirmBtn?.isEnabled = false
        mConfirmBtn?.alpha = 0.4f

        // Create and add animated dots
        if (mTranscribingDots == null) {
            mTranscribingDots = TranscribingDotsView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (20 * density).toInt()
                ).apply {
                    topMargin = (2 * density).toInt()
                }
            }
            container.addView(mTranscribingDots)
        }
        mTranscribingDots?.visibility = View.VISIBLE
        mTranscribingDots?.startAnimating()
    }

    /**
     * Restores the keyboard to standard standby mode.
     */
    fun stopActiveRecording() {
        mIsTranscribing = false

        // Stop dots animation
        mTranscribingDots?.stopAnimating()
        mTranscribingDots?.visibility = View.GONE


        // 2. Fade out and remove Active Recording Toolbar
        mActiveToolbar?.let { toolbar ->
            toolbar.animate().alpha(0f).setDuration(150).withEndAction {
                val parent = toolbar.parent as? ViewGroup
                parent?.removeView(toolbar)
                mActiveToolbar = null
                mWaveformView = null
                mLanguageText = null
                mTranscribingDots = null
                mConfirmBtn = null
                mMiddleContainer = null
            }.start()
        }

        // 3. Restore Suggestion Strip and AUTO Pill
        mSuggestionStripView?.let { strip ->
            strip.visibility = View.VISIBLE
            strip.animate().alpha(1f).setDuration(150).start()
        }

        mAutoPill?.let { pill ->
            pill.visibility = View.VISIBLE
            pill.animate().alpha(1f).setDuration(150).start()
        }
    }

    fun updateLanguageText(text: String) {
        mHandler.post {
            mLanguageText?.text = text.uppercase()
        }
    }

    fun addAmplitude(normalized: Float) {
        if (mIsTranscribing) return
        mWaveformView?.addAmplitude(normalized)
    }

    private fun removeYapsViews() {
        val parent = mSuggestionStripView?.parent as? ViewGroup
        mAutoPill?.let { parent?.removeView(it) }
        mAutoPill = null

        mTranscribingDots?.stopAnimating()
        mActiveToolbar?.let { parent?.removeView(it) }
        mActiveToolbar = null
        mWaveformView = null
        mLanguageText = null
        mTranscribingDots = null
        mConfirmBtn = null
        mMiddleContainer = null
        mIsTranscribing = false

    }
}

/**
 * Custom waveform visualizer with smooth animations matching Yaps specs.
 */
class YapsWaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.parseColor("#E87A5D")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val numBars = 35
    private val barHeights = FloatArray(numBars)
    private val targetHeights = FloatArray(numBars)
    private val smoothingFactor = 0.2f
    private val amplitudeMultiplier = 6f

    fun addAmplitude(amp: Float) {
        System.arraycopy(targetHeights, 1, targetHeights, 0, numBars - 1)
        val scaled = (amp * amplitudeMultiplier).coerceAtMost(1f)
        targetHeights[numBars - 1] = scaled * height.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val barWidth = (w / numBars) * 0.6f
        val gap = (w / numBars) * 0.4f

        for (i in 0 until numBars) {
            // Apply smoothing
            barHeights[i] = barHeights[i] + (targetHeights[i] - barHeights[i]) * smoothingFactor

            val barH = barHeights[i].coerceAtLeast(4f) // Always show at least a tiny line
            val left = i * (barWidth + gap) + gap / 2f
            val top = (h - barH) / 2f
            val right = left + barWidth
            val bottom = top + barH

            // Draw rounded bar
            canvas.drawRoundRect(left, top, right, bottom, barWidth / 2f, barWidth / 2f, paint)
        }

        // Keep updating as long as heights animate
        var needsRedraw = false
        for (i in 0 until numBars) {
            if (Math.abs(targetHeights[i] - barHeights[i]) > 0.5f) {
                needsRedraw = true
                break
            }
        }
        if (needsRedraw) {
            postInvalidateDelayed(16) // ~60fps smooth render
        }
    }
}

/**
 * Custom animated pulsing dots to indicate transcription is processing.
 * Three dots pulse with staggered delays, cycling continuously.
 */
class TranscribingDotsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dotCount = 3
    private val dotRadii = FloatArray(dotCount)
    private var animating = false
    private var animStartTime = 0L

    private val paint = Paint().apply {
        color = Color.parseColor("#62f9ee")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun startAnimating() {
        animating = true
        animStartTime = System.currentTimeMillis()
        postInvalidateDelayed(16)
    }

    fun stopAnimating() {
        animating = false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!animating) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val maxRadius = (h / 3f).coerceAtMost(w / (dotCount * 4f))
        val minRadius = maxRadius * 0.3f
        val spacing = w / (dotCount + 1)
        val cycleMs = 800L
        val elapsed = (System.currentTimeMillis() - animStartTime) % cycleMs
        val phase = elapsed.toFloat() / cycleMs

        for (i in 0 until dotCount) {
            val dotPhase = (phase - i.toFloat() / dotCount).let { if (it < 0) it + 1f else it }
            val radiusFraction = (Math.sin(dotPhase * Math.PI * 2) + 1.0) / 2.0
            dotRadii[i] = minRadius + (maxRadius - minRadius) * radiusFraction.toFloat()
            val cx = spacing * (i + 1)
            val cy = h / 2f
            canvas.drawCircle(cx, cy, dotRadii[i], paint)
        }

        postInvalidateDelayed(50)
    }
}
