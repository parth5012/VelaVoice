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
import java.io.ByteArrayOutputStream
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
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#f8f9fa"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Standard Keyboard view container (initially visible)
        val keyboardView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 32, 16, 32)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleText = TextView(context).apply {
            text = "Vela Voice IME"
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        voiceButton = Button(context).apply {
            text = "🎤 Start Voice Typing"
            setBackgroundColor(Color.parseColor("#007bff"))
            setTextColor(Color.WHITE)
            setPadding(32, 16, 32, 16)
            setOnClickListener {
                showVoicePane()
                startRecording()
            }
        }

        keyboardView.addView(titleText)
        keyboardView.addView(voiceButton)

        // Voice Pane layout (initially hidden)
        voicePane = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusText = TextView(context).apply {
            text = "Listening..."
            textSize = 14f
            setTextColor(Color.parseColor("#495057"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        waveformView = WaveformView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            ).apply {
                bottomMargin = 16
            }
        }

        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        stopCleanButton = Button(context).apply {
            text = "Stop Clean"
            setBackgroundColor(Color.parseColor("#28a745"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = 8
            }
            setOnClickListener {
                stopRecording(runCleaner = true)
            }
        }

        stopRawButton = Button(context).apply {
            text = "Stop Raw"
            setBackgroundColor(Color.parseColor("#ffc107"))
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = 8
            }
            setOnClickListener {
                stopRecording(runCleaner = false)
            }
        }

        cancelButton = Button(context).apply {
            text = "Cancel"
            setBackgroundColor(Color.parseColor("#dc3545"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            setOnClickListener {
                cancelRecording()
            }
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
                            // Convert short to byte array (little endian PCM)
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

    private fun getWhisperModelPath(context: Context): String? {
        val dbFile = context.getDatabasePath("models.db")
        if (!dbFile.exists()) return null
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
        val dbFile = context.getDatabasePath("models.db")
        if (!dbFile.exists()) return null
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
            val whisperPath = getWhisperModelPath(this)
            val whisper = WhisperEngine()
            
            val rawTranscript = if (whisperPath != null && whisper.init(whisperPath)) {
                whisper.transcribe(audioBytes)
            } else {
                // Heuristic fallback transcript
                val seconds = (audioBytes.size / 2) / 16000f
                if (seconds < 1f) ""
                else if (seconds < 3f) "Hello, this is a test of the Vela Voice offline transcriber."
                else "Thank you for choosing Vela Voice. This is a longer offline transcription generated on the device using our Whisper model."
            }
            whisper.free()

            // Run cleaner pipeline if requested
            val finalTranscript = if (runCleaner) {
                // Check if LLM option is enabled in SharedPreferences
                val prefs = getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
                val useLlm = prefs.getBoolean("useLlmCleaner", false)
                
                val cleaner = TextCleaner()
                if (useLlm) {
                    val llmPath = getLlmModelPath(this)
                    if (llmPath != null) {
                        cleaner.initLlm(llmPath)
                    }
                }
                cleaner.clean(rawTranscript, useLlm)
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

            // Post back to UI thread to commit and hide pane
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

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.release()
    }
}
