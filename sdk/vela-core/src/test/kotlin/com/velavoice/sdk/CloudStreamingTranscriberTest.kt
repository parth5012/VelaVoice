package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CloudStreamingTranscriberTest {

    // --- Area 8: Cloud partial accumulation ---

    private fun makeCloudTranscriber(): CloudStreamingTranscriber {
        return CloudStreamingTranscriber()
    }

    private fun collectMarkers(transcriber: CloudStreamingTranscriber): MutableList<RevisionMarker> {
        val markers = mutableListOf<RevisionMarker>()
        transcriber.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) { markers.add(marker) }
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {}
            override fun onAmplitude(normalized: Float) {}
        })
        return markers
    }

    @Test
    fun `handleDelta accumulates partialText and emits partial markers`() {
        val t = makeCloudTranscriber()
        val markers = collectMarkers(t)

        t.handleDelta("Hello")
        t.handleDelta(" there")
        t.handleDelta(" world")

        assertEquals(3, markers.size)
        assertEquals("partial", markers[0].type)
        assertEquals("Hello", markers[0].text)
        assertEquals("partial", markers[1].type)
        assertEquals(" there", markers[1].text)
        assertEquals("partial", markers[2].type)
        assertEquals(" world", markers[2].text)

        assertEquals("Hello there world", t.partialText)
    }

    @Test
    fun `handleDelta ranges are contiguous and non-overlapping`() {
        val t = makeCloudTranscriber()
        val markers = collectMarkers(t)

        t.handleDelta("abc")
        t.handleDelta("def")
        t.handleDelta("ghi")

        // Ranges: [0,3), [3,6), [6,9)
        assertEquals(0..2, markers[0].range)
        assertEquals(3..5, markers[1].range)
        assertEquals(6..8, markers[2].range)

        // Non-overlapping: end of one == start of next
        assertEquals(markers[0].range.last + 1, markers[1].range.first)
        assertEquals(markers[1].range.last + 1, markers[2].range.first)
    }

    @Test
    fun `handleCompleted emits commit and resets partialText`() {
        val t = makeCloudTranscriber()
        val markers = collectMarkers(t)

        t.handleDelta("Hello there")
        t.handleCompleted("Hello there")

        // Should have the partial marker plus the commit marker
        assertEquals(2, markers.size)
        assertEquals("partial", markers[0].type)
        assertEquals("commit", markers[1].type)
        assertEquals("Hello there", markers[1].text)
        assertEquals(0..10, markers[1].range)

        // partialText must be reset
        assertTrue("partialText should be reset after completion", t.partialText.isEmpty())
    }

    @Test
    fun `handleCompleted uses partialText when transcript blank`() {
        val t = makeCloudTranscriber()
        val markers = collectMarkers(t)

        t.handleDelta("accumulated partial")
        t.handleCompleted("")

        val commitMarkers = markers.filter { it.type == "commit" }
        assertEquals(1, commitMarkers.size)
        assertEquals("accumulated partial", commitMarkers[0].text)
    }

    @Test
    fun `handleCompleted advances committedLength`() {
        val t = makeCloudTranscriber()
        collectMarkers(t)

        t.handleDelta("first segment")
        t.handleCompleted("first segment")
        assertEquals("first segment".length, t.committedLength)

        t.handleDelta("second")
        t.handleCompleted("second")
        assertEquals("first segment".length + "second".length, t.committedLength)
    }

    @Test
    fun `delta then completion then new delta works correctly`() {
        val t = makeCloudTranscriber()
        val markers = collectMarkers(t)

        t.handleDelta("alpha ")
        t.handleCompleted("alpha ")
        t.handleDelta("beta")
        t.handleCompleted("beta")

        val commits = markers.filter { it.type == "commit" }
        assertEquals(2, commits.size)
        assertEquals("alpha ", commits[0].text)
        assertEquals("beta", commits[1].text)
        // Second commit starts where first ended
        assertEquals(commits[0].range.last + 1, commits[1].range.first)
    }

    @Test
    fun `handleServerMessage with transcription delta calls handleDelta path`() {
        val t = makeCloudTranscriber()
        val markers = collectMarkers(t)

        val json = """{"type":"transcription.delta","delta":"test delta","item_id":"item_1"}"""
        t.handleServerMessage(json)

        assertEquals(1, markers.size)
        assertEquals("partial", markers[0].type)
        assertEquals("test delta", markers[0].text)
        assertEquals("test delta", t.partialText)
    }

    @Test
    fun `handleServerMessage with transcription completed calls handleCompleted path`() {
        val t = makeCloudTranscriber()
        val markers = collectMarkers(t)

        t.handleDelta("prefix ")
        val json = """{"type":"transcription.completed","transcript":"prefix completed"}"""
        t.handleServerMessage(json)

        val commitMarkers = markers.filter { it.type == "commit" }
        assertEquals(1, commitMarkers.size)
        assertEquals("prefix completed", commitMarkers[0].text)
    }

    @Test
    fun `handleServerMessage ignores unknown type`() {
        val t = makeCloudTranscriber()
        val markers = collectMarkers(t)

        val json = """{"type":"response.create","response":{}}"""
        t.handleServerMessage(json)

        assertTrue("Unknown message type should not emit markers", markers.isEmpty())
    }

    @Test
    fun `handleServerMessage with malformed json does not crash`() {
        val t = makeCloudTranscriber()
        collectMarkers(t)

        // Must not throw
        t.handleServerMessage("not json at all")
        t.handleServerMessage("")
        t.handleServerMessage("""{"type":""")
    }
}
