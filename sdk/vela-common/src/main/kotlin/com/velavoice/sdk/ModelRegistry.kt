package com.velavoice.sdk

/**
 * Code-driven registry of all supported transcription models.
 *
 * Ticket 801 (map #290): replaces the hard-coded two-model setup. WHISPER rows
 * ship with pinned hashes from `huggingface.co/ggerganov/whisper.cpp`. SHERPA
 * rows are registered by id here; the sherpa engine lands in ticket #802 and
 * these entries must degrade gracefully until then.
 */
object ModelRegistry {

    val ALL: List<ModelDefinition> = listOf(
        // ── WHISPER (whisper.cpp GGML) ──────────────────────────────
        ModelDefinition(
            id = "whisper-tiny-en",
            displayName = "Whisper Tiny (English)",
            engine = EngineKind.WHISPER,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
            filename = "ggml-tiny.en.bin",
            expectedSha256 = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f",
            minRamMb = 512,
            supportsStreaming = false,
            languages = setOf("en"),
            multilingual = false,
            downloadSizeHintMb = 75
        ),
        ModelDefinition(
            id = "whisper-tiny-en-q5_0",
            displayName = "Whisper Tiny Q5_0 (English)",
            engine = EngineKind.WHISPER,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en-q5_0.bin",
            filename = "ggml-tiny.en-q5_0.bin",
            expectedSha256 = null,
            minRamMb = 512,
            supportsStreaming = false,
            languages = setOf("en"),
            multilingual = false,
            downloadSizeHintMb = 31
        ),
        ModelDefinition(
            id = "whisper-base-en",
            displayName = "Whisper Base (English)",
            engine = EngineKind.WHISPER,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
            filename = "ggml-base.en.bin",
            expectedSha256 = null,
            minRamMb = 1024,
            supportsStreaming = false,
            languages = setOf("en"),
            multilingual = false,
            downloadSizeHintMb = 148
        ),
        ModelDefinition(
            id = "whisper-small-en",
            displayName = "Whisper Small (English)",
            engine = EngineKind.WHISPER,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin",
            filename = "ggml-small.en.bin",
            expectedSha256 = null,
            minRamMb = 2048,
            supportsStreaming = false,
            languages = setOf("en"),
            multilingual = false,
            downloadSizeHintMb = 487
        ),
        // ── SHERPA (sherpa-onnx) ─────────────────────────────────
        // Streaming NeMo transducer (ticket 803, map #290): Nemotron-3.5-ASR-
        // Streaming-0.6b is streamable — sherpa-onnx re-exported it to ONNX in
        // v1.13.5 (PR #3732) with streaming NeMo transducer greedy-search fix
        // #3785 (v1.13.5) and per-stream options (PR #3891, v1.13.7+). The
        // vendored AAR classes jar includes the full OnlineRecognizer API.
        ModelDefinition(
            id = "nemotron-streaming-en",
            displayName = "Nemotron 3.5 ASR Streaming 0.6B (English)",
            engine = EngineKind.SHERPA,
            url = "",
            filename = "nemotron-streaming-en",
            expectedSha256 = null,
            minRamMb = 1024,
            supportsStreaming = true,
            languages = setOf("en"),
            multilingual = false,
            downloadSizeHintMb = 0
        ),
        ModelDefinition(
            id = "fluid-speech-mini",
            displayName = "Fluid Speech Mini (English, dictation)",
            engine = EngineKind.SHERPA,
            url = "",
            filename = "fluid-speech-mini",
            expectedSha256 = null,
            minRamMb = 1024,
            supportsStreaming = false,
            languages = setOf("en"),
            multilingual = false,
            downloadSizeHintMb = 0
        ),
        ModelDefinition(
            id = "parakeet-tdt-v3",
            displayName = "Parakeet TDT v3 (25 EU languages)",
            engine = EngineKind.SHERPA,
            url = "",
            filename = "parakeet-tdt-v3",
            expectedSha256 = null,
            minRamMb = 2048,
            supportsStreaming = false,
            languages = emptySet(),
            multilingual = true,
            downloadSizeHintMb = 0
        ),
        ModelDefinition(
            id = "moonshine-tiny",
            displayName = "Moonshine Tiny",
            engine = EngineKind.SHERPA,
            url = "",
            filename = "moonshine-tiny",
            expectedSha256 = null,
            minRamMb = 512,
            supportsStreaming = false,
            languages = setOf("en"),
            multilingual = false,
            downloadSizeHintMb = 27
        )
    )

    fun find(id: String): ModelDefinition? = ALL.firstOrNull { it.id == id }

    fun byEngine(engine: EngineKind): List<ModelDefinition> = ALL.filter { it.id.let { true } && it.engine == engine }

    fun streamingCapable(engine: EngineKind? = null): List<ModelDefinition> =
        ALL.filter { (engine == null || it.engine == engine) && it.supportsStreaming }

    /**
     * Candidate look-up order for native services resolving a completed
     * whisper download: every WHISPER row in registry order. Never hard-code a
     * single id — users may have downloaded any variant (e.g. whisper-base-en).
     */
    fun whisperModelIds(): List<String> = ALL.filter { it.engine == EngineKind.WHISPER }.map { it.id }
}
