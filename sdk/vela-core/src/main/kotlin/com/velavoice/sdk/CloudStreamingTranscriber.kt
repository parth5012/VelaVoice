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
    internal var committedLength = 0
    internal var partialText = ""
    private var lastCommittedText = ""
    /**
     * Revision sequence for emitted markers (map #105 ticket #108): every
     * partial/commit emission takes the next id so the committer can tell a
     * superseding partial from a stale duplicate. Reset in [start].
     */
    internal var nextRevisionId = 1L

    /**
     * Reconnect attempt count for the current session (map #105 ticket #111).
     * Incremented on every connection failure, reset in [start] and by
     * [resetReconnectForTest]. When it passes [StreamingBackoff.MAX_ATTEMPTS]
     * the failure surfaces as a user-visible [callback.onError] instead of
     * silence; before that the reconnect is spaced by [StreamingBackoff]
     * (exponential + jitter) so a hard-down network never hot-loops inside
     * the IME process.
     */
    internal var reconnectAttempts = 0
    /**
     * Monotonic session epoch: bumped in [start]/[stop]/[release] so a
     * reconnect timer armed by a dead session can tell it is stale and exit
     * instead of opening a socket for a newer session.
     */
    private var sessionGen = 0
    /** Injectable reconnect scheduler (tests capture delays, prod uses a timer). */
    internal var scheduleReconnectForTest: ((delayMs: Long, task: () -> Unit) -> Unit)? = null
    private var retryScheduler: java.util.concurrent.ScheduledExecutorService? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    override fun start(config: StreamConfig) {
        if (isRunning.get()) return
        sessionGen++
        this.streamConfig = config
        committedLength = 0
        partialText = ""
        lastCommittedText = ""
        nextRevisionId = 1L
        reconnectAttempts = 0

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
        sessionGen++

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
        sessionGen++
        webSocket?.close(1000, "Released")
        webSocket = null
        try {
            retryScheduler?.shutdownNow()
        } catch (e: Exception) {
            Log.e("CloudStreamingTranscriber", "Error shutting down retry scheduler", e)
        }
        retryScheduler = null
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } catch (e: Exception) {
            Log.e("CloudStreamingTranscriber", "Error releasing client", e)
        }
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
                // Normal teardown (stop()/release() already cleared isRunning,
                // or the server closed cleanly) is final; an abnormal close
                // mid-session is a connection failure and retries with backoff.
                if (code != 1000 && isRunning.get()) {
                    handleConnectionFailure(RuntimeException("WebSocket closed: $code"))
                } else {
                    isRunning.set(false)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("CloudStreamingTranscriber", "WebSocket failure", t)
                handleConnectionFailure(t)
            }
        }
    }

    /**
     * Connection-failure path (map #105 ticket #111): back off exponentially
     * with jitter up to [StreamingBackoff.MAX_ATTEMPTS], then surface a
     * user-visible error instead of silence. The session stays alive
     * ([isRunning] true) across retries so buffered audio is not dropped;
     * only exhaustion (or an explicit stop/release) ends it.
     */
    internal fun handleConnectionFailure(t: Throwable) {
        reconnectAttempts++
        if (!StreamingBackoff.shouldRetry(reconnectAttempts)) {
            isRunning.set(false)
            callback?.onError(
                VelaError("Cloud streaming unavailable after ${StreamingBackoff.MAX_ATTEMPTS} attempts: ${t.message}")
            )
            return
        }
        val delayMs = StreamingBackoff.delayForAttempt(reconnectAttempts, kotlin.random.Random.Default)
        val gen = sessionGen
        val reconnect = {
            if (gen == sessionGen && isRunning.get()) {
                reconnectNow()
            }
        }
        val scheduled = scheduleReconnectForTest
        if (scheduled != null) {
            scheduled(delayMs, reconnect)
        } else {
            val scheduler = synchronized(this) {
                var s = retryScheduler
                if (s == null || s.isShutdown) {
                    s = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                    retryScheduler = s
                }
                s!!
            }
            try {
                scheduler.schedule({ reconnect() }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.e("CloudStreamingTranscriber", "Failed to schedule reconnect", e)
            }
        }
    }

    /** Re-open the socket for the current session config (backoff retry). */
    private fun reconnectNow() {
        val config = streamConfig ?: return
        try {
            val endpoint = buildEndpoint(config)
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()
            webSocket = client.newWebSocket(request, createWebSocketListener())
            Log.d("CloudStreamingTranscriber", "Reconnecting (attempt $reconnectAttempts)")
        } catch (e: Exception) {
            Log.e("CloudStreamingTranscriber", "Reconnect failed", e)
            handleConnectionFailure(e)
        }
    }

    /** Test hook: run the failure path without a socket (see [handleConnectionFailure]). */
    internal fun handleConnectionFailureForTest(t: Throwable) = handleConnectionFailure(t)

    /** Test hook: reset the attempt count as [start] does. */
    internal fun resetReconnectForTest() {
        reconnectAttempts = 0
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

    internal fun handleServerMessage(text: String) {
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
            // Never log the frame payload - it may contain dictated text
            // (map #72, ticket #79). Log type/length/exception class only, and
            // surface the failure instead of silently dropping the segment.
            Log.e(
                "CloudStreamingTranscriber",
                "Failed to parse message (len=${text.length}): ${e.javaClass.simpleName}"
            )
            callback?.onError(VelaError("Failed to parse transcription message"))
        }
    }

    internal fun handleDelta(delta: String) {
        val startIdx = committedLength + partialText.length
        val endIdx = startIdx + delta.length

        val marker = RevisionMarker(
            type = "partial",
            text = delta,
            range = startIdx until endIdx,
            revisionId = nextRevisionId++
        )
        partialText += delta
        callback?.onRevisionMarker(marker)
    }

    internal fun handleCompleted(transcript: String) {
        // Commit the accumulated segment, preferring the authoritative transcript
        val segment = transcript.ifBlank { partialText }
        if (segment.isNotEmpty()) {
            val commitMarker = RevisionMarker(
                type = "commit",
                text = segment,
                range = committedLength until (committedLength + segment.length),
                revisionId = nextRevisionId++
            )
            callback?.onRevisionMarker(commitMarker)
            committedLength += segment.length
            lastCommittedText = segment
        }
        partialText = ""

        // Emit final
        callback?.onFinal(transcript)
    }
}
