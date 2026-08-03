package com.velavoice.sdk.cleaner

/**
 * Immutable configuration for the on-device text cleaner.
 *
 * The Scribe fields ([scribeEnabled], [defaultScribeStyle], [customSystemPrompt]) configure
 * the intent-based rewrite subsystem (Ticket 003). Scribe routes raw voice input through the
 * LLM with a style-specific prompt instead of plain grammar cleanup.
 */
data class CleanerConfig(
    val useLlm: Boolean = false,
    val llmModelPath: String? = null,
    val personalDictionary: PersonalDictionary? = null,
    val customFillers: List<String>? = null,
    val dictionaryKeywords: DictionaryKeywords? = null,
    // Scribe configuration addition (Ticket 003)
    val scribeEnabled: Boolean = false,
    val defaultScribeStyle: String = "Professional",
    val customSystemPrompt: String? = null
)
