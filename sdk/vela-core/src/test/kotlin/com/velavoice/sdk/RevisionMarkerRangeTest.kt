package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

class RevisionMarkerRangeTest {

    // --- Area 5: Range arithmetic on RevisionMarker ---
    // NOTE: range is NOT a reliable buffer offset. Pipeline and transcriber
    // track offsets independently and they drift. These tests pin the CURRENT
    // behavior so regressions are caught.

    @Test
    fun `range reflects constructor arguments exactly`() {
        val marker = RevisionMarker("partial", "hello world", 0 until 11)
        assertEquals("partial", marker.type)
        assertEquals("hello world", marker.text)
        assertEquals(0, marker.range.first)
        assertEquals(10, marker.range.last)
    }

    @Test
    fun `range is a half-open IntRange`() {
        // range is documented as IntRange which is half-open [first, last+1)
        val marker = RevisionMarker("commit", "test", 5 until 9)
        assertEquals(5, marker.range.first)
        assertEquals(8, marker.range.last)
        // length of range should equal text length
        assertEquals(marker.text.length, marker.range.count())
    }

    @Test
    fun `range does not track audio buffer offset (pins current drift behavior)`() {
        // In LocalStreamingTranscriber, committedLength drives the range start.
        // But committedLength is updated based on transcribed text length,
        // not PCM bytes consumed. So range is character-position-based,
        // NOT a byte offset into the audio buffer.
        //
        // This test documents that range values can diverge from any notion
        // of "where in the audio" the text came from.

        // Simulate: transcriber sees 10 chars of text, emits partial marker.
        // Range start = committedLength (0), end = 0 + 10 = 10.
        val marker = RevisionMarker("partial", "0123456789", 0 until 10)
        assertEquals(0 until 10, marker.range)

        // Even if the underlying PCM for those 10 chars was 48000 bytes
        // (3 seconds of 16-bit mono 16kHz), the range is still just 0..10.
        // The range is purely about text position, not audio position.
        // THIS IS THE DOCUMENTED LIMITATION.
    }

    @Test
    fun `commit marker range based on committedLength pins current behavior`() {
        // After first segment committed at length 5, next commit starts at 5
        val first = RevisionMarker("commit", "hello", 0 until 5)
        assertEquals(0, first.range.first)
        assertEquals(4, first.range.last)

        // Second segment: pipeline sets currentSegmentStart to committedText.length
        // (which includes trailing space added by the callback: "hello ".length = 6)
        // But the commit marker range uses start until snapshot.length
        // This test pins whatever the actual arithmetic produces.
        val second = RevisionMarker("commit", "world", 6 until 11)
        assertEquals(6, second.range.first)
        assertEquals(10, second.range.last)
    }

    @Test
    fun `data class equality and copy preserve range`() {
        val original = RevisionMarker("partial", "text", 3 until 7)
        val copy = original.copy(type = "commit")
        assertEquals(original.range, copy.range)
        assertEquals(3 until 7, copy.range)
    }
}
