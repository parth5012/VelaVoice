package com.velavoice.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Ticket #95 (map #89): Model integrity — SHA-256 verification and path containment.
 *
 * TDD: these tests were written before the implementation changes and define
 * the expected behaviour for every download and load path.
 */
@RunWith(RobolectricTestRunner::class)
class ModelIntegrityTest {

    // ── SHA-256 verification ─────────────────────────────────────────

    @Test
    fun `sha256 computes correct hash for known content`() {
        val tmp = File.createTempFile("model-integrity-test", ".bin")
        try {
            // SHA-256 of "hello" is 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
            tmp.writeBytes("hello".toByteArray(Charsets.UTF_8))
            val hash = ModelIntegrity.sha256(tmp)
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                hash
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `verifySha256 returns true for matching hash`() {
        val tmp = File.createTempFile("model-integrity-test", ".bin")
        try {
            tmp.writeBytes("hello".toByteArray(Charsets.UTF_8))
            assertTrue(
                ModelIntegrity.verifySha256(
                    tmp,
                    "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
                )
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `verifySha256 returns false for wrong hash`() {
        val tmp = File.createTempFile("model-integrity-test", ".bin")
        try {
            tmp.writeBytes("hello".toByteArray(Charsets.UTF_8))
            assertFalse(
                ModelIntegrity.verifySha256(tmp, "0000000000000000000000000000000000000000000000000000000000000000")
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `verifySha256 returns false for non-existent file`() {
        assertFalse(
            ModelIntegrity.verifySha256(
                File("/nonexistent/file.bin"),
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            )
        )
    }

    @Test
    fun `verifySha256 is case insensitive`() {
        val tmp = File.createTempFile("model-integrity-test", ".bin")
        try {
            tmp.writeBytes("hello".toByteArray(Charsets.UTF_8))
            assertTrue(
                ModelIntegrity.verifySha256(
                    tmp,
                    "2CF24DBA5FB0A30E26E83B2AC5B9E29E1B161E5C1FA7425E73043362938B9824"
                )
            )
        } finally {
            tmp.delete()
        }
    }

    // ── Path containment ─────────────────────────────────────────────

    @Test
    fun `isInsideAllowedRoots accepts path inside allowed root`() {
        val root = File("/data/data/com.velavoice/files")
        assertTrue(
            ModelIntegrity.isInsideAllowedRoots(
                "/data/data/com.velavoice/files/vela_models/model.bin",
                listOf(root)
            )
        )
    }

    @Test
    fun `isInsideAllowedRoots rejects path outside all roots`() {
        val root = File("/data/data/com.velavoice/files")
        assertFalse(
            ModelIntegrity.isInsideAllowedRoots(
                "/sdcard/Models/model.bin",
                listOf(root)
            )
        )
    }

    @Test
    fun `isInsideAllowedRoots blocks traversal with dot-dot`() {
        val root = File("/data/data/com.velavoice/files")
        assertFalse(
            ModelIntegrity.isInsideAllowedRoots(
                "/data/data/com.velavoice/files/../../other_app/secrets.db",
                listOf(root)
            )
        )
    }

    @Test
    fun `isInsideAllowedRoots blocks prefix attack`() {
        // "/data/data/com.velavoice2" should NOT match root "/data/data/com.velavoice"
        val root = File("/data/data/com.velavoice")
        assertFalse(
            ModelIntegrity.isInsideAllowedRoots(
                "/data/data/com.velavoice2/files/model.bin",
                listOf(root)
            )
        )
    }

    @Test
    fun `isInsideAllowedRoots accepts with multiple roots`() {
        val roots = listOf(
            File("/data/data/com.velavoice/files"),
            File("/storage/emulated/0/Android/data/com.velavoice/files")
        )
        assertTrue(
            ModelIntegrity.isInsideAllowedRoots(
                "/storage/emulated/0/Android/data/com.velavoice/files/model.bin",
                roots
            )
        )
    }

    @Test
    fun `isInsideAllowedRoots returns false for empty roots list`() {
        assertFalse(
            ModelIntegrity.isInsideAllowedRoots("/any/path/model.bin", emptyList())
        )
    }

    // ── genai_config.json validation ─────────────────────────────────

    @Test
    fun `validateGenaiConfig returns true for valid config`() {
        val dir = createTempDir("model-dir")
        try {
            File(dir, "genai_config.json").writeText("""{"model": {"type": "phi3v"}}""")
            assertTrue(ModelIntegrity.validateGenaiConfig(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `validateGenaiConfig returns false when file missing`() {
        val dir = createTempDir("model-dir")
        try {
            assertFalse(ModelIntegrity.validateGenaiConfig(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `validateGenaiConfig returns false for non-JSON content`() {
        val dir = createTempDir("model-dir")
        try {
            File(dir, "genai_config.json").writeText("not json at all")
            assertFalse(ModelIntegrity.validateGenaiConfig(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── Expected hashes are pinned ───────────────────────────────────

    @Test
    fun `EXPECTED_HASHES contains whisper and llm models`() {
        assertTrue(ModelIntegrity.EXPECTED_HASHES.containsKey("ggml-tiny.en.bin"))
        assertTrue(ModelIntegrity.EXPECTED_HASHES.containsKey("llama-cleaner.onnx"))
    }

    @Test
    fun `MAX_MODEL_BYTES is 500 MB`() {
        assertEquals(500L * 1024 * 1024, ModelIntegrity.MAX_MODEL_BYTES)
    }

    // ── Tampered download rejection ──────────────────────────────────

    @Test
    fun `verifySha256 rejects tampered file after download`() {
        val tmp = File.createTempFile("tampered-model", ".bin")
        try {
            tmp.writeBytes("tampered content here".toByteArray(Charsets.UTF_8))
            val result = ModelIntegrity.verifySha256(
                tmp,
                "0000000000000000000000000000000000000000000000000000000000000000"
            )
            assertFalse("tampered file must fail verification", result)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `verifySha256 matches for correct content`() {
        val tmp = File.createTempFile("verified-model", ".bin")
        try {
            tmp.writeBytes("test model content".toByteArray(Charsets.UTF_8))
            val expectedHash = ModelIntegrity.sha256(tmp)
            assertTrue(ModelIntegrity.verifySha256(tmp, expectedHash))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `verifySha256 rejects wrong hash`() {
        val tmp = File.createTempFile("bad-model", ".bin")
        try {
            tmp.writeBytes("bad content".toByteArray(Charsets.UTF_8))
            assertFalse(
                ModelIntegrity.verifySha256(tmp, "aaaa")
            )
        } finally {
            tmp.delete()
        }
    }
}
