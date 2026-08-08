package com.velavoice.sdk

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloud streaming transcription using OpenAI Realtime WebSocket API.
 *
 * Connects to wss://api.openai.com/v1/realtime/transcription_sessions
 * and streams PCM audio chunks, receiving transcription.delta
 * and transcription.completed events.
 *
 * Architecture decisions:
 * - Server VAD (server_vad) for automatic segment detection
 * - OkHttp WebSocket client (Android-compatible)
 * - Emits revision markers for partial and committed text
 * - Groq fallback = local whisper (Groq doesn't support streaming)
 */
class CloudStreamingTranscriber : StreamingTranscriber {
    private var callback: StreamingTranscriptionCallback? = null
    private var streamConfig: StreamConfig? = null
    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var committedLength = 0
    private var partialText = ""
    private var lastCommittedText = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    override fun start(config: StreamConfig) {
        if (isRunning.get()) return
        this.streamConfig = config
        committedLength = 0
        partialText = ""
        lastCommittedText = ""

        if (config.apiKey.isBlank()) {
            callback?.onError(VelaError("API key required for cloud streaming"))
            return
        }

        val endpoint = buildEndpoint(config)
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, createWebSocketListener())
        isRunning.set(true)
        Log.d("CloudStreamingTranscriber", "Connecting to $endpoint")
    }

    override fun emit(audioChunk: ByteArray) {
        if (!isRunning.get() || webSocket == null) return

        try {
            // Send audio as base64-encoded PCM
            val base64Audio = android.util.Base64.encodeToString(audioChunk, android.util.Base64.NO_WRAP)
            val audioEvent = JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", base64Audio)
            }
            webSocket?.send(audioEvent.toString())
        } catch (e: Exception) {
            Log.e("CloudStreamingTranscriber", "Failed to send audio chunk", e)
        }
    }

    override fun stop() {
        if (!isRunning.get()) return

        try {
            // Commit the audio buffer
            val commitEvent = JSONObject().apply {
                put("type", "input_audio_buffer.commit")
            }
            webSocket?.send(commitEvent.toString())

            // Send session close after a short delay
            Thread({
                Thread.sleep(2000)
                webSocket?.close(1000, "Session complete")
            }).start()
        } catch (e: Exception) {
            Log.e("CloudStreamingTranscriber", "Error stopping", e)
            webSocket?.close(1000, "Stopped")
        }

        isRunning.set(false)
    }

    override fun setCallback(callback: StreamingTranscriptionCallback) {
        this.callback = callback
    }

    override fun release() {
        isRunning.set(false)
        webSocket?.close(1000, "Released")
        webSocket = null
    }

    private fun buildEndpoint(config: StreamConfig): String {
        val base = config.endpoint.ifBlank { "wss://api.openai.com/v1/realtime/transcription_sessions" }
        val model = config.model.ifBlank { "gpt-live-transcribe" }
        return "$base?model=$model"
    }

    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("CloudStreamingTranscriber", "WebSocket connected")
                // Configure the session
                sendSessionConfig()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleServerMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("CloudStreamingTranscriber", "WebSocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("CloudStreamingTranscriber", "WebSocket closed: $code $reason")
                isRunning.set(false)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("CloudStreamingTranscriber", "WebSocket failure", t)
                isRunning.set(false)
                callback?.onError(VelaError("WebSocket error: ${t.message}"))
            }
        }
    }

    private fun sendSessionConfig() {
        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("input_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", streamConfig?.model ?: "gpt-live-transcribe")
                    put("language", streamConfig?.language ?: "en")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", streamConfig?.vadThreshold ?: 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 500)
                })
            })
        }
        webSocket?.send(sessionConfig.toString())
    }

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")

            when (type) {
                "transcription.delta" -> {
                    val delta = json.optString("delta", "")
                    val itemId = json.optString("item_id", "")
                    if (delta.isNotBlank()) {
                        handleDelta(delta)
                    }
                }
                "transcription.completed" -> {
                    val transcript = json.optString("transcript", "")
                    if (transcript.isNotBlank()) {
                        handleCompleted(transcript)
                    }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.optString("transcript", "")
                    if (transcript.isNotBlank()) {
                        handleCompleted(transcript)
                    }
                }
                "error" -> {
                    val message = json.optJSONObject("error")?.optString("message", "Unknown error")
                        ?: json.optString("message", "Unknown error")
                    callback?.onError(VelaError("Server error: $message"))
                }
                "session.created" -> {
                    Log.d("CloudStreamingTranscriber", "Session created")
                }
            }
        } catch (e: Exception) {
            Log.e("CloudStreamingTranscriber", "Failed to parse message: $text", e)
        }
    }

    private fun handleDelta(delta: String) {
        val startIdx = committedLength
        val endIdx = startIdx + delta.length

        val marker = RevisionMarker(
            type = "partial",
            text = delta,
            range = startIdx until endIdx
        )
        partialText = delta
        callback?.onRevisionMarker(marker)
    }

    private fun handleCompleted(transcript: String) {
        // Commit the current partial
        if (partialText.isNotEmpty()) {
            val commitMarker = RevisionMarker(
                type = "commit",
                text = partialText,
                range = committedLength until (committedLength + partialText.length)
            )
            callback?.onRevisionMarker(commitMarker)
            committedLength += partialText.length
            lastCommittedText = transcript
        }

        // Emit final
        callback?.onFinal(transcript)
    }
}
