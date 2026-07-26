package com.velavoice.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
        // In real implementation, load ONNX Runtime / MediaPipe LLM Inference JNI
        Log.d("TextCleaner", "Initialized on-device LLM Cleaner model: $modelPath")
        isLlmInitialized = true
        return true
    }

    fun clean(context: Context, text: String, useLlm: Boolean): String {
        // Step 1: Rule-based pre-processor (Regex) run first
        val regexCleaned = cleanRuleBased(context, text)

        // Step 2: If LLM requested and initialized, run advanced cleanup
        if (useLlm && isLlmInitialized) {
            return cleanLlm(regexCleaned)
        }

        return regexCleaned
    }

    fun cleanRuleBased(context: Context, text: String): String {
        if (text.isEmpty()) return ""

        // Apply personal dictionary replacements first
        var cleaned = applyPersonalDictionary(context, text)

        // Regex: remove common filler words case-insensitively
        val fillersRegex = Regex("(?i)\\b(um|ah|like|eh|uh|er|hm|oh)\\b,?\\s*")
        cleaned = cleaned.replace(fillersRegex, "")

        // Remove duplicate spaces and trim
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()

        return cleaned
    }

    private fun applyPersonalDictionary(context: Context, text: String): String {
        var result = text
        val dbFile = findDatabaseFile(context) ?: return result
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor = db.rawQuery(
                "SELECT original_word, replacement FROM personal_dictionary ORDER BY priority DESC, name ASC",
                null
            )
            if (cursor.moveToFirst()) {
                do {
                    val original = cursor.getString(0)
                    val replacement = cursor.getString(1)
                    if (original.isNotEmpty()) {
                        val regex = Regex("(?i)\\b" + Regex.escape(original) + "\\b")
                        result = result.replace(regex, replacement)
                    }
                } while (cursor.moveToNext())
            }
            cursor.close()
        } catch (e: Exception) {
            Log.e("TextCleaner", "Error querying personal dictionary: ${e.message}")
        } finally {
            db?.close()
        }
        return result
    }

    private fun findDatabaseFile(context: Context): File? {
        val paths = listOf(
            context.getDatabasePath("models.db"),
            File(context.filesDir, "SQLite/models.db"),
            File(context.filesDir, "databases/models.db")
        )
        for (path in paths) {
            if (path != null && path.exists()) {
                return path
            }
        }
        return null
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
