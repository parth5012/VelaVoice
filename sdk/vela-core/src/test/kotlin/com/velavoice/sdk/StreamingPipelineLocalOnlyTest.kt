package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Map #130 ticket #132: the VAS/VIMS streaming wiring shape — always local
 * mode with the session privacy flag — must stay local-only for sensitive
 * sessions with zero cloud emits, inheriting the #77 start-gate,
 * `dispatchEmit` gate and refusal-once semantics from [StreamingPipeline].
 *
 * Companion to [StreamingPipelineConsentTest]: that file locks the consent
 * matrix; this one locks the exact route the two native services take
 * (`start("local", privacySensitive=...)`, emits via `dispatchEmit` only).
 */
@RunWith(RobolectricTestRunner::class)
class StreamingPipelineLocalOnlyTest {

    private class FakeTranscriber : StreamingTranscriber {
        var startedConfig: StreamConfig? = null
        val emitted = mutableListOf<ByteArray>()
        override fun start(config: StreamConfig) { startedConfig = config }
        override fun emit(audioChunk: ByteArray) { emitted += audioChunk }
        override fun stop() {}
        override fun setCallback(callback: StreamingTranscriptionCallback) {}
        override fun release() {}
    }

    private class RecordingCallback : StreamingTranscriptionCallback {
        val errors = mutableListOf<String>()
        override fun onRevisionMarker(marker: RevisionMarker) {}
        override fun onFinal(text: String) {}
        override fun onError(error: VelaException) { errors += (error.message ?: "error") }
        override fun onAmplitude(normalized: Float) {}
    }

    @Test
    fun `sensitive local session starts local and never feeds cloud`() {
        val local = FakeTranscriber()
        val cloud = FakeTranscriber()
        val callback = RecordingCallback()
        val pipeline = StreamingPipeline(local, cloud, StreamConfig())
        pipeline.setCallback(callback)

        // The services' wiring shape: always local mode, session privacy flag.
        pipeline.start("local", StreamConfig(privacySensitive = true))

        assertNotNull("local transcriber must start for a sensitive session", local.startedConfig)
        assertNull("cloud transcriber must not start", cloud.startedConfig)

        pipeline.dispatchEmit(local, ByteArray(64))
        assertEquals("local emits must pass through", 1, local.emitted.size)

        // Defense in depth: even a direct dispatch at the cloud backend refuses.
        pipeline.dispatchEmit(cloud, ByteArray(64))
        assertTrue("zero cloud emits for a sensitive session", cloud.emitted.isEmpty())
    }

    @Test
    fun `cloud refusal is reported exactly once across repeated emits`() {
        val local = FakeTranscriber()
        val cloud = FakeTranscriber()
        val callback = RecordingCallback()
        val pipeline = StreamingPipeline(local, cloud, StreamConfig())
        pipeline.setCallback(callback)

        pipeline.start("local", StreamConfig(privacySensitive = true))

        repeat(3) { pipeline.dispatchEmit(cloud, ByteArray(64)) }

        assertTrue("zero cloud emits", cloud.emitted.isEmpty())
        assertEquals("refusal must be reported exactly once", 1, callback.errors.size)
    }
}
