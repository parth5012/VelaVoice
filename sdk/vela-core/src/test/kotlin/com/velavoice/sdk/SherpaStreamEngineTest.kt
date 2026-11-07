package com.velavoice.sdk

import com.velavoice.sdk.sherpa.SherpaConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Ticket 803 (map #290): SherpaStreamEngine streaming pipeline tests.
 *
 * Drives the engine's protected-open native call points with a canned fake
 * so the full state machine (feeding, cumulative partial pumping, endpoint
 * commits, finalize, idempotent teardown) is exercised on the JVM without
 * the JNI .so — same pattern as SherpaEngineTest in vela-sherpa.
 */
@RunWith(RobolectricTestRunner::class)
class SherpaStreamEngineTest {

    /**
     * Fake test double: subclass the engine through the protected
     * (config, stubInit) constructor so model-path/containment checks still
     * run, while native allocation and every native handle call are faked.
     */
    private class FakeSherpaStreamEngine(
        config: SherpaConfig? = null,
        stubInit: Boolean = true
    ) : SherpaStreamEngine(config, stubInit) {

        var allocateCount = 0
        var feedCount = 0
        val fedAudio = mutableListOf<FloatArray>()
        var pumpedText: String? = null
        var endpointHit = false
        var finalizeText: String? = null
        var closeCount = 0

        // Op-ordering instrumentation (serialization test)
        val ops = java.util.Vector<String>()
        @Volatile var feedGate: CountDownLatch? = null

        fun resetOps() {
            ops.clear()
            feedGate = null
        }

        private fun record(op: String) {
            ops.add(op)
        }

        override fun nativeAllocate(modelPath: String, language: String, threads: Int): Boolean {
            record("allocate")
            allocateCount++
            return true
        }

        override fun nativeFeed(floatAudio: FloatArray) {
            record("feed:start")
            feedCount++
            fedAudio.add(floatAudio)
            try {
                feedGate?.await(3, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                // ignore
            }
            record("feed:end")
        }

        override fun nativePump(): String? {
            record("pump")
            return pumpedText
        }

        override fun nativeEndpointCheck(): Boolean {
            record("endpoint")
            return endpointHit
        }

        override fun nativeResetStream() {
            record("reset")
            endpointHit = false
        }

        override fun nativeFinalize(): String? {
            record("finalize")
            return finalizeText
        }

        override fun nativeClose() {
            record("close")
            closeCount++
        }
    }

    private class RecordingCallback : StreamingTranscriptionCallback {
        val markers = mutableListOf<RevisionMarker>()
        val finals = mutableListOf<String>()
        var error: VelaException? = null

        override fun onRevisionMarker(marker: RevisionMarker) {
            markers.add(marker)
        }

        override fun onFinal(text: String) {
            finals.add(text)
        }

        override fun onError(error: VelaException) {
            this.error = error
        }

        override fun onAmplitude(normalized: Float) = Unit
    }

    private fun pcmChunk(samples: Int): ByteArray = ByteArray(samples * 2) { 0x11 }

    private fun tempModelFile(): File {
        val f = File.createTempFile("test_sherpa_model", ".bin")
        f.deleteOnExit()
        return f
    }

    // ── start() failure paths ───────────────────────────────────

    @Test
    fun `start with missing model path reports error and stays stopped`() {
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)

        engine.start(StreamConfig(modelPath = "/definitely/not/here"))

        assertNotNull(cb.error)
        assertFalse(engine.isRunningForTest)
    }

    @Test
    fun `start with blank model path falls back to constructor config`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine(
            SherpaConfig(modelFile.absolutePath), stubInit = true
        )
        val cb = RecordingCallback()
        engine.setCallback(cb)

        engine.start(StreamConfig())

        assertNull(cb.error)
        assertTrue(engine.isRunningForTest)
    }

    @Test
    fun `start outside allowed roots is rejected`() {
        val modelFile = tempModelFile()
        // A root that cannot contain the model file: a nested directory the
        // model is definitely not inside (tempModelFile's parent differs).
        val unrelatedRoot = File(modelFile.parentFile, "unrelated-${System.nanoTime()}").apply { mkdirs() }
        unrelatedRoot.deleteOnExit()
        val engine = FakeSherpaStreamEngine(
            SherpaConfig(
                modelPath = modelFile.absolutePath,
                allowedModelRoots = listOf(unrelatedRoot)
            ),
            stubInit = true
        )
        val cb = RecordingCallback()
        engine.setCallback(cb)

        engine.start(StreamConfig())

        assertNotNull(cb.error)
        assertFalse(engine.isRunningForTest)
    }

    // ── emit() / partial pumping ────────────────────────────────

    @Test
    fun `emit feeds audio and pumps cumulative partial markers`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)

        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.pumpedText = "hello"
        engine.emit(pcmChunk(100))
        engine.pumpedText = "hello world"
        engine.emit(pcmChunk(100))

        assertEquals(2, engine.feedCount)
        assertEquals(2, cb.markers.size)

        val first = cb.markers[0]
        assertEquals("partial", first.type)
        assertEquals("hello", first.text)
        assertEquals(0, first.range.first)
        assertEquals(1, first.revisionId)

        val second = cb.markers[1]
        assertEquals("partial", second.type)
        assertEquals("hello world", second.text)
        assertEquals("cumulative text owns the same segment range", 0, second.range.first)
        assertTrue(second.revisionId > first.revisionId)
    }

    @Test
    fun `emit with unchanged pumped text emits no duplicate marker`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.pumpedText = "hello"
        engine.emit(pcmChunk(100))
        engine.emit(pcmChunk(100)) // pump returns identical text

        assertEquals(2, engine.feedCount)
        assertEquals(1, cb.markers.size)
    }

    @Test
    fun `emit empty chunk is a no-op`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.emit(ByteArray(0))

        assertEquals(0, engine.feedCount)
        assertEquals(0, cb.markers.size)
    }

    @Test
    fun `emit before start is ignored`() {
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)

        engine.emit(pcmChunk(100))

        assertEquals(0, engine.feedCount)
        assertEquals(0, cb.markers.size)
    }

    // ── endpoint handling ───────────────────────────────────────

    @Test
    fun `endpoint commit finalizes segment then new segment starts fresh`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)

        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.pumpedText = "hello"
        engine.emit(pcmChunk(100))

        // Trailing silence: native reports endpoint at the next feed cycle
        engine.endpointHit = true
        engine.pumpedText = "hello world"
        engine.emit(pcmChunk(100))

        // The current segment ("hello world") committed at the endpoint,
        // stream reset consumed the flag.
        val commit = cb.markers.last()
        assertEquals("commit", commit.type)
        assertEquals("hello world", commit.text)
        assertFalse("endpoint reset consumed the flag", engine.endpointHit)
        assertEquals(1, cb.markers.count { it.type == "partial" && it.text == "hello" })

        // Post-reset segment starts fresh with an offset range
        engine.pumpedText = "good"
        engine.emit(pcmChunk(100))

        val after = cb.markers.last()
        assertEquals("partial", after.type)
        assertEquals("good", after.text)
        assertEquals(
            "fresh segment range starts after the committed text + separator",
            "hello world".length + 1,
            after.range.first
        )
    }

    // ── stop() ──────────────────────────────────────────────────

    @Test
    fun `stop finalizesDrain and delivers onFinal`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.pumpedText = "hello world"
        engine.emit(pcmChunk(100))
        engine.finalizeText = "hello world"
        engine.stop()

        assertEquals(listOf("hello world"), cb.finals)
        assertFalse(engine.isRunningForTest)
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun `stop with pending partial text commits it before onFinal`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.pumpedText = "pending tail"
        engine.emit(pcmChunk(100))
        engine.finalizeText = null
        engine.stop()

        val commit = cb.markers.last()
        assertEquals("commit", commit.type)
        assertEquals("pending tail", commit.text)
        assertEquals(listOf("pending tail"), cb.finals)
    }

    @Test
    fun `stop with endpointed committed text and no tail drains into final`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.pumpedText = "hello"
        engine.emit(pcmChunk(100))
        engine.endpointHit = true
        engine.pumpedText = "hello world"
        engine.emit(pcmChunk(100)) // endpoint commit fires, segment resets
        engine.finalizeText = null
        engine.stop()

        // Final = the committed segment text
        assertEquals(listOf("hello world"), cb.finals)
    }

    @Test
    fun `stop without any audio emits empty onFinal`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.stop()

        assertEquals(listOf(""), cb.finals)
        assertFalse(engine.isRunningForTest)
    }

    @Test
    fun `double stop is idempotent`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.stop()
        engine.stop()

        assertEquals(1, cb.finals.size)
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun `emit after stop is ignored`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))
        engine.stop()

        engine.pumpedText = "late text"
        engine.emit(pcmChunk(100))

        assertEquals(0, engine.feedCount)
        assertEquals(0, cb.markers.size)
    }

    @Test
    fun `release stops the session`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.release()

        assertFalse(engine.isRunningForTest)
        assertEquals(1, engine.closeCount)
    }

    @Test
    fun `double start is idempotent`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)

        engine.start(StreamConfig(modelPath = modelFile.absolutePath))
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        assertTrue(engine.isRunningForTest)
        assertNull(cb.error)
        // Second start must not disturb session state (no markers expected)
        assertEquals(0, cb.markers.size)
    }

    @Test
    fun `restart after stop delivers a fresh onFinal`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)

        // Session 1
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))
        engine.pumpedText = "first session"
        engine.emit(pcmChunk(100))
        engine.stop()
        assertEquals(1, cb.finals.size)

        // Session 2 must get its own final even though session 1 delivered one
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))
        engine.pumpedText = "second session"
        engine.emit(pcmChunk(100))
        engine.stop()

        assertEquals(2, cb.finals.size)
        assertEquals("second session", cb.finals[1])
    }

    // ── CodeRabbit round-1 findings ─────────────────────────────

    @Test
    fun `stop supersedes stale partial with drained text instead of appending it`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.pumpedText = "hello wor"
        engine.emit(pcmChunk(100))
        engine.finalizeText = "hello world"
        engine.stop()

        // The commit must carry the authoritative drained text, and the final
        // must NOT contain the stale partial followed by its supersession.
        val commit = cb.markers.last()
        assertEquals("commit", commit.type)
        assertEquals("hello world", commit.text)
        assertEquals(1, cb.finals.size)
        assertEquals("hello world", cb.finals[0])
        assertFalse(
            "final must not duplicate text",
            cb.finals[0].contains("hello wor hello world")
        )
    }

    @Test
    fun `commit markers always get a fresh revision id`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        engine.pumpedText = "hello"
        engine.emit(pcmChunk(100))
        engine.endpointHit = true
        engine.pumpedText = "hello world"
        engine.emit(pcmChunk(100))

        val commit = cb.markers.last()
        assertEquals("commit", commit.type)
        val priorPartial = cb.markers.last { it.type == "partial" }
        assertTrue(
            "commit id ${commit.revisionId} must exceed previous partial ${priorPartial.revisionId}",
            commit.revisionId > priorPartial.revisionId
        )
    }

    @Test
    fun `start failure is retriable - failed start does not brick the engine`() {
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)

        engine.start(StreamConfig(modelPath = "/definitely/not/here"))
        assertNotNull(cb.error)
        assertFalse(engine.isRunningForTest)

        // A later start with a valid path must succeed on the same instance.
        val cb2 = RecordingCallback()
        engine.setCallback(cb2)
        val modelFile = tempModelFile()
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))
        assertNull(cb2.error)
        assertTrue(engine.isRunningForTest)
    }

    @Test
    fun `emit in flight holds the native lock - stop finalizes only afterwards`() {
        val modelFile = tempModelFile()
        val engine = FakeSherpaStreamEngine()
        val cb = RecordingCallback()
        engine.setCallback(cb)
        engine.start(StreamConfig(modelPath = modelFile.absolutePath))

        val feedGate = CountDownLatch(1)
        engine.feedGate = feedGate
        val emitThread = Thread { engine.emit(pcmChunk(64)) }
        emitThread.start()

        // Give the emit thread time to enter nativeFeed (blocked on the gate).
        BlockingLatch.awaitQuiet(200)
        engine.stop()
        // Unblock feed; the finalize/close must have waited for feed to finish.
        feedGate.countDown()
        emitThread.join(5000)

        val ops = engine.ops
        val feedEndIdx = ops.indexOf("feed:end")
        val finalizeIdx = ops.indexOf("finalize")
        val closeIdx = ops.indexOf("close")
        assertTrue("feed must have run", feedEndIdx >= 0)
        assertTrue("finalize must have run", finalizeIdx >= 0)
        assertTrue("close must have run", closeIdx >= 0)
        assertTrue(
            "finalize ($finalizeIdx) must come after feed end ($feedEndIdx)",
            feedEndIdx < finalizeIdx
        )
        assertTrue(
            "close ($closeIdx) must come after feed end ($feedEndIdx)",
            feedEndIdx < closeIdx
        )
    }
}

/** Bounded sleep helper that never leaks InterruptedException. */
internal object BlockingLatch {
    fun awaitQuiet(ms: Long) {
        val latch = CountDownLatch(1)
        try {
            latch.await(ms, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            // ignore
        }
    }
}
