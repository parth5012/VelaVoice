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
        // Whisper large-v3-turbo q5_0: a real whisper.cpp GGML artifact, but
        // 574,041,195 bytes — above the global 500 MB disk-DoS cap. The
        // override is scoped to this row so the default guard stays intact.
        ModelDefinition(
            id = "whisper-turbo-q5_0",
            displayName = "Whisper Large v3 Turbo Q5_0 (multilingual)",
            engine = EngineKind.WHISPER,
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
            filename = "ggml-large-v3-turbo-q5_0.bin",
            expectedSha256 = "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
            maxDownloadBytes = 600L * 1024 * 1024,
            minRamMb = 2048,
            supportsStreaming = false,
            languages = emptySet(),
            multilingual = true,
            downloadSizeHintMb = 547
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
        // Moondream's ternary Parakeet Redux is not Android-loadable as-is;
        // cohearo's sherpa-onnx re-export ships exactly the four filenames
        // SherpaEngine loads. It is a bundle, so `url` stays blank until the
        // bundle download path is wired (catalog-only pass).
        ModelDefinition(
            id = "parakeet-redux",
            displayName = "Parakeet Redux (25 EU languages)",
            engine = EngineKind.SHERPA,
            url = "",
            filename = "parakeet-redux",
            expectedSha256 = null,
            minRamMb = 2048,
            supportsStreaming = false,
            languages = emptySet(),
            multilingual = true,
            downloadSizeHintMb = 397,
            bundleParts = listOf(
                BundleArtifact(
                    name = "encoder.onnx",
                    url = "https://huggingface.co/cohearo/sherpa-onnx-nemo-parakeet-redux/resolve/main/encoder.onnx",
                    expectedSha256 = "e0bad06465fac0b7023231d9cab78bcebb4c295fb3f3c4e45fae6f1162b90cb1",
                    sizeBytes = 343_842_409L
                ),
                BundleArtifact(
                    name = "decoder.onnx",
                    url = "https://huggingface.co/cohearo/sherpa-onnx-nemo-parakeet-redux/resolve/main/decoder.onnx",
                    expectedSha256 = "326f1b9070b4b530ec39fe897fb28b505aa9cea135b7e17ede50926872730677",
                    sizeBytes = 47_249_584L
                ),
                BundleArtifact(
                    name = "joiner.onnx",
                    url = "https://huggingface.co/cohearo/sherpa-onnx-nemo-parakeet-redux/resolve/main/joiner.onnx",
                    expectedSha256 = "0d98c1c102143ea272af9730ac376e7f4591c7ad9839bfff6fa52587ce83d721",
                    sizeBytes = 25_285_351L
                ),
                BundleArtifact(
                    name = "tokens.txt",
                    url = "https://huggingface.co/cohearo/sherpa-onnx-nemo-parakeet-redux/resolve/main/tokens.txt",
                    expectedSha256 = "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
                    sizeBytes = 93_939L
                )
            ),
            catalogNote = "sherpa-onnx bundle — source moondream/parakeet-redux; 4 pinned parts, 397 MB"
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
        ),
        // Catalog-only: MLX ternary weights are Apple-only, and the ONNX
        // re-export carries a 2.43 GB external data file — neither loads in
        // velaboard's WHISPER/SHERPA runtimes. Keep the row visible with its
        // source pinned, but not selectable until an Android artifact exists.
        ModelDefinition(
            id = "phonon-2",
            displayName = "Phonon 2 (Fermion Research)",
            engine = EngineKind.SHERPA,
            url = "",
            filename = "phonon-2",
            expectedSha256 = null,
            minRamMb = 2048,
            supportsStreaming = false,
            languages = setOf("en"),
            multilingual = false,
            downloadSizeHintMb = 0,
            catalogNote = "MLX ternary, Apple-only — source FermionResearch/Phonon-2; no Android runtime"
        ),
        // Catalog-only: ships whistle.cact for the Cactus runtime, which has
        // no EngineKind in this SDK yet.
        ModelDefinition(
            id = "whistle",
            displayName = "Whistle (Cactus Compute)",
            engine = EngineKind.SHERPA,
            url = "",
            filename = "whistle",
            expectedSha256 = null,
            minRamMb = 1024,
            supportsStreaming = false, // no Android runtime — keep out of streamingCapable()
            languages = setOf("en", "de", "fr", "es", "it", "nl", "pl"),
            multilingual = true,
            downloadSizeHintMb = 17,
            catalogNote = "Cactus .cact runtime — source Cactus-Compute/whistle; no Android runtime"
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
