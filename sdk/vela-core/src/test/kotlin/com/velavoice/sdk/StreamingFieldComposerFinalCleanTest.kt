package com.velavoice.sdk

import com.velavoice.sdk.cleaner.CleanerConfig
import com.velavoice.sdk.cleaner.TextCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Map #130 ticket #132: VAS streaming must apply the final [TextCleaner] pass
 * (the parity that `VelaStreamingSession.runCleanup` gives the keyboard path)
 * and compose it onto the [StreamingFieldComposer] baseline — never onto the
 * field's current (partially streamed) content.
 *
 * Baseline + cleaned-final is the only non-duplicating shape: interim commit
 * markers rewrite the field with partials before onFinal arrives, so
 * current + final re-appends everything already streamed.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingFieldComposerFinalCleanTest {

    @Test
    fun `final write is baseline plus cleaned final`() {
        val composer = StreamingFieldComposer()
        val cleaner = TextCleaner(CleanerConfig())

        // Pre-streaming baseline observed at the first write.
        composer.observe("Notes: ")
        // Interim commits have since rewritten the field with partials.
        val currentWithPartials = "Notes: hello wor"
        val rawFinal = "um hello world"

        val cleaned = cleaner.clean(rawFinal, privacySensitive = false)
        assertEquals("rule-based pass must strip the filler", "hello world", cleaned)

        assertEquals(
            "final write must be baseline + cleaned final",
            "Notes: hello world",
            composer.finalWriteText(currentWithPartials, cleaned)
        )
    }

    @Test
    fun `final write never duplicates interim partials`() {
        val composer = StreamingFieldComposer()
        val cleaner = TextCleaner(CleanerConfig())

        composer.observe("Notes: ")
        val currentWithPartials = "Notes: hello wor"
        val cleaned = cleaner.clean("um hello world", privacySensitive = false)

        val write = composer.finalWriteText(currentWithPartials, cleaned)

        assertNotEquals(
            "current + final would re-append the already-streamed partials",
            currentWithPartials + cleaned,
            write
        )
        assertEquals(
            "cleaned final must appear exactly once",
            1,
            write.split(cleaned).size - 1
        )
    }

    @Test
    fun `sensitive final still gets the rule-based cleaner pass`() {
        val composer = StreamingFieldComposer()
        val cleaner = TextCleaner(CleanerConfig(useLlm = true))

        composer.observe("")
        val cleaned = cleaner.clean("uh secret phrase", privacySensitive = true)

        assertEquals(
            "privacy gate disables LLM/Scribe but keeps local rule-based cleanup",
            "secret phrase",
            cleaned
        )
        assertEquals("secret phrase", composer.finalWriteText("", cleaned))
    }

    @Test
    fun `baseline first-wins even when interim observes carry partials`() {
        // Locks the OCR-dup baseline semantics VAS relies on: observe() is
        // called on every streaming write, but only the first (pre-streaming)
        // value is the baseline. A buggy current+final compose would re-append
        // partials already in the field.
        val composer = StreamingFieldComposer()
        composer.observe("Notes: ")
        composer.observe("Notes: hello wor")
        composer.observe("Notes: hello world hello world")

        val write = composer.finalWriteText("Notes: hello world hello world", "hello world")

        assertEquals("Notes: hello world", write)
    }

    @Test
    fun `empty baseline plus final never duplicates`() {
        val composer = StreamingFieldComposer()
        composer.observe("")
        val cleaned = TextCleaner(CleanerConfig()).clean("um hello world", privacySensitive = false)
        val currentWithPartials = "hello wor"

        assertEquals("hello world", composer.finalWriteText(currentWithPartials, cleaned))
        assertNotEquals(currentWithPartials + cleaned, composer.finalWriteText(currentWithPartials, cleaned))
    }
}
