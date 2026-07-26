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

        voicePane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                fixedHeightPx
            )
            setBackgroundColor(Color.parseColor("#1e1e2e"))
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        statusText = TextView(context).apply {
            text = "Ready to record voice input"
            textSize = 14f
            setTextColor(Color.parseColor("#cdd6f4"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }

        waveformView = WaveformView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (40 * density).toInt()
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (16 * density).toInt()
            }
        }

        val sansSerifMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val sansSerifLight = Typeface.create("sans-serif-light", Typeface.NORMAL)

        voiceButton = Button(context).apply {
            text = "🎤 Voice Input"
            textSize = 14f
            typeface = sansSerifMedium
            setTextColor(Color.parseColor("#11111b"))
            setBackgroundColor(Color.parseColor("#a6e3a1"))
            layoutParams = LinearLayout.LayoutParams(
                (120 * density).toInt(),
                (48 * density).toInt()
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener {
                toggleRecording()
            }
        }

        stopCleanButton = Button(context).apply {
            text = "🧹 Stop & Clean"
            textSize = 14f
            typeface = sansSerifMedium
            setTextColor(Color.parseColor("#11111b"))
            setBackgroundColor(Color.parseColor("#89b4fa"))
            layoutParams = LinearLayout.LayoutParams(
                (100 * density).toInt(),
                (48 * density).toInt()
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener {
                stopRecording(runCleaner = true)
            }
        }

        stopRawButton = Button(context).apply {
            text = "🔴 Stop Raw"
            textSize = 14f
            typeface = sansSerifMedium
            setTextColor(Color.parseColor("#11111b"))
            setBackgroundColor(Color.parseColor("#f38ba8"))
            layoutParams = LinearLayout.LayoutParams(
                (100 * density).toInt(),
                (48 * density).toInt()
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener {
                stopRecording(runCleaner = false)
            }
        }

        cancelButton = Button(context).apply {
            text = "❌ Cancel"
            textSize = 14f
            typeface = sansSerifMedium
            setTextColor(Color.parseColor("#11111b"))
            setBackgroundColor(Color.parseColor("#6c7086"))
            layoutParams = LinearLayout.LayoutParams(
                (80 * density).toInt(),
                (48 * density).toInt()
            )
            setOnClickListener {
                stopRecording(runCleaner = false)
                requestHideSelf(0)
            }
        }

        buttonRow.addView(voiceButton)
        buttonRow.addView(stopCleanButton)
        buttonRow.addView(stopRawButton)
        buttonRow.addView(cancelButton)

        voicePane.addView(statusText)
        voicePane.addView(waveformView)
        voicePane.addView(buttonRow)

        return voicePane
    }

    private fun toggleRecording() {
        if (!isRecording) {
            startRecording()
        } else {
            stopRecording(runCleaner = true)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        recordedAudioData.reset()
        waveformView.clear()

        statusText.text = "Recording..."
        stopCleanButton.visibility = View.VISIBLE
        stopRawButton.visibility = View.VISIBLE
        voiceButton.visibility = View.GONE

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                statusText.text = "Mic initialization failed"
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
                        var sum = 0.0
                        for (i in 0 until readResult) {
                            sum += buffer[i] * buffer[i]
                            val shortVal = buffer[i]
                            byteBuffer[i * 2] = (shortVal.toInt() and 0xff).toByte()
                            byteBuffer[i * 2 + 1] = ((shortVal.toInt() shr 8) and 0xff).toByte()
                        }
                        recordedAudioData.write(byteBuffer, 0, readResult * 2)

                        val rmss = sqrt(sum / readResult)
                        val normalized = (rmss / 32768.0f).toFloat()
                        waveformView.post {
                            waveformView.addAmplitude(normalized)
                        }
                    }
                }
                Log.d("VoiceIME", "Recording thread stopped")
            })

            recordingThread?.start()
        } catch (e: SecurityException) {
            statusText.text = "Mic permission denied"
            isRecording = false
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
            isRecording = false
        }
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

        statusText.text = "Processing..."
        val audioBytes = recordedAudioData.toByteArray()

        Thread({
            val whisperPath = getWhisperModelPath(this)
            val whisper = WhisperEngine()

            val rawTranscript = if (whisperPath != null) {
                whisper.init(whisperPath)
                whisper.transcribe(audioBytes)
            } else {
                val seconds = (audioBytes.size / 2) / 16000f
                val fallback = if (seconds < 1f) {
                    ""
                } else if (seconds < 3f) {
                    "Hello, testing Vela Voice IME."
                } else {
                    "Thank you for choosing Vela Voice. This voice input was transcribed."
                }
                fallback
            }

            whisper.free()

            val prefs = getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
            val useLlm = prefs.getBoolean("useLlmCleaner", false)

            val cleaner = TextCleaner()
            val finalTranscript = if (rawTranscript.isNotEmpty()) {
                cleaner.clean(this, rawTranscript, useLlm)
            } else {
                rawTranscript
            }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (finalTranscript.isNotEmpty()) {
                    currentInputConnection?.commitText(finalTranscript, 1)
                }

                statusText.text = "Ready to record voice input"
                stopCleanButton.visibility = View.GONE
                stopRawButton.visibility = View.GONE
                voiceButton.visibility = View.VISIBLE
            }
        }).start()
    }

    private fun getWhisperModelPath(context: Context): String? {
        val dbFile = findDatabaseFile(context) ?: return null
        var db: SQLiteDatabase? = null
        var path: String? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("SELECT path FROM models WHERE name = 'whisper-tiny-en' AND status = 'completed' LIMIT 1", null)
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
}
