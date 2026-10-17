package com.velavoice.sdk

/**
 * Single commit-text owner (map #105 ticket #107).
 *
 * Partial results and finals used to reach the field through several
 * uncoordinated paths (IME stop/final/batch, accessibility capture, keyboard
 * session), so a final repeating text already inserted as the last partial
 * duplicated it. Every insert path routes its text decisions through this
 * committer, which owns the [StreamingFieldComposer] bookkeeping from #108:
 *
 * - [applyPartial] replaces exactly the owned span (revision-guarded, so
 *   stale/duplicate redeliveries are no-ops) — a re-emitted partial never
 *   appends a second copy.
 * - [commitFinal] replaces the trailing partial with the final instead of
 *   appending it, and stays a no-op for blank finals via the shared
 *   [shouldCommitTranscript] gate (#106 intact).
 * - [discardOwnedRange] is the cancel path: forget the span without touching
 *   the field, while late markers from the dead session stay stale.
 *
 * The committer computes text only; each service performs its own editor
 * write (InputConnection vs ACTION_SET_TEXT) from the returned value. That
 * keeps the pure replace/dedup logic unit-testable here while the Android
 * services stay thin owners of their single write site.
 */
class TextCommitter {
    private val composer = StreamingFieldComposer()

    /** Observe the field's pre-streaming text (first value wins). */
    fun observe(currentFieldText: String) = composer.observe(currentFieldText)

    /**
     * Apply a streaming partial marker, replacing only the owned span.
     * Returns the full text to write to the field.
     */
    fun applyPartial(marker: RevisionMarker, currentFieldText: String): String =
        composer.applyPartial(marker, currentFieldText)

    /**
     * Commit the final transcript, replacing only the owned span so
     * user-typed text outside it survives. Blank finals are a no-op (the
     * returned text equals the current field text).
     */
    fun commitFinal(finalText: String, currentFieldText: String): String =
        composer.commitFinal(finalText, currentFieldText)

    /** True while this session owns a revisable span in the field. */
    fun hasOwnedRange(): Boolean = composer.hasOwnedRange()

    /** Cancel path: forget the owned span without touching the field. */
    fun discardOwnedRange() = composer.discardOwnedRange()

    /** Forget all session state for the next streaming session. */
    fun reset() = composer.reset()
}
