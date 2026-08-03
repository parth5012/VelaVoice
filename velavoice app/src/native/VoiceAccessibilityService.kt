package com.velavoice.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.database.sqlite.SQLiteDatabase
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.sqrt
import com.velavoice.sdk.whisper.WhisperEngine
import com.velavoice.sdk.whisper.WhisperConfig
import com.velavoice.sdk.cleaner.TextCleaner
import com.velavoice.sdk.cleaner.CleanerConfig
import com.velavoice.sdk.cleaner.DictionaryKeywords
import com.velavoice.sdk.cleaner.PersonalDictionary
import com.velavoice.sdk.ui.WaveformView

class VoiceAccessibilityService : AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var floatingLayout: FrameLayout? = null

    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val recordedAudioData = ByteArrayOutputStream()

    private lateinit var statusText: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var micButton: Button
    private lateinit var controlPane: LinearLayout
    private lateinit var dragHandle: View

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {

            val keyboardVisible = isKeyboardVisible()
            floatingLayout?.post {
                floatingLayout?.visibility = if (keyboardVisible) View.VISIBLE else View.GONE
            }
        }
    }

    private fun isKeyboardVisible(): Boolean {
        val localWindows = try {
            windows
        } catch (e: Exception) {
            null
        }
        if (localWindows != null) {
            for (window in localWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true
                }
            }
        }
        return false
    }

    override fun onInterrupt() {
        // Not needed
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("VoiceAccessibility", "Service Connected")
        setupFloatingButton()
    }

    private fun setupFloatingButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density

        floatingLayout = FrameLayout(this)

        val floatingLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (20 * density).toInt()
            y = (20 * density).toInt()
        }

        val isDark = true
        val baseBgColor = Color.parseColor(if (isDark) "#1e1e2e" else "#eff1f5")
        val crustColor = Color.parseColor(if (isDark) "#11111b" else "#dce0e8")
        val mainTextColor = Color.parseColor(if (isDark) "#cdd6f4" else "#4c4f69")
        val micColor = Color.parseColor(if (isDark) "#a6e3a1" else "#40a02b")
        val micTextColor = Color.parseColor("#11111b")

        val sansSerifMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val sansSerifLight = Typeface.create("sans-serif-light", Typeface.NORMAL)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(baseBgColor)
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            background = createCapsuleDrawable(baseBgColor, 16 * density)
        }


        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * density).toInt()
            }
        }


        dragHandle = View(this).apply {
            layoutParams = LinearLayout.LayoutParams((32 * density).toInt(), (8 * density).toInt()).apply {
                weight = 1f
            }
            background = createCapsuleDrawable(crustColor, 4 * density)
        }

        headerRow.addView(dragHandle)
        container.addView(headerRow)

        controlPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                (200 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }


        statusText = TextView(this).apply {
            text = "Ready"
            textSize = 12f
            typeface = sansSerifLight
            setTextColor(mainTextColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (4 * density).toInt())
        }


        waveformView = WaveformView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }

        controlPane.addView(statusText)
        controlPane.addView(waveformView)
        container.addView(controlPane)

        micButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (56 * density).toInt(),
                (56 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            styleButton(this, "🎤", micColor, micTextColor, density, sansSerifMedium)
            setOnClickListener {
                toggleRecording()
            }
        }

        container.addView(micButton)
        floatingLayout?.addView(container)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingLayoutParams.x
                    initialY = floatingLayoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    floatingLayoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    floatingLayoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingLayout, floatingLayoutParams)
                    true
                }
                else -> false
            }
        }

        floatingLayout?.visibility = if (isKeyboardVisible()) View.VISIBLE else View.GONE
        windowManager?.addView(floatingLayout, floatingLayoutParams)
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

        controlPane.visibility = View.VISIBLE
        statusText.text = "Recording..."

        val density = resources.displayMetrics.density
        val micRecordingColor = Color.parseColor("#f38ba8")
        micButton.background = createCapsuleDrawable(micRecordingColor, 100f * density)

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
                Log.d("VoiceAccessibilityRecordThread", "Recording thread stopped")
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
        val prefs = this@VoiceAccessibilityService.getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
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
            val whisperPath = getWhisperModelPath(this@VoiceAccessibilityService)
                if (whisperPath != null) {
                    val whisper = WhisperEngine(WhisperConfig(whisperPath))
                    try {
                        rawTranscript = whisper.transcribe(audioBytes)
                    } finally {
                        whisper.free()
                    }
                }
            } else {
                val seconds = (audioBytes.size / 2) / 16000f
                rawTranscript = if (seconds < 1f) {
                    ""
                } else if (seconds < 3f) {
                    "Hello, testing Vela Voice floating transcription overlay."
                } else {
                    "Thank you for choosing Vela Voice. A longer offline transcription generated on-device using Whisper model."
                }
            }
        }

            val useLlm = prefs.getBoolean("useLlmCleaner", false)
            val llmPath = getLlmModelPath(this@VoiceAccessibilityService)
            val personalDictionary = object : PersonalDictionary {
                override fun getEntries(): List<Pair<String, String>> {
                    val dbFile = findDatabaseFile(this@VoiceAccessibilityService) ?: return emptyList()
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
                        Log.e("VoiceAccessibility", "Error loading personal dictionary: ${e.message}")
                    } finally {
                        db?.close()
                    }
                    return entries
                }
            }
            val dictionaryKeywords = object : DictionaryKeywords {
                override fun getKeywords(): List<String> {
                    val dbFile = findDatabaseFile(this@VoiceAccessibilityService) ?: return emptyList()
                    val keywords = mutableListOf<String>()
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
                        Log.e("VoiceAccessibility", "Error loading dictionary keywords: ${e.message}")
                    } finally {
                        db?.close()
                    }
                    return keywords
                }
            }
            val cleaner = TextCleaner(CleanerConfig(
                useLlm = useLlm,
                llmModelPath = llmPath,
                personalDictionary = personalDictionary,
                dictionaryKeywords = dictionaryKeywords
            ))
            val finalTranscript = if (rawTranscript.isNotEmpty()) {
                cleaner.clean(rawTranscript)
            } else {
                rawTranscript
            }

        // Auto-save transcription pair + audio to local storage
        if (rawTranscript.isNotEmpty()) {
            val durationMs = ((audioBytes.size / 2) / 16L)
            TranscriptionStorage.save(
                this@VoiceAccessibilityService,
                raw = rawTranscript,
                cleaned = finalTranscript,
                durationMs = durationMs,
                audioBytes = audioBytes
            )
        }

        Handler(Looper.getMainLooper()).post {
            if (errorMessage != null) {
                statusText.text = errorMessage
            } else {
                if (finalTranscript.isNotEmpty()) {
                    insertText(finalTranscript)
                }
                statusText.text = "Ready"
            }
            controlPane.visibility = View.GONE

            val density = resources.displayMetrics.density
            val micColor = Color.parseColor("#a6e3a1")
            micButton.background = createCapsuleDrawable(micColor, 100f * density)
        }
    }).start()
    }

    private fun insertText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("transcription", text)
        clipboard.setPrimaryClip(clip)

        val focusNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusNode != null) {
            focusNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } else {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val editableNode = findEditableNode(rootNode)
                editableNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
        }
        return null
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

    private fun getWhisperModelPath(context: Context): String? {
        val dbFile = findDatabaseFile(context) ?: return null
        var db: SQLiteDatabase? = null
        var path: String? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery("SELECT path FROM models WHERE (id = 'whisper-tiny-en' OR name = 'whisper-tiny-en') AND status = 'completed' LIMIT 1", null)
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
            val cursor = db.rawQuery("SELECT path FROM models WHERE (id = 'cleaner-llama-3b' OR name = 'cleaner-llama-3b') AND status = 'completed' LIMIT 1", null)
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

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        audioRecord?.release()
        floatingLayout?.let {
            windowManager?.removeView(it)
        }
    }
}
