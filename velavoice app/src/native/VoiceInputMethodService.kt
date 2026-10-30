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
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import com.velavoice.sdk.AudioRecorder
import com.velavoice.sdk.AmplitudeCoalescer
import com.velavoice.sdk.TranscriptionResult
import com.velavoice.sdk.VelaException
import com.velavoice.sdk.VelaRecordingCallback
import com.velavoice.sdk.VelaTranscriber
import com.velavoice.sdk.cleaner.DictionaryKeywords
import com.velavoice.sdk.cleaner.PersonalDictionary
import com.velavoice.sdk.ui.VoiceRecordingPane
import com.velavoice.sdk.ui.VoiceStatus
import com.velavoice.sdk.StreamingTranscriptionCallback
import com.velavoice.sdk.RevisionMarker
import com.velavoice.sdk.TextCommitter
import com.velavoice.sdk.StreamConfig
import com.velavoice.sdk.StreamingPipeline
import com.velavoice.sdk.PrivacyGuard
import com.velavoice.sdk.RecordingSessionGate
import com.velavoice.sdk.ScribeInput
import com.velavoice.sdk.sanitizeForInsertion
import com.velavoice.sdk.shouldCommitTranscript
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

    // Recording state — single IDLE/RECORDING/PROCESSING gate (map #97 #98):
    // the only entry into a recording session; start/stop/cancel are no-ops
    // outside their legal transitions, so double-taps and re-entries can
    // never re-enter a live session or re-run teardown.
    private var recordingSeconds = 0
    private var timerHandler: Handler? = null
    private val recordingGate = RecordingSessionGate()

    /**
     * Amplitude meter throttle (map #105 ticket #113): the mic callbacks fire
     * per read buffer (~50/s); without this each one allocates a `post {}`
     * runnable on the main thread. The coalescer keeps the latest sample and
     * grants at most one post per 66 ms (~15 Hz); offers while the pane is
     * hidden grant nothing. The VAD path is unaffected — `adaptiveVad.observe`
     * still runs per tick inside the pipeline (#111).
     */
    private val amplitudeCoalescer = AmplitudeCoalescer()
    /**
     * Pane visibility for the capture thread (main-thread writes, volatile
     * read): `View.isShown` must not be read off-main, so show/hide maintain
     * this mirror instead. Hidden offers are dropped before any post.
     */
    @Volatile
    private var voicePaneVisible = false
    /**
     * The SINGLE amplitude runnable (ticket #113): the only object ever posted
     * for the meter — it drains the latest coalesced sample. The [pending]
     * guard inside the coalescer guarantees at most one instance is queued.
     */
    private val amplitudeRunnable = Runnable {
        val wave = voiceRecordingPane.waveformView
        amplitudeCoalescer.poll(SystemClock.uptimeMillis())?.let { wave.addAmplitude(it) }
    }

    // Streaming state
    private val isStreaming = AtomicBoolean(false)
    private var streamingMode = "instant"
    // Written by the bg startup worker, read by the main thread on cancel/stop:
    // @Volatile so the cancel path sees a freshly published pipeline (map #130
    // PR #137 TOCTOU fix — visibility only, the publish-time re-checks below
    // are what close the race).
    @Volatile
    private var streamingPipeline: StreamingPipeline? = null
    private val streamingBuffer = StringBuilder()
    private var streamingCommittedLength = 0
    /**
     * Single commit-text owner (map #105 ticket #107): [textCommitter] tracks
     * the owned revision span (#108) so streaming finals replace the trailing
     * partial instead of appending a duplicate. All InputConnection.commitText
     * calls route through [insertTranscribedText].
     */
    private val textCommitter = TextCommitter()
    /**
     * Buffer length actually rendered via setComposingText (map #105 ticket
     * #107). stopStreaming() finishes the composing span — which commits the
     * shown text — so only the never-shown tail may still need a commit;
     * without this, finishing plus re-committing the shown text duplicates it.
     */
    private var streamingShownLength = 0
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
     * Monotonic streaming-session generation (map #130 PR #137 fix, extended
     * to the batch recording path in PR #138). Bumped at queue time by
     * startStreaming() and at start by startRecording(), and by
     * invalidatePendingStreamingStart(), so a worker whose start outlived its
     * session can tell it is stale — including an async AudioRecorder.stop()
     * onResult that outlives a later session's privacy-flag reset.
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
            // Emoji label alone misreads in TalkBack (map #105 ticket #115).
            contentDescription = context.getString(com.velavoice.sdk.ui.R.string.vela_voice_tap_to_speak)
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
            onStopCleanListener = {
                // #222 routing (F01): startStreaming() never claims the batch
                // gate, so subscription gating by recordingGate.tryStop() is a
                // no-op in streamed mode and left the mic running. Route an
                // active streamed session to stopStreaming() (same path
                // onFinishInput uses), batch sessions to the gated stop.
                if (isStreaming.get()) stopStreaming() else stopRecording(runCleaner = true)
            }
            onStopRawListener = {
                if (isStreaming.get()) stopStreaming() else stopRecording(runCleaner = false)
            }
            onCancelListener = {
                // Same routing for Cancel: cover a queued-but-unstarted
                // pipeline too (streamingStartPending, map #130 PR #137),
                // batch otherwise.
                if (isStreaming.get() || streamingStartPending) cancelStreaming() else cancelRecording()
            }
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
        } else if (recordingGate.isRecording()) {
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
        } else if (recordingGate.isRecording()) {
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
        return recordingGate.isSessionActive() || isStreaming.get() || streamingStartPending
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
        voiceRecordingPane.setStatus(VoiceStatus.STREAMING)

        val handler = bgHandler
        if (handler == null) {
            // No worker (service destroyed): release the claim so a later
            // startStreaming() is not blocked by a phantom pending start.
            invalidatePendingStreamingStart()
            return
        }

        handler.post {
            // Held from build() until the pipeline is published (or discarded):
            // start() can throw before publication, and the failure path below
            // must release whatever this worker built (no leak, #132-fix).
            var unpublished: StreamingPipeline? = null
            try {
                loadModelPaths()
                val whisperPath = cachedWhisperPath
                if (whisperPath == null) {
                    invalidatePendingStreamingStart()
                    mainHandler.post {
                        voiceRecordingPane.setStatus(VoiceStatus.NO_MODEL)
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
                unpublished = pipeline
                // Stale-start guard (map #130 PR #137 fix): a privacy flip or
                // onFinishInput may have invalidated this start while the
                // pipeline was being built — discard it (never start, never
                // record) and release it so it owns nothing.
                if (generation != streamingSessionGeneration) {
                    teardownVoiceEngine(pipeline = pipeline)
                    unpublished = null
                    mainHandler.post {
                        voiceRecordingPane.setStatus(VoiceStatus.READY)
                    }
                    return@post
                }

                pipeline.setCallback(object : StreamingTranscriptionCallback {
                    override fun onRevisionMarker(marker: RevisionMarker) {
                        // Generation-bound (map #130 PR #137 TOCTOU fix): events
                        // from a stale pipeline must never write into a newer
                        // session's editor — its buffer is not ours to compose.
                        if (generation != streamingSessionGeneration) return
                        // Aborted session (privacy flip): discard trailing markers.
                        if (streamingCancelled) return
                        mainHandler.post main@{
                            // Re-check at effect time: the session may have moved
                            // on while this marker was queued.
                            if (generation != streamingSessionGeneration) return@main
                            when (marker.type) {
                                "partial" -> {
                                    updateStreamingBuffer(marker)
                                    // Show only the uncommitted tail as composing: the committed
                                    // prefix is already finalized in the editor, and re-setting
                                    // the whole buffer as composing would duplicate it.
                                    val ic = currentInputConnection
                                    if (ic != null) {
                                        val start = streamingCommittedLength.coerceIn(0, streamingBuffer.length)
                                        ic.setComposingText(streamingBuffer.substring(start), 1)
                                        streamingShownLength = streamingBuffer.length
                                    }
                                    // Live partial: silent text update (map #105 ticket
                                    // #115) — announcing every tick would spam
                                    // TalkBack; the DONE/NOTHING_HEARD state
                                    // change announces instead.
                                    voiceRecordingPane.setStatusText(marker.text, announce = false)
                                }
                                "commit" -> {
                                    // Finalize everything buffered: the composing span shown
                                    // above becomes committed text in the editor. Lengths are
                                    // buffer coordinates (not marker ranges) so trimmed commit
                                    // text cannot rewind them.
                                    val ic = currentInputConnection
                                    if (ic != null) {
                                        ic.finishComposingText()
                                        streamingCommittedLength = streamingBuffer.length
                                        streamingShownLength = streamingBuffer.length
                                    }
                                }
                            }
                        }
                    }

                    override fun onFinal(text: String) {
                        // Generation-bound (map #130 PR #137 TOCTOU fix): a stale
                        // pipeline's final must never commit or save under a
                        // newer session, whatever that session's verdict is.
                        if (generation != streamingSessionGeneration) return
                        // Aborted session (privacy flip): discard the tail — no save.
                        if (streamingCancelled) return
                        // Capture the verdict + generation synchronously, before
                        // posting (map #130 PR #137 fix): stopStreaming() emits
                        // this final inline, and startStreaming() resets the flag
                        // before the posted task runs — so reading the live flag
                        // inside the task could save a sensitive transcript.
                        val sensitiveAtFinal = sessionStartedPrivacySensitive
                        val sessionGeneration = streamingSessionGeneration
                        mainHandler.post main@{
                            // Generation-bound at effect time too: the session may
                            // have moved on while this task was queued, and then
                            // neither the flag flip nor the keyboard restore is
                            // ours to perform.
                            if (sessionGeneration != streamingSessionGeneration) return@main
                            // Single-commit: stopStreaming() already committed the
                            // remaining buffer to the InputConnection — onFinal
                            // must NOT commit again (double commit). Only
                            // status, privacy-gated save, keyboard restore.
                            // A blank final is a no-op (map #105 ticket #106):
                            // brief 'nothing heard' affordance, no save — same
                            // gate as the batch path, so both agree on what
                            // "nothing heard" means.
                            val heardAnything = shouldCommitTranscript(text)
                            voiceRecordingPane.setStatus(
                                if (heardAnything) VoiceStatus.DONE else VoiceStatus.NOTHING_HEARD
                            )
                            isStreaming.set(false)
                            // Auto-save — never persist transcripts of privacy-sensitive fields.
                            // Gate on the verdict captured at onFinal for this session
                            // and reject a completion whose session has moved on
                            // (a newer start already reset the flag) — reading the
                            // mutable service flag here would save a sensitive final.
                            // Encode/write runs on the persistence executor, never the
                            // main thread (map #97 #100): the keyboard restore above and
                            // the committed text are already settled, insertion does not
                            // wait on storage.
                            if (sessionGeneration == streamingSessionGeneration && !sensitiveAtFinal) {
                                // Blank final is a no-op (map #105 ticket #106): never
                                // persist "nothing heard" — nested inside the privacy
                                // gate so its shape stays intact (#137).
                                if (heardAnything) {
                                    TranscriptionStorage.persistAsync(Runnable {
                                        TranscriptionStorage.save(
                                            this@VoiceInputMethodService,
                                            raw = text,
                                            cleaned = text,
                                            durationMs = 0,
                                            privacySensitive = sensitiveAtFinal
                                        )
                                    })
                                }
                            }
                            showKeyboardView()
                        }
                    }

                    override fun onError(error: VelaException) {
                        // Generation-bound (map #130 PR #137 TOCTOU fix): a stale
                        // pipeline's error must not flip a newer session's state.
                        if (generation != streamingSessionGeneration) return
                        mainHandler.post main@{
                            if (generation != streamingSessionGeneration) return@main
                            voiceRecordingPane.setStatusText(error.message)
                            isStreaming.set(false)
                        }
                    }

                    override fun onAmplitude(normalized: Float) {
                        // Generation-bound (map #130 PR #137 TOCTOU fix): a stale
                        // pipeline must not draw into the newer session's meter.
                        if (generation != streamingSessionGeneration) return
                        // Coalesced meter (ticket #113): at most one shared
                        // runnable per 66 ms, nothing while hidden.
                        if (amplitudeCoalescer.offer(normalized, SystemClock.uptimeMillis(), voicePaneVisible)) {
                            voiceRecordingPane.waveformView.post(amplitudeRunnable)
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

                // Post-start guard (map #130 PR #137 TOCTOU fix, CWE-367):
                // start() begins audio capture before it returns, so a cancel,
                // privacy flip or input finish can invalidate this session while
                // it runs and only become visible here. Stop and release the
                // capture we just opened, then return without publishing the
                // pipeline and without touching session state — a canceled
                // start never records, never commits, never activates.
                // StreamingPipeline offers no pre-start/synchronized-start hook,
                // so this check -> start -> re-check ordering is best-effort and
                // the guards below cover the remaining windows.
                if (generation != streamingSessionGeneration) {
                    try {
                        pipeline.stop()
                    } catch (ignored: Exception) {
                    }
                    teardownVoiceEngine(pipeline = pipeline)
                    unpublished = null
                    return@post
                }

                // Publish only a pipeline whose generation is still current.
                streamingPipeline = pipeline
                unpublished = null

                mainHandler.post main@{
                    // Success-post guard (map #130 PR #137 TOCTOU fix): the
                    // session can be invalidated between the post-start check
                    // and this task (cancel sees no pipeline yet, or onFinishInput
                    // invalidates a still-pending start). Stop and release the
                    // raced pipeline instead of marking a dead session active.
                    if (generation != streamingSessionGeneration) {
                        if (streamingPipeline === pipeline) {
                            try {
                                pipeline.stop()
                            } catch (ignored: Exception) {
                            }
                            teardownVoiceEngine(pipeline = pipeline)
                            streamingPipeline = null
                        }
                        return@main
                    }
                    isStreaming.set(true)
                    streamingStartPending = false
                    streamingBuffer.setLength(0)
                    streamingCommittedLength = 0
                    streamingShownLength = 0
                    textCommitter.reset()
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceIME", "Start streaming failed", e)
                // No leak: start() may throw after the pipeline was built but
                // before it was published — release what this worker owns and
                // nullify so a failed start owns nothing. unpublished and
                // streamingPipeline reference the same pipeline (or one is null),
                // so a single teardown covers both without double-release.
                teardownVoiceEngine(pipeline = unpublished ?: streamingPipeline)
                streamingPipeline = null
                streamingStartPending = false
                mainHandler.post {
                    voiceRecordingPane.setStatus(VoiceStatus.STREAMING_ERROR)
                }
            }
        }
    }

    private fun stopStreaming() {
        if (!isStreaming.get()) return
        isStreaming.set(false)

        // Single-commit site for streaming (onFinal below must NOT commit
        // again). finishComposingText commits the shown composing span, so
        // only the never-shown tail (e.g. partials that arrived with no input
        // connection) still needs a commit — re-committing shown text would
        // duplicate it. Blank remainder is a no-op via insertTranscribedText's
        // shared gate (map #105 ticket #106).
        val ic = currentInputConnection
        if (ic != null) {
            ic.finishComposingText()
            streamingCommittedLength = maxOf(streamingCommittedLength, streamingShownLength)
            if (streamingBuffer.length > streamingCommittedLength) {
                val remaining = streamingBuffer.substring(streamingCommittedLength).trim()
                insertTranscribedText("$remaining ")
                streamingCommittedLength = streamingBuffer.length
            }
        }

        streamingPipeline?.stop()
        teardownVoiceEngine(pipeline = streamingPipeline)
        streamingPipeline = null
    }

    /**
     * Sole commit-text owner for this service (map #105 ticket #107): the ONLY
     * InputConnection.commitText call site. The streaming stop path and the
     * batch onResult path both route through here, so partial/final/batch
     * text cannot double-insert. Blank input is a no-op via the shared #106
     * gate, so every path agrees on what "nothing heard" means.
     */
    private fun insertTranscribedText(text: String) {
        // Shared insert boundary (map #105 ticket #109): sanitize the
        // transcript before commit; sanitized-to-blank stays a no-op (#106).
        val clean = sanitizeForInsertion(text)
        if (!shouldCommitTranscript(clean)) return
        currentInputConnection?.commitText(clean, 1)
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
        streamingShownLength = 0
        textCommitter.discardOwnedRange()
        streamingPipeline?.stop()
        teardownVoiceEngine(pipeline = streamingPipeline)
        streamingPipeline = null
        voiceRecordingPane.waveformView.clear()
        showKeyboardView()
    }

    /**
     * Buffer the latest streaming revision through the shared committer (map
     * #105 ticket #107): a re-emitted partial replaces its own owned span
     * instead of appending a duplicate, stale/duplicate revisions are ignored
     * by id, and the NUL-padding raw replace is gone.
     */
    private fun updateStreamingBuffer(marker: RevisionMarker) {
        val composed = textCommitter.applyPartial(marker, streamingBuffer.toString())
        streamingBuffer.setLength(0)
        streamingBuffer.append(composed)
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingGate.reset()
        isStreaming.set(false)
        // A queued start must not survive the service: the worker discards its
        // pipeline instead of recording after destroy (map #130 PR #137 fix).
        invalidatePendingStreamingStart()
        timerHandler?.removeCallbacksAndMessages(null)
        teardownVoiceEngine(transcriber = transcriber, pipeline = streamingPipeline)
        transcriber = null
        streamingPipeline = null
        bgHandlerThread?.quitSafely()
        bgHandlerThread = null
    }

    /**
     * Single lifetime-owner teardown routine (ticket #102): this is the ONLY
     * place `.release()` is invoked on the SDK session objects
     * ([VelaTranscriber], [StreamingPipeline]). All other teardown paths —
     * cancel, discard, stale-start guards, session replacement — route through
     * here so ownership is auditable in one place.
     *
     * The routine releases only; it does NOT call `stop()`, `cancelRecording()`
     * or commit text. Those are caller-specific semantic choices and stay at
     * the call sites so routing through this routine never silently discards
     * a transcript.
     */
    private fun teardownVoiceEngine(
        transcriber: VelaTranscriber? = null,
        pipeline: StreamingPipeline? = null
    ) {
        try {
            pipeline?.release()
        } catch (ignored: Exception) {
        }
        try {
            transcriber?.release()
        } catch (ignored: Exception) {
        }
    }

    // ──────────────────────────────────────────────
    // UI state management
    // ──────────────────────────────────────────────

    private fun showVoicePane() {
        keyboardView.visibility = View.GONE
        voiceRecordingPane.visibility = View.VISIBLE
        // New meter session: the first bar posts immediately (no stale
        // throttle from the previous session) and hidden-drop lifts.
        amplitudeCoalescer.reset()
        voicePaneVisible = true
    }

    private fun showKeyboardView() {
        voiceRecordingPane.visibility = View.GONE
        keyboardView.visibility = View.VISIBLE
        // Pane gone: capture-thread offers drop before any main-thread post
        // (the view's own isShown gate from #112 stays as second defence).
        voicePaneVisible = false
    }

    // ──────────────────────────────────────────────
    // Recording lifecycle (offloaded to background)
    // ──────────────────────────────────────────────

    private fun startRecording() {
        // Single gate (map #97 #98): a non-IDLE state (live session, or a
        // session still building on the bg worker) makes start a no-op, so a
        // double-tap or onStartInputView re-entry can never open a second
        // AudioRecord over the live one.
        if (!recordingGate.tryStart()) return
        voiceRecordingPane.resetDisplay()
        voiceRecordingPane.setStatus(VoiceStatus.INITIALIZING)
        val sessionPrivacySensitive = isSessionPrivacySensitive()
        sessionStartedPrivacySensitive = sessionPrivacySensitive
        // Generation-bound batch (map #130 PR #138 fix): AudioRecorder.stop()
        // delivers onResult asynchronously on the main thread; a later session
        // resets sessionStartedPrivacySensitive before the old callback runs,
        // so the live-flag gate alone would save a sensitive transcript. Claim
        // the generation at start and reject stale completions in onResult.
        val recordingGeneration = ++streamingSessionGeneration

        bgHandler?.post {
            try {
                // Cache model paths on first use to avoid repeated DB queries
                loadModelPaths()

                val whisperPath = cachedWhisperPath
                if (whisperPath == null) {
                    if (recordingGeneration == streamingSessionGeneration) {
                        recordingGate.reset()
                    }
                    mainHandler.post {
                        voiceRecordingPane.setStatus(VoiceStatus.NO_MODEL_DOWNLOAD)
                    }
                    return@post
                }

                // Load personal dictionary, keywords, and prefs
                val personalDictionary = loadPersonalDictionary()
                val dictionaryKeywords = loadDictionaryKeywords()
                val prefs = this@VoiceInputMethodService
                    .getSharedPreferences("com.velavoice.app_preferences", Context.MODE_PRIVATE)
                val useLlm = prefs.getBoolean("useLlmCleaner", false)

                // Build transcriber (includes WhisperEngine init - on background).
                // LIFETIME OWNER (ticket #102): VIMS is the single owner of its
                // VelaTranscriber and StreamingPipeline lifetimes. This builder
                // call is the ONLY construction site; teardownVoiceEngine() is the
                // ONLY release site. No other class constructs or releases these.
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

                mainHandler.post main@{
                    // Stale-start guard (map #97 #98): cancelRecording bumped the
                    // generation and/or stopRecording moved the gate out of
                    // RECORDING while this transcriber was building — discard it
                    // (never start, never record, never leak) and settle the
                    // gate if a stop was waiting on this build.
                    if (recordingGeneration != streamingSessionGeneration || !recordingGate.isRecording()) {
                        teardownVoiceEngine(transcriber = builtTranscriber)
                        if (recordingGeneration == streamingSessionGeneration) {
                            // Stop landed in the init window: nothing was
                            // published, so there is nothing to process — settle
                            // the session instead of leaving it stuck in
                            // PROCESSING, and put the keyboard back.
                            recordingGate.finish()
                            mainHandler.post {
                                voiceRecordingPane.setStatus(VoiceStatus.READY)
                                showKeyboardView()
                            }
                        }
                        return@main
                    }
                    // Assign to main thread reference
                    teardownVoiceEngine(transcriber = transcriber)
                    transcriber = builtTranscriber

                    voiceRecordingPane.setStatus(VoiceStatus.RECORDING)
                    voiceRecordingPane.hideTimer()

                    recordingSeconds = 0
                    startTimer()

                    builtTranscriber.startRecording(object : VelaRecordingCallback {
                        override fun onAmplitude(normalized: Float) {
                            // Coalesced meter (ticket #113): at most one shared
                            // runnable per 66 ms, nothing while hidden. The VAD
                            // path is untouched (per-tick observe lives in the
                            // capture pipeline, #111).
                            if (amplitudeCoalescer.offer(normalized, SystemClock.uptimeMillis(), voicePaneVisible)) {
                                voiceRecordingPane.waveformView.post(amplitudeRunnable)
                            }
                        }

                        override fun onResult(result: TranscriptionResult) {
                            // Generation-bound batch (map #130 PR #138 fix): a
                            // stale async result must never save or commit under
                            // a newer session — return before touching shared
                            // state so the new session's gate/timer/UI and
                            // storage stay intact. Fail-closed: discard.
                            if (recordingGeneration != streamingSessionGeneration) return
                            recordingGate.finish()
                            timerHandler?.removeCallbacksAndMessages(null)
                            val finalTranscript = result.cleanedTranscript
                            // Empty/blank transcript is a no-op (map #105 ticket
                            // #106): committing "" would clear an in-progress
                            // composition the user already typed. State still
                            // returns to IDLE (gate finished above); the pane
                            // shows a brief 'nothing heard' affordance instead
                            // of "Done", and nothing is committed or saved.
                            val heardAnything = shouldCommitTranscript(finalTranscript)
                            voiceRecordingPane.setStatus(
                                if (heardAnything) VoiceStatus.DONE else VoiceStatus.NOTHING_HEARD
                            )
                            // Auto-save — never persist transcripts of privacy-sensitive fields,
                            // and never persist a blank transcript (no-op above).
                            // Read the volatile flip snapshot (not the frozen start local) so a
                            // mid-session flip-to-sensitive blocks the save (map #130 ticket #133 fix).
                            if (!sessionStartedPrivacySensitive) {
                                // Verdict captured with the gate: the task runs later on the
                                // persistence executor, never inline on this main-looper
                                // callback (map #97 #100) — encode + WAV/JSON writes would
                                // otherwise stall the IME. The commit below is independent
                                // of whether the save succeeds.
                                val sensitiveAtResult = sessionStartedPrivacySensitive
                                // Blank transcript is a no-op (map #105 ticket #106):
                                // never persist "nothing heard" — nested inside the
                                // privacy gate so its shape stays intact (#133).
                                if (heardAnything) {
                                    TranscriptionStorage.persistAsync(Runnable {
                                        TranscriptionStorage.save(
                                            this@VoiceInputMethodService,
                                            raw = result.rawTranscript,
                                            cleaned = result.cleanedTranscript,
                                            durationMs = result.durationMs,
                                            audioBytes = result.audioBytes,
                                            privacySensitive = sensitiveAtResult
                                        )
                                    })
                                }
                            }
                            voiceRecordingPane.post {
                                if (heardAnything) {
                                    insertTranscribedText(finalTranscript)
                                }
                                showKeyboardView()
                            }
                        }

                        override fun onError(error: VelaException) {
                            recordingGate.finish()
                            timerHandler?.removeCallbacksAndMessages(null)
                            voiceRecordingPane.setStatusText(error.message)
                        }

                        override fun onDurationLimitReached() {
                            // Hard duration cap (map #97 #104): delivered on the
                            // capture thread — run the normal gated stop path on
                            // the main thread so RECORDING -> PROCESSING -> IDLE
                            // settles, and surface a short notice (pane status
                            // text, replaced by "Done" when the result lands).
                            if (recordingGeneration != streamingSessionGeneration) return
                            mainHandler.post {
                                if (recordingGeneration != streamingSessionGeneration) return@post
                                stopRecording(runCleaner = true)
                                voiceRecordingPane.setStatus(VoiceStatus.TIME_LIMIT)
                            }
                        }
                    }, ScribeInput(privacySensitive = sessionPrivacySensitive))
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceIME", "Start recording failed", e)
                if (recordingGeneration == streamingSessionGeneration) {
                    recordingGate.reset()
                }
                mainHandler.post {
                    voiceRecordingPane.setStatus(VoiceStatus.INIT_ERROR)
                }
            }
        }
    }

    private fun stopRecording(runCleaner: Boolean) {
        // Gated + idempotent (map #97 #98): only RECORDING may stop; a second
        // stop, or a stop with no live session, must not re-run teardown or
        // show a phantom Processing state.
        if (!recordingGate.tryStop()) return
        timerHandler?.removeCallbacksAndMessages(null)
        voiceRecordingPane.setStatus(VoiceStatus.PROCESSING)
        transcriber?.stopRecording(clean = runCleaner)
    }

    private fun cancelRecording() {
        // Gated + idempotent (map #97 #98): only RECORDING may cancel; cancel
        // after stop (PROCESSING) is a no-op so stop's in-flight transcript
        // stays owned by the stop path.
        if (!recordingGate.tryCancel()) return
        // Invalidate a pending bg start (mirror of cancelStreaming): a cancel
        // landing while startRecording's transcriber is still building must
        // discard that build instead of recording into the canceled session.
        streamingSessionGeneration++
        timerHandler?.removeCallbacksAndMessages(null)
        // Cancel semantics (#74): discard audio without transcription, flush
        // or save — never plain stopRecording which would transcribe the tail.
        // AudioRecorder.cancel() emits no callback, so no transcript/file/UI tail.
        transcriber?.cancelRecording()
        teardownVoiceEngine(transcriber = transcriber)
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
                if (!recordingGate.isRecording()) return
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
