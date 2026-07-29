package com.velavoice.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
import com.velavoice.sdk.cleaner.DictionaryKeywords
import com.velavoice.sdk.cleaner.PersonalDictionary
import com.velavoice.sdk.ui.VoiceRecordingPane
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VoiceInputMethodService : InputMethodService() {
    private var transcriber: VelaTranscriber? = null

    private lateinit var voiceRecordingPane: VoiceRecordingPane
    private lateinit var voiceButton: Button
    private lateinit var keyboardView: LinearLayout

    // Background worker for DB and ML initialization
    private var bgHandlerThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Cached model paths to avoid repeated DB queries
    private var cachedWhisperPath: String? = null
    private var cachedLlmPath: String? = null

    // Recording state
    private var recordingSeconds = 0
    private var timerHandler: Handler? = null
    private val isRecording = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        // Initialize background thread for DB/ML operations
        bgHandlerThread = HandlerThread("VelaIME-BG")
        bgHandlerThread?.start()
        bgHandler = Handler(bgHandlerThread!!.looper)
    }

    override fun onCreateInputView(): View {
        val context: Context = this
        val density = context.resources.displayMetrics.density

        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // Catppuccin colorscheme
        val baseBgColor = Color.parseColor(if (isDark) "#1e1e2e" else "#eff1f5")
        val stopCleanColor = Color.parseColor(if (isDark) "#a6e3a1" else "#40a02b")
        val stopCleanTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")

        val sansSerifMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // Root Layout - MATCH_PARENT to fill the IME window (keyboard-sized by the system)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(baseBgColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
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

        // Voice Recording Pane - fills the IME window at keyboard size
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
        isRecording.set(false)
        timerHandler?.removeCallbacksAndMessages(null)
        transcriber?.release()
        transcriber = null
        bgHandlerThread?.quitSafely()
        bgHandlerThread = null
    }

    // ──────────────────────────────────────────────
    // UI state management
    // ──────────────────────────────────────────────

    private fun showVoicePane() {
        keyboardView.visibility = View.GONE
        voiceRecordingPane.visibility = View.VISIBLE
    }

    private fun showKeyboardView() {
        voiceRecordingPane.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
    }

    // ──────────────────────────────────────────────
    // Recording lifecycle (offloaded to background)
    // ──────────────────────────────────────────────

    private fun startRecording() {
        voiceRecordingPane.resetDisplay()
        voiceRecordingPane.statusText.text = "Initializing..."

        bgHandler?.post {
            try {
                // Cache model paths on first use to avoid repeated DB queries
                loadModelPaths()

                val whisperPath = cachedWhisperPath
                if (whisperPath == null) {
                    mainHandler.post {
                        voiceRecordingPane.statusText.text = "No whisper model found. Download one first."
                    }
                    return@post
                }

                // Load personal dictionary, keywords, and prefs
                val personalDictionary = loadPersonalDictionary()
                val dictionaryKeywords = loadDictionaryKeywords()
                val prefs = this@VoiceInputMethodService
                    .getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
                val useLlm = prefs.getBoolean("useLlmCleaner", false)

                // Build transcriber (includes WhisperEngine init - on background)
                val builder = VelaTranscriber.Builder(this@VoiceInputMethodService)
                    .whisperModel(whisperPath)
                    .language("en")
                    .threads(4)
                    .personalDictionary(personalDictionary)
                    .dictionaryKeywords(dictionaryKeywords)

                if (useLlm && cachedLlmPath != null) {
                    builder.useLlmCleaner(true, cachedLlmPath)
                }

                val builtTranscriber = builder.build()

                mainHandler.post {
                    // Assign to main thread reference
                    transcriber?.release()
                    transcriber = builtTranscriber

                    voiceRecordingPane.statusText.text = "Recording..."
                    voiceRecordingPane.hideTimer()

                    isRecording.set(true)
                    recordingSeconds = 0
                    startTimer()

                    builtTranscriber.startRecording(object : VelaRecordingCallback {
                        override fun onAmplitude(normalized: Float) {
                            voiceRecordingPane.waveformView.post {
                                voiceRecordingPane.waveformView.addAmplitude(normalized)
                            }
                        }

                        override fun onResult(result: TranscriptionResult) {
                            isRecording.set(false)
                            timerHandler?.removeCallbacksAndMessages(null)
                            voiceRecordingPane.statusText.text = "Done"
                            val finalTranscript = result.cleanedTranscript
                            // Auto-save transcription pair + audio to local storage
                            TranscriptionStorage.save(
                                this@VoiceInputMethodService,
                                raw = result.rawTranscript,
                                cleaned = result.cleanedTranscript,
                                durationMs = result.durationMs,
                                audioBytes = result.audioBytes
                            )
                            voiceRecordingPane.post {
                                val ic = currentInputConnection
                                if (ic != null && finalTranscript.isNotEmpty()) {
                                    ic.commitText(finalTranscript, 1)
                                }
                                showKeyboardView()
                            }
                        }

                        override fun onError(error: VelaException) {
                            isRecording.set(false)
                            timerHandler?.removeCallbacksAndMessages(null)
                            voiceRecordingPane.statusText.post {
                                voiceRecordingPane.statusText.text = error.message
                            }
                        }
                    })
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceIME", "Start recording failed", e)
                mainHandler.post {
                    voiceRecordingPane.statusText.text = "Initialization error"
                }
            }
        }
    }

    private fun stopRecording(runCleaner: Boolean) {
        isRecording.set(false)
        timerHandler?.removeCallbacksAndMessages(null)
        voiceRecordingPane.statusText.text = "Processing..."
        transcriber?.stopRecording(clean = runCleaner)
    }

    private fun cancelRecording() {
        isRecording.set(false)
        timerHandler?.removeCallbacksAndMessages(null)
        transcriber?.release()
        transcriber = null
        voiceRecordingPane.waveformView.clear()
        showKeyboardView()
    }

    // ──────────────────────────────────────────────
    // Timer
    // ──────────────────────────────────────────────

    private fun startTimer() {
        timerHandler = Handler(Looper.getMainLooper())
        recordingSeconds = 0
        voiceRecordingPane.updateTimer(0)
        timerHandler?.post(object : Runnable {
            override fun run() {
                if (!isRecording.get()) return
                recordingSeconds++
                voiceRecordingPane.updateTimer(recordingSeconds)
                timerHandler?.postDelayed(this, 1000)
            }
        })
    }

    // ──────────────────────────────────────────────
    // Database operations (background-thread only)
    // ──────────────────────────────────────────────

    /** Load and cache model paths from the database */
    private fun loadModelPaths() {
        if (cachedWhisperPath != null && cachedLlmPath != null) return
        val dbFile = findDatabaseFile(this) ?: return
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            cachedWhisperPath = queryModelPath(db, "whisper-tiny-en")
            cachedLlmPath = queryModelPath(db, "cleaner-llama-3b")
        } catch (e: Exception) {
            android.util.Log.e("VoiceIME", "Failed to load model paths", e)
        } finally {
            db?.close()
        }
    }

    private fun queryModelPath(db: SQLiteDatabase, modelType: String): String? {
        val cursor = db.rawQuery(
            "SELECT path FROM models WHERE (type = ? OR name = ?) AND status = 'completed' LIMIT 1",
            arrayOf(modelType, modelType)
        )
        return try {
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } finally {
            cursor.close()
        }
    }

    /** Load personal dictionary entries from the database */
    private fun loadPersonalDictionary(): PersonalDictionary {
        val dbFile = findDatabaseFile(this)
        val entries = mutableListOf<Pair<String, String>>()
        if (dbFile != null) {
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
        }
        return object : PersonalDictionary {
            override fun getEntries(): List<Pair<String, String>> = entries
        }
    }

    /** Load dictionary keywords from the database */
    private fun loadDictionaryKeywords(): DictionaryKeywords {
        val dbFile = findDatabaseFile(this)
        val keywords = mutableListOf<String>()
        if (dbFile != null) {
            var db: SQLiteDatabase? = null
            try {
                db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                val cursor = db.rawQuery(
                    "SELECT keyword FROM dictionary_keywords ORDER BY keyword ASC",
                    null
                )
                if (cursor.moveToFirst()) {
                    do {
                        val keyword = cursor.getString(0)
                        if (keyword.isNotEmpty()) {
                            keywords.add(keyword)
                        }
                    } while (cursor.moveToNext())
                }
                cursor.close()
            } catch (e: Exception) {
                android.util.Log.e("VoiceIME", "Error loading dictionary keywords: ${e.message}")
            } finally {
                db?.close()
            }
        }
        return object : DictionaryKeywords {
            override fun getKeywords(): List<String> = keywords
        }
    }

    private fun findDatabaseFile(context: Context): File? {
        val paths = listOf(
            context.getDatabasePath("models.db"),
            File(context.filesDir, "SQLite/models.db"),
            File(context.filesDir, "databases/models.db")
        )
        for (path in paths) {
            if (path != null && path.exists()) {
                return path
            }
        }
        return null
    }

    // ──────────────────────────────────────────────
    // Button styling helpers
    // ──────────────────────────────────────────────

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
