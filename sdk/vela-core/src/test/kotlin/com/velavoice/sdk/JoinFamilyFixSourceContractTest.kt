package com.velavoice.sdk

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Ticket #230 join-family contracts:
 * - F34: StreamingPipeline.stopAudioCapture() must stop/release the AudioRecord
 *   BEFORE joining the capture thread (a thread parked in native read() never
 *   exits while the record runs, so a join-first teardown burns its whole
 *   timeout on the caller — often the main thread). The join must also be
 *   interrupted/bounded and skipped when the caller IS the capture thread
 *   (same self-join guard as AudioRecorder, F21).
 * - Rider (map #210 decision #2): StreamingPipeline.audioRecord and
 *   recordingThread are touched from the capture thread and the caller thread
 *   plain vars → must be @Volatile.
 * - F11: VAS stopRecording must bound its join with
 *   [AudioRecorder.CANCEL_JOIN_TIMEOUT_MS] (also fix hi: map #130 #133 bounded
 *   only the cancel path).
 * - F21 (tile half): DictationTileService must NOT call stopRecording
 *   synchronously from onDurationLimitReached (that runs ON the capture
 *   thread) — it posts to the main handler, like VIMS.
 *
 * Source-contract guards for the app/service surfaces that cannot be
 * instantiated in unit tests (same pattern as ServiceFocusRecheckAbortTest).
 */
class JoinFamilyFixSourceContractTest {

    // ── helpers (mirrored from ServiceFocusRecheckAbortTest) ──

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

    private fun readSource(relative: String): String {
        val file = File(findRepoRoot(), relative)
        if (!file.isFile) fail("$relative not found under ${findRepoRoot()}")
        return file.readText()
    }

    /** Extract the body of the first `fun <name>(` with balanced braces. */
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

    /** Code without line/block comments — guards must ignore prose mentions. */
    private fun codeOnly(source: String): String {
        var s = source.replace(Regex("//.*"), "")
        s = s.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return s
    }

    private val pipelineSource by lazy {
        codeOnly(readSource("sdk/vela-core/src/main/kotlin/com/velavoice/sdk/StreamingPipeline.kt"))
    }

    // ── F34: stopAudioCapture ordering, boundedness, self-join guard ──

    @Test
    fun `stopAudioCapture stops and releases the AudioRecord BEFORE joining the capture thread`() {
        val body = codeOnly(extractFunBody(pipelineSource, "stopAudioCapture"))
        val stopIdx = body.lastIndexOf("stop()")
        val releaseIdx = body.indexOf("release()")
        val joinIdx = body.indexOf("join(")
        assertTrue("stopAudioCapture must stop the AudioRecord", stopIdx >= 0)
        assertTrue("stopAudioCapture must release the AudioRecord", releaseIdx >= 0)
        assertTrue("stopAudioCapture must join the capture thread", joinIdx >= 0)
        assertTrue(
            "stopAudioCapture must stop/release the AudioRecord BEFORE join() so a capture " +
                "thread blocked in native read() can exit and the join never burns the full " +
                "timeout (F34, ticket #230)",
            stopIdx >= 0 && releaseIdx >= 0 && joinIdx >= 0 &&
                stopIdx < joinIdx && releaseIdx < joinIdx
        )
    }

    @Test
    fun `stopAudioCapture join is bounded and interrupted`() {
        val body = codeOnly(extractFunBody(pipelineSource, "stopAudioCapture"))
        val joinIdx = body.indexOf("join(")
        assertTrue("stopAudioCapture must join the capture thread", joinIdx >= 0)
        assertTrue(
            "stopAudioCapture join must use AudioRecorder.CANCEL_JOIN_TIMEOUT_MS (bounded, ANR budget)",
            body.contains("CANCEL_JOIN_TIMEOUT_MS")
        )
        val interruptIdx = body.indexOf("interrupt()")
        assertTrue(
            "stopAudioCapture must interrupt the capture thread so a parked thread cannot " +
                "outlive the join timeout",
            interruptIdx >= 0 && interruptIdx < joinIdx
        )
    }

    @Test
    fun `stopAudioCapture skips the join when the caller is the capture thread`() {
        val body = codeOnly(extractFunBody(pipelineSource, "stopAudioCapture"))
        val guardIdx = body.indexOf("Thread.currentThread()")
        assertTrue(
            "stopAudioCapture must skip joining the caller when it IS the recording thread " +
                "(a thread joining itself can never complete, F21)",
            guardIdx >= 0
        )
    }

    // ── Rider: @Volatile capture-vs-main thread fields ──

    @Test
    fun `StreamingPipeline audioRecord and recordingThread fields are @Volatile`() {
        for (field in listOf("audioRecord", "recordingThread")) {
            val match = Regex("@Volatile\\s+(?:private\\s+)?var\\s+$field").find(pipelineSource)
            assertTrue(
                "StreamingPipeline.$field is read/written from the capture thread and the " +
                    "caller thread — must be @Volatile (rider, map #210 decision #2)",
                match != null
            )
        }
    }

    // ── F11: VAS normal stop path join is bounded ──

    @Test
    fun `VAS stopRecording bounds its join like the cancel path`() {
        val vas = codeOnly(readSource("velavoice app/src/native/VoiceAccessibilityService.kt"))
        val body = codeOnly(extractFunBody(vas, "stopRecording"))
        assertTrue(
            "VAS stopRecording must bound recordingThread.join with AudioRecorder." +
                "CANCEL_JOIN_TIMEOUT_MS — an unbounded join on main blocks indefinitely " +
                "when the capture thread is wedged in read() (F11, ticket #230)",
            body.contains("join(AudioRecorder.CANCEL_JOIN_TIMEOUT_MS)")
        )
        Regex("join\\(\\s*\\)").find(body)?.let {
            fail("VAS stopRecording must not keep an unbounded join — found '${it.value}'")
        }
    }

    // ── F21 (tile half): duration-limit stop is posted, never synchronous ──

    @Test
    fun `DictationTileService posts the duration-limit stop to its handler`() {
        val tile = codeOnly(
            readSource("velaboard/app/src/main/java/helium314/keyboard/quicktile/DictationTileService.kt")
        )
        val body = codeOnly(extractFunBody(tile, "onDurationLimitReached"))
        assertTrue(
            "onDurationLimitReached runs ON the capture thread — DictationTileService must " +
                "post the stopRecording call to its main-handler, not stop synchronously " +
                "(F21, ticket #230)",
            body.contains("handler.post")
        )
    }

    // ── F34 runtime: bounded + interrupted join via reflection ──

    @Test(timeout = 10000)
    fun `stopAudioCapture interrupts a parked capture thread and returns promptly`() {
        val pipeline = StreamingPipeline(null, null, StreamConfig())
        val stopped = CountDownLatch(1)
        val started = CountDownLatch(1)
        val parked = Thread({
            started.countDown()
            try {
                // Uninterruptible-ish park: a plain join(2000) first would burn
                // the whole timeout; the fixed path interrupts before joining.
                stopped.await(30, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // expected once the fix interrupts before joining
            }
        }, "ParkedFakeCaptureThread")
        parked.isDaemon = true
        parked.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val recordField = StreamingPipeline::class.java.getDeclaredField("audioRecord")
        recordField.isAccessible = true
        recordField.set(pipeline, null)
        val threadField = StreamingPipeline::class.java.getDeclaredField("recordingThread")
        threadField.isAccessible = true
        threadField.set(pipeline, parked)

        val stopCapture = StreamingPipeline::class.java.getDeclaredMethod("stopAudioCapture")
        stopCapture.isAccessible = true
        val startedAt = System.currentTimeMillis()
        stopCapture.invoke(pipeline)
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue(
            "stopAudioCapture returned after ${elapsed}ms with the capture thread parked — " +
                "it must interrupt the thread BEFORE a bounded join (F34)",
            elapsed < joinFamilyBudgetMs()
        )
        parked.join(5000)
        assertTrue("parked thread must exit once interrupted", !parked.isAlive)
    }

    @Test(timeout = 10000)
    fun `stopAudioCapture skips the join when the caller IS the capture thread`() {
        val pipeline = StreamingPipeline(null, null, StreamConfig())
        val threadField = StreamingPipeline::class.java.getDeclaredField("recordingThread")
        threadField.isAccessible = true
        val recordField = StreamingPipeline::class.java.getDeclaredField("audioRecord")
        recordField.isAccessible = true

        val started = System.currentTimeMillis()
        val elapsed = AtomicLong(-1)
        val fakeCapture = Thread({
            // Register THIS thread as the recording thread, then stop from it
            // (the duration-limit synchronous-stop shape).
            recordField.set(pipeline, null)
            threadField.set(pipeline, Thread.currentThread())
            val stopCapture = StreamingPipeline::class.java.getDeclaredMethod("stopAudioCapture")
            stopCapture.isAccessible = true
            val callStartedAt = System.currentTimeMillis()
            stopCapture.invoke(pipeline)
            elapsed.set(System.currentTimeMillis() - callStartedAt)
        }, "FakeSelfStoppingCaptureThread")
        fakeCapture.isDaemon = true
        fakeCapture.start()
        fakeCapture.join(8000)
        val ms = elapsed.get()
        assertTrue(
            "stopAudioCapture on the capture thread itself took ${ms}ms (total join wait " +
                "${System.currentTimeMillis() - started}ms) — joining a thread from itself " +
                "can never complete, the guard must skip the join",
            ms in 0 until joinFamilyBudgetMs()
        )
    }

    private fun joinFamilyBudgetMs(): Long = AudioRecorder.CANCEL_JOIN_TIMEOUT_MS
}
