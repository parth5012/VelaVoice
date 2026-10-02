package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Cloud upload must be an explicit opt-in and must never run for
 * privacy-sensitive sessions — map #72 ticket #77 (audit ref H2).
 *
 * Drives a privacy-sensitive / non-consenting [StreamConfig] through a fake
 * cloud transcriber and asserts zero `emit` calls reach it.
 */
@RunWith(RobolectricTestRunner::class)
class StreamingPipelineConsentTest {

    private val context = RuntimeEnvironment.getApplication()

    private class FakeCloudTranscriber : StreamingTranscriber {
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

    private fun pipelineWith(
        cloud: StreamingTranscriber?,
        local: StreamingTranscriber? = null
    ): StreamingPipeline = StreamingPipeline(local, cloud, StreamConfig())

    @Test
    fun `privacy-sensitive session never starts or feeds the cloud transcriber`() {
        val cloud = FakeCloudTranscriber()
        val callback = RecordingCallback()
        val pipeline = pipelineWith(cloud)
        pipeline.setCallback(callback)

        pipeline.start("cloud", StreamConfig(privacySensitive = true, consentToUpload = true))

        assertNull("cloud transcriber must not start", cloud.startedConfig)
        assertTrue("an error must be surfaced to the caller", callback.errors.isNotEmpty())

        // Defense in depth: even a direct dispatch must refuse to emit.
        pipeline.dispatchEmit(cloud, ByteArray(64))
        assertTrue("zero emit calls for a sensitive session", cloud.emitted.isEmpty())
    }

    @Test
    fun `cloud upload requires explicit consent even for non-sensitive sessions`() {
        val cloud = FakeCloudTranscriber()
        val callback = RecordingCallback()
        val pipeline = pipelineWith(cloud)
        pipeline.setCallback(callback)

        pipeline.start("cloud", StreamConfig(privacySensitive = false, consentToUpload = false))

        assertNull("cloud transcriber must not start without consent", cloud.startedConfig)
        assertTrue("an error must be surfaced to the caller", callback.errors.isNotEmpty())
        pipeline.dispatchEmit(cloud, ByteArray(64))
        assertTrue("zero emit calls without consent", cloud.emitted.isEmpty())
    }

    @Test
    fun `consented non-sensitive session uses the cloud transcriber`() {
        val cloud = FakeCloudTranscriber()
        val pipeline = pipelineWith(cloud)
        pipeline.setCallback(RecordingCallback())

        pipeline.start("cloud", StreamConfig(privacySensitive = false, consentToUpload = true))

        assertNotNull("consented cloud session must start", cloud.startedConfig)
        pipeline.dispatchEmit(cloud, ByteArray(64))
        assertEquals("emit must pass through when allowed", 1, cloud.emitted.size)
    }

    @Test
    fun `privacy-sensitive session with a local model falls back to local`() {
        val cloud = FakeCloudTranscriber()
        val local = FakeCloudTranscriber()
        val pipeline = pipelineWith(cloud, local)
        pipeline.setCallback(RecordingCallback())

        pipeline.start("cloud", StreamConfig(privacySensitive = true, consentToUpload = true))

        assertNotNull("local transcriber must take over", local.startedConfig)
        assertNull("cloud transcriber must not start", cloud.startedConfig)
        pipeline.dispatchEmit(cloud, ByteArray(64))
        assertTrue("zero emit calls on the cloud transcriber", cloud.emitted.isEmpty())
    }

    @Test
    fun `allowsCloudUpload is the single consent predicate`() {
        assertFalse(StreamConfig().allowsCloudUpload())
        assertFalse(StreamConfig(privacySensitive = true).allowsCloudUpload())
        assertFalse(StreamConfig(consentToUpload = false).allowsCloudUpload())
        assertFalse(StreamConfig(privacySensitive = true, consentToUpload = true).allowsCloudUpload())
        assertTrue(StreamConfig(consentToUpload = true).allowsCloudUpload())
    }

    @Test
    fun `builder propagates privacy and consent flags into StreamConfig`() {
        val pipeline = StreamingPipeline.Builder(context)
            .privacySensitive(true)
            .consentToUpload(true)
            .build()
        assertTrue(pipeline.config().privacySensitive)
        assertTrue(pipeline.config().consentToUpload)
    }
}
