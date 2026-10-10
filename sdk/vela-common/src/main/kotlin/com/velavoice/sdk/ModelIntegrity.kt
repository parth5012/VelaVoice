package com.velavoice.sdk

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Shared model integrity utilities: SHA-256 verification and path containment.
 *
 * Ticket #95 (map #89): every download and load path must verify model bytes
 * and refuse models outside app-private storage.
 */
object ModelIntegrity {

    /** Maximum model file size (500 MB). Prevents resource exhaustion from malicious servers. */
    const val MAX_MODEL_BYTES: Long = 500L * 1024 * 1024

    /**
     * Known SHA-256 hashes for each model file. Pinned to published upstream values.
     * Keys are bare filenames (e.g. "ggml-tiny.en.bin").
     */
    val EXPECTED_HASHES: Map<String, String> = mapOf(
        "ggml-tiny.en.bin" to "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f",
        "llama-cleaner.onnx" to "3002ec321434a9ac3e6e9b5e05b1e9e6eb751a2b560ecb898538f9cf7c1ae203"
    )

    /**
     * Computes the SHA-256 hex digest of [file].
     * Returns the lowercase hex string.
     *
     * @throws IOException if the file cannot be read.
     */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies that [file] matches [expectedHash] (case-insensitive hex comparison).
     *
     * Returns `false` if the file does not exist or the hash does not match.
     */
    fun verifySha256(file: File, expectedHash: String): Boolean {
        if (!file.exists()) return false
        return try {
            sha256(file).equals(expectedHash, ignoreCase = true)
        } catch (_: IOException) {
            false
        }
    }

    /**
     * Checks that [modelPath] resolves (canonically) to a location inside one of the
     * [allowedRoots]. Prevents path-traversal attacks where a model path like
     * `/data/data/com.velavoice/files/../../other_app/secret` escapes the sandbox.
     *
     * @return `true` if the canonical path starts with at least one allowed root's
     *         canonical path.
     */
    fun isInsideAllowedRoots(modelPath: String, allowedRoots: List<File>): Boolean {
        val canonical = try {
            File(modelPath).canonicalPath
        } catch (_: Exception) {
            return false
        }
        return allowedRoots.any { root ->
            val rootCanonical = try {
                root.canonicalPath
            } catch (_: Exception) {
                return@any false
            }
            // Ensure the canonical path is inside the root (with trailing separator to
            // prevent "/data/data/com.app2" matching root "/data/data/com.app").
            canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)
        }
    }

    /**
     * Validates that a genai_config.json file exists and contains a minimal JSON header.
     * This catches obviously-corrupt model directories before native init.
     */
    fun validateGenaiConfig(modelDir: File): Boolean {
        val configFile = File(modelDir, "genai_config.json")
        if (!configFile.exists() || !configFile.isFile) return false
        return try {
            val header = configFile.readText(Charsets.UTF_8).take(256).trim()
            header.startsWith("{")
        } catch (_: Exception) {
            false
        }
    }
}
