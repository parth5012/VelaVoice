package com.velavoice.sdk

/**
 * Commit gate for final transcripts (map #105 ticket #106): an empty or
 * failed transcription must be a no-op — committing `""` over the field
 * would silently clear an in-progress composition the user already typed.
 *
 * Blank (whitespace-only) counts as empty: `isNotEmpty()` passes `"   "`,
 * so the gate uses [isNullOrBlank]. Null (failed transcription with no
 * text at all) never commits. Single gate shared by the batch `onResult`
 * path and the streaming `stopStreaming`/`onFinal` path so every commit
 * site agrees on what "nothing heard" means.
 */
fun shouldCommitTranscript(transcript: String?): Boolean = !transcript.isNullOrBlank()

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
    /**
     * Field offsets `[ownedStart, ownedEnd)` currently owned by the streaming
     * session (map #105 ticket #108): the span the last partial wrote and the
     * next partial/final may replace. Text the user typed outside this span is
     * never touched — replace splices prefix + new text + suffix.
     */
    private var ownedStart: Int = 0
    private var ownedEnd: Int = 0
    private var hasOwned: Boolean = false
    /**
     * Field offset where this session's FIRST owned span began. Delta-chain
     * producers (map #105 ticket #108, finding F02) emit each window's new
     * portion as a new segment, so [ownedStart] tracks only the LAST delta
     * while the transcribers' final delivers the FULL accumulated transcript.
     * `commitFinal` therefore replaces `[sessionOwnedStart, ownedEnd)` — the
     * whole session range — so prior deltas are never duplicated next to the
     * final. Single-segment (re-emitted) sessions have
     * `sessionOwnedStart == ownedStart`, unchanged behavior.
     */
    private var sessionOwnedStart: Int = 0
    /**
     * Transcript end offset owned by the last applied marker, in the marker's
     * own full-output coordinates. A marker starting at or past this offset
     * opens a new segment (appended at the owned end); one starting before it
     * re-emits the same segment (replaces the owned span).
     */
    private var ownedTranscriptEnd: Int = 0
    /**
     * Highest revision id applied so far. Stale duplicates and out-of-order
     * redeliveries (`revisionId <= lastRevisionId`) are ignored; unstamped
     * legacy markers (`revisionId == 0`) always apply. Retained by
     * [discardOwnedRange] so late markers from a cancelled session stay
     * ignored; cleared by [reset] for the next session.
     */
    private var lastRevisionId: Long = 0

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

    /**
     * Apply a partial marker to the field, replacing only the owned span
     * (map #105 ticket #108). Returns the text to write to the field.
     *
     * - Stale markers (`0 < revisionId <= last applied`) return
     *   [currentFieldText] unchanged: a new partial supersedes only its own
     *   prior text, never an older or duplicate delivery.
     * - Otherwise the owned span is spliced out and [marker.text] spliced in;
     *   everything the user typed outside the span (before or after it)
     *   survives verbatim.
     * - A marker starting at/past the previously owned transcript end opens a
     *   new segment appended at the owned end (delta chains); one starting
     *   before it re-emits the same segment (the owned span is replaced).
     * - Empty-text partials are a no-op.
     */
    fun applyPartial(marker: RevisionMarker, currentFieldText: String): String {
        observe(currentFieldText)
        if (marker.text.isEmpty()) return currentFieldText
        if (marker.revisionId > 0 && marker.revisionId <= lastRevisionId) return currentFieldText
        val base = baseline.orEmpty()

        val start = ownedStart.coerceIn(0, currentFieldText.length)
        val end = ownedEnd.coerceIn(start, currentFieldText.length)
        val anchor: Int
        val newSegment = !hasOwned || marker.range.first >= ownedTranscriptEnd
        if (!hasOwned) {
            anchor = (base.length + marker.range.first).coerceIn(base.length, currentFieldText.length)
            // First write of the session: everything this session composes
            // from here on is owned from this offset (finding F02).
            sessionOwnedStart = anchor
        } else if (newSegment) {
            // New segment: continue after what this session already owns.
            anchor = end
        } else {
            // Same segment re-emitted: replace the owned span.
            anchor = start
        }
        val prefix = currentFieldText.substring(0, anchor)
        val suffix = currentFieldText.substring(if (!hasOwned) anchor else end)
        val composed = prefix + marker.text + suffix

        ownedStart = anchor
        ownedEnd = anchor + marker.text.length
        ownedTranscriptEnd = if (newSegment) {
            maxOf(ownedTranscriptEnd, marker.range.last + 1)
        } else {
            // Re-emission defines the owned span exactly (covers shrink
            // corrections, where the new end precedes the old one).
            marker.range.last + 1
        }
        hasOwned = true
        if (marker.revisionId > 0) lastRevisionId = marker.revisionId
        return composed
    }

    /**
     * Commit the final transcript, replacing the whole session span so
     * user-typed text outside it survives (map #105 ticket #108). The span
     * starts at [sessionOwnedStart] — the session's first write — not just the
     * last partial: delta-chain finals deliver the FULL accumulated transcript,
     * and replacing only the last delta's span would duplicate every prior
     * delta (finding F02). With no owned span this falls back to
     * [finalWriteText] (baseline + final). A blank final is a no-op per the
     * shared [shouldCommitTranscript] gate (map #105 ticket #106): "nothing
     * heard" must never clear the field.
     */
    fun commitFinal(finalText: String, currentFieldText: String): String {
        if (!shouldCommitTranscript(finalText)) return currentFieldText
        if (!hasOwned) return finalWriteText(currentFieldText, finalText)
        observe(currentFieldText)
        val start = sessionOwnedStart.coerceIn(0, currentFieldText.length)
        val end = ownedEnd.coerceIn(start, currentFieldText.length)
        val composed = currentFieldText.substring(0, start) + finalText +
            currentFieldText.substring(end)
        ownedStart = start
        ownedEnd = start + finalText.length
        hasOwned = false
        return composed
    }

    /** True while this session owns a revisable span in the field. */
    fun hasOwnedRange(): Boolean = hasOwned

    /**
     * Cancel path (map #105 ticket #108, coordinate with 5.1's cancel): forget
     * the owned span without touching the field — emits no write, so whatever
     * the user typed stays exactly as it is. [lastRevisionId] is retained so
     * late markers from the dead session are still ignored as stale.
     */
    fun discardOwnedRange() {
        ownedStart = 0
        ownedEnd = 0
        ownedTranscriptEnd = 0
        sessionOwnedStart = 0
        hasOwned = false
    }

    /** Forget the baseline so the next streaming session captures its own. */
    fun reset() {
        baseline = null
        discardOwnedRange()
        lastRevisionId = 0
    }
}
