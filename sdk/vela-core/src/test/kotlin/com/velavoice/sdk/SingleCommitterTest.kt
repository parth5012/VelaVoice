package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map #105 ticket #107: the single commit-text owner contract, pinned through
 * the exact [TextCommitter] API the IME / accessibility / keyboard paths route
 * through. The headline case: a partial plus a matching final must yield
 * exactly the final text, once — never current + final appended on top of the
 * already-inserted partial.
 */
class SingleCommitterTest {

    @Test
    fun `partial plus matching final yields exactly the final text once`() {
        val committer = TextCommitter()

        var field = "Notes: "
        field = committer.applyPartial(RevisionMarker("partial", "hello ", 0 until 6, 1), field)
        assertEquals("Notes: hello ", field)

        // The final repeats the partial prefix: it must REPLACE the trailing
        // partial, not append a second copy.
        field = committer.commitFinal("hello world", field)

        assertEquals("Notes: hello world", field)
        assertEquals(
            "final text must appear exactly once (no partial + final duplication)",
            1,
            field.split("hello world").size - 1
        )
    }

    @Test
    fun `growing partial replaces instead of appending`() {
        val committer = TextCommitter()

        var field = ""
        field = committer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        field = committer.applyPartial(RevisionMarker("partial", "hello world", 0 until 11, 2), field)

        assertEquals("hello world", field)
        assertEquals(1, field.split("hello").size - 1)
    }

    @Test
    fun `duplicate and stale partials never duplicate the field`() {
        val committer = TextCommitter()

        var field = ""
        field = committer.applyPartial(RevisionMarker("partial", "hello world", 0 until 11, 2), field)
        assertEquals("hello world", field)

        val replayed = committer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        assertEquals("stale revision must not rewrite the field", field, replayed)
        val duplicated = committer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 2), field)
        assertEquals("duplicate revision must not rewrite the field", field, duplicated)
    }

    @Test
    fun `blank final stays a no-op (ticket 106 gate intact)`() {
        val committer = TextCommitter()

        var field = "Notes: "
        field = committer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        assertEquals("Notes: hello", field)

        assertEquals(field, committer.commitFinal("", field))
        assertEquals(field, committer.commitFinal("   ", field))
        assertFalse("blank final must not commit", shouldCommitTranscript(""))
    }

    @Test
    fun `user-typed text outside the owned span survives partials and the final`() {
        val committer = TextCommitter()

        var field = "Notes: "
        field = committer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        // User types mid-stream, outside (after) the owned range.
        field += "!!"
        assertEquals("Notes: hello!!", field)

        field = committer.applyPartial(RevisionMarker("partial", "hello world", 0 until 11, 2), field)
        assertEquals("Notes: hello world!!", field)

        field = committer.commitFinal("hello world", field)
        assertEquals("Notes: hello world!!", field)
        assertTrue("user-typed characters must be preserved", field.contains("!!"))
    }

    @Test
    fun `cancel discards the owned span without touching the field`() {
        val committer = TextCommitter()

        var field = ""
        field = committer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        assertEquals("hello", field)
        assertTrue(committer.hasOwnedRange())

        val beforeCancel = field
        committer.discardOwnedRange()

        assertFalse("owned range must be gone after cancel", committer.hasOwnedRange())
        assertEquals("cancel must not rewrite the field", beforeCancel, field)
    }

    @Test
    fun `streamed delta windows plus full-text final render exactly one copy`() {
        // Finding F02: the real LocalStreamingTranscriber shape — each window
        // emits ONLY its new portion as a new segment (delta chain), while
        // onFinal delivers the FULL accumulated transcript. The final must
        // replace the whole session range, not just the last delta's span.
        val committer = TextCommitter()

        var field = ""
        field = committer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        field = committer.applyPartial(RevisionMarker("partial", "world", 5 until 10, 2), field)
        field = committer.applyPartial(RevisionMarker("partial", "foo", 10 until 13, 3), field)

        field = committer.commitFinal("hello world foo", field)

        assertEquals("hello world foo", field)
        assertEquals(
            "prior deltas must not survive next to the final",
            1,
            field.split("hello").size - 1
        )
        assertEquals(
            "final text must appear exactly once (no partial + final duplication)",
            1,
            field.split("world").size - 1
        )
    }

    @Test
    fun `user-typed text typed mid-delta-chain survives the final`() {
        // Same delta-chain shape with user text typed between windows: the
        // final replaces the whole session span, so text typed outside it
        // (before or after) must survive verbatim.
        val committer = TextCommitter()

        var field = "Notes: "
        field = committer.applyPartial(RevisionMarker("partial", "hello", 0 until 5, 1), field)
        field += "!!"
        field = committer.applyPartial(RevisionMarker("partial", "world", 5 until 10, 2), field)
        field = committer.applyPartial(RevisionMarker("partial", "foo", 10 until 13, 3), field)

        field = committer.commitFinal("hello world foo", field)

        assertEquals("Notes: hello world foo!!", field)
        assertTrue("user-typed characters must be preserved", field.contains("!!"))
    }
}
