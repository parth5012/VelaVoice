package com.velavoice.sdk

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Wayfinder map #209 ticket #226 (F31 MEDIUM):
 * (a) release() used to only clear the buffer — a transcribe() already queued
 * on the executor still ran after release() and fired callback (stale
 * onFinal/onError into a torn-down pipeline). Callbacks must be discarded
 * post-release, and a released provider must be restartable via start().
 * (b) every HTTP 400 was misreported as "Invalid Gemini API key" — a
 * malformed request is NOT an auth failure, so 400 and 403 need distinct
 * messages.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiPostReleaseGuardTest {

    /** Executor that defers tasks to the test so release() can win the race. */
    private class DeferredExecutor : java.util.concurrent.AbstractExecutorService() {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        /** Snapshot BEFORE clearing — toList on the cleared deque returns nothing. */
        fun drain(): List<Runnable> = synchronized(this) { val snapshot = tasks.toList(); tasks.clear(); snapshot }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown(): Boolean = false
        override fun isTerminated(): Boolean = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private class RecordingCallback : StreamingTranscriptionCallback {
        var finals = mutableListOf<String>()
        var commits = mutableListOf<RevisionMarker>()
        var errors = mutableListOf<VelaException>()
        override fun onRevisionMarker(marker: RevisionMarker) { commits.add(marker) }
        override fun onFinal(text: String) { finals.add(text) }
        override fun onError(error: VelaException) { errors.add(error) }
        override fun onAmplitude(normalized: Float) {}
    }

    private fun mockClient(responseCode: Int, body: String = ""): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message("Error")
                    .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            })
            .build()

    @Test
    fun `no callback delivered after release wins the race`() {
        val deferred = DeferredExecutor()
        val provider = GeminiTranscriptionProvider(
            client = mockClient(200, """{"candidates":[]}"""),
            executor = deferred
        )
        val cb = RecordingCallback()
        provider.setCallback(cb)

        provider.start(StreamConfig(apiKey = "test-key"))
        provider.emit(ByteArray(320) { 10 })
        provider.stop() // enqueues the transcribe task; not yet run
        provider.release()

        // Race: release() completed before the executor got to the task.
        deferred.drain().forEach { it.run() }

        assertTrue(
            "callbacks must be discarded after release() (F31/#226): " +
                "finals=${cb.finals} errors=${cb.errors} commits=${cb.commits.size}",
            cb.finals.isEmpty() && cb.errors.isEmpty() && cb.commits.isEmpty()
        )
    }

    @Test
    fun `release then start restarts the provider and callbacks flow again`() {
        val deferred = DeferredExecutor()
        val provider = GeminiTranscriptionProvider(
            client = mockClient(
                200,
                """{"candidates":[{"content":{"parts":[{"text":"again"}],"role":"model"}}]}"""
            ),
            executor = deferred
        )
        val cb = RecordingCallback()
        provider.setCallback(cb)

        provider.start(StreamConfig(apiKey = "test-key"))
        provider.stop()
        provider.release()
        deferred.drain().forEach { it.run() } // discarded

        // Restart: the released guard must be cleared, or the session would
        // be permanently silent after a single release.
        provider.start(StreamConfig(apiKey = "test-key"))
        provider.stop() // enqueued again
        val picked = deferred.drain()
        assertEquals("restart must enqueue one transcribe task", 1, picked.size)
        picked.first().run()
        assertEquals("callbacks flow again after restart", listOf("again"), cb.finals)
    }

    @Test
    fun `release clears a pending stop task even when already stopped`() {
        val deferred = DeferredExecutor()
        val provider = GeminiTranscriptionProvider(
            client = mockClient(200, """{"candidates":[]}"""),
            executor = deferred
        )
        val cb = RecordingCallback()
        provider.setCallback(cb)
        provider.start(StreamConfig(apiKey = "test-key"))
        provider.stop()

        // release() before the task ever ran, then stop() again: idempotent no-op
        provider.release()
        provider.stop()
        deferred.drain().forEach { it.run() }
        assertTrue(
            "post-release callbacks must never fire",
            cb.finals.isEmpty() && cb.errors.isEmpty() && cb.commits.isEmpty()
        )
    }

    @Test
    fun `HTTP 400 is not reported as an auth failure`() {
        val client = mockClient(400, """{"error": {"message": "Invalid character in audio"}}""")
        val provider = GeminiTranscriptionProvider(client = client)

        try {
            provider.transcribe(ByteArray(160), "good-key")
            fail("Expected VelaException.Network on 400")
        } catch (e: VelaException.Network) {
            val msg = e.message.orEmpty()
            assertTrue("400 must be distinct from the auth message", !msg.contains("Invalid Gemini API key"))
            assertTrue(
                "400 must point at the request being malformed: $msg",
                msg.contains("Gemini rejected the request") &&
                    msg.contains("Invalid character in audio")
            )
        }
    }

    @Test
    fun `HTTP 403 keeps the auth failure message`() {
        val client = mockClient(403, """{"error": {"message": "Permission denied"}}""")
        val provider = GeminiTranscriptionProvider(client = client)

        try {
            provider.transcribe(ByteArray(160), "unauthorized-key")
            fail("Expected VelaException.Network on 403")
        } catch (e: VelaException.Network) {
            assertTrue(
                "403 stays the auth diagnosis",
                e.message.orEmpty().contains("Invalid Gemini API key or unauthorized access.")
            )
        }
    }

    @Test
    fun `HTTP 400 with unparseable body still gets a distinct message`() {
        val client = mockClient(400, "not json at all")
        val provider = GeminiTranscriptionProvider(client = client)

        try {
            provider.transcribe(ByteArray(160), "good-key")
            fail("Expected VelaException.Network on 400")
        } catch (e: VelaException.Network) {
            val msg = e.message.orEmpty()
            assertTrue("must be distinct from auth", !msg.contains("Invalid Gemini API key"))
            assertTrue("points at malformed request: $msg", msg.contains("Gemini rejected the request"))
        }
    }
}
