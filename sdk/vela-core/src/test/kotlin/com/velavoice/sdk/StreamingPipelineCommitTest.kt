package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class StreamingPipelineCommitTest {

    private val context = RuntimeEnvironment.getApplication()

    // --- Area 4: Commit-boundary logic ---

    @Test
    fun `checkCommitBoundary fires commit after VAD pause when committedText populated`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        // Simulate the createTranscriberCallback partial path: committedText was populated
        val transcriberCallback = pipeline.createTranscriberCallback()
        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "hello world", 0 until 11))

        // Advance time so VAD pause threshold is exceeded
        val now = System.currentTimeMillis() + 10_000L
        pipeline.lastCommitTime = now - 10_000L
        pipeline.lastActivityTime = now - 10_000L
        pipeline.currentSegmentStart = 0

        // Amplitude below threshold => VAD pause detected
        pipeline.checkCommitBoundary(0.001f, now)

        val commits = markers.filter { it.type == "commit" }
        assertEquals("Expected exactly one commit marker", 1, commits.size)
        assertEquals("hello world", commits[0].text)
    }

    @Test
    fun `checkCommitBoundary does not fire when committedText is empty (the bug)`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        // committedText stays empty — simulates the pre-fix bug
        val now = System.currentTimeMillis() + 10_000L
        pipeline.lastCommitTime = now - 10_000L
        pipeline.lastActivityTime = now - 10_000L
        pipeline.currentSegmentStart = 0

        pipeline.checkCommitBoundary(0.001f, now)

        assertTrue("No commit should fire when committed text is empty", markers.isEmpty())
    }

    @Test
    fun `checkCommitBoundary fires on MAX_SEGMENT_MS exceeded`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        val transcriberCallback = pipeline.createTranscriberCallback()
        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "segment text here", 0 until 16))

        val now = System.currentTimeMillis() + 20_000L
        // Activity is recent (no VAD pause), but lastCommitTime is far in the past
        pipeline.lastCommitTime = now - 20_000L
        pipeline.lastActivityTime = now
        pipeline.currentSegmentStart = 0

        // Amplitude above threshold => no VAD pause, but MAX_SEGMENT_MS exceeded
        pipeline.checkCommitBoundary(0.5f, now)

        val commits = markers.filter { it.type == "commit" }
        assertEquals("Commit should fire on MAX_SEGMENT_MS path", 1, commits.size)
        assertEquals("segment text here", commits[0].text)
    }

    @Test
    fun `checkCommitBoundary does not fire when conditions not met`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        val transcriberCallback = pipeline.createTranscriberCallback()
        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "text", 0 until 4))

        val now = System.currentTimeMillis() + 100L
        // Recent activity, recent commit, loud amplitude => no commit
        pipeline.lastCommitTime = now - 100L
        pipeline.lastActivityTime = now - 100L
        pipeline.currentSegmentStart = 0

        pipeline.checkCommitBoundary(0.5f, now)

        assertTrue(
            "No commit should fire when neither VAD pause nor MAX_SEGMENT_MS",
            markers.none { it.type == "commit" }
        )
    }

    @Test
    fun `partial markers accumulate into committedText via callback`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val transcriberCallback = pipeline.createTranscriberCallback()

        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "hello", 0 until 5))
        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "world", 6 until 11))

        // The callback appends partial text with a trailing space
        assertEquals("hello world ", pipeline.committedText)
    }

    @Test
    fun `commit marker advances currentSegmentStart`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        val transcriberCallback = pipeline.createTranscriberCallback()
        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "first segment", 0 until 13))

        val now = System.currentTimeMillis() + 10_000L
        pipeline.lastCommitTime = now - 10_000L
        pipeline.lastActivityTime = now - 10_000L
        pipeline.currentSegmentStart = 0

        pipeline.checkCommitBoundary(0.001f, now)

        assertEquals("currentSegmentStart should advance to committedText length", pipeline.committedText.length, pipeline.currentSegmentStart)
    }
}
