package com.velavoice.sdk

/**
 * Builds the cloud [StreamingTranscriber] for a [StreamingPipeline] session.
 *
 * Extracted from `StreamingPipeline.Builder.build` so backend selection
 * (Gemini vs OpenAI-WebSocket vs REST) lives in one testable place.
 * Returns null when no API key is configured (local-only session).
 */
internal object TranscriberFactory {

    fun createCloud(
        apiKey: String,
        model: String,
        endpoint: String,
        allowCustomEndpoint: Boolean
    ): StreamingTranscriber? {
        if (apiKey.isBlank()) return null
        if (model.contains("gemini") || endpoint.contains("googleapis.com")) {
            val liveModel = if (model.isBlank() || model == "gpt-live-transcribe" || model.contains("gemini-3.5")) {
                GeminiTranscriptionProvider.MODEL_TRANSCRIBE_LIVE
            } else {
                model
            }
            return GeminiTranscriptionProvider(
                rawModel = liveModel,
                baseUrl = endpoint.takeIf { it.isNotBlank() },
                allowCustomEndpoint = allowCustomEndpoint
            )
        }
        if (endpoint.startsWith("ws://") || endpoint.startsWith("wss://")) {
            return CloudStreamingTranscriber()
        }
        val resolvedEndpoint = endpoint.takeIf { it.isNotBlank() && !it.startsWith("ws") } ?: when {
            model.contains("whisper-large") || apiKey.startsWith("gsk_") ->
                "https://api.groq.com/openai/v1/audio/transcriptions"
            else ->
                "https://api.openai.com/v1/audio/transcriptions"
        }
        return WhisperRestTranscriptionProvider(
            apiKey = apiKey,
            model = model.ifBlank { "whisper-1" },
            endpoint = resolvedEndpoint
        )
    }
}
