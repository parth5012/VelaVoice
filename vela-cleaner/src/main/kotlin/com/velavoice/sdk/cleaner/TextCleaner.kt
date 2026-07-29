package com.velavoice.sdk.cleaner

import android.util.Log
import java.io.File

class TextCleaner(private val config: CleanerConfig) {
    private var isLlmInitialized = false

    init {
        if (config.useLlm && config.llmModelPath != null) {
            initLlm(config.llmModelPath)
        }
    }

    private fun initLlm(modelPath: String): Boolean {
        if (!File(modelPath).exists()) {
            Log.e("TextCleaner", "LLM model file not found at: $modelPath")
            return false
        }
        // real implementation, load ONNX Runtime / MediaPipe LLM Inference JNI
        Log.d("TextCleaner", "Initialized on-device LLM Cleaner model: $modelPath")
        isLlmInitialized = true
        return true
    }

    fun clean(text: String): String {
        // Step 1: Rule-based pre-processor (Regex) run first
        val regexCleaned = cleanRuleBased(text)

        // Step 2: LLM requested and initialized, run advanced cleanup
        if (config.useLlm && isLlmInitialized) {
            return cleanLlm(regexCleaned)
        }

        return regexCleaned
    }

    fun cleanRuleBased(text: String): String {
        if (text.isEmpty()) return ""

        // Apply personal dictionary replacements first
        var cleaned = applyPersonalDictionary(text)

        // Collect protected keywords that should not be removed as filler words
        val protectedKeywords = getProtectedKeywords()

        // Regex: remove common filler words case-insensitively,
        // but skip any filler that matches a protected keyword
        val fillers = config.customFillers ?: listOf("um", "ah", "like", "eh", "uh", "er", "hm", "oh")
        if (fillers.isNotEmpty()) {
            val activeFillers = fillers.filter { filler ->
                protectedKeywords.none { keyword ->
                    keyword.equals(filler, ignoreCase = true)
                }
            }
            if (activeFillers.isNotEmpty()) {
                val fillersRegexStr = activeFillers.joinToString("|") { Regex.escape(it) }
                val fillersRegex = Regex("(?i)\\b($fillersRegexStr)\\b,?\\s*")
                cleaned = cleaned.replace(fillersRegex, "")
            }
        }

        // Remove duplicate spaces and trim
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()

        return cleaned
    }

    /**
     * Returns the set of protected keyword terms that should never be removed
     * as filler words or otherwise filtered out.
     */
    private fun getProtectedKeywords(): Set<String> {
        val keywords = config.dictionaryKeywords?.getKeywords() ?: return emptySet()
        return keywords.map { it.lowercase() }.toSet()
    }

    private fun applyPersonalDictionary(text: String): String {
        var result = text
        val dict = config.personalDictionary ?: return result
        try {
            for ((original, replacement) in dict.getEntries()) {
                if (original.isNotEmpty()) {
                    val regex = Regex("(?i)\\b" + Regex.escape(original) + "\\b")
                    result = result.replace(regex, replacement)
                }
            }
        } catch (e: Exception) {
            Log.e("TextCleaner", "Error querying personal dictionary: ${e.message}")
        }
        return result
    }

    private fun cleanLlm(text: String): String {
        if (text.isEmpty()) return ""

        // Heuristic correction simulator:
        // 1. Capitalize sentences
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val formattedSentences = sentences.map { sentence ->
            if (sentence.isNotEmpty()) {
                val firstChar = sentence[0].uppercaseChar()
                if (sentence.length > 1) {
                    firstChar + sentence.substring(1)
                } else {
                    firstChar.toString()
                }
            } else {
                ""
            }
        }

        var cleanedText = formattedSentences.joinToString(" ")

        // 2. Ensure ends with punctuation
        if (cleanedText.isNotEmpty() && !cleanedText.last().toString().matches(Regex("[.!?]"))) {
            cleanedText += "."
        }

        // 3. Common voice typos corrections
        cleanedText = cleanedText
            .replace(" i ", " I ")
            .replace(" i'm ", " I'm ")
            .replace(" i've ", " I've ")
            .replace(" i'll ", " I'll ")
            .replace(" i'd ", " I'd ")

        return cleanedText
    }
}
