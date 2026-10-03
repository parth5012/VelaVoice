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

    @Test
    fun `cloud upload needs explicit consent and non-sensitive session - single choke point`() {
        // Locks the #77 contract the services rely on (no duplicated
        // allowsCloudUpload at emit sites): only consent+non-sensitive allows
        // cloud. A buggy `||` or consent-only check would green-light uploads
        // for sensitive sessions.
        assertEquals(false, StreamConfig(privacySensitive = true, consentToUpload = true).allowsCloudUpload())
        assertEquals(false, StreamConfig(privacySensitive = false, consentToUpload = false).allowsCloudUpload())
        assertEquals(false, StreamConfig(privacySensitive = true, consentToUpload = false).allowsCloudUpload())
        assertEquals(true, StreamConfig(privacySensitive = false, consentToUpload = true).allowsCloudUpload())
    }

    @Test
    fun `non-sensitive without consent still refuses cloud - zero cloud emits`() {
        val local = FakeTranscriber()
        val cloud = FakeTranscriber()
        val callback = RecordingCallback()
        val pipeline = StreamingPipeline(local, cloud, StreamConfig())
        pipeline.setCallback(callback)

        // Services start local-only with no consent flag: cloud must stay refused.
        pipeline.start("local", StreamConfig(privacySensitive = false, consentToUpload = false))

        pipeline.dispatchEmit(cloud, ByteArray(64))

        assertTrue("zero cloud emits without explicit consent", cloud.emitted.isEmpty())
        assertEquals("refusal must be reported exactly once", 1, callback.errors.size)
    }
}
