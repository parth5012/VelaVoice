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
        // 1. Whitespace and line/paragraph separators (e.g. \u2028, \u2029) collapse to space
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
            if (!lastWasSpace && sb.isNotEmpty()) {
                sb.append(' ')
                lastWasSpace = true
            }
        }
        // 2. Reject ISO control characters and Unicode format controls (e.g. \u202E bidirectional override)
        else if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT.toInt()) {
            // Drop control/format character
        }
        // 3. Accept printable ASCII (' '..'~') and printable Unicode
        else if (c in ' '..'~' || c > '\u007F') {
            sb.append(c)
            lastWasSpace = false
        }
    }

    val trimmed = sb.toString().trim()
    return if (trimmed.isEmpty()) "Unknown error" else trimmed.take(cap)
}
