package com.velavoice.sdk

/**
 * Composes a Whisper `initial_prompt` string from the user's personal dictionary
 * entries (names, acronyms, custom vocabulary) to bias recognition.
 *
 * Rules (Batch 3, ticket #174):
 *  - entries are comma-separated
 *  - case-insensitive dedup, first occurrence wins (input order = priority, most-recently-used first)
 *  - single-character entries are skipped
 *  - blank entries are skipped
 *  - composed string is capped at [maxLength] characters (Whisper works best under ~224 tokens)
 *  - empty result -> null (no prompt fed)
 */
class DictionaryPromptComposer(private val maxLength: Int = DEFAULT_MAX_LENGTH) {

    fun compose(entries: List<String>): String? {
        if (entries.isEmpty()) return null
        val seen = mutableSetOf<String>()
        val chosen = mutableListOf<String>()
        var length = 0
        for (raw in entries) {
            val entry = raw.trim()
            if (entry.length <= 1) continue
            val key = entry.lowercase()
            if (!seen.add(key)) continue
            // account for ", " separator
            val added = if (chosen.isEmpty()) entry.length else entry.length + 2
            if (length + added > maxLength) {
                // If nothing chosen yet, still try to fit a truncated single entry? Spec: cap string.
                if (chosen.isEmpty() && entry.length <= maxLength) {
                    chosen.add(entry)
                }
                break
            }
            chosen.add(entry)
            length += added
        }
        if (chosen.isEmpty()) return null
        return chosen.joinToString(", ")
    }

    companion object {
        const val DEFAULT_MAX_LENGTH = 800
    }
}
