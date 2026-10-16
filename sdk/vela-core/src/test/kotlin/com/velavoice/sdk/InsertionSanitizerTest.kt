package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Map #105 ticket #109: harden accessibility text insertion.
 *
 * Transcribed text reaches the field unvalidated: a NUL or malformed
 * surrogate from the tokenizer path can blank or corrupt the destination
 * EditText/compose field. [sanitizeForInsertion] is the shared defence-in-
 * depth boundary (cross-ref 4.5 cleans UTF-8 upstream): strip NUL and lone
 * surrogates, cap length — wired into the VAS insert boundary and the VIMS
 * single commit-text owner.
 */
class InsertionSanitizerTest {

    @Test
    fun `NUL-containing transcript inserts the sanitized string`() {
        assertEquals("helloworld", sanitizeForInsertion("hello\u0000world"))
    }

    @Test
    fun `lone surrogates are stripped but valid pairs survive`() {
        val emoji = "\uD83D\uDE00" // U+1F600, valid surrogate pair
        assertEquals("a${emoji}b", sanitizeForInsertion("a\uD800${emoji}b\uDC00"))
    }

    @Test
    fun `lone low surrogate is stripped`() {
        assertEquals("ab", sanitizeForInsertion("a\uDC00b"))
    }

    @Test
    fun `over-cap text is truncated to maxLength`() {
        val long = "x".repeat(5000)
        assertEquals(MAX_INSERTION_TEXT_LENGTH, sanitizeForInsertion(long).length)
        assertEquals(10, sanitizeForInsertion(long, maxLength = 10).length)
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        val emoji = "\uD83D\uDE00"
        val text = "ab${emoji}c"
        // Cap lands mid-pair: the pair must be dropped whole AND truncation
        // must stop — downstream chars past the cut must NOT be spliced in
        // (regression: old code returned "abc").
        val capped = sanitizeForInsertion(text, maxLength = 3)
        assertEquals("ab", capped)
        assertTrue("capped text must contain no lone surrogates", capped.none { it.isSurrogate() })
    }

    @Test
    fun `clean text passes through unchanged`() {
        val clean = "Hello, world! 123"
        assertEquals(clean, sanitizeForInsertion(clean))
    }

    @Test
    fun `empty text stays empty`() {
        assertEquals("", sanitizeForInsertion(""))
    }

    @Test
    fun `NUL-only transcript sanitizes to blank so the 106 gate no-ops it`() {
        val sanitized = sanitizeForInsertion("\u0000\u0000")
        assertEquals("", sanitized)
        assertFalse(
            "a NUL-only transcript must not commit after sanitizing",
            shouldCommitTranscript(sanitized)
        )
    }
}
