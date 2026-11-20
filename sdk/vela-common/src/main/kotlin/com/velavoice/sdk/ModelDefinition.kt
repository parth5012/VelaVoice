package com.velavoice.sdk

/**
 * One pinned artifact of a multi-file model bundle (e.g. a sherpa-onnx
 * transducer directory). Registry rows carry these so download wiring can be
 * driven by the registry instead of hard-coding per-model URLs in the app.
 */
data class BundleArtifact(
    val name: String,
    val url: String,
    val expectedSha256: String,
    val sizeBytes: Long
)

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
    val downloadSizeHintMb: Int,
    /**
     * Per-model download size cap. Null keeps the global
     * [ModelIntegrity.MAX_MODEL_BYTES] guard. Needed for large but legitimate
     * artifacts such as Whisper large-v3-turbo q5_0 (574 MB).
     */
    val maxDownloadBytes: Long? = null,
    /**
     * Pinned parts for models shipped as a directory rather than a single
     * file. Empty for single-file downloads and for catalog-only placeholders.
     */
    val bundleParts: List<BundleArtifact> = emptyList(),
    /**
     * Human-readable runtime/source note for catalog rows that have no
     * Android-loadable artifact yet. When set, the UI shows this instead of a
     * misleading engine label.
     */
    val catalogNote: String? = null
) {
    /** Effective download cap: the per-model override, or the global default. */
    fun effectiveMaxBytes(): Long = maxDownloadBytes ?: ModelIntegrity.MAX_MODEL_BYTES
}
