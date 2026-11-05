package com.velavoice.sdk

/**
 * Code-driven definition of a downloadable transcription model.
 *
 * Ticket 801 (map #290). Registry rows are the single source of truth; the
 * native engine kind ([engine]) decides which runtime loads the file(s).
 */
data class ModelDefinition(
    val id: String,
    val displayName: String,
    val engine: EngineKind,
    val url: String,
    val filename: String,
    val expectedSha256: String?,
    val minRamMb: Int,
    val supportsStreaming: Boolean,
    val languages: Set<String>,
    val multilingual: Boolean,
    val downloadSizeHintMb: Int
)
