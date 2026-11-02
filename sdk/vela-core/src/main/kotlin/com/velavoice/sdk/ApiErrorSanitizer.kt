package com.velavoice.sdk

/**
 * Capping and sanitizing remote provider error bodies (Finding F37, map #211 ticket #238).
 *
 * Prevents log injection, terminal/UI escape sequence corruption, and HTML walls by:
 * 1. Stripping non-printable characters and control codes (replacing control sequences with space).
 * 2. Capping length to at most [MAX_API_ERROR_LENGTH] (200 chars).
 */
const val MAX_API_ERROR_LENGTH = 200

fun sanitizeApiErrorBody(raw: String?, maxLength: Int = MAX_API_ERROR_LENGTH): String {
    if (raw.isNullOrBlank()) return "Unknown error"
    val cap = maxLength.coerceAtLeast(0)

    val sb = StringBuilder(raw.length.coerceAtMost(cap))
    var lastWasSpace = false

    for (c in raw) {
        if (sb.length >= cap) break
        // Check if character is printable:
        // Printable ASCII is 0x20 (' ') through 0x7E ('~').
        // Non-ASCII Unicode characters above 0x7F are allowed if not ISO control characters.
        if (c in ' '..'~' || (c > '\u007F' && !Character.isISOControl(c))) {
            sb.append(c)
            lastWasSpace = (c == ' ')
        } else if (Character.isISOControl(c) || Character.isWhitespace(c)) {
            // Collapse whitespace/newlines/control characters to a single space if not already trailing space
            if (!lastWasSpace && sb.isNotEmpty()) {
                sb.append(' ')
                lastWasSpace = true
            }
        }
    }

    val trimmed = sb.toString().trim()
    return if (trimmed.isEmpty()) "Unknown error" else trimmed.take(cap)
}
