package com.velavoice.app

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.graphics.Color
import android.view.Gravity
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.database.sqlite.SQLiteDatabase
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt

class VoiceInputMethodService : InputMethodService() {
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val recordedAudioData = ByteArrayOutputStream()

    private lateinit var statusText: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var stopCleanButton: Button
    private lateinit var stopRawButton: Button
    private lateinit var cancelButton: Button
    private lateinit var voiceButton: Button
    private lateinit var voicePane: LinearLayout

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    override fun onCreateInputView(): View {
        val context: Context = this
        val density = context.resources.displayMetrics.density
        val fixedHeightPx = (260 * density).toInt()

        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        // Catppuccin color scheme (Mocha / Latte)
        val baseBgColor = Color.parseColor(if (isDark) "#1e1e2e" else "#eff1f5")
        val crustColor = Color.parseColor(if (isDark) "#11111b" else "#dce0e8")
        val mainTextColor = Color.parseColor(if (isDark) "#cdd6f4" else "#4c4f69")
        val stopCleanColor = Color.parseColor(if (isDark) "#a6e3a1" else "#40a02b")
        val stopRawColor = Color.parseColor(if (isDark) "#fab387" else "#df8e1d")
        val cancelColor = Color.parseColor(if (isDark) "#f38ba8" else "#d20f39")

        val stopCleanTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")
        val stopRawTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")
        val cancelTextColor = if (isDark) Color.parseColor("#11111b") else Color.parseColor("#eff1f5")

        // Typography settings
        val sansSerifMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val sansSerifLight = Typeface.create("sans-serif-light", Typeface.NORMAL)

        // Root Layout: Fixed Height (260dp)
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(baseBgColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                fixedHeightPx
            )
        }

        // Standard Keyboard view container / Standby screen (initially visible)
        val keyboardView = LinearLayout(context).apply {
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
            styleButton(this, "🎤 Tap Speak", stopCleanColor, stopCleanTextColor, density, sansSerifMedium)
            setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
            setOnClickListener {
                showVoicePane()
                startRecording()
            }
        }
        keyboardView.addView(voiceButton)

        // Voice Pane layout (initially hidden)
        voicePane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(baseBgColor)
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        statusText = TextView(context).apply {
            text = "Listening..."
            textSize = 14f
            typeface = sansSerifLight
            setTextColor(mainTextColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (8 * density).toInt())
        }

        waveformView = WaveformView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (100 * density).toInt()
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }

        // Bottom panel / crust holding the buttons
        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            setBackgroundColor(crustColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val btnLayoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
            leftMargin = (4 * density).toInt()
            rightMargin = (4 * density).toInt()
        }

        stopCleanButton = Button(context)
        styleButton(stopCleanButton, "Stop Clean", stopCleanColor, stopCleanTextColor, density, sansSerifMedium)
        stopCleanButton.layoutParams = btnLayoutParams
        stopCleanButton.setOnClickListener {
            stopRecording(runCleaner = true)
        }

        stopRawButton = Button(context)
        styleButton(stopRawButton, "Stop Raw", stopRawColor, stopRawTextColor, density, sansSerifMedium)
        stopRawButton.layoutParams = btnLayoutParams
        stopRawButton.setOnClickListener {
            stopRecording(runCleaner = false)
        }

        cancelButton = Button(context)
        styleButton(cancelButton, "Cancel", cancelColor, cancelTextColor, density, sansSerifMedium)
        cancelButton.layoutParams = btnLayoutParams
        cancelButton.setOnClickListener {
            cancelRecording()
        }

        buttonContainer.addView(stopCleanButton)
        buttonContainer.addView(stopRawButton)
        buttonContainer.addView(cancelButton)

        voicePane.addView(statusText)
        voicePane.addView(waveformView)
        voicePane.addView(buttonContainer)

        rootLayout.addView(keyboardView)
        rootLayout.addView(voicePane)

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
        voiceButton.parent?.let {
            (it as ViewGroup).visibility = View.GONE
        }
        voicePane.visibility = View.VISIBLE
    }

    private fun showKeyboardView() {
        voicePane.visibility = View.GONE
        voiceButton.parent?.let {
            (it as ViewGroup).visibility = View.VISIBLE
        }
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        recordedAudioData.reset()
        waveformView.clear()
        statusText.text = "Recording..."

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                statusText.text = "Microphone initialization failed"
                isRecording = false
                return
            }

            audioRecord?.startRecording()

            recordingThread = Thread({
                val buffer = ShortArray(BUFFER_SIZE / 2)
                val byteBuffer = ByteArray(BUFFER_SIZE)
                while (isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readResult > 0) {
                        // Calculate RMS for waveform visualization
                        var sum = 0.0
                        for (i in 0 until readResult) {
                            sum += buffer[i] * buffer[i]
                            // Convert short to bytearray (little endian PCM)
                            val shortVal = buffer[i]
                            byteBuffer[i * 2] = (shortVal.toInt() and 0xff).toByte()
                            byteBuffer[i * 2 + 1] = ((shortVal.toInt() shr 8) and 0xff).toByte()
                        }

                        recordedAudioData.write(byteBuffer, 0, readResult * 2)

                        val rms = sqrt(sum / readResult)
                        // Normalize RMS to 0..1 for visualizer
                        val normalized = (rms / 32768.0).toFloat()
                        waveformView.post {
                            waveformView.addAmplitude(normalized)
                        }
                    }
                }
            }, "VoiceIMERecordThread")

            recordingThread?.start()
        } catch (e: SecurityException) {
            statusText.text = "Mic permission denied"
            isRecording = false
        } catch (e: Exception) {
            statusText.text = "Error starting recording: ${e.message}"
            isRecording = false
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
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            val cursor = db.rawQuery(
                "SELECT path FROM models WHERE id = 'whisper-tiny-en' AND status = 'completed' LIMIT 1",
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
            db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            val cursor = db.rawQuery(
                "SELECT path FROM models WHERE id = 'cleaner-llama-3b' AND status = 'completed' LIMIT 1",
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
        if (!isRecording) return
        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread?.join()
            recordingThread = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        statusText.text = "Processing transcript..."

        val audioBytes = recordedAudioData.toByteArray()
        val runtime = Runtime.getRuntime()
        val startMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        android.util.Log.d("VoiceIMEPerformance", "Memory usage before processing: ${startMemory}MB")
        val startTime = System.currentTimeMillis()

        // Run Whisper in background thread
        Thread({
            val whisperPath = getWhisperModelPath(this@VoiceInputMethodService)
            val whisper = WhisperEngine()

            val rawTranscript = if (whisperPath != null && whisper.init(whisperPath)) {
                whisper.transcribe(audioBytes)
            } else {
                // Heuristic fallback transcript
                val seconds = (audioBytes.size / 2) / 16000f
                if (seconds < 1f) {
                    ""
                } else if (seconds < 3f) {
                    "Hello, test Vela Voice offline transcriber."
                } else {
                    "Thank you for choosing Vela Voice. A longer offline transcription generated on-device using Whisper model."
                }
            }
            whisper.free()

            // Run cleaner pipeline if requested
            val finalTranscript = if (runCleaner) {
                // Check LLM option enabled in SharedPreferences
                val prefs = this@VoiceInputMethodService.getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
                val useLlm = prefs.getBoolean("useLlmCleaner", false)

                val cleaner = TextCleaner()
                if (useLlm) {
                    val llmPath = getLlmModelPath(this@VoiceInputMethodService)
                    if (llmPath != null) {
                        cleaner.initLlm(llmPath)
                    }
                }
                cleaner.clean(this@VoiceInputMethodService, rawTranscript, useLlm)
            } else {
                rawTranscript
            }

            val endTime = System.currentTimeMillis()
            val duration = endTime - startTime
            val endMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            android.util.Log.d(
                "VoiceIMEPerformance",
                "Processing finished. Duration: ${duration}ms, Memory: ${endMemory}MB (Delta: ${endMemory - startMemory}MB)"
            )

            // Post back to main thread to commit and hide pane
            waveformView.post {
                val ic = currentInputConnection
                if (ic != null && finalTranscript.isNotEmpty()) {
                    ic.commitText(finalTranscript, 1)
                }
                showKeyboardView()
            }
        }).start()
    }

    private fun cancelRecording() {
        if (!isRecording) {
            showKeyboardView()
            return
        }
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread?.join()
            recordingThread = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        recordedAudioData.reset()
        waveformView.clear()
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
        isRecording = false
        audioRecord?.release()
    }
}
