package com.velavoice.sdk.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
class SherpaEngineTest {

    @Test
    fun `constructor throws on missing model file before native check`() {
        val config = SherpaConfig("/nonexistent/modeldir")
        assertThrows(IllegalArgumentException::class.java) {
            SherpaEngine(config)
        }
    }

    @Test
    fun `transcribe after free throws IllegalStateException`() {
        val engine = SherpaEngineErrorHarness()
        engine.free()
        assertThrows(IllegalStateException::class.java) {
            engine.transcribe(ByteArray(320))
        }
    }

    @Test
    fun `free is idempotent`() {
        val engine = SherpaEngineErrorHarness()
        engine.free()
        engine.free()
    }

    @Test
    fun `empty audio returns empty string`() {
        val engine = SherpaEngineErrorHarness(
            transcribeError = RuntimeException("should not be called")
        )
        val result = engine.transcribe(ByteArray(0))
        assertEquals("", result)
        engine.close()
    }

    @Test
    fun `constructor throws SecurityException for path outside allowed roots`() {
        val tmpFile = File.createTempFile("sherpa-test-model", ".bin")
        try {
            val allowedRoot = File("/data/data/com.velavoice/files")
            val config = SherpaConfig(
                modelPath = tmpFile.absolutePath,
                allowedModelRoots = listOf(allowedRoot)
            )
            val ex = assertThrows(SecurityException::class.java) {
                SherpaEngine(config)
            }
            assertTrue(ex.message?.contains("outside allowed storage roots") == true)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `constructor throws SecurityException for wrong SHA-256 hash`() {
        val tmpFile = File.createTempFile("sherpa-tampered", ".bin")
        try {
            tmpFile.writeBytes("tampered model content".toByteArray())
            val config = SherpaConfig(
                modelPath = tmpFile.absolutePath,
                allowedModelRoots = listOf(tmpFile.parentFile),
                expectedHash = "0000000000000000000000000000000000000000000000000000000000000000"
            )
            val ex = assertThrows(SecurityException::class.java) {
                SherpaEngine(config)
            }
            assertTrue(ex.message?.contains("SHA-256") == true)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun `directory model path with expectedHash fails fast instead of silently skipping verification`() {
        val tmpDir = java.nio.file.Files.createTempDirectory("sherpa-model-dir").toFile()
        try {
            val config = SherpaConfig(
                modelPath = tmpDir.absolutePath,
                expectedHash = "0".repeat(64)
            )
            val ex = assertThrows(SecurityException::class.java) {
                SherpaEngine(config)
            }
            assertTrue(
                "message should explain the hash cannot be verified for a directory",
                ex.message?.contains("directory") == true
            )
        } finally {
            tmpDir.delete()
        }
    }

    // ── CodeRabbit round-1 findings ─────────────────────────────
    // NOTE: OfflineStream.release-on-failure cannot be exercised headlessly —
    // both OfflineStream and OfflineRecognizer run System.loadLibrary in their
    // class initializers, so no instance (even Unsafe-allocated) exists
    // without the native library. The release fix lives in
    // SherpaEngine.doNativeTranscribe's try/finally, verified by code review
    // and javap (OfflineStream.release() exists). Consistent with the
    // stub-mode design used by SherpaStreamEngine for the same reason.

    @Test
    fun `cancel during transcription returns promptly`() {
        val engine = SherpaEngineHarness()
        val transcribeEntered = CountDownLatch(1)
        val transcribeDone = CountDownLatch(1)
        val transcribeResult = java.util.concurrent.atomic.AtomicReference<String?>(null)

        engine.onTranscribe = {
            transcribeEntered.countDown()
            while (!engine.isCancelled.get()) {
                Thread.sleep(10)
            }
        }

        val thread = Thread {
            try {
                transcribeResult.set(engine.transcribe(ByteArray(320)))
            } finally {
                transcribeDone.countDown()
            }
        }
        thread.start()

        assertTrue("transcribe should enter", transcribeEntered.await(2, TimeUnit.SECONDS))
        val started = System.currentTimeMillis()
        engine.cancel()
        val finished = transcribeDone.await(2, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - started
        assertTrue("should complete promptly", finished)
        assertTrue("cancel too long", elapsed < 2000)
        assertEquals("", transcribeResult.get())
        engine.close()
    }
}

private class SherpaEngineErrorHarness(
    private val transcribeError: RuntimeException? = null,
    private val transcribeReturnsNull: Boolean = false
) : SherpaEngine(stubInit = true) {
    init { setContextPtrForTest(0xDEAD_BEEF) }
    override fun doNativeTranscribe(ptr: Long, floatAudio: FloatArray, language: String, threads: Int): String? {
        if (transcribeError != null) throw transcribeError
        if (transcribeReturnsNull) return null
        return "stub transcription"
    }
    override fun doNativeFree(ptr: Long) {}
    override fun doNativeCancel(ptr: Long) {}
}

private class SherpaEngineHarness(
    customConfig: SherpaConfig = SherpaConfig("/tmp/model.bin")
) : SherpaEngine(config = customConfig, stubInit = true) {
    val isCancelled = AtomicBoolean(false)
    var onTranscribe: (() -> Unit)? = null
    var capturedThreads: Int = 0
    var capturedLanguage: String = ""
    var stubResult: String = "stub transcription"

    init { setContextPtrForTest(0xCAFE_BABE) }

    override fun doNativeCancel(ptr: Long) {
        isCancelled.set(true)
    }
    override fun doNativeTranscribe(ptr: Long, floatAudio: FloatArray, language: String, threads: Int): String? {
        capturedLanguage = language
        capturedThreads = threads
        onTranscribe?.invoke()
        if (isCancelled.get()) return ""
        return stubResult
    }
    override fun doNativeFree(ptr: Long) {}
    fun transcribeWithLanguage(audio: ByteArray, language: String): String {
        return transcribe(audio)
    }
}
