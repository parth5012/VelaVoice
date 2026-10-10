package com.velavoice.sdk.cleaner

import java.io.File

/**
 * Immutable configuration for the on-device text cleaner.
 *
 * The Scribe fields ([scribeEnabled], [defaultScribeStyle], [customSystemPrompt]) configure
 * the intent-based rewrite subsystem (Ticket 003). Scribe routes raw voice input through the
 * LLM with a style-specific prompt instead of plain grammar cleanup.
 *
 * Ticket #95 (map #89): [allowedModelRoots] restricts which directories the model path
 * may resolve inside (canonical-path containment). Callers should pass `context.filesDir`
 * and/or `context.getExternalFilesDir(null)`.
 */
data class CleanerConfig @JvmOverloads constructor(
    val useLlm: Boolean = false,
    val llmModelPath: String? = null,
    val personalDictionary: PersonalDictionary? = null,
    val customFillers: List<String>? = null,
    val dictionaryKeywords: DictionaryKeywords? = null,
    // Scribe configuration addition (Ticket 003 & 005)
    val scribeEnabled: Boolean = false,
    val defaultScribeStyle: String = "Professional",
    val customSystemPrompt: String? = null,
    val scribeTemperature: Double? = null,
    // Ticket #95: path containment for model load security
    val allowedModelRoots: List<File> = emptyList()
)
