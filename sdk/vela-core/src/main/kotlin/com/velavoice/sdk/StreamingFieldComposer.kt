package com.velavoice.sdk

/**
 * Computes ACTION_SET_TEXT payloads for streaming insertion into a target field
 * (map #72, OCR finding session a7d7065a).
 *
 * The streaming pipeline writes interim commit markers into the field before the
 * final transcript arrives; appending the final text to the field's *current*
 * content therefore duplicates everything already streamed. The text observed at
 * the FIRST streaming write is the pre-streaming baseline, and every final write
 * is `baseline + finalText` regardless of what interim writes put in the field.
 */
class StreamingFieldComposer {

    private var baseline: String? = null

    /** Observe the field's current text before a streaming write (first value wins). */
    fun observe(currentFieldText: String) {
        if (baseline == null) baseline = currentFieldText
    }

    /**
     * Text to dispatch via ACTION_SET_TEXT for a final insert: the captured
     * baseline plus [finalText] — never the field's current (partially streamed)
     * content plus [finalText].
     */
    fun finalWriteText(currentFieldText: String, finalText: String): String {
        observe(currentFieldText)
        return baseline.orEmpty() + finalText
    }

    /** Forget the baseline so the next streaming session captures its own. */
    fun reset() {
        baseline = null
    }
}
