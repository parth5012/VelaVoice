package com.velavoice.sdk

import com.velavoice.sdk.whisper.WhisperConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Map #105 ticket #108: revision bookkeeping for streaming partials.
 *
 * A new partial must replace only its own prior text without clobbering user
 * input typed mid-stream. That requires:
 * - a monotonically increasing revision id plus the character range each
 *   partial owns on every partial/final emission;
 * - a committer ([StreamingFieldComposer]) that replaces exactly the owned
 *   range, so user-typed text outside it survives;
 * - a cancel path ([StreamingFieldComposer.discardOwnedRange]) that discards
 *   the owned range without touching the field.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingRevisionBookkeepingTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun collectFrom(transcriber: CloudStreamingTranscriber): MutableList<RevisionMarker> {
        val markers = mutableListOf<RevisionMarker>()
        transcriber.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })
        return markers
    }

    // --- Emission: revision ids ---

    @Test
    fun `cloud partials carry strictly increasing revision ids`() {
        val t = CloudStreamingTranscriber()
        val markers = collectFrom(t)

        t.handleDelta("hello")
        t.handleDelta(" there")
        t.handleDelta(" world")

        assertEquals(3, markers.size)
        assertTrue("first emission must carry a positive revision id", markers[0].revisionId > 0)
        assertTrue("revision ids must increase", markers[1].revisionId > markers[0].revisionId)
        assertTrue("revision ids must increase", markers[2].revisionId > markers[1].revisionId)
    }

    @Test
    fun `cloud commit continues the revision sequence`() {
        val t = CloudStreamingTranscriber()
        val markers = collectFrom(t)

        t.handleDelta("hello")
        t.handleCompleted("hello")

        assertEquals(2, markers.size)
        assertEquals("commit", markers[1].type)
        assertTrue(
            "commit revision must follow the partial sequence",
            markers[1].revisionId > markers[0].revisionId
        )
    }

    @Test
    fun `local partials carry strictly increasing revision ids`() {
        val t = LocalStreamingTranscriber(WhisperConfig("/dummy/model.bin", "en", 4))
        val markers = mutableListOf<RevisionMarker>()
        t.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        t.handleTranscriptionResult("hello")
        t.handleTranscriptionResult("hello world")

        val partials = markers.filter { it.type == "partial" }
        assertEquals(2, partials.size)
        assertTrue("first emission must carry a positive revision id", partials[0].revisionId > 0)
        assertTrue("revision ids must increase", partials[1].revisionId > partials[0].revisionId)
    }

    @Test
    fun `pipeline commit markers carry revision ids above pass-through partials`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        val transcriberCallback = pipeline.createTranscriberCallback()
        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "hello world", 0 until 11, 7))

        val now = System.currentTimeMillis() + 10_000L
        pipeline.lastCommitTime = now - 10_000L
        pipeline.lastActivityTime = now - 10_000L
        pipeline.currentSegmentStart = 0
        pipeline.checkCommitBoundary(0.001f, now)

        val commits = markers.filter { it.type == "commit" }
        assertEquals(1, commits.size)
        assertTrue(
            "pipeline commit id must stay monotonic above the preserved pass-through id",
            commits[0].revisionId > 7
        )
    }

    @Test
    fun `pipeline stamps unstamped legacy markers without breaking the sequence`() {
        val pipeline = StreamingPipeline.Builder(context).build()
        val markers = mutableListOf<RevisionMarker>()
        pipeline.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })

        val transcriberCallback = pipeline.createTranscriberCallback()
        transcriberCallback.onRevisionMarker(RevisionMarker("partial", "hello", 0 until 5))
        transcriberCallback.onRevisionMarker(RevisionMarker("partial", " world", 5 until 11))

        assertEquals(2, markers.size)
        assertTrue(markers[0].revisionId > 0)
        assertTrue(markers[1].revisionId > markers[0].revisionId)
    }

    // --- Committer: owned-range replace ---

    @Test
    fun `growing partial replaces only its owned range`() {
        val composer = StreamingFieldComposer()

        var field = "Notes: "
        field = composer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        assertEquals("Notes: hello", field)

        // Re-emitted growing partial owns the same span: replace, do not append.
        field = composer.applyPartial(RevisionMarker("partial", "hello world", 0 until 11, 2), field)
        assertEquals("Notes: hello world", field)
    }

    @Test
    fun `user-typed text after the owned range survives a partial replace`() {
        val composer = StreamingFieldComposer()

        var field = "Notes: "
        field = composer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        // User types mid-stream, outside (after) the owned range.
        field += "!!"
        assertEquals("Notes: hello!!", field)

        field = composer.applyPartial(RevisionMarker("partial", "hello world", 0 until 11, 2), field)
        assertEquals("Notes: hello world!!", field)
    }

    @Test
    fun `user-typed text survives the final commit`() {
        val composer = StreamingFieldComposer()

        var field = "Notes: "
        field = composer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        field += "!!"
        field = composer.applyPartial(RevisionMarker("partial", "hello world", 0 until 11, 2), field)

        val final = composer.commitFinal("hello world", field)
        assertEquals("Notes: hello world!!", final)
        assertTrue("user-typed characters must be preserved", final.contains("!!"))
    }

    @Test
    fun `stale revision is ignored`() {
        val composer = StreamingFieldComposer()

        var field = "Notes: "
        field = composer.applyPartial(RevisionMarker("partial", "hello world", 0 until 11, 2), field)
        assertEquals("Notes: hello world", field)

        // Late duplicate / out-of-order delivery of an older revision: no-op.
        val replayed = composer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        assertEquals("stale revision must not rewrite the field", field, replayed)
        val duplicated = composer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 2), field)
        assertEquals("duplicate revision must not rewrite the field", field, duplicated)
    }

    @Test
    fun `cancel discards the owned range without touching the field`() {
        val composer = StreamingFieldComposer()

        var field = "Notes: "
        field = composer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        assertEquals("Notes: hello", field)
        assertTrue(composer.hasOwnedRange())

        val beforeCancel = field
        composer.discardOwnedRange()

        assertFalse("owned range must be gone after cancel", composer.hasOwnedRange())
        assertEquals("cancel must not rewrite the field", beforeCancel, field)
    }

    @Test
    fun `blank final stays a no-op (ticket 106 gate intact)`() {
        val composer = StreamingFieldComposer()

        var field = "Notes: "
        field = composer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)

        assertEquals(field, composer.commitFinal("", field))
        assertEquals(field, composer.commitFinal("   ", field))
        assertFalse("blank final must not commit", shouldCommitTranscript(""))
    }

    @Test
    fun `delta-chain partials append at the owned end`() {
        val composer = StreamingFieldComposer()

        var field = "Notes: "
        field = composer.applyPartial(RevisionMarker("partial", "abc", 0 until 3, 1), field)
        field = composer.applyPartial(RevisionMarker("partial", "def", 3 until 6, 2), field)
        field = composer.applyPartial(RevisionMarker("partial", "ghi", 6 until 9, 3), field)

        assertEquals("Notes: abcdefghi", field)
    }
}
