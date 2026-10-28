// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

/**
 * Deterministic spoken-command lexicon (VelaBoard Voice-UX Batch 1, ticket #148).
 *
 * Applied to the RAW ASR text BEFORE the SDK TextCleaner. No model/API calls.
 * Case-insensitive, word-boundary safe. English v1 set.
 */
object SpokenCommands {

    /** Punctuation + structural command phrase -> replacement. Multi-word phrases first. */
    private val PUNCTUATION: Map<String, String> = linkedMapOf(
        "new paragraph" to "\n\n",
        "new line" to "\n",
        "question mark" to "?",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "full stop" to ".",
        "open parenthesis" to "(",
        "close parenthesis" to ")",
        "open paren" to "(",
        "close paren" to ")",
        "open bracket" to "[",
        "close bracket" to "]",
        "open brace" to "{",
        "close brace" to "}",
        "dot dot dot" to "...",
        "at sign" to "@",
        "dollar sign" to "$",
        "semicolon" to ";",
        "apostrophe" to "'",
        "ampersand" to "&",
        "ellipsis" to "...",
        "colon" to ":",
        "comma" to ",",
        "period" to ".",
        "dash" to "-",
        "hyphen" to "-",
        "slash" to "/",
        "quote" to "\"",
        "hash" to "#",
        "hashtag" to "#",
        "percent" to "%"
    )

    // Emoji commands (#163 extension): the spoken form is always
    // "<name> emoji" — the bare name alone is NOT a command ("fire" stays
    // literal; only "fire emoji" produces 🔥). Curated ~40-name v1 set.
    private val EMOJI: Map<String, String> = linkedMapOf(
        "thumbs up" to "\uD83D\uDC4D",
        "thumbs down" to "\uD83D\uDC4E",
        "smiley face" to "\uD83D\uDE0A",
        "smiley" to "\uD83D\uDE0A",
        "smile" to "\uD83D\uDE0A",
        "grin" to "\uD83D\uDE04",
        "laughing" to "\uD83D\uDE02",
        "lol" to "\uD83D\uDE02",
        "joy" to "\uD83D\uDE02",
        "rofl" to "\uD83E\uDD23",
        "wink" to "\uD83D\uDE09",
        "sad face" to "\uD83D\uDE22",
        "sad" to "\uD83D\uDE22",
        "sob" to "\uD83D\uDE2D",
        "heart" to "\u2764\uFE0F",
        "red heart" to "\u2764\uFE0F",
        "broken heart" to "\uD83D\uDC94",
        "thinking" to "\uD83E\uDD14",
        "fire" to "\uD83D\uDD25",
        "clap" to "\uD83D\uDC4F",
        "raising hands" to "\uD83D\uDE4C",
        "ok hand" to "\uD83D\uDC4C",
        "wave" to "\uD83D\uDC4B",
        "pray" to "\uD83D\uDE4F",
        "rocket" to "\uD83D\uDE80",
        "star" to "\u2B50",
        "party" to "\uD83C\uDF89",
        "tada" to "\uD83C\uDF89",
        "balloon" to "\uD83C\uDF88",
        "sparkles" to "\u2728",
        "check mark" to "\u2705",
        "cross mark" to "\u274C",
        "warning" to "\u26A0\uFE0F",
        "high voltage" to "\u26A1",
        "eyes" to "\uD83D\uDC40",
        "skull" to "\uD83D\uDC80",
        "100" to "\uD83D\uDCAF",
        "poop" to "\uD83D\uDCA9",
        "cake" to "\uD83C\uDF82",
        "pizza" to "\uD83C\uDF55"
    )

    /** Spoken phrase forms of the emoji commands: "<name> emoji". */
    private val EMOJI_PHRASES: Set<String> by lazy { EMOJI.keys.map { "$it emoji" }.toSet() }

    private val EDIT_COMMANDS = setOf("scratch that", "delete last word", "delete last sentence")

    /** All command phrases, longest first for greedy matching. */
    private val ALL_PHRASES: List<String> by lazy {
        (PUNCTUATION.keys + EMOJI_PHRASES + EDIT_COMMANDS).sortedByDescending { it.length }
    }

    fun applySpokenCommands(rawText: String): String {
        val words = rawText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < words.size) {
            val phrase = matchPhrase(words, i)
            if (phrase != null) {
                val (cmd, consumed) = phrase
                when {
                    cmd in EDIT_COMMANDS -> applyEdit(cmd, sb)
                    cmd.endsWith(" emoji") && cmd.removeSuffix(" emoji") in EMOJI ->
                        appendEmoji(sb, EMOJI[cmd.removeSuffix(" emoji")]!!)
                    else -> PUNCTUATION[cmd]?.let { appendPunct(sb, it) }
                        ?: appendWord(sb, cmd) // defensive: unknown phrase, keep literal
                }
                i += consumed
            } else {
                appendWord(sb, words[i])
                i++
            }
        }
        return sb.toString().trimEnd()
    }

    private fun matchPhrase(words: List<String>, start: Int): Pair<String, Int>? {
        // try longest phrase
        val remaining = words.size - start
        val maxLen = ALL_PHRASES.maxOf { it.split(" ").size }.coerceAtMost(remaining)
        for (len in maxLen downTo 1) {
            val candidate = words.subList(start, start + len).joinToString(" ").lowercase()
            if (candidate in ALL_PHRASES_SET) {
                // ensure exact phrase match (ALL_PHRASES_SET contains phrases)
                return candidate to len
            }
        }
        return null
    }

    private val ALL_PHRASES_SET: Set<String> by lazy { ALL_PHRASES.toSet() }

    private fun appendWord(sb: StringBuilder, word: String) {
        // F39 (map #208 ticket #219): a trailing newline is already a complete
        // separator — the next word is appended directly after it ("new line
        // hello" -> "\nhello"), matching the locked "a new line b" -> "a\nb"
        // contract from ticket #148. No extra space is inserted after a line
        // break; words after non-whitespace get the normal joining space.
        if (sb.isNotEmpty() && !sb.last().isWhitespace()) {
            sb.append(' ')
        }
        sb.append(word)
    }

    private fun appendEmoji(sb: StringBuilder, emoji: String) {
        if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
        sb.append(emoji)
    }

    private fun appendPunct(sb: StringBuilder, punct: String) {
        // remove trailing spaces before punctuation
        while (sb.isNotEmpty() && sb.last().isWhitespace() && sb.last() != '\n') {
            sb.deleteCharAt(sb.length - 1)
        }
        // if ends with newline, keep newline then punct? attach after newline without space
        sb.append(punct)
    }

    private fun applyEdit(cmd: String, sb: StringBuilder) {
        when (cmd) {
            "delete last word" -> deleteLastWord(sb)
            "delete last sentence" -> deleteLastSentence(sb)
            "scratch that" -> scratchThat(sb)
        }
    }

    private fun deleteLastWord(sb: StringBuilder) {
        // trim trailing whitespace/newlines? delete last word token
        var end = sb.length
        while (end > 0 && sb[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && !sb[start - 1].isWhitespace() && sb[start - 1] != '.' && sb[start - 1] != '!' && sb[start - 1] != '?' && sb[start - 1] != '\n') {
            start--
        }
        // remove from start to end plus one trailing space if present
        sb.delete(start, sb.length)
        // trim trailing spaces
        while (sb.isNotEmpty() && sb.last().isWhitespace() && sb.last() != '\n') sb.deleteCharAt(sb.length - 1)
    }

    private fun deleteLastSentence(sb: StringBuilder) {
        // find last sentence terminator
        var idx = -1
        for (i in sb.length - 1 downTo 0) {
            val c = sb[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') { idx = i; break }
        }
        if (idx >= 0) {
            sb.delete(idx + 1, sb.length)
            // trim trailing spaces
            while (sb.isNotEmpty() && sb.last().isWhitespace() && sb.last() != '\n') sb.deleteCharAt(sb.length - 1)
        } else {
            sb.setLength(0)
        }
    }

    private fun scratchThat(sb: StringBuilder) {
        // remove last clause: find last clause separator
        var idx = -1
        for (i in sb.length - 1 downTo 0) {
            val c = sb[i]
            if (c == ',' || c == ';' || c == ':' || c == '\n' || c == '.' || c == '!' || c == '?') { idx = i; break }
        }
        if (idx >= 0) {
            sb.delete(idx + 1, sb.length)
            while (sb.isNotEmpty() && sb.last().isWhitespace() && sb.last() != '\n') sb.deleteCharAt(sb.length - 1)
            // if separator was comma/semicolon, keep it? remove separator too for clean? keep terminator for sentence
            if (idx >= 0 && (sb.getOrNull(idx) == ',' || sb.getOrNull(idx) == ';' || sb.getOrNull(idx) == ':')) {
                // remove the separator itself
                sb.deleteCharAt(idx)
                while (sb.isNotEmpty() && sb.last().isWhitespace() && sb.last() != '\n') sb.deleteCharAt(sb.length - 1)
            }
        } else {
            // no separator: delete last word
            deleteLastWord(sb)
        }
    }
}
