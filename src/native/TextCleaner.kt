package com.velavoice.app

import android.util.Log
import java.io.File

class TextCleaner {
    private var modelPath: String? = null
    private var isLlmInitialized = false

    fun initLlm(modelPath: String): Boolean {
        if (!File(modelPath).exists()) {
            Log.e("TextCleaner", "LLM model file not found at: $modelPath")
            return false
        }
        this.modelPath = modelPath
        // In real implementation, this would load ONNX Runtime / MediaPipe LLM Inference JNI
        Log.d("TextCleaner", "Initialized on-device LLM Cleaner with model: $modelPath")
        isLlmInitialized = true
        return true
    }

    fun clean(text: String, useLlm: Boolean): String {
        // Step 1: Rule-based pre-processor (Regex) is always run first
        val regexCleaned = cleanRuleBased(text)
        
        // Step 2: If LLM is requested and initialized, run advanced cleanup
        if (useLlm && isLlmInitialized) {
            return cleanLlm(regexCleaned)
        }
        
        return regexCleaned
    }

    fun cleanRuleBased(text: String): String {
        if (text.isEmpty()) return ""
        
        // Regex to remove common filler words case-insensitively
        val fillersRegex = Regex("(?i)\\b(um|ah|like|eh|uh|er|hm|oh)\\b,?\\s*")
        var cleaned = text.replace(fillersRegex, "")
        
        // Remove duplicate spaces and trim
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        
        return cleaned
    }

    private fun cleanLlm(text: String): String {
        // Simulated offline LLM inference for text correction (grammar, capitalisation, punctuation)
        Log.d("TextCleaner", "Running LLM grammatical/semantic refinement on: '$text'")
        
        if (text.isEmpty()) return ""
        
        // Heuristic correction simulator:
        // 1. Capitalize sentences
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        val formattedSentences = sentences.map { sentence ->
            if (sentence.isNotEmpty()) {
                val firstChar = sentence[0].toUpperCase()
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
        
        // 2. Ensure it ends with punctuation
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
