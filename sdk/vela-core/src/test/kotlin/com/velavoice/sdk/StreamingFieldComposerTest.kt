package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Streaming insertion contract (OCR finding, session a7d7065a):
 * commit markers write the interim buffer into the field BEFORE onFinal arrives,
 * so appending the final transcript to the field's *current* text duplicates
 * everything already streamed. The first observed field text is the baseline;
 * every final write must be baseline + finalText, never current + finalText.
 */
class StreamingFieldComposerTest {

    @Test
    fun `final write uses first-observed baseline even when interim partials are already in the field`() {
        val composer = StreamingFieldComposer()
        // First streaming write: field still holds the user's original text.
        composer.observe("Hello ")

        // Interim commits have since rewritten the field to include the partials.
        val write = composer.finalWriteText("Hello hello worhello world", "hello world")

        assertEquals(
            "final write must be baseline + finalText; interim partials must not be re-appended",
            "Hello hello world",
            write
        )
    }

    @Test
    fun `final write as first contact captures the current field text as baseline`() {
        val composer = StreamingFieldComposer()
        assertEquals(
            "no prior commits: baseline is the untouched field text",
            "Hello hi",
            composer.finalWriteText("Hello ", "hi")
        )
    }

    @Test
    fun `baseline observation keeps the first value`() {
        val composer = StreamingFieldComposer()
        composer.observe("first")
        composer.observe("first-second")
        assertEquals(
            "only the first observation is the pre-streaming baseline",
            "firstX",
            composer.finalWriteText("first-second", "X")
        )
    }

    @Test
    fun `reset clears the baseline for the next streaming session`() {
        val composer = StreamingFieldComposer()
        composer.observe("old session text")
        composer.reset()
        assertEquals(
            "after reset the next session's field text becomes the baseline",
            "new session text",
            composer.finalWriteText("new session text", "")
        )
    }
}
