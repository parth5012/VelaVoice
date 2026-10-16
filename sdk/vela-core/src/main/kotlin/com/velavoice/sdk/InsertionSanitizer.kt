package com.velavoice.sdk

/**
 * Insertion hardening (map #105 ticket #109): the shared defence-in-depth
 * boundary every service crosses before placing transcribed text into a
 * field (cross-ref 4.5, which produces clean UTF-8 upstream).
 *
 * A NUL or malformed surrogate from the tokenizer path can blank or corrupt
 * the destination EditText/compose field, so transcribed text is sanitized
 * here — never trusted raw:
 * - NUL (`\u0000`) chars are stripped (they truncate/blank native text).
 * - Lone (unpaired) surrogates are stripped; valid pairs (emoji) survive.
 * - Output is capped at [MAX_INSERTION_TEXT_LENGTH] chars; the cut never
 *   splits a surrogate pair.
 *
 * Only the NEW transcript text is sanitized — never the field's existing
 * content, which the splice paths must preserve verbatim. A transcript that
 * sanitizes to blank falls through to the shared #106 gate
 * ([shouldCommitTranscript]), which no-ops it.
 */
const val MAX_INSERTION_TEXT_LENGTH = 4096

fun sanitizeForInsertion(text: String, maxLength: Int = MAX_INSERTION_TEXT_LENGTH): String {
    if (text.isEmpty()) return text
    val cap = maxLength.coerceAtLeast(0)
    val sb = StringBuilder(text.length.coerceAtMost(cap))
    var i = 0
    while (i < text.length && sb.length < cap) {
        val c = text[i]
        when {
            c == '\u0000' -> i++
            c.isHighSurrogate() -> {
                if (i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    // Valid pair: keep whole, never split at the cap.
                    // If it doesn't fit, STOP (longest fitting prefix) — never
                    // skip it and splice downstream chars past the cut.
                    if (sb.length + 2 <= cap) {
                        sb.append(c)
                        sb.append(text[i + 1])
                        i += 2
                    } else {
                        break
                    }
                } else {
                    i++ // Lone high surrogate: drop.
                }
            }
            c.isLowSurrogate() -> i++ // Lone low surrogate: drop.
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

/**
 * Stable identity of the field a session started dictating into (map #105
 * ticket #109). [AccessibilityNodeInfo] handles go stale on focus change, so
 * the service snapshots this platform-free triple at session start and
 * compares it against the node resolved at insert time.
 */
data class InsertionTarget(
    val windowId: Int,
    val viewIdResourceName: String?,
    val packageName: String
)

/**
 * Verdict over the node id captured at session start vs the node resolved
 * immediately before acting: a KNOWN start that no longer matches (focus
 * moved to another field/app mid-capture) or a disappeared target (nothing
 * focused) must be a clean no-op — never spray text into the wrong field.
 *
 * A null [captured] (no focus was established at start) falls back to the
 * freshly resolved node: with no start identity there is nothing to prove
 * stale, so the platform freshness check (`refresh()`) at the write site is
 * the guard. Deliberately fail-open here, fail-closed everywhere else.
 */
fun shouldInsertIntoTarget(captured: InsertionTarget?, current: InsertionTarget?): Boolean =
    current != null && (captured == null || captured == current)
