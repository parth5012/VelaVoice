package com.velavoice.app

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.graphics.Color
import android.view.Gravity
import android.content.Context

class VoiceInputMethodService : InputMethodService() {
    override fun onCreateInputView(): View {
        val context: Context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(context).apply {
            text = "Vela Voice Keyboard"
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
        }

        val statusText = TextView(context).apply {
            text = "Tap microphone to record"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 16)
        }

        val voiceButton = Button(context).apply {
            text = "🎤 Start Capture"
            setOnClickListener {
                statusText.text = "Recording..."
            }
        }

        layout.addView(titleText)
        layout.addView(statusText)
        layout.addView(voiceButton)

        return layout
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
    }
}
