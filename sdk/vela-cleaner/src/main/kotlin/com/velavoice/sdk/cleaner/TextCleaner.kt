package com.velavoice.sdk.cleaner

import ai.onnxruntime.genai.GenAI
import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import android.util.Log
import java.io.File

/**
 * On-device text cleaner. Runs rule-based cleanup first, then (optionally) routes the text
 * through the LLM either for standard cleanup or for Scribe (intent-based rewrite).
 *
 * The [clean] overload with [contextBefore]/[contextAfter]/[appName]/[inputType]/[overrideStyle]
 * follows Ticket 003's API: the IME service supplies surrounding editor text and app metadata,
 * while this class owns prompt formatting and style routing.
 */
class TextCleaner(private val config: CleanerConfig) {
    private var isLlmInitialized = false
    private var model: Model? = null
    private var tokenizer: Tokenizer? = null

    init {
        if (config.useLlm && config.llmModelPath != null) {
            initLlm(config.llmModelPath)
        }
    }

    /**
     * Initializes the on-device LLM via ONNX Runtime GenAI (Java API).
     *
     * [modelPath] points at the model directory (containing genai_config.json + onnx model
     * files) or a single .onnx file. Telemetry bundled with the GenAI AAR is disabled first
     * for privacy. Any failure (missing file, native load error, invalid model) leaves
     * [isLlmInitialized] false so [clean] falls back to rule-based cleanup.
     */
    private fun initLlm(modelPath: String): Boolean {
        if (!File(modelPath).exists()) {
            Log.e("TextCleaner", "LLM model file not found at: $modelPath")
            return false
        }
        return try {
            // Loads onnxruntime + onnxruntime-genai + onnxruntime-genai-jni native libraries
            // (setTelemetry triggers GenAI.init() internally). Disable Microsoft telemetry.
            GenAI.setTelemetry(false)
            val m = Model(modelPath)
            model = m
            tokenizer = Tokenizer(m)
            isLlmInitialized = true
            Log.d("TextCleaner", "Initialized on-device LLM Cleaner model: $modelPath")
            true
        } catch (e: Throwable) {
            Log.e("TextCleaner", "Failed to initialize on-device LLM: ${e.message}")
            closeLlm()
            false
        }
    }

    private fun closeLlm() {
        runCatching { tokenizer?.close() }
        runCatching { model?.close() }
        tokenizer = null
        model = null
        isLlmInitialized = false
    }

    fun clean(text: String): String = clean(
        text,
        contextBefore = null,
        contextAfter = null,
        appName = null,
        inputType = null,
        overrideStyle = null,
        privacySensitive = false
    )

    /**
     * Clean and optionally Scribe-rewrite [text]. Context and metadata are passed from the IME.
     *
     * When [config.scribeEnabled] is set, builds a Scribe prompt from the raw input, requested
     * [overrideStyle] (falling back to [config.defaultScribeStyle]), surrounding editor context,
     * app metadata, and runs it through the LLM. Otherwise standard cleanup is applied.
     *
     * [privacySensitive] (Ticket 004) force-disables Scribe and LLM cleanup for password/PII
     * fields: only local rule-based cleanup runs, so sensitive text never leaves the device.
     */
    fun clean(
        text: String,
        contextBefore: String? = null,
        contextAfter: String? = null,
        appName: String? = null,
        inputType: String? = null,
        overrideStyle: String? = null,
        privacySensitive: Boolean = false
    ): String {
        // Step 1: Rule-based pre-processor (Regex) run first
        val regexCleaned = cleanRuleBased(text)

        // Step 2: LLM requested and initialized AND the field is not privacy-sensitive
        if (config.useLlm && isLlmInitialized && !privacySensitive) {
            val prompt = if (config.scribeEnabled) {
                formatScribePrompt(
                    rawInput = regexCleaned,
                    style = overrideStyle ?: config.defaultScribeStyle,
                    contextBefore = contextBefore,
                    contextAfter = contextAfter,
                    appName = appName,
                    inputType = inputType
                )
            } else {
                formatStandardCleanupPrompt(regexCleaned)
            }
            return generate(prompt) ?: regexCleaned
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

    // ──────────────────────────────────────────────
    // Scribe prompt formatting (Ticket 002 template)
    // ──────────────────────────────────────────────

    internal fun formatScribePrompt(
        rawInput: String,
        style: String,
        contextBefore: String?,
        contextAfter: String?,
        appName: String?,
        inputType: String?
    ): String {
        val systemPrompt = config.customSystemPrompt ?: """
            You are Scribe, an on-device keyboard writing assistant.
            Task: Rewrite the user's raw voice input based on the requested style, surrounding context, and app context.
            Only output the rewritten text. Do not include introductory phrases, conversational fillers, or explanations. Keep the original language.
        """.trimIndent()

        val styleInstruction = styleInstruction(style)

        val contextBeforeSafe = contextBefore?.take(256) ?: ""
        val contextAfterSafe = contextAfter?.take(256) ?: ""
        val appSafe = appName ?: "Unknown App"
        val inputSafe = inputType ?: "text"

        return buildString {
            append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
            append(systemPrompt).append('\n')
            append("Style: ").append(styleInstruction).append('\n')
            append("App Name/ID: ").append(appSafe).append('\n')
            append("Input Type: ").append(inputSafe).append('\n')
            if (contextBeforeSafe.isNotEmpty() || contextAfterSafe.isNotEmpty()) {
                append("Preceding Context: ").append(contextBeforeSafe).append('\n')
                append("Following Context: ").append(contextAfterSafe).append('\n')
            }
            append("<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n")
            append("Raw input: ").append(rawInput).append('\n')
            append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n")
        }
    }

    private fun formatStandardCleanupPrompt(text: String): String {
        val systemPrompt = config.customSystemPrompt ?: """
            You are a text cleaning assistant for voice dictation.
            Fix any spelling, grammar, and punctuation mistakes without changing the style or structure.
            Only output the corrected text.
        """.trimIndent()
        return buildString {
            append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
            append(systemPrompt).append('\n')
            append("<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n")
            append("Raw input: ").append(text).append('\n')
            append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n")
        }
    }

    /**
     * Canonical style instructions from Ticket 002. Values match the style enum used by
     * the keyboard settings (scribe_default_style / scribe_app_<package>).
     */
    fun styleInstruction(style: String): String = when (style) {
        "Professional" -> "Rewrite the input to be formal, professional, polite, and grammatically perfect. Retain the core meaning."
        "Casual" -> "Rewrite the input to be casual, friendly, natural, and conversational."
        "Bullet Points" -> "Summarize the input as a clear, concise bullet-point list."
        "Email Draft" -> "Draft a professional email based on the brief notes provided, including a subject line and greeting."
        "Proofread" -> "Fix any spelling, grammar, and punctuation mistakes without changing the style or structure."
        else -> "Rewrite the input to be formal, professional, polite, and grammatically perfect. Retain the core meaning."
    }

    /**
     * Runs [prompt] through the loaded model with greedy decoding and returns the
     * generated text, or null if the model is unavailable / generation fails.
     *
     * Uses the official GenAI Java loop: feed encoded prompt tokens, then iterate the
     * generator (each step runs generateNextToken and yields the last token), decoding
     * each token incrementally through a TokenizerStream to preserve multi-byte text.
     */
    private fun generate(prompt: String): String? {
        val m = model ?: return null
        val t = tokenizer ?: return null
        return try {
            val params = GeneratorParams(m)
            // Greedy decoding for deterministic, reproducible cleanup output.
            params.setSearchOption("do_sample", false)
            val generator = Generator(m, params)
            val stream = t.createStream()
            try {
                generator.appendTokenSequences(t.encode(prompt))
                val sb = StringBuilder()
                for (token in generator) {
                    sb.append(stream.decode(token))
                }
                sb.toString()
            } finally {
                stream.close()
                generator.close()
            }
        } catch (e: Throwable) {
            Log.e("TextCleaner", "LLM generation failed: ${e.message}")
            null
        }
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
