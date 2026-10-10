package com.velavoice.sdk.whisper

import java.io.File

/**
 * Configuration for the Whisper transcription engine.
 *
 * Ticket #95 (map #89): added [allowedModelRoots] for canonical-path containment
 * and [expectedHash] for SHA-256 verification before native init.
 *
 * @param allowedModelRoots  directories the model path must resolve inside
 *                           (canonical). Pass `context.filesDir` and/or
 *                           `context.getExternalFilesDir(null)`. Empty list
 *                           disables the check (backward compat only — callers
 *                           should always supply roots).
 * @param expectedHash       expected SHA-256 hex digest. When non-null, the model
 *                           file is verified before native init; a mismatch
 *                           throws [SecurityException].
 */
data class WhisperConfig private constructor(
    val modelPath: String,
    val language: String,
    val numThreads: Int,
    val allowedModelRoots: List<File>,
    val expectedHash: String?,
    @Suppress("UNUSED_PARAMETER") private val marker: Boolean
) {
    constructor(
        modelPath: String,
        language: String = DEFAULT_LANGUAGE,
        numThreads: Int = DEFAULT_THREADS,
        allowedModelRoots: List<File> = emptyList(),
        expectedHash: String? = null
    ) : this(
        modelPath = modelPath,
        language = language,
        numThreads = clampThreads(numThreads),
        allowedModelRoots = allowedModelRoots,
        expectedHash = expectedHash,
        marker = true
    )

    init {
        require(isValidLanguage(language)) { "Invalid language: $language" }
    }

    companion object {
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_THREADS = 4
        const val MAX_THREADS_CAP = 8

        private val VALID_LANGUAGES: Set<String> = setOf(
            "auto",
            "en", "english",
            "zh", "chinese",
            "de", "german",
            "es", "spanish",
            "ru", "russian",
            "ko", "korean",
            "fr", "french",
            "ja", "japanese",
            "pt", "portuguese",
            "tr", "turkish",
            "pl", "polish",
            "ca", "catalan",
            "nl", "dutch",
            "ar", "arabic",
            "sv", "swedish",
            "it", "italian",
            "id", "indonesian",
            "hi", "hindi",
            "fi", "finnish",
            "vi", "vietnamese",
            "he", "hebrew",
            "uk", "ukrainian",
            "el", "greek",
            "ms", "malay",
            "cs", "czech",
            "ro", "romanian",
            "da", "danish",
            "hu", "hungarian",
            "ta", "tamil",
            "no", "norwegian",
            "th", "thai",
            "ur", "urdu",
            "hr", "croatian",
            "bg", "bulgarian",
            "lt", "lithuanian",
            "la", "latin",
            "mi", "maori",
            "ml", "malayalam",
            "cy", "welsh",
            "sk", "slovak",
            "te", "telugu",
            "fa", "persian",
            "lv", "latvian",
            "bn", "bengali",
            "sr", "serbian",
            "az", "azerbaijani",
            "sl", "slovenian",
            "kn", "kannada",
            "et", "estonian",
            "mk", "macedonian",
            "br", "breton",
            "eu", "basque",
            "is", "icelandic",
            "hy", "armenian",
            "ne", "nepali",
            "mn", "mongolian",
            "bs", "bosnian",
            "kk", "kazakh",
            "sq", "albanian",
            "sw", "swahili",
            "gl", "galician",
            "mr", "marathi",
            "pa", "punjabi",
            "si", "sinhala",
            "km", "khmer",
            "sn", "shona",
            "yo", "yoruba",
            "so", "somali",
            "af", "afrikaans",
            "oc", "occitan",
            "ka", "georgian",
            "be", "belarusian",
            "tg", "tajik",
            "sd", "sindhi",
            "gu", "gujarati",
            "am", "amharic",
            "yi", "yiddish",
            "lo", "lao",
            "uz", "uzbek",
            "fo", "faroese",
            "ht", "haitian creole",
            "ps", "pashto",
            "tk", "turkmen",
            "nn", "nynorsk",
            "mt", "maltese",
            "sa", "sanskrit",
            "lb", "luxembourgish",
            "my", "myanmar",
            "bo", "tibetan",
            "tl", "tagalog",
            "mg", "malagasy",
            "as", "assamese",
            "tt", "tatar",
            "haw", "hawaiian",
            "ln", "lingala",
            "ha", "hausa",
            "ba", "bashkir",
            "jw", "javanese",
            "su", "sundanese",
            "yue", "cantonese"
        )

        fun isValidLanguage(language: String): Boolean =
            VALID_LANGUAGES.contains(language.lowercase().trim())

        fun clampThreads(threads: Int): Int {
            val hw = Runtime.getRuntime().availableProcessors()
            val maxThreads = maxOf(1, minOf(MAX_THREADS_CAP, hw))
            return threads.coerceIn(1, maxThreads)
        }
    }
}

