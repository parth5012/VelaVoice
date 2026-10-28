package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket #104: StreamingPipeline's partial-transcript bookkeeping buffer
 * (committedText) is drop-oldest-bounded. Simulates a long streamed session
 * (one partial marker every ~200 ms, forced segment commit every ~9 s via the
 * MAX_SEGMENT_MS path, exactly as the capture thread does) and asserts the
 * buffer never exceeds the cap while commit markers keep flowing — nothing is
 * dropped before it has been delivered to the UI as a commit marker.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingPipelinePartialCapTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `committedText stays bounded across a synthetic five-minute streamed session`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        val transcriberCallback = pipeline.createTranscriberCallback()
        var now = System.currentTimeMillis()
        pipeline.lastCommitTime = now
        pipeline.lastActivityTime = now

        val partialBase = "y".repeat(96)
        // 1000 partials * 100 chars = 100_000 chars of transcript (~5 minutes
        // of speech), far above the 4096-char cap. Each partial carries its
        // index so consecutive markers are distinct: the #111 identical-partial
        // dedup must not collapse a genuinely advancing transcript. Owned
        // ranges advance with the index (#217): a delta-chain partial extends
        // the full output, whereas a re-owned overlapping span would fold as a
        // rolling hypothesis replacing the uncommitted tail instead.
        for (i in 0 until 1000) {
            val partial = partialBase + "%04d".format(i)
            val start = i * (partial.length + 1)
            transcriberCallback.onRevisionMarker(
                RevisionMarker("partial", partial, start until start + partial.length)
            )
            if (i % 50 == 49) {
                // Capture-thread equivalent: MAX_SEGMENT_MS forces a segment
                // commit so the consumed prefix becomes droppable.
                now += 9_000L
                pipeline.lastActivityTime = now
                pipeline.checkCommitBoundary(0.5f, now)
            }
            assertTrue(
                "committedText grew unbounded: ${pipeline.committedText.length} chars at partial $i",
                pipeline.committedText.length <=
                    StreamingPipeline.MAX_PARTIAL_CHARS + 50 * (partial.length + 1) + partial.length + 1
            )
        }

        val commits = markers.filter { it.type == "commit" }
        assertTrue(
            "segment commits must keep flowing (UI receives every segment), got ${commits.size}",
            commits.size >= 10
        )
    }

    /**
     * Ticket #104 blocker: after trimCommittedTextLocked() drops the committed
     * prefix, commit markers must STILL emit absolute ranges in the full-output
     * coordinate space (StreamingTranscriber.kt:7 contract), monotonically
     * non-decreasing across the session.
     *
     * Models the VIMS consumer exactly (VoiceInputMethodService.kt:396 + :557-563):
     * streamingBuffer mirrors the full session text the pipeline accumulates,
     * streamingCommittedLength = marker.range.last, and stopStreaming() re-commits
     * buffer.substring(streamingCommittedLength). A rewound range makes that slice
     * re-insert already-committed text (massive duplicate insertion).
     */
    @Test
    fun `commit ranges stay absolute and monotonic across trims so VIMS tail re-commit has no duplicates`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val commits = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {
                if (marker.type == "commit") commits.add(marker)
            }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        val transcriberCallback = pipeline.createTranscriberCallback()
        var now = System.currentTimeMillis()
        pipeline.lastCommitTime = now
        pipeline.lastActivityTime = now

        val vimsBuffer = StringBuilder()
        var streamingCommittedLength = 0

        val partialBase = "y".repeat(96)
        for (i in 0 until 1000) {
            // Distinct per index (see above): advancing speech, not a redelivery.
            // Owned ranges advance too (#217): each partial extends the full
            // output the vimsBuffer below mirrors.
            val partial = partialBase + "%04d".format(i)
            val start = i * (partial.length + 1)
            vimsBuffer.append(partial).append(' ')
            transcriberCallback.onRevisionMarker(
                RevisionMarker("partial", partial, start until start + partial.length)
            )
            if (i % 50 == 49) {
                now += 9_000L
                pipeline.lastActivityTime = now
                pipeline.checkCommitBoundary(0.5f, now)
            }
        }
        // Session-ending VAD pause commits the uncommitted tail (stopStreaming path)
        now += 10_000L
        pipeline.lastActivityTime = now - 10_000L
        pipeline.checkCommitBoundary(0.001f, now)

        assertTrue("expected segment commits, got ${commits.size}", commits.size >= 10)

        // 1) Absolute + monotonic: ranges never rewind below a previous end.
        assertEquals("first commit must start at absolute offset 0", 0, commits.first().range.first)
        var prevLast = -1
        for (c in commits) {
            assertTrue(
                "range rewound/overlapped after trim: ${c.range} vs previous end $prevLast",
                c.range.first > prevLast
            )
            prevLast = c.range.last
        }

        // 2) Contiguous coverage of the full output (exclusive-end convention,
        //    same as CloudStreamingTranscriberTest): no chars lost or duplicated
        //    at a trim boundary.
        for (i in 1 until commits.size) {
            assertEquals(
                "commit ranges must be contiguous across the trim boundary",
                commits[i - 1].range.last + 1,
                commits[i].range.first
            )
        }
        assertEquals(
            "final commit must reach the end of the full output",
            vimsBuffer.length,
            commits.last().range.last + 1
        )

        // 3) VIMS stopStreaming() tail re-commit (VoiceInputMethodService.kt:396 +
        //    :557-563) must not re-insert already-committed text. VIMS sets
        //    streamingCommittedLength = marker.range.last (last committed CHAR
        //    index used as a length — pre-existing off-by-one), so an exact-mirror
        //    buffer leaves at most 1 already-committed char in the tail; a rewound
        //    range would leave thousands.
        for (c in commits) streamingCommittedLength = c.range.last
        val tail = if (vimsBuffer.length > streamingCommittedLength) {
            vimsBuffer.substring(streamingCommittedLength)
        } else {
            ""
        }
        assertTrue(
            "stopStreaming() tail re-commits already-committed text " +
                "(${tail.length} chars): '${tail.take(40)}'",
            tail.length <= 1
        )
    }
}
