package com.velavoice.sdk

/**
 * Kind of transcription engine backing a [ModelDefinition].
 *
 * Ticket 801 (map #290): introduced to replace the hard-coded whisper-only
 * local path with a code-driven registry. SHERPA rows are registered here even
 * before the sherpa engine module (#802) ships; callers must degrade gracefully
 * when the engine implementation is absent.
 */
enum class EngineKind {
    WHISPER,
    SHERPA
}
