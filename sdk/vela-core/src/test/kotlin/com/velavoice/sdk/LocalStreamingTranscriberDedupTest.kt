package com.velavoice.sdk

import com.velavoice.sdk.whisper.WhisperConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalStreamingTranscriberDedupTest {

    private val transcriber = LocalStreamingTranscriber(WhisperConfig("/dummy/model.bin", "en", 4))

    // --- Area 3: Overlap dedup (findOverlapLength) ---

    @Test
    fun `findOverlapLength zero when previous empty`() {
        assertEquals(0, transcriber.findOverlapLength("", "hello world"))
    }

    @Test
    fun `findOverlapLength zero when current empty`() {
        assertEquals(0, transcriber.findOverlapLength("hello world", ""))
    }

    @Test
    fun `findOverlapLength full length when identical strings`() {
        assertEquals(11, transcriber.findOverlapLength("hello world", "hello world"))
    }

    @Test
    fun `findOverlapLength when current fully contains previous as prefix`() {
        // current starts with previous => full overlap of previous
        assertEquals(5, transcriber.findOverlapLength("hello", "hello world"))
    }

    @Test
    fun `findOverlapLength partial word-boundary suffix prefix overlap`() {
        // suffix "brown fox" of previous matches prefix of current
        val previous = "the quick brown fox"
        val current = "brown fox jumps over"
        assertEquals("brown fox".length, transcriber.findOverlapLength(previous, current))
    }

    @Test
    fun `findOverlapLength no overlap at all`() {
        assertEquals(0, transcriber.findOverlapLength("hello world", "goodbye earth"))
    }

    @Test
    fun `findOverlapLength repeated words longest match wins`() {
        // "the the the" vs "the the cat" => longest suffix of previous matching prefix of current is "the the"
        val previous = "the the the"
        val current = "the the cat"
        assertEquals("the the".length, transcriber.findOverlapLength(previous, current))
    }

    @Test
    fun `findOverlapLength single word overlap`() {
        assertEquals("world".length, transcriber.findOverlapLength("hello world", "world peace"))
    }

    @Test
    fun `findOverlapLength no false match on repeated single word`() {
        // "cat cat" vs "cat" => only one word can match (min words = 1)
        // suffixes of "cat cat": "cat" (last 1), "cat cat" (last 2)
        // current "cat" starts with "cat" yes, starts with "cat cat" no
        // so overlap = 3
        assertEquals(3, transcriber.findOverlapLength("cat cat", "cat"))
    }

    @Test
    fun `findOverlapLength full sentence overlap`() {
        val text = "the quick brown fox jumps over the lazy dog"
        assertEquals(text.length, transcriber.findOverlapLength(text, text + " and more"))
    }

    @Test
    fun `findOverlapLength no overlap when matching word not at start of current`() {
        // "world" appears in both but not at the start of current
        // suffixes of "hello world": "world", "hello world"
        // "world" is not a prefix of "the world" (starts with "the ")
        // => 0
        assertEquals(0, transcriber.findOverlapLength("hello world", "the world"))
    }

    @Test
    fun `findOverlapLength equal length different strings no overlap`() {
        assertEquals(0, transcriber.findOverlapLength("abcde", "fghij"))
    }

    @Test
    fun `findOverlapLength prefix match not at word boundary still works`() {
        // "abcd" vs "cdef" - the algorithm splits on spaces, so no word overlap
        // But "abcd" is one word, "cdef" is one word
        // suffix of "abcd" with 1 word = "abcd", does "cdef" start with "abcd"? no
        // => 0
        assertEquals(0, transcriber.findOverlapLength("abcd", "cdef"))
    }
}
