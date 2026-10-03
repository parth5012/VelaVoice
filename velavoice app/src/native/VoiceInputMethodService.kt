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
import com.velavoice.sdk.StreamingTranscriptionCallback
import com.velavoice.sdk.RevisionMarker
import com.velavoice.sdk.StreamConfig
import com.velavoice.sdk.StreamingPipeline
import com.velavoice.sdk.PrivacyGuard
import com.velavoice.sdk.ScribeInput
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

    // Streaming state
    private val isStreaming = AtomicBoolean(false)
    private var streamingMode = "instant"
    private var streamingPipeline: StreamingPipeline? = null
    private val streamingBuffer = StringBuilder()
    private var streamingCommittedLength = 0
    /**
     * Privacy verdict captured when the current voice session started
     * (map #130 ticket #133, mirrors keyboard #78 snapshot). Rechecks may
     * only TIGHTEN (abort non-sensitive→sensitive), never widen.
     */
    @Volatile
    private var sessionStartedPrivacySensitive = false
    /**
     * Set when a privacy recheck aborts streaming: suppresses the trailing
     * onFinal save so the aborted tail is discarded (no save, no UI tail).
     */
    @Volatile
    private var streamingCancelled = false
    /**
     * Monotonic streaming-session generation (map #130 PR #137 fix). Bumped at
     * queue time by startStreaming() and by invalidatePendingStreamingStart(),
     * so a worker whose start outlived its session can tell it is stale.
     */
    @Volatile
    private var streamingSessionGeneration = 0
    /**
     * True between queueing startStreaming()'s pipeline construction and
     * isStreaming becoming true (map #130 PR #137 fix). startStreaming() used
     * to be a no-op for recheck/onFinishInput during that window, letting the
     * pipeline start later with the stale privacy verdict and record past the
     * end of input.
     */
    @Volatile
    private var streamingStartPending = false

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
                loadStreamingMode()
                if (streamingMode == "streamed") {
                    startStreaming()
                } else {
                    startRecording()
                }
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
        loadStreamingMode()
        if (streamingMode == "streamed") {
            startStreaming()
        } else {
            startRecording()
        }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // Focus moved: abort a running voice session if the new field is
        // sensitive (map #130 ticket #133, same wiring LatinIME got in #78).
        recheckSessionPrivacy(info)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Editor attributes can change mid-session without input finishing;
        // cheap no-op outside an active voice session (ticket #133, cf. #78).
        recheckSessionPrivacy(currentInputEditorInfo)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Input is ending: stop any running voice session and never keep
        // recording into the next field (same wiring LatinIME got in #78:
        // onFinishInput* -> stop).
        if (isStreaming.get()) {
            stopStreaming()
        } else if (streamingStartPending) {
            // Input ended while the pipeline was still being built: discard the
            // queued start, never record into the next field (map #130 PR #137 fix).
            invalidatePendingStreamingStart()
        } else if (isRecording.get()) {
            cancelRecording()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (isStreaming.get()) {
            stopStreaming()
        } else if (streamingStartPending) {
            // Same as onFinishInput: a queued start must not outlive the input.
            invalidatePendingStreamingStart()
        } else if (isRecording.get()) {
            cancelRecording()
        }
    }

    // ──────────────────────────────────────────────
    // Streaming Transcription (velaboard IME)
    // ──────────────────────────────────────────────

    private fun loadStreamingMode() {
        val prefs = getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
        streamingMode = prefs.getString("streamingMode", "instant") ?: "instant"
    }

    /**
     * Privacy classification for the current editor (map #72 ticket #76).
     * Fails closed when no editor can be resolved.
     */
    private fun isSessionPrivacySensitive(): Boolean {
        val editor = currentInputEditorInfo
        return editor == null || PrivacyGuard.isPrivacySensitiveEditor(editor)
    }

    /** True while a voice session may still be recording or streaming (map #130 ticket #133). */
    private fun isSessionActive(): Boolean {
        // A queued-but-unstarted streaming session is still active: the worker
        // has not reached pipeline.start() yet, so a flip must be able to abort
        // it (map #130 PR #137 fix).
        return isRecording.get() || isStreaming.get() || streamingStartPending
    }

    /**
     * Invalidate a queued streaming start (map #130 PR #137 fix): bumping the
     * generation makes the bg worker discard its pipeline instead of starting
     * it, so no audio is recorded under a stale privacy verdict and nothing is
     * recorded after the input finished.
     */
    private fun invalidatePendingStreamingStart() {
        streamingSessionGeneration++
        streamingStartPending = false
    }

    /**
     * Re-evaluate the privacy verdict while a session runs (map #130 ticket #133,
     * mirrors keyboard #78 recheckSessionPrivacy).
     * Focus can change without the input finishing; if the verdict flips to
     * sensitive after a non-sensitive start, abort the session (fail closed)
     * and discard audio — never transcribe/save/commit the tail.
     * A session already started sensitive is never touched, and outside an
     * active session this is a no-op so hot paths stay cheap. Never widens:
     * only false→true, never true→false. Null editor fails closed (abort).
     */
    fun recheckSessionPrivacy(editorInfo: EditorInfo?) {
        if (!isSessionActive()) return
        if (sessionStartedPrivacySensitive) return
        if (!PrivacyGuard.isPrivacySensitiveEditor(editorInfo, true)) return
        // Tighten-only: record the flip so trailing callbacks stay gated.
        sessionStartedPrivacySensitive = true
        cancelRecording()
        // cancelStreaming() also invalidates a still-queued start (map #130
        // PR #137 fix) — isSessionActive() above counts it as a live session.
        cancelStreaming()
    }

    private fun startStreaming() {
        if (isStreaming.get() || streamingStartPending) return
        loadStreamingMode()
        val sessionPrivacySensitive = isSessionPrivacySensitive()
        sessionStartedPrivacySensitive = sessionPrivacySensitive
        streamingCancelled = false
        // Claim the session generation at queue time so a flip or onFinishInput
        // during construction invalidates this start (map #130 PR #137 fix).
        val generation = ++streamingSessionGeneration
        streamingStartPending = true

        if (streamingMode == "instant") {
            streamingStartPending = false
            startRecording()
            return
        }

        voiceRecordingPane.resetDisplay()
        voiceRecordingPane.statusText.text = "Streaming..."

        val handler = bgHandler
        if (handler == null) {
            // No worker (service destroyed): release the claim so a later
            // startStreaming() is not blocked by a phantom pending start.
            invalidatePendingStreamingStart()
            return
        }

        handler.post {
            try {
                loadModelPaths()
                val whisperPath = cachedWhisperPath
                if (whisperPath == null) {
                    invalidatePendingStreamingStart()
                    mainHandler.post {
                        voiceRecordingPane.statusText.text = "No whisper model found."
                    }
                    return@post
                }

                // Route streaming through the single gated emit path (map #130
                // ticket #132): start() force-selects local for sensitive
                // sessions and dispatchEmit() refuses cloud uploads, so
                // local-only stays local-only (no cloud mode introduced). The
                // pipeline owns mic capture and VAD — no raw transcriber.emit.
                val pipeline = StreamingPipeline.Builder(this@VoiceInputMethodService)
                    .whisperModelPath(whisperPath)
                    .privacySensitive(sessionPrivacySensitive)
                    .build()
                // Stale-start guard (map #130 PR #137 fix): a privacy flip or
                // onFinishInput may have invalidated this start while the
                // pipeline was being built — discard it (never start, never
                // record) and release it so it owns nothing.
                if (generation != streamingSessionGeneration) {
                    try {
                        pipeline.release()
                    } catch (ignored: Exception) {
                    }
                    mainHandler.post {
                        voiceRecordingPane.statusText.text = "Ready"
                    }
                    return@post
                }
                streamingPipeline = pipeline

                pipeline.setCallback(object : StreamingTranscriptionCallback {
                    override fun onRevisionMarker(marker: RevisionMarker) {
                        // Aborted session (privacy flip): discard trailing markers.
                        if (streamingCancelled) return
                        mainHandler.post {
                            when (marker.type) {
                                "partial" -> {
                                    updateStreamingBuffer(marker.text, marker.range)
                                    // Show partial text as composing (gray/italic via spans)
                                    val ic = currentInputConnection
                                    if (ic != null) {
                                        val text = streamingBuffer.toString()
                                        ic.setComposingText(text, 1)
                                    }
                                    voiceRecordingPane.statusText.text = marker.text
                                }
                                "commit" -> {
                                    // Commit text
                                    val ic = currentInputConnection
                                    if (ic != null) {
                                        ic.finishComposingText()
                                        streamingCommittedLength = marker.range.last
                                    }
                                }
                            }
                        }
                    }

                    override fun onFinal(text: String) {
                        // Aborted session (privacy flip): discard the tail — no save.
                        if (streamingCancelled) return
                        // Capture the verdict + generation synchronously, before
                        // posting (map #130 PR #137 fix): stopStreaming() emits
                        // this final inline, and startStreaming() resets the flag
                        // before the posted task runs — so reading the live flag
                        // inside the task could save a sensitive transcript.
                        val sensitiveAtFinal = sessionStartedPrivacySensitive
                        val sessionGeneration = streamingSessionGeneration
                        mainHandler.post {
                            // Single-commit: stopStreaming() already committed the
                            // remaining buffer to the InputConnection — onFinal
                            // must NOT commit again (double commit). Only
                            // status, privacy-gated save, keyboard restore.
                            voiceRecordingPane.statusText.text = "Done"
                            isStreaming.set(false)
                            // Auto-save — never persist transcripts of privacy-sensitive fields.
                            // Gate on the verdict captured at onFinal for this session
                            // and reject a completion whose session has moved on
                            // (a newer start already reset the flag) — reading the
                            // mutable service flag here would save a sensitive final.
                            if (sessionGeneration == streamingSessionGeneration && !sensitiveAtFinal) {
                                TranscriptionStorage.save(
                                    this@VoiceInputMethodService,
                                    raw = text,
                                    cleaned = text,
                                    durationMs = 0
                                )
                            }
                            showKeyboardView()
                        }
                    }

                    override fun onError(error: VelaException) {
                        mainHandler.post {
                            voiceRecordingPane.statusText.text = error.message
                            isStreaming.set(false)
                        }
                    }

                    override fun onAmplitude(normalized: Float) {
                        voiceRecordingPane.waveformView.post {
                            voiceRecordingPane.waveformView.addAmplitude(normalized)
                        }
                    }
                })

                val streamConfig = StreamConfig(
                    modelPath = whisperPath,
                    chunkDurationMs = 3000,
                    windowDurationMs = 15000,
                    overlapMs = 1500,
                    resetIntervalMs = 30000,
                    useVad = true,
                    vadThreshold = 0.02f,
                    privacySensitive = sessionPrivacySensitive
                )
                pipeline.start("local", streamConfig)

                mainHandler.post {
                    isStreaming.set(true)
                    streamingStartPending = false
                    streamingBuffer.setLength(0)
                    streamingCommittedLength = 0
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceIME", "Start streaming failed", e)
                // No leak: start() may throw after the pipeline was assigned —
                // release and nullify so a failed start owns nothing.
                try {
                    streamingPipeline?.release()
                } catch (ignored: Exception) {
                }
                streamingPipeline = null
                streamingStartPending = false
                mainHandler.post {
                    voiceRecordingPane.statusText.text = "Streaming init error"
                }
            }
        }
    }

    private fun stopStreaming() {
        if (!isStreaming.get()) return
        isStreaming.set(false)

        // Commit remaining text
        val ic = currentInputConnection
        if (ic != null) {
            ic.finishComposingText()
            if (streamingBuffer.length > streamingCommittedLength) {
                val remaining = streamingBuffer.substring(streamingCommittedLength).trim()
                if (remaining.isNotEmpty()) {
                    ic.commitText(remaining + " ", 1)
                }
            }
        }

        streamingPipeline?.stop()
        streamingPipeline?.release()
        streamingPipeline = null
    }

    /**
     * Abort streaming and discard buffered audio without commit, save or
     * composing-tail (map #130 ticket #133). Distinct from stopStreaming()
     * which commits remaining text and drives the single canonical onFinal;
     * cancel suppresses that trailing onFinal via streamingCancelled so the
     * aborted tail is never persisted nor committed.
     */
    private fun cancelStreaming() {
        if (!isStreaming.get() && !streamingStartPending) return
        isStreaming.set(false)
        streamingCancelled = true
        // Abort a queued-but-unstarted pipeline too (map #130 PR #137 fix):
        // isStreaming is still false while startStreaming() builds.
        invalidatePendingStreamingStart()
        streamingBuffer.setLength(0)
        streamingCommittedLength = 0
        streamingPipeline?.stop()
        streamingPipeline?.release()
        streamingPipeline = null
        voiceRecordingPane.waveformView.clear()
        showKeyboardView()
    }

    private fun updateStreamingBuffer(text: String, range: IntRange) {
        if (streamingBuffer.length < range.last) {
            streamingBuffer.setLength(range.last)
        }
        streamingBuffer.replace(range.first, range.last, text)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording.set(false)
        isStreaming.set(false)
        // A queued start must not survive the service: the worker discards its
        // pipeline instead of recording after destroy (map #130 PR #137 fix).
        invalidatePendingStreamingStart()
        timerHandler?.removeCallbacksAndMessages(null)
        transcriber?.release()
        transcriber = null
        streamingPipeline?.release()
        streamingPipeline = null
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
        val sessionPrivacySensitive = isSessionPrivacySensitive()
        sessionStartedPrivacySensitive = sessionPrivacySensitive

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

                if (PrivacyGuard.shouldEnableLlmCleaner(useLlm, sessionPrivacySensitive) && cachedLlmPath != null) {
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
                            // Auto-save — never persist transcripts of privacy-sensitive fields.
                            // Read the volatile flip snapshot (not the frozen start local) so a
                            // mid-session flip-to-sensitive blocks the save (map #130 ticket #133 fix).
                            if (!sessionStartedPrivacySensitive) {
                                TranscriptionStorage.save(
                                    this@VoiceInputMethodService,
                                    raw = result.rawTranscript,
                                    cleaned = result.cleanedTranscript,
                                    durationMs = result.durationMs,
                                    audioBytes = result.audioBytes
                                )
                            }
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
                    }, ScribeInput(privacySensitive = sessionPrivacySensitive))
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
        if (!isRecording.get()) return
        isRecording.set(false)
        timerHandler?.removeCallbacksAndMessages(null)
        // Cancel semantics (#74): discard audio without transcription, flush
        // or save — never plain stopRecording which would transcribe the tail.
        // AudioRecorder.cancel() emits no callback, so no transcript/file/UI tail.
        transcriber?.cancelRecording()
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
        db.rawQuery(
            "SELECT path FROM models WHERE (id = ? OR name = ?) AND status = 'completed' LIMIT 1",
            arrayOf(modelType, modelType)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
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
                db.rawQuery(
                    "SELECT original_word, replacement FROM personal_dictionary ORDER BY priority DESC, original_word ASC",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        do {
                            val original = cursor.getString(0)
                            val replacement = cursor.getString(1)
                            if (original.isNotEmpty()) {
                                entries.add(Pair(original, replacement))
                            }
                        } while (cursor.moveToNext())
                    }
                }
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
                db.rawQuery(
                    "SELECT keyword FROM dictionary_keywords ORDER BY keyword ASC",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        do {
                            val keyword = cursor.getString(0)
                            if (keyword.isNotEmpty()) {
                                keywords.add(keyword)
                            }
                        } while (cursor.moveToNext())
                    }
                }
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
