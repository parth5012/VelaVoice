package com.velavoice.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import com.velavoice.sdk.AudioRecorder
import com.velavoice.sdk.TranscriptionResult
import com.velavoice.sdk.VelaException
import com.velavoice.sdk.VelaRecordingCallback
import com.velavoice.sdk.VelaTranscriber
import com.velavoice.sdk.cleaner.PersonalDictionary
import com.velavoice.sdk.ui.VoiceRecordingPane
import java.io.File

class VoiceInputMethodService : InputMethodService() {
    private var transcriber: VelaTranscriber? = null

    private lateinit var voiceRecordingPane: VoiceRecordingPane
    private lateinit var voiceButton: Button
    private lateinit var keyboardView: LinearLayout

    override fun onCreateInputView(): View {
        val context: Context = this
        val density = context.resources.displayMetrics.density
        val fixedHeightPx = (260 * density).toInt()

        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // Catppuccin colorscheme
        val baseBgColor = Color.parseColor(if (isDark) "#1e1e2e" else "#eff1f5")
        val stopCleanColor = Color.parseColor(if (isDark) "#a6e3a1" else "#40a02b")
        val stopCleanTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")

        val sansSerifMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // Root Layout
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(baseBgColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                fixedHeightPx
            )
        }

        // Standby / Keyboard View
        keyboardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(baseBgColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        voiceButton = Button(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (56 * density).toInt()
            )
            styleButton(this, "🎤 Tap to Speak", stopCleanColor, stopCleanTextColor, density, sansSerifMedium)
            setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
            setOnClickListener {
                showVoicePane()
                startRecording()
            }
        }
        keyboardView.addView(voiceButton)

        // Voice Recording Pane
        voiceRecordingPane = VoiceRecordingPane(context).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            onStopCleanListener = { stopRecording(runCleaner = true) }
            onStopRawListener = { stopRecording(runCleaner = false) }
            onCancelListener = { cancelRecording() }
        }

        rootLayout.addView(keyboardView)
        rootLayout.addView(voiceRecordingPane)

        return rootLayout
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

    private fun showVoicePane() {
        keyboardView.visibility = View.GONE
        voiceRecordingPane.visibility = View.VISIBLE
    }

    private fun showKeyboardView() {
        voiceRecordingPane.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
    }

    private fun startRecording() {
        val whisperPath = getWhisperModelPath(this)
        val llmPath = getLlmModelPath(this)

        val personalDictionary = object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> {
                val dbFile = findDatabaseFile(this@VoiceInputMethodService) ?: return emptyList()
                val entries = mutableListOf<Pair<String, String>>()
                var db: SQLiteDatabase? = null
                try {
                    db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val cursor = db.rawQuery(
                        "SELECT original_word, replacement FROM personal_dictionary ORDER BY priority DESC, name ASC",
                        null
                    )
                    if (cursor.moveToFirst()) {
                        do {
                            val original = cursor.getString(0)
                            val replacement = cursor.getString(1)
                            if (original.isNotEmpty()) {
                                entries.add(Pair(original, replacement))
                            }
                        } while (cursor.moveToNext())
                    }
                    cursor.close()
                } catch (e: Exception) {
                    android.util.Log.e("VoiceIME", "Error loading personal dictionary: ${e.message}")
                } finally {
                    db?.close()
                }
                return entries
            }
        }

        val prefs = getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
        val useLlm = prefs.getBoolean("useLlmCleaner", false)

        val builder = VelaTranscriber.Builder(this)
            .whisperModel(whisperPath ?: "")
            .language("en")
            .threads(4)
            .personalDictionary(personalDictionary)

        if (useLlm && llmPath != null) {
            builder.useLlmCleaner(true, llmPath)
        }

        transcriber = builder.build()
        voiceRecordingPane.waveformView.clear()
        voiceRecordingPane.statusText.text = "Recording..."

        transcriber?.startRecording(object : VelaRecordingCallback {
            override fun onAmplitude(normalized: Float) {
                voiceRecordingPane.waveformView.post {
                    voiceRecordingPane.waveformView.addAmplitude(normalized)
                }
            }

            override fun onResult(result: TranscriptionResult) {
                val finalTranscript = result.cleanedTranscript
                voiceRecordingPane.post {
                    val ic = currentInputConnection
                    if (ic != null && finalTranscript.isNotEmpty()) {
                        ic.commitText(finalTranscript, 1)
                    }
                    showKeyboardView()
                }
            }

            override fun onError(error: VelaException) {
                voiceRecordingPane.statusText.post {
                    voiceRecordingPane.statusText.text = error.message
                }
            }
        })
    }

    private fun findDatabaseFile(context: Context): File? {
        val paths = listOf(
            context.getDatabasePath("models.db"),
            File(context.filesDir, "SQLite/models.db"),
            File(context.filesDir, "databases/models.db")
        )
        for (path in paths) {
            if (path != null && path.exists()) {
                android.util.Log.d("VoiceIME", "Found database at: ${path.absolutePath}")
                return path
            }
        }
        android.util.Log.w("VoiceIME", "models.db database file not found in any standard locations")
        return null
    }

    private fun getWhisperModelPath(context: Context): String? {
        val dbFile = findDatabaseFile(context) ?: return null
        var db: SQLiteDatabase? = null
        var path: String? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT path FROM models WHERE type = 'whisper-tiny-en' AND status = 'completed' LIMIT 1",
                null
            )
            if (cursor.moveToFirst()) {
                path = cursor.getString(0)
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db?.close()
        }
        return path
    }

    private fun getLlmModelPath(context: Context): String? {
        val dbFile = findDatabaseFile(context) ?: return null
        var db: SQLiteDatabase? = null
        var path: String? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT path FROM models WHERE type = 'cleaner-llama-3b' AND status = 'completed' LIMIT 1",
                null
            )
            if (cursor.moveToFirst()) {
                path = cursor.getString(0)
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            db?.close()
        }
        return path
    }

    private fun stopRecording(runCleaner: Boolean) {
        voiceRecordingPane.statusText.text = "Processing transcript..."
        transcriber?.stopRecording(clean = runCleaner)
    }

    private fun cancelRecording() {
        transcriber?.release()
        transcriber = null
        voiceRecordingPane.waveformView.clear()
        showKeyboardView()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        showVoicePane()
        startRecording()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        transcriber?.release()
        transcriber = null
    }
}
