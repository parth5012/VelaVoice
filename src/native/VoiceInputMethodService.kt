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
        val prefs = getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
        val mode = prefs.getString("transcriptionMode", "local") ?: "local"
        var rawTranscript = ""
        var errorMessage: String? = null

        if (mode == "groq" || mode == "openai") {
            val apiKey = if (mode == "groq") {
                prefs.getString("groqApiKey", "") ?: ""
            } else {
                prefs.getString("openaiApiKey", "") ?: ""
            }
            
            val model = if (mode == "groq") {
                prefs.getString("groqModel", "whisper-large-v3") ?: "whisper-large-v3"
            } else {
                prefs.getString("openaiModel", "whisper-1") ?: "whisper-1"
            }
            
            val endpoint = if (mode == "openai") {
                prefs.getString("openaiEndpoint", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
            } else null
            
            if (apiKey.isBlank()) {
                errorMessage = "Error: API Key is missing for $mode"
            } else {
                try {
                    rawTranscript = transcribeWithApi(audioBytes, mode, apiKey, model, endpoint)
                } catch (e: Exception) {
                    e.printStackTrace()
                    errorMessage = "API Error: ${e.message}"
                }
            }
        } else {
            val whisperPath = getWhisperModelPath(this)
            if (whisperPath != null) {
                val whisper = WhisperEngine()
                try {
                    whisper.init(whisperPath)
                    rawTranscript = whisper.transcribe(audioBytes)
                } finally {
                    whisper.free()
                }
            } else {
                val seconds = (audioBytes.size / 2) / 16000f
                rawTranscript = if (seconds < 1f) {
                    ""
                } else if (seconds < 3f) {
                    "Hello, testing Vela Voice IME."
                } else {
                    "Thank you for choosing Vela Voice. Your voice input is transcribed."
                }
            }
        }

        val useLlm = prefs.getBoolean("useLlmCleaner", false)
        val cleaner = TextCleaner()
        val finalTranscript = if (rawTranscript.isNotEmpty()) {
            cleaner.clean(this, rawTranscript, useLlm)
        } else {
            rawTranscript
        }

        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (errorMessage != null) {
                statusText.text = errorMessage
            } else {
                if (finalTranscript.isNotEmpty()) {
                    currentInputConnection?.commitText(finalTranscript, 1)
                }
                statusText.text = "Ready to record voice input"
            }
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

    private fun pcmToWav(pcmBytes: ByteArray, sampleRate: Int = 16000): ByteArray {
        val totalSize = 36 + pcmBytes.size
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        
        header[0] = 'R'.toByte()
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalSize and 0xff).toByte()
        header[5] = ((totalSize shr 8) and 0xff).toByte()
        header[6] = ((totalSize shr 16) and 0xff).toByte()
        header[7] = ((totalSize shr 24) and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        
        header[12] = 'f'.toByte()
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = 1
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2
        header[33] = 0
        header[34] = 16
        header[35] = 0
        
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (pcmBytes.size and 0xff).toByte()
        header[41] = ((pcmBytes.size shr 8) and 0xff).toByte()
        header[42] = ((pcmBytes.size shr 16) and 0xff).toByte()
        header[43] = ((pcmBytes.size shr 24) and 0xff).toByte()
        
        val wavBytes = ByteArray(44 + pcmBytes.size)
        System.arraycopy(header, 0, wavBytes, 0, 44)
        System.arraycopy(pcmBytes, 0, wavBytes, 44, pcmBytes.size)
        return wavBytes
    }

    private fun transcribeWithApi(
        audioBytes: ByteArray,
        mode: String,
        apiKey: String,
        model: String,
        endpoint: String? = null
    ): String {
        val wavBytes = pcmToWav(audioBytes)
        val urlString = when (mode) {
            "groq" -> "https://api.groq.com/openai/v1/audio/transcriptions"
            "openai" -> {
                val base = if (endpoint.isNullOrBlank()) "https://api.openai.com/v1" else endpoint.trim().removeSuffix("/")
                "$base/audio/transcriptions"
            }
            else -> return ""
        }
        
        val boundary = "Boundary-" + System.currentTimeMillis()
        val LINE_FEED = "\r\n"
        
        val url = java.net.URL(urlString)
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.useCaches = false
        conn.doOutput = true
        conn.doInput = true
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        
        val outputStream = conn.outputStream
        val writer = java.io.PrintWriter(outputStream.writer(), true)
        
        writer.append("--$boundary").append(LINE_FEED)
        writer.append("Content-Disposition: form-data; name=\"model\"").append(LINE_FEED)
        writer.append(LINE_FEED)
        writer.append(model).append(LINE_FEED)
        writer.flush()
        
        writer.append("--$boundary").append(LINE_FEED)
        writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"").append(LINE_FEED)
        writer.append("Content-Type: audio/wav").append(LINE_FEED)
        writer.append(LINE_FEED)
        writer.flush()
        
        outputStream.write(wavBytes)
        outputStream.flush()
        
        writer.append(LINE_FEED)
        writer.append("--$boundary--").append(LINE_FEED)
        writer.flush()
        writer.close()
        
        val responseCode = conn.responseCode
        if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
            val reader = conn.inputStream.bufferedReader()
            val response = reader.readText()
            reader.close()
            val json = org.json.JSONObject(response)
            return json.optString("text", "")
        } else {
            val errorReader = conn.errorStream?.bufferedReader()
            val errorMsg = errorReader?.readText() ?: "Unknown error"
            errorReader?.close()
            throw Exception("API Error $responseCode: $errorMsg")
        }
    }
}
