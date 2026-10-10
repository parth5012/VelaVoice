package com.velavoice.sdk.cleaner

import ai.onnxruntime.genai.GenAI
import ai.onnxruntime.genai.Generator
import ai.onnxruntime.genai.GeneratorParams
import ai.onnxruntime.genai.Model
import ai.onnxruntime.genai.Tokenizer
import android.util.Log
import com.velavoice.sdk.ModelIntegrity
import java.io.File

/**
 * On-device text cleaner. Runs rule-based cleanup first, then (optionally) routes the text
 * through the LLM either for standard cleanup or for Scribe (intent-based rewrite).
 *
 * The [clean] overload with [contextBefore]/[contextAfter]/[appName]/[inputType]/[overrideStyle]
 * follows Ticket 003's API: the IME service supplies surrounding editor text and app metadata,
 * while this class owns prompt formatting and style routing.
 *
 * Ticket #95 (map #89): model load path hardened.
 * - Canonical-path containment check against [CleanerConfig.allowedModelRoots].
 * - genai_config.json presence/sanity checked before native init.
 */
open class TextCleaner(@Volatile internal var config: CleanerConfig) : AutoCloseable {
    private var isLlmInitialized = false
    private var model: Model? = null
    private var tokenizer: Tokenizer? = null
    @Volatile private var isClosed = false

    init {
        val modelPath = config.llmModelPath
        if (config.useLlm && modelPath != null) {
            initLlm(modelPath)
        }
    }

    /**
     * Initializes the on-device LLM via ONNX Runtime GenAI (Java API).
     *
     * [modelPath] points at the model directory (containing genai_config.json + onnx model
     * files) or a single .onnx file. Telemetry bundled with the GenAI AAR is disabled first
     * for privacy. Any failure (missing file, native load error, invalid model) leaves
     * [isLlmInitialized] false so [clean] falls back to rule-based cleanup.
     *
     * Ticket #95 (map #89): path containment and genai_config.json sanity.
     */
    private fun initLlm(modelPath: String): Boolean {
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            Log.e("TextCleaner", "LLM model file not found at: $modelPath")
            return false
        }
        // Ticket #95: containment check — refuse models outside app-private storage
        if (config.allowedModelRoots.isNotEmpty()) {
            if (!ModelIntegrity.isInsideAllowedRoots(modelPath, config.allowedModelRoots)) {
                Log.e("TextCleaner",
                    "LLM model path '$modelPath' is outside allowed storage roots. " +
                    "Only app-private storage (filesDir / getExternalFilesDir) is accepted."
                )
                return false
            }
        }
        // Ticket #95: genai_config.json sanity check for model directories
        if (modelFile.isDirectory) {
            if (!ModelIntegrity.validateGenaiConfig(modelFile)) {
                Log.e("TextCleaner",
                    "LLM model directory '$modelPath' missing or invalid genai_config.json"
                )
                return false
            }
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

    /**
     * Closes the on-device LLM session and tokenizer, releasing native memory.
     * Safe to call multiple times.
     */
    open fun release() {
        close()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        val path = config.llmModelPath
        if (!path.isNullOrBlank()) {
            modelCache.remove(path, this)
        }
        closeLlm()
    }

    private fun closeLlm() {
        runCatching { tokenizer?.close() }
        runCatching { model?.close() }
        tokenizer = null
        model = null
        isLlmInitialized = false
    }

    fun isClosed(): Boolean = isClosed

    /**
     * Clean and optionally Scribe-rewrite [text]. Context and metadata are passed from the IME.
     *
     * When [config.scribeEnabled] is set, builds a Scribe prompt from the raw input, requested
     * [overrideStyle] (falling back to [config.defaultScribeStyle]), surrounding editor context,
     * app metadata, and runs it through the LLM. Otherwise standard cleanup is applied.
     *
     * [privacySensitive] (Ticket 004 / map #72) force-disables Scribe and LLM cleanup for
     * password/PII fields: only local rule-based cleanup runs, so sensitive text never leaves
     * the device. It is a REQUIRED parameter on purpose — there is no privacy-off default
     * overload, so every call site must classify the text explicitly (ticket #76).
     */
    fun clean(
        text: String,
        contextBefore: String? = null,
        contextAfter: String? = null,
        appName: String? = null,
        inputType: String? = null,
        overrideStyle: String? = null,
        privacySensitive: Boolean
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

    internal fun compileCustomPrompt(
        promptTemplate: String,
        contextBefore: String?,
        contextAfter: String?,
        appName: String?,
        inputType: String?
    ): String {
        val app = if (!appName.isNullOrBlank()) appName else "general"
        val field = if (!inputType.isNullOrBlank()) inputType else "text"
        val rawContext = ((contextBefore ?: "") + " " + (contextAfter ?: "")).trim()
        val surrounding = if (rawContext.length > 1000) rawContext.takeLast(1000) else rawContext

        val placeholders = mapOf(
            "app_name" to app,
            "target_field_type" to field,
            "surrounding_text" to surrounding
        )
        var compiled = promptTemplate
        for ((key, value) in placeholders) {
            compiled = compiled
                .replace("{{$key}}", value)
                .replace("{$key}", value)
        }
        return compiled
    }

    /** Wraps system/user blocks in the Llama-3 chat framing shared by all prompts. */
    private fun wrapLlamaPrompt(systemBlock: String, userBlock: String): String = buildString {
        append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n")
        append(systemBlock)
        append("<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n")
        append(userBlock)
        append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n")
    }

    internal fun formatScribePrompt(
        rawInput: String,
        style: String,
        contextBefore: String?,
        contextAfter: String?,
        appName: String?,
        inputType: String?
    ): String {
        val basePrompt = config.customSystemPrompt ?: """
            You are Scribe, an on-device keyboard writing assistant.
            Task: Rewrite user's raw voice input based on the requested style, surrounding context, and app context.
            Only output the rewritten text. Do not include introductory phrases, conversational fillers, or explanations. Keep the original language.
        """.trimIndent()

        val compiledSystemPrompt = compileCustomPrompt(basePrompt, contextBefore, contextAfter, appName, inputType)
        val styleInstruction = styleInstruction(style)

        val contextBeforeSafe = contextBefore?.take(256) ?: ""
        val contextAfterSafe = contextAfter?.take(256) ?: ""
        val appSafe = appName ?: "Unknown App"
        val inputSafe = inputType ?: "text"

        val systemBlock = buildString {
            append(compiledSystemPrompt).append('\n')
            append("Style: ").append(styleInstruction).append('\n')
            append("App Name/ID: ").append(appSafe).append('\n')
            append("Input Type: ").append(inputSafe).append('\n')
            if (contextBeforeSafe.isNotEmpty() || contextAfterSafe.isNotEmpty()) {
                append("Preceding Context: ").append(contextBeforeSafe).append('\n')
                append("Following Context: ").append(contextAfterSafe).append('\n')
            }
        }
        return wrapLlamaPrompt(systemBlock, "Raw input: $rawInput\n")
    }

    private fun formatStandardCleanupPrompt(text: String): String {
        val systemPrompt = config.customSystemPrompt ?: """
            You are a text cleaning assistant for voice dictation.
            Fix any spelling, grammar, and punctuation mistakes without changing the style or structure.
            Only output the corrected text.
        """.trimIndent()
        return wrapLlamaPrompt("$systemPrompt\n", "Raw input: $text\n")
    }

    /**
     * Canonical style instructions from Ticket 002. Values match the style enum used by
     * the keyboard settings (scribe_default_style / scribe_app_<package>).
     */
    companion object {
        const val PROFESSIONAL_INSTRUCTION =
            "Rewrite the input to be formal, professional, polite, and grammatically perfect. Retain the core meaning."

        private val modelCache = java.util.concurrent.ConcurrentHashMap<String, TextCleaner>()

        /**
         * Returns a cached [TextCleaner] instance for the given [CleanerConfig.llmModelPath]
         * if LLM cleanup is enabled, or creates and caches one.
         * If the cached instance already exists, updates its [CleanerConfig] so dynamic settings
         * (e.g. personal dictionary, scribe styles, fillers) reflect the latest call without
         * reloading native ONNX model weights.
         * If LLM is not used or model path is null/blank, returns an un-cached [TextCleaner].
         */
        @JvmStatic
        fun getOrCreate(config: CleanerConfig): TextCleaner {
            val path = config.llmModelPath
            if (config.useLlm && !path.isNullOrBlank()) {
                return modelCache.compute(path) { _, existing ->
                    if (existing != null && !existing.isClosed) {
                        existing.config = config
                        existing
                    } else {
                        TextCleaner(config)
                    }
                }!!
            }
            return TextCleaner(config)
        }

        /**
         * Clears all cached [TextCleaner] instances, invoking [close] on each to free
         * native ONNX sessions and tokenizers.
         */
        @JvmStatic
        fun clearCache() {
            val cleaners = ArrayList(modelCache.values)
            modelCache.clear()
            for (cleaner in cleaners) {
                cleaner.close()
            }
        }

        /**
         * Returns the number of currently cached [TextCleaner] instances.
         */
        @JvmStatic
        fun cachedCount(): Int = modelCache.size
    }

    fun styleInstruction(style: String): String = when (style) {
        "Professional" -> PROFESSIONAL_INSTRUCTION
        "Casual" -> "Rewrite the input to be casual, friendly, natural, and conversational."
        "Bullet Points" -> "Summarize the input as a clear, concise bullet-point list."
        "Email Draft" -> "Draft a professional email based on the brief notes provided, including a subject line and greeting."
        "Proofread" -> "Fix any spelling, grammar, and punctuation mistakes without changing the style or structure."
        else -> PROFESSIONAL_INSTRUCTION
    }

    /**
     * Visible-for-testing harness (map #72, ticket #80): forces [isLlmInitialized] so the
     * `!privacySensitive` guard is exercised against an initialized LLM path without
     * needing a real model file.
     */
    internal fun forceLlmInitializedForTesting() {
        isLlmInitialized = true
    }

    /**
     * Runs [prompt] through the loaded model with greedy decoding and returns the
     * generated text, or null if the model is unavailable / generation fails.
     *
     * Uses the official GenAI Java loop: feed encoded prompt tokens, then iterate the
     * generator (each step runs generateNextToken and yields the last token), decoding
     * each token incrementally through a TokenizerStream to preserve multi-byte text.
     */
    internal open fun generate(prompt: String): String? {
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
}
