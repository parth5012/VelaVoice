package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Map #105 ticket #111 (partial dedup): identical consecutive partials must
 * not touch the UI — no additional callback, no buffer churn. Pairs with
 * the #108 revision-id ordering (stale ids already drop); this covers
 * same-text redeliveries that carry a fresh id.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingPipelinePartialDedupTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `identical consecutive partials produce zero additional UI updates`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })
        val transcriberCallback = pipeline.createTranscriberCallback()

        val first = RevisionMarker("partial", "hello world", 0 until 11, revisionId = 7)
        transcriberCallback.onRevisionMarker(first)
        assertEquals(1, markers.size)
        val bufferedAfterFirst = pipeline.committedText

        // Identical redelivery (same text, same range, fresh id).
        val dup = RevisionMarker("partial", "hello world", 0 until 11, revisionId = 8)
        transcriberCallback.onRevisionMarker(dup)

        assertEquals("identical partial must not touch the UI", 1, markers.size)
        assertEquals(bufferedAfterFirst, pipeline.committedText)
    }

    @Test
    fun `distinct partials still flow`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })
        val transcriberCallback = pipeline.createTranscriberCallback()

        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "hello", 0 until 5, revisionId = 1))
        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "hello world", 0 until 11, revisionId = 2))

        assertEquals(2, markers.size)
    }

    @Test
    fun `dedup state resets on start`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })
        val cb = pipeline.createTranscriberCallback()
        cb.onRevisionMarker(RevisionMarker("partial", "hello", 0 until 5, revisionId = 1))
        // Simulate a fresh session: start() resets revision + dedup state.
        // (start() needs mic; emulate the reset contract directly.)
        pipeline.resetPartialDedupForTest()
        cb.onRevisionMarker(RevisionMarker("partial", "hello", 0 until 5, revisionId = 1))
        assertTrue("after reset the same text must flow again", markers.size == 2)
    }
}
