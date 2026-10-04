package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * PR #137 review follow-up on top of #131/#132/#133: bind every queued
 * streaming side effect to the session that produced it.
 *
 * Four races, one root cause — mutable service state read by a task that runs
 * after the state moved on:
 * 1. VAS (`VoiceAccessibilityService.onFinal`): a sensitive session queues a
 *    background TextCleaner pass + main-thread insert/save. A new non-sensitive
 *    session resets `sessionPrivacySensitive` meanwhile, so both cleaner gates
 *    (`CleanerConfig.useLlm` and `TextCleaner.clean(..., privacySensitive)`) and
 *    the save gate saw the WRONG verdict — the sensitive final could be cleaned
 *    by the on-device ONNX model and inserted/saved under the new session.
 *    Fix: capture session id + verdict before enqueueing, thread the verdict
 *    through both gates, reject stale completions.
 * 2. VIMS (`VoiceInputMethodService.startStreaming`): pipeline construction is
 *    queued on bgHandler and `isStreaming` only flips on the main thread
 *    afterwards. During that window a sensitive-field recheck or `onFinishInput`
 *    saw "no session", so the worker started the pipeline with the stale
 *    non-sensitive verdict and recorded after the input ended.
 *    Fix: generation counter + pending-start marker, counted in
 *    `isSessionActive()`, invalidated on recheck and on input finish, re-checked
 *    after `build()` and before `pipeline.start(...)`.
 * 3. VIMS (`onFinal`): `stopStreaming()` emits the final synchronously and the
 *    callback posts a save task that read the service-wide privacy flag; a new
 *    non-sensitive session starting first resets that flag and the sensitive
 *    transcript got saved. Fix: capture the verdict (and session generation)
 *    synchronously in the callback and gate the posted save on that snapshot.
 * 4. VIMS (`startStreaming`, CWE-367 TOCTOU, CodeRabbit r4176579899): the
 *    generation was re-checked before `pipeline.start(...)` — which starts
 *    audio capture before it returns — but a cancellation landing during or
 *    after that call and before publication was invisible, and the
 *    unconditional success post then marked the canceled session active. Fix:
 *    check -> start -> re-check ordering that stops+releases a raced pipeline
 *    without publishing it, a generation check in the success post, callbacks
 *    bound to the generation captured for their pipeline, and a @Volatile
 *    pipeline field for cross-thread visibility.
 *
 * Source-contract guards over the two native services (which cannot be
 * instantiated in a unit test) plus tiny executable race models that replay the
 * interleavings: RED on the pre-fix shape, GREEN once each queued task is bound
 * to its originating session.
 */
class ServiceStreamingSessionBindingTest {

    private data class ServiceSources(val vas: String, val vims: String)

    private fun serviceSources(): ServiceSources {
        val root = findRepoRoot()
        val vas = File(root, "velavoice app/src/native/VoiceAccessibilityService.kt")
        val vims = File(root, "velavoice app/src/native/VoiceInputMethodService.kt")
        if (!vas.isFile) fail("VoiceAccessibilityService.kt not found under $root")
        if (!vims.isFile) fail("VoiceInputMethodService.kt not found under $root")
        return ServiceSources(vas.readText(), vims.readText())
    }

    private fun findRepoRoot(): File {
        var dir: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (File(dir, "velavoice app").isDirectory && File(dir, "sdk/vela-core").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return dir
        }
        fail("repo root (containing 'velavoice app' + 'sdk/vela-core') not found above user.dir")
        throw IllegalStateException("unreachable")
    }

    private fun assertNoMatch(pattern: Regex, source: String, message: String) {
        val hit = pattern.find(source)
        assertTrue("$message — found: '${hit?.value}'", hit == null)
    }

    /** Extract the body of `fun <name>(` with balanced braces (first match). */
    private fun extractFunBody(source: String, funName: String): String {
        val idx = source.indexOf("fun $funName(")
        if (idx < 0) fail("fun $funName( not found")
        val open = source.indexOf('{', idx)
        if (open < 0) fail("opening brace for $funName not found")
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        fail("unbalanced braces for $funName")
        throw IllegalStateException("unreachable")
    }

    /** Extract the streaming `override fun onFinal` block. */
    private fun extractOnFinal(source: String): String = extractFunBody(source, "onFinal")

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    /** Balanced-brace block starting at the first `{` at or after [fromIdx]. */
    private fun extractBraceBlock(source: String, fromIdx: Int): String {
        val open = source.indexOf('{', fromIdx)
        assertTrue("no '{' at or after index $fromIdx", open >= 0)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        fail("unbalanced braces at or after index $fromIdx")
        throw IllegalStateException("unreachable")
    }

    // ══════════════════════════════════════════════════════════════════════
    // Finding 1 — VAS: queued cleanup bound to its session + verdict
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VAS owns a volatile streaming session id bumped on start and abort`() {
        val (vas, _) = serviceSources()
        val code = codeOnly(vas)
        assertTrue(
            "VAS must own @Volatile private var streamingSessionId = 0 (read from the executor + main post)",
            Regex("@Volatile\\s+private var streamingSessionId = 0").containsMatchIn(code)
        )
        for (funName in listOf("startStreamingTranscription", "cancelStreamingTranscription")) {
            assertTrue(
                "VAS $funName must bump streamingSessionId so queued finals from the old session are stale",
                codeOnly(extractFunBody(vas, funName)).contains("streamingSessionId++")
            )
        }
    }

    @Test
    fun `VAS captures session id and verdict before enqueueing cleanup`() {
        val (vas, _) = serviceSources()
        val onFinal = codeOnly(extractOnFinal(vas))
        assertTrue(
            "VAS onFinal must capture the session id before enqueueing: val sessionId = streamingSessionId",
            onFinal.contains("val sessionId = streamingSessionId")
        )
        assertTrue(
            "VAS onFinal must capture the verdict before enqueueing: val sensitiveAtFinal = sessionPrivacySensitive",
            onFinal.contains("val sensitiveAtFinal = sessionPrivacySensitive")
        )
        val enqueueIdx = onFinal.indexOf("streamingCleanupExecutor.execute")
        assertTrue("VAS onFinal must still dispatch cleanup through the executor", enqueueIdx >= 0)
        for (capture in listOf("val sessionId = streamingSessionId", "val sensitiveAtFinal = sessionPrivacySensitive")) {
            val idx = onFinal.indexOf(capture)
            assertTrue(
                "VAS onFinal must capture '$capture' BEFORE enqueueing the cleanup (else a new session resets it first)",
                idx >= 0 && idx < enqueueIdx
            )
        }
        assertTrue(
            "VAS onFinal must pass the captured verdict into the final clean",
            onFinal.contains("cleanStreamingFinalText(text, sensitiveAtFinal)")
        )
    }

    @Test
    fun `VAS rejects stale finals before insert and save`() {
        val (vas, _) = serviceSources()
        val onFinal = codeOnly(extractOnFinal(vas))
        assertTrue(
            "VAS main post must reject a completion whose session id moved on",
            onFinal.contains("if (sessionId != streamingSessionId) return@post")
        )
        val guardIdx = onFinal.indexOf("if (sessionId != streamingSessionId) return@post")
        for (effect in listOf("insertStreamingText(cleaned, true)", "TranscriptionStorage.save")) {
            val idx = onFinal.indexOf(effect)
            assertTrue("VAS onFinal must still own '$effect'", idx >= 0)
            assertTrue(
                "VAS stale-session guard must run BEFORE '$effect' (never write a dead session's final)",
                guardIdx >= 0 && guardIdx < idx
            )
        }
    }

    @Test
    fun `VAS save gate uses the captured verdict, never the live session flag`() {
        val (vas, _) = serviceSources()
        val onFinal = codeOnly(extractOnFinal(vas))
        assertTrue(
            "VAS save must be gated on the verdict captured at onFinal: !sensitiveAtFinal",
            onFinal.contains("&& !sensitiveAtFinal)")
        )
        assertNoMatch(
            Regex("!streamingCancelled && !sessionPrivacySensitive"),
            codeOnly(vas),
            "VAS must not gate the streaming save on the live sessionPrivacySensitive (a newer session resets it)"
        )
        // #133 cancel semantics are preserved alongside the new guard.
        assertTrue(
            "VAS insert must still recheck !streamingCancelled (cancel may land during the background clean)",
            onFinal.contains("!streamingCancelled && cleaned.isNotBlank()")
        )
    }

    @Test
    fun `VAS final cleaner threads the captured verdict through both gates`() {
        val (vas, _) = serviceSources()
        val clean = codeOnly(extractFunBody(vas, "cleanStreamingFinalText"))
        assertTrue(
            "cleanStreamingFinalText must accept the captured verdict",
            vas.contains("fun cleanStreamingFinalText(raw: String, privacySensitive: Boolean): String")
        )
        assertTrue(
            "cleanStreamingFinalText must build the cleaner with the captured verdict",
            clean.contains("buildStreamingFinalCleaner(privacySensitive)")
        )
        assertTrue(
            "cleanStreamingFinalText must pass the captured verdict to clean(..., privacySensitive = ...)",
            clean.contains("privacySensitive = privacySensitive")
        )
        assertNoMatch(
            Regex("sessionPrivacySensitive"),
            clean,
            "cleanStreamingFinalText must not read the live session flag (that is the race)"
        )
        val build = codeOnly(extractFunBody(vas, "buildStreamingFinalCleaner"))
        assertTrue(
            "buildStreamingFinalCleaner must accept the captured verdict",
            vas.contains("fun buildStreamingFinalCleaner(privacySensitive: Boolean): TextCleaner")
        )
        assertTrue(
            "LLM gate must use the captured verdict: shouldEnableLlmCleaner(useLlm, privacySensitive)",
            build.contains("PrivacyGuard.shouldEnableLlmCleaner(useLlm, privacySensitive)")
        )
        assertNoMatch(
            Regex("sessionPrivacySensitive"),
            build,
            "buildStreamingFinalCleaner must not read the live session flag (#131 gate would open for a sensitive final)"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Finding 2 — VIMS: pending streaming starts are real sessions
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS counts a queued streaming start as an active session`() {
        val (_, vims) = serviceSources()
        val code = codeOnly(vims)
        assertTrue(
            "VIMS must own a volatile session generation counter",
            Regex("@Volatile\\s+private var streamingSessionGeneration = 0").containsMatchIn(code)
        )
        assertTrue(
            "VIMS must own a volatile pending-start marker",
            Regex("@Volatile\\s+private var streamingStartPending = false").containsMatchIn(code)
        )
        val active = codeOnly(extractFunBody(vims, "isSessionActive"))
        assertTrue(
            "isSessionActive must include the pending startup, else recheck/onFinishInput see 'no session'",
            active.contains("streamingStartPending")
        )
    }

    @Test
    fun `VIMS claims the generation at queue time and clears the marker when streaming`() {
        val (_, vims) = serviceSources()
        val start = codeOnly(extractFunBody(vims, "startStreaming"))
        assertTrue(
            "startStreaming must claim the generation at queue time",
            start.contains("val generation = ++streamingSessionGeneration")
        )
        val claimIdx = start.indexOf("val generation = ++streamingSessionGeneration")
        val postIdx = start.indexOf("handler.post")
        assertTrue(
            "startStreaming must claim the generation BEFORE posting the pipeline build",
            claimIdx >= 0 && postIdx >= 0 && claimIdx < postIdx
        )
        assertTrue("startStreaming must set the pending-start marker while queued", start.contains("streamingStartPending = true"))
        val pendingTrueIdx = start.indexOf("streamingStartPending = true")
        assertTrue("pending marker must be set before posting the build", pendingTrueIdx < postIdx)
        val startedIdx = start.indexOf("isStreaming.set(true)")
        assertTrue("startStreaming must still set isStreaming on success", startedIdx > 0)
        assertTrue(
            "startStreaming must clear the pending marker when the session goes live",
            start.substring(startedIdx).contains("streamingStartPending = false")
        )
    }

    @Test
    fun `VIMS discards a stale pipeline after build and before start`() {
        val (_, vims) = serviceSources()
        val start = codeOnly(extractFunBody(vims, "startStreaming"))
        val buildIdx = start.indexOf(".build()")
        val guardIdx = start.indexOf("if (generation != streamingSessionGeneration)")
        val startCallIdx = start.indexOf("pipeline.start(")
        assertTrue("startStreaming must build a pipeline", buildIdx > 0)
        assertTrue(
            "startStreaming must re-check the generation AFTER build() and BEFORE pipeline.start(...)",
            guardIdx > buildIdx && startCallIdx > guardIdx
        )
        val between = start.substring(buildIdx, startCallIdx)
        assertTrue(
            "a stale start must release the pipeline it just built (no leak)",
            between.contains("pipeline.release()")
        )
        assertTrue(
            "a stale start must return before starting the pipeline (never record with a stale verdict)",
            between.contains("return@post")
        )
    }

    @Test
    fun `VIMS invalidates pending starts on privacy flip and on input finish`() {
        val (_, vims) = serviceSources()
        val cancel = codeOnly(extractFunBody(vims, "cancelStreaming"))
        assertTrue(
            "cancelStreaming must run when only a start is pending: if (!isStreaming.get() && !streamingStartPending) return",
            cancel.contains("if (!isStreaming.get() && !streamingStartPending) return")
        )
        assertTrue(
            "cancelStreaming must invalidate the queued start",
            cancel.contains("invalidatePendingStreamingStart()")
        )
        val invalidate = codeOnly(extractFunBody(vims, "invalidatePendingStreamingStart"))
        assertTrue(
            "invalidation must bump the generation so the queued worker knows it is stale",
            invalidate.contains("streamingSessionGeneration++")
        )
        assertTrue(
            "invalidation must clear the pending marker",
            invalidate.contains("streamingStartPending = false")
        )
        val recheck = codeOnly(extractFunBody(vims, "recheckSessionPrivacy"))
        assertTrue(
            "the flip path must reach cancelStreaming so a queued start is invalidated",
            recheck.contains("cancelStreaming()")
        )
        for (funName in listOf("onFinishInput", "onFinishInputView")) {
            val finish = codeOnly(extractFunBody(vims, funName))
            assertTrue(
                "VIMS $funName must invalidate a pending streaming start (never record past the end of input)",
                finish.contains("streamingStartPending") && finish.contains("invalidatePendingStreamingStart()")
            )
        }
    }

    @Test
    fun `VIMS start-failure path still releases and clears the pending marker`() {
        val (_, vims) = serviceSources()
        val start = codeOnly(extractFunBody(vims, "startStreaming"))
        val catchIdx = start.indexOf("catch (e: Exception)")
        assertTrue("startStreaming must keep its start-failure path (#132-fix: no leak)", catchIdx > 0)
        val tail = start.substring(catchIdx)
        assertTrue("start failure must release the pipeline", tail.contains("streamingPipeline?.release()"))
        assertTrue("start failure must nullify the pipeline", tail.contains("streamingPipeline = null"))
        assertTrue(
            "start failure must clear the pending marker, else every later start is blocked",
            tail.contains("streamingStartPending = false")
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Finding 3 — VIMS: final save uses the callback's own verdict snapshot
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS captures the session verdict before posting the final save`() {
        val (_, vims) = serviceSources()
        val onFinal = codeOnly(extractOnFinal(vims))
        assertTrue(
            "VIMS onFinal must capture the verdict synchronously: val sensitiveAtFinal = sessionStartedPrivacySensitive",
            onFinal.contains("val sensitiveAtFinal = sessionStartedPrivacySensitive")
        )
        assertTrue(
            "VIMS onFinal must capture the session generation synchronously",
            onFinal.contains("val sessionGeneration = streamingSessionGeneration")
        )
        val postIdx = onFinal.indexOf("mainHandler.post")
        assertTrue("VIMS onFinal must still post to the main handler", postIdx > 0)
        for (capture in listOf(
            "val sensitiveAtFinal = sessionStartedPrivacySensitive",
            "val sessionGeneration = streamingSessionGeneration"
        )) {
            val idx = onFinal.indexOf(capture)
            assertTrue(
                "VIMS onFinal must capture '$capture' BEFORE posting (startStreaming resets the flag first)",
                idx >= 0 && idx < postIdx
            )
        }
        assertTrue(
            "VIMS posted save must be gated on the captured verdict",
            onFinal.contains("if (sessionGeneration == streamingSessionGeneration && !sensitiveAtFinal) {")
        )
        assertNoMatch(
            Regex("!sessionStartedPrivacySensitive\\s*\\)\\s*\\{"),
            onFinal,
            "VIMS streaming onFinal must not read the live sessionStartedPrivacySensitive inside the posted task"
        )
    }

    @Test
    fun `VIMS streaming onFinal keeps the single-commit and keyboard-restore contract`() {
        val (_, vims) = serviceSources()
        val onFinal = codeOnly(extractOnFinal(vims))
        assertNoMatch(
            Regex("commitText"),
            onFinal,
            "VIMS onFinal must not commitText — stopStreaming already committed remaining (single-commit, #132)"
        )
        assertTrue("VIMS onFinal must still end the session", onFinal.contains("isStreaming.set(false)"))
        assertTrue("VIMS onFinal must still restore the keyboard view", onFinal.contains("showKeyboardView()"))
        assertTrue(
            "VIMS onFinal must still keep the cancel guard (#133: aborted tail is discarded)",
            onFinal.contains("if (streamingCancelled) return")
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Executable race models — replay the interleavings the guards describe
    // ══════════════════════════════════════════════════════════════════════

    /** Manual stand-in for Handler/Executor: runnable runs only when drained. */
    private class PostQueue {
        private val queue = ArrayDeque<() -> Unit>()
        fun post(block: () -> Unit) { queue.addLast(block) }
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
    }

    /**
     * VAS onFinal shape: capture session id + verdict at enqueue time, gate the
     * cleaner and the save on the captured verdict, reject stale completions.
     */
    private class VasStreamingSession {
        var streamingSessionId = 0
        var sessionPrivacySensitive = false
        var streamingCancelled = false
        val mainQueue = PostQueue()
        val inserts = mutableListOf<String>()
        val saves = mutableListOf<String>()
        var llmGateUsed: Boolean? = null

        fun startSession(sensitive: Boolean) {
            streamingSessionId++
            sessionPrivacySensitive = sensitive
            streamingCancelled = false
        }

        fun cancel() {
            streamingSessionId++
            streamingCancelled = true
        }

        /** `shouldEnableLlmCleaner(useLlmPref, privacySensitive)` = `useLlm && !privacySensitive`. */
        fun onFinal(text: String, cleanupQueue: PostQueue, useLlmPref: Boolean = true) {
            if (streamingCancelled || text.isBlank()) return
            val sessionId = streamingSessionId
            val sensitiveAtFinal = sessionPrivacySensitive
            cleanupQueue.post {
                llmGateUsed = useLlmPref && !sensitiveAtFinal
                val cleaned = text
                mainQueue.post {
                    if (sessionId != streamingSessionId) return@post
                    if (!streamingCancelled && cleaned.isNotBlank()) inserts.add(cleaned)
                    if (!streamingCancelled && !sensitiveAtFinal) saves.add(cleaned)
                }
            }
        }
    }

    @Test
    fun `VAS model - sensitive final is neither LLM-cleaned nor saved after a new non-sensitive session starts`() {
        val session = VasStreamingSession()
        val cleanup = PostQueue()
        session.startSession(sensitive = true)
        session.onFinal("secret", cleanup)

        // A new non-sensitive session starts while the cleanup is still queued:
        // it resets the live verdict AND bumps the session id.
        session.startSession(sensitive = false)
        cleanup.drain()
        session.mainQueue.drain()

        assertFalse(
            "sensitive final must never be cleaned by the on-device ONNX model (captured verdict keeps the #131 gate shut)",
            session.llmGateUsed == true
        )
        assertTrue("stale sensitive final must never be inserted under the new session", session.inserts.isEmpty())
        assertTrue("stale sensitive final must never be saved under the new session", session.saves.isEmpty())
    }

    @Test
    fun `VAS model - non-sensitive final still cleans and saves when nothing moved on`() {
        val session = VasStreamingSession()
        val cleanup = PostQueue()
        session.startSession(sensitive = false)
        session.onFinal("hello", cleanup)
        cleanup.drain()
        session.mainQueue.drain()

        assertTrue("non-sensitive final must keep the LLM gate open", session.llmGateUsed == true)
        assertEquals(listOf("hello"), session.inserts)
        assertEquals(listOf("hello"), session.saves)
    }

    @Test
    fun `VAS model - cancelled sensitive final stays discarded`() {
        val session = VasStreamingSession()
        val cleanup = PostQueue()
        session.startSession(sensitive = true)
        session.onFinal("secret", cleanup)
        session.cancel()
        cleanup.drain()
        session.mainQueue.drain()

        assertTrue("cancelled tail must never be inserted (#133 cancel semantics)", session.inserts.isEmpty())
        assertTrue("cancelled tail must never be saved (#133 cancel semantics)", session.saves.isEmpty())
    }

    /**
     * VIMS startStreaming shape: claim the generation at queue time, count the
     * pending start as an active session, invalidate it on flip/finish, and
     * discard (never start) a pipeline whose generation went stale.
     */
    private class VimsStreamingSession {
        var streamingSessionGeneration = 0
        var isStreaming = false
        var streamingStartPending = false
        var isRecording = false
        val bgQueue = PostQueue()
        var pipelineStarted = false
        var pipelineReleased = false
        var recordedVerdict: Boolean? = null

        fun isSessionActive(): Boolean = isRecording || isStreaming || streamingStartPending

        fun invalidatePendingStreamingStart() {
            streamingSessionGeneration++
            streamingStartPending = false
        }

        /** Start with a frozen non-sensitive verdict; only `pipeline.start` records. */
        fun startStreaming(sessionPrivacySensitive: Boolean) {
            if (isStreaming || streamingStartPending) return
            val generation = ++streamingSessionGeneration
            streamingStartPending = true
            bgQueue.post {
                val pipeline = object {
                    fun release() { pipelineReleased = true }
                    fun start(sensitive: Boolean) {
                        pipelineStarted = true
                        recordedVerdict = sensitive
                    }
                }
                if (generation != streamingSessionGeneration) {
                    pipeline.release()
                    return@post
                }
                pipeline.start(sessionPrivacySensitive)
                isStreaming = true
                streamingStartPending = false
            }
        }

        fun recheckSessionPrivacy(newFieldSensitive: Boolean) {
            if (!isSessionActive()) return
            if (newFieldSensitive) cancelStreaming()
        }

        fun cancelStreaming() {
            if (!isStreaming && !streamingStartPending) return
            isStreaming = false
            invalidatePendingStreamingStart()
        }

        fun onFinishInput() {
            if (isStreaming) {
                isStreaming = false
            } else if (streamingStartPending) {
                invalidatePendingStreamingStart()
            } else if (isRecording) {
                isRecording = false
            }
        }
    }

    @Test
    fun `VIMS model - privacy flip during a queued start prevents recording`() {
        val session = VimsStreamingSession()
        session.startStreaming(sessionPrivacySensitive = false)
        assertTrue("queued start must count as an active session", session.isSessionActive())

        // Sensitive field appears before the pipeline is built.
        session.recheckSessionPrivacy(newFieldSensitive = true)
        session.bgQueue.drain()

        assertFalse("flipped session must never start the pipeline (no recording under a stale verdict)", session.pipelineStarted)
        assertTrue("stale pipeline must be released, not leaked", session.pipelineReleased)
    }

    @Test
    fun `VIMS model - input finishing during a queued start prevents recording`() {
        val session = VimsStreamingSession()
        session.startStreaming(sessionPrivacySensitive = false)
        assertTrue("queued start must count as an active session", session.isSessionActive())

        session.onFinishInput()
        session.bgQueue.drain()

        assertFalse("input ended — the pipeline must never start", session.pipelineStarted)
        assertTrue("stale pipeline must be released, not leaked", session.pipelineReleased)
        assertFalse("pending marker must be cleared so a later start is not blocked", session.isSessionActive())
    }

    /** VIMS onFinal shape: capture verdict + generation before posting the save. */
    private class VimsFinalSave {
        var streamingSessionGeneration = 0
        var sessionStartedPrivacySensitive = false
        var streamingCancelled = false
        val mainQueue = PostQueue()
        val saves = mutableListOf<String>()

        fun startSession(sensitive: Boolean) {
            streamingSessionGeneration++
            sessionStartedPrivacySensitive = sensitive
            streamingCancelled = false
        }

        /** stopStreaming() emits the final inline, then the callback posts. */
        fun onFinal(text: String) {
            if (streamingCancelled) return
            val sensitiveAtFinal = sessionStartedPrivacySensitive
            val sessionGeneration = streamingSessionGeneration
            mainQueue.post {
                if (sessionGeneration == streamingSessionGeneration && !sensitiveAtFinal) {
                    saves.add(text)
                }
            }
        }
    }

    @Test
    fun `VIMS model - sensitive final is not saved after a new non-sensitive session starts`() {
        val session = VimsFinalSave()
        session.startSession(sensitive = true)
        session.onFinal("secret")

        session.startSession(sensitive = false)
        session.mainQueue.drain()

        assertTrue("sensitive transcript must never be saved after a new session resets the flag", session.saves.isEmpty())
    }

    @Test
    fun `VIMS model - non-sensitive final is still saved when nothing moved on`() {
        val session = VimsFinalSave()
        session.startSession(sensitive = false)
        session.onFinal("hello")
        session.mainQueue.drain()

        assertEquals(listOf("hello"), session.saves)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Finding 4 — VIMS: CWE-367 TOCTOU between the generation check and
    // pipeline.start(...) (CodeRabbit r4176579899 on PR #137)
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `VIMS publishes the streaming pipeline field as volatile`() {
        val (_, vims) = serviceSources()
        assertTrue(
            "VIMS must declare streamingPipeline @Volatile (bg worker publishes, main thread cancels)",
            Regex("@Volatile\\s+private var streamingPipeline: StreamingPipeline\\? = null")
                .containsMatchIn(codeOnly(vims))
        )
    }

    @Test
    fun `VIMS re-checks the generation after start and stops the raced pipeline before publishing`() {
        val (_, vims) = serviceSources()
        val start = codeOnly(extractFunBody(vims, "startStreaming"))
        val startCallIdx = start.indexOf("pipeline.start(")
        assertTrue("startStreaming must still start the pipeline", startCallIdx > 0)
        val postStartGuardIdx = start.indexOf("if (generation != streamingSessionGeneration)", startCallIdx)
        assertTrue(
            "startStreaming must re-check the generation AFTER pipeline.start(...) — start() begins " +
                "audio capture before it returns, so cancellation can land while it runs",
            postStartGuardIdx > startCallIdx
        )
        val publishIdx = start.indexOf("streamingPipeline = pipeline")
        assertTrue(
            "the pipeline may only be published once its generation is still current " +
                "(a stale start must never become the service's pipeline)",
            publishIdx > postStartGuardIdx
        )
        val racedBranch = start.substring(postStartGuardIdx, publishIdx)
        assertTrue(
            "a start that raced cancellation must stop the capture it just began",
            racedBranch.contains("pipeline.stop()")
        )
        assertTrue(
            "a start that raced cancellation must release the pipeline it just built",
            racedBranch.contains("pipeline.release()")
        )
        assertTrue(
            "a start that raced cancellation must return without touching session state",
            racedBranch.contains("return@post")
        )
        assertNoMatch(
            Regex("isStreaming\\.set\\(true\\)"),
            racedBranch,
            "a raced start must never mark the session active"
        )
        assertNoMatch(
            Regex("streamingStartPending = false"),
            racedBranch,
            "a raced start must not clear the pending marker (a newer session may already own it)"
        )
    }

    @Test
    fun `VIMS success post stops a raced-start pipeline instead of marking it active`() {
        val (_, vims) = serviceSources()
        val start = codeOnly(extractFunBody(vims, "startStreaming"))
        val startCallIdx = start.indexOf("pipeline.start(")
        assertTrue(startCallIdx > 0)
        val successIdx = start.indexOf("mainHandler.post", startCallIdx)
        assertTrue("startStreaming must still post its success task after start", successIdx > startCallIdx)
        val success = extractBraceBlock(start, successIdx)
        val guardIdx = success.indexOf("if (generation != streamingSessionGeneration)")
        val activeIdx = success.indexOf("isStreaming.set(true)")
        assertTrue(activeIdx > 0)
        assertTrue(
            "the success post must re-check the generation before marking the session active " +
                "(cancellation can land between start and this task)",
            guardIdx in 0 until activeIdx
        )
        val racedBranch = success.substring(guardIdx, activeIdx)
        assertTrue(
            "the success post must stop a pipeline whose cancellation raced startup",
            racedBranch.contains("pipeline.stop()")
        )
        assertTrue(
            "the success post must release that raced pipeline",
            racedBranch.contains("pipeline.release()")
        )
        assertTrue(
            "the success post must bail out without touching session state",
            racedBranch.contains("return@main")
        )
        assertTrue(
            "the success post must still activate a session whose generation held",
            success.substring(activeIdx).contains("streamingStartPending = false")
        )
    }

    @Test
    fun `VIMS binds every streaming callback to the generation captured for its pipeline`() {
        val (_, vims) = serviceSources()
        val start = codeOnly(extractFunBody(vims, "startStreaming"))
        val guard = "if (generation != streamingSessionGeneration) return"
        for (cb in listOf("onRevisionMarker", "onFinal", "onError", "onAmplitude")) {
            val body = codeOnly(extractFunBody(start, cb))
            val guardIdx = body.indexOf(guard)
            assertTrue("VIMS $cb must ignore events from a stale pipeline", guardIdx >= 0)
            val postIdx = body.indexOf("mainHandler.post")
            if (postIdx >= 0) {
                assertTrue(
                    "VIMS $cb must check the generation BEFORE it touches shared state",
                    guardIdx < postIdx
                )
            }
        }
        val onFinal = codeOnly(extractFunBody(start, "onFinal"))
        assertTrue(
            "the onFinal generation guard must run before the verdict snapshot — a stale final " +
                "must never reach the save gate",
            onFinal.indexOf(guard) < onFinal.indexOf("val sensitiveAtFinal")
        )
        for (cb in listOf("onRevisionMarker", "onError")) {
            val body = codeOnly(extractFunBody(start, cb))
            val postIdx = body.indexOf("mainHandler.post")
            assertTrue(postIdx >= 0)
            assertTrue(
                "VIMS $cb must re-check the generation when its posted task runs (the session " +
                    "may have moved on while the task was queued)",
                body.substring(postIdx).contains("return@main")
            )
        }
    }

    /** Minimal stand-in for a pipeline the worker built but may not publish. */
    private class RacePipeline(private val duringStart: () -> Unit = {}) {
        var started = false
        var stopped = false
        var released = false

        fun start() {
            started = true
            duringStart()
        }

        fun stop() {
            stopped = true
        }

        fun release() {
            released = true
        }
    }

    /**
     * VIMS startStreaming race (CWE-367): the generation is checked before
     * `pipeline.start(...)`, but cancellation can invalidate it after that
     * check — while start() runs, or after start() returns and before the
     * success post marks the session active. The model replays both windows
     * against the guarded shape: stop+release the raced pipeline and never
     * activate it.
     */
    private class VimsStartRace {
        var streamingSessionGeneration = 0
        var isStreaming = false
        var streamingStartPending = false
        var streamingPipeline: RacePipeline? = null
        var lastPipeline: RacePipeline? = null
        val bgQueue = PostQueue()
        val mainQueue = PostQueue()
        var duringStart: (() -> Unit)? = null
        var beforeSuccessPost: (() -> Unit)? = null

        fun isSessionActive(): Boolean = isStreaming || streamingStartPending

        fun invalidatePendingStreamingStart() {
            streamingSessionGeneration++
            streamingStartPending = false
        }

        fun startStreaming() {
            if (isStreaming || streamingStartPending) return
            val generation = ++streamingSessionGeneration
            streamingStartPending = true
            bgQueue.post {
                val pipeline = RacePipeline { duringStart?.invoke() }
                lastPipeline = pipeline
                if (generation != streamingSessionGeneration) {
                    pipeline.release()
                    return@post
                }
                pipeline.start()
                // Post-start guard (mirrors VIMS check -> start -> re-check):
                // start() begins capture before it returns, so a cancel that
                // landed during start() is only visible here — stop+release
                // without publishing and without touching session state.
                if (generation != streamingSessionGeneration) {
                    pipeline.stop()
                    pipeline.release()
                    return@post
                }
                streamingPipeline = pipeline
                beforeSuccessPost?.invoke()
                mainQueue.post main@{
                    // Success-post guard (mirrors VIMS): cancellation can land
                    // between publication and this task — stop+release instead
                    // of marking the dead session active.
                    if (generation != streamingSessionGeneration) {
                        if (streamingPipeline === pipeline) {
                            pipeline.stop()
                            pipeline.release()
                            streamingPipeline = null
                        }
                        return@main
                    }
                    isStreaming = true
                    streamingStartPending = false
                }
            }
        }

        fun cancelStreaming() {
            if (!isStreaming && !streamingStartPending) return
            isStreaming = false
            invalidatePendingStreamingStart()
            streamingPipeline?.stop()
            streamingPipeline?.release()
            streamingPipeline = null
        }
    }

    @Test
    fun `VIMS model - cancellation landing during start never marks the session active`() {
        val session = VimsStartRace()
        session.duringStart = { session.cancelStreaming() }
        session.startStreaming()
        session.bgQueue.drain()

        // The worker itself must stop the capture before any main-thread hop:
        // a success post would only run later, leaving the mic live meanwhile.
        val pipeline = session.lastPipeline
        assertTrue("the raced pipeline must actually have started capturing", pipeline!!.started)
        assertTrue(
            "capture begun during start must be stopped by the worker, not deferred to a main-thread task",
            pipeline.stopped
        )
        assertTrue("the raced pipeline must be released, not leaked", pipeline.released)
        assertNull(
            "a stale start must never be published as the service's pipeline",
            session.streamingPipeline
        )

        session.mainQueue.drain()

        assertFalse("a canceled session must never be marked active", session.isStreaming)
        assertFalse("nothing may count as an active session after cancellation", session.isSessionActive())
    }

    @Test
    fun `VIMS model - cancellation after startup but before the success post stops the pipeline`() {
        val session = VimsStartRace()
        session.beforeSuccessPost = { session.cancelStreaming() }
        session.startStreaming()
        session.bgQueue.drain()
        session.mainQueue.drain()

        val pipeline = session.lastPipeline
        assertTrue("the raced pipeline must be stopped", pipeline!!.stopped)
        assertTrue("the raced pipeline must be released", pipeline.released)
        assertFalse(
            "the success post must not mark a canceled session active",
            session.isStreaming
        )
        assertFalse("nothing may count as an active session after cancellation", session.isSessionActive())
    }

    /**
     * VIMS callback shape: each callback captures the generation claimed for
     * its pipeline and refuses every event from a stale one — otherwise
     * stopping a dead pipeline commits or saves under a newer session.
     */
    private class VimsCallbackGeneration {
        var streamingSessionGeneration = 0
        var streamingCancelled = false
        var isStreaming = false
        val mainQueue = PostQueue()
        val saves = mutableListOf<String>()
        val commits = mutableListOf<String>()
        var errorPosted = 0

        fun startPipeline(): Int {
            streamingSessionGeneration++
            streamingCancelled = false
            isStreaming = true
            return streamingSessionGeneration
        }

        fun onFinal(generation: Int, text: String) {
            if (generation != streamingSessionGeneration) return
            if (streamingCancelled) return
            val sessionGeneration = streamingSessionGeneration
            mainQueue.post {
                if (sessionGeneration != streamingSessionGeneration) return@post
                if (sessionGeneration == streamingSessionGeneration && text.isNotEmpty()) {
                    saves.add(text)
                }
            }
        }

        fun onRevisionMarker(generation: Int, text: String) {
            if (generation != streamingSessionGeneration) return
            if (streamingCancelled) return
            mainQueue.post {
                if (generation != streamingSessionGeneration) return@post
                commits.add(text)
            }
        }

        fun onError(generation: Int) {
            if (generation != streamingSessionGeneration) return
            mainQueue.post {
                if (generation != streamingSessionGeneration) return@post
                errorPosted++
                isStreaming = false
            }
        }
    }

    @Test
    fun `VIMS model - callbacks of a stale generation never commit, save or flip the session`() {
        val session = VimsCallbackGeneration()
        val staleGeneration = session.startPipeline()
        session.startPipeline() // a newer session now owns the service

        session.onRevisionMarker(staleGeneration, "stale partial")
        session.onFinal(staleGeneration, "stale secret")
        session.onError(staleGeneration)
        session.mainQueue.drain()

        assertTrue(
            "a stale pipeline's markers must never commit into the newer session",
            session.commits.isEmpty()
        )
        assertTrue(
            "a stale pipeline's final must never be saved",
            session.saves.isEmpty()
        )
        assertEquals(
            "a stale pipeline's error must not post into the newer session",
            0,
            session.errorPosted
        )
        assertTrue("the newer session must still be active", session.isStreaming)

        // Control: the current generation still commits and saves.
        val fresh = VimsCallbackGeneration()
        val current = fresh.startPipeline()
        fresh.onRevisionMarker(current, "fresh partial")
        fresh.onFinal(current, "fresh text")
        fresh.mainQueue.drain()
        assertEquals(listOf("fresh partial"), fresh.commits)
        assertEquals(listOf("fresh text"), fresh.saves)
    }
}