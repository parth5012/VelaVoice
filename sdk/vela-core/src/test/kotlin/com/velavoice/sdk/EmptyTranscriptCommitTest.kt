package com.velavoice.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map #105 ticket #106: an empty or failed transcription must be a no-op —
 * nothing committed over the field (which would silently clear an in-progress
 * composition the user already typed). The whitespace-only case matters too:
 * `isNotEmpty()` passes `"   "`, so the gate must reject blanks.
 */
class EmptyTranscriptCommitTest {

    @Test
    fun `empty transcript must not commit`() {
        assertFalse(
            "committing \"\" would clear the field's existing text",
            shouldCommitTranscript("")
        )
    }

    @Test
    fun `whitespace-only transcript must not commit`() {
        assertFalse(
            "committing \"   \" would clear the field's existing text",
            shouldCommitTranscript("   ")
        )
        assertFalse(shouldCommitTranscript("\t\n "))
    }

    @Test
    fun `null transcript must not commit`() {
        assertFalse(shouldCommitTranscript(null))
    }

    @Test
    fun `non-empty transcript must commit`() {
        assertTrue(shouldCommitTranscript("hello world"))
    }

    @Test
    fun `transcript with surrounding whitespace but real content must commit`() {
        assertTrue(shouldCommitTranscript("  hello  "))
    }
}
