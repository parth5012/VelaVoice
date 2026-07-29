package com.velavoice.sdk.cleaner

data class CleanerConfig(
    val useLlm: Boolean = false,
    val llmModelPath: String? = null,
    val personalDictionary: PersonalDictionary? = null,
    val customFillers: List<String>? = null,
    val dictionaryKeywords: DictionaryKeywords? = null
)
