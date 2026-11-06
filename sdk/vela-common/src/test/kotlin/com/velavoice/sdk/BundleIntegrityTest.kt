package com.velavoice.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Ticket 804 (map #290): multi-file model bundle integrity.
 *
 * TDD red first — these define the expected contract for
 * [BundleIntegrity.verifyBundle] and the [ModelIntegrity.verifyBundle] alias.
 */
@RunWith(RobolectricTestRunner::class)
class BundleIntegrityTest {

    @Test
    fun `verifyBundle passes for a complete manifest with matching hash`() {
        val dir = newTempDir()
        val encoder = File(dir, "encoder.onnx")
        encoder.writeText("alpha")
        val tokenizer = File(dir, "tokenizer.json")
        tokenizer.writeText("beta")
        // extra unlisted file must NOT fail the verify (manifest is authoritative)
        File(dir, "unlisted.bin").writeText("gamma")

        val ok = ModelIntegrity.verifyBundle(
            dir,
            listOf(
                BundleFileSpec("encoder.onnx", ModelIntegrity.sha256(encoder)),
                BundleFileSpec("tokenizer.json", null)
            )
        )
        assertTrue("complete matching bundle should verify", ok)
        dir.deleteRecursively()
    }

    @Test
    fun `verifyBundle fails closed on hash mismatch`() {
        val dir = newTempDir()
        File(dir, "encoder.onnx").writeText("alpha")
        val bogus = "0".repeat(64)
        val ok = ModelIntegrity.verifyBundle(
            dir,
            mapOf("encoder.onnx" to bogus)
        )
        assertFalse("hash mismatch must fail", ok)
        dir.deleteRecursively()
    }

    @Test
    fun `verifyBundle fails closed on missing file`() {
        val dir = newTempDir()
        File(dir, "encoder.onnx").writeText("alpha")
        val ok = ModelIntegrity.verifyBundle(
            dir,
            mapOf(
                "encoder.onnx" to null,
                "tokenizer.json" to null // never written
            )
        )
        assertFalse("missing bundle file must fail", ok)
        dir.deleteRecursively()
    }

    @Test
    fun `verifyBundle fails closed when listed path is a directory`() {
        val dir = newTempDir()
        val nested = File(dir, "encoder.onnx")
        nested.mkdirs() // same name, but a directory not a file
        val ok = BundleIntegrity.verifyBundle(dir, listOf(BundleFileSpec("encoder.onnx", null)))
        assertFalse("directory masquerading as bundle file must fail", ok)
        dir.deleteRecursively()
    }

    @Test
    fun `verifyBundle fails closed when dir does not exist`() {
        val missing = File(newTempDir(), "no_such_bundle")
        val ok = BundleIntegrity.verifyBundle(missing, listOf(BundleFileSpec("encoder.onnx", null)))
        assertFalse("missing bundle directory must fail", ok)
        missing.parentFile?.deleteRecursively()
    }

    @Test
    fun `verifyBundle enforces total size cap`() {
        val dir = newTempDir()
        val big = File(dir, "encoder.onnx")
        big.writeBytes(ByteArray(64) { 'x'.code.toByte() })
        val ok = BundleIntegrity.verifyBundle(
            dir,
            listOf(BundleFileSpec("encoder.onnx", null)),
            sizeCapBytes = 32L // arbitrary small cap: disk-space DoS guard semantics
        )
        assertFalse("bundle exceeding total size cap must fail", ok)
        dir.deleteRecursively()
    }

    @Test
    fun `verifyBundle with empty manifest and empty dir stays false`() {
        val dir = newTempDir()
        val ok = BundleIntegrity.verifyBundle(dir, emptyList())
        assertFalse(
            "verifying an empty bundle with no manifests is a config error, not success",
            ok
        )
        dir.deleteRecursively()
    }

    private fun newTempDir(): File {
        val f = File.createTempFile("bundle-integrity-test", ".dir")
        f.delete()
        f.mkdirs()
        return f
    }
}
