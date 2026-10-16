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
}
