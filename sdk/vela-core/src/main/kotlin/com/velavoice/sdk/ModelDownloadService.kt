package com.velavoice.sdk

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Model download with integrity verification.
 *
 * Ticket #95 (map #89): hardened download path.
 * - SHA-256 verification via [ModelIntegrity.verifySha256].
 * - [maxBytes] enforced (including `contentLength == -1` / unknown).
 * - Download streams into a `.part` temp file, renamed atomically after checksum.
 * - Streams use `use {}` for guaranteed close; `.part` deleted in `finally`.
 */
object ModelDownloadService {
    
    fun verifySha256(file: File, expectedHash: String): Boolean {
        return ModelIntegrity.verifySha256(file, expectedHash)
    }

    /**
     * Downloads a model from [urlString] into [targetFile] and verifies its SHA-256.
     *
     * @param maxBytes  maximum acceptable download size; rejects responses exceeding this
     *                  (including unknown content-length). Defaults to [ModelIntegrity.MAX_MODEL_BYTES].
     */
    fun downloadModelWithChecksum(
        urlString: String,
        targetFile: File,
        expectedHash: String,
        maxBytes: Long = ModelIntegrity.MAX_MODEL_BYTES,
        onProgress: ((progress: Float) -> Unit)? = null
    ): Boolean {
        targetFile.parentFile?.mkdirs()
        val partFile = File(targetFile.parentFile, targetFile.name + ".part")
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return false
            }

            val contentLength = connection.contentLengthLong
            // Reject if server claims a size > maxBytes, or refuses to declare size
            if (contentLength > maxBytes || contentLength == -1L) {
                return false
            }

            connection.inputStream.use { inputStream ->
                FileOutputStream(partFile).use { outputStream ->
                    val data = ByteArray(8192)
                    var total: Long = 0
                    var count: Int

                    while (inputStream.read(data).also { count = it } != -1) {
                        total += count
                        if (total > maxBytes) {
                            // Server sent more bytes than declared or allowed
                            return false
                        }
                        if (contentLength > 0) {
                            onProgress?.invoke((total.toFloat() / contentLength))
                        }
                        outputStream.write(data, 0, count)
                    }
                    outputStream.flush()
                }
            }

            // Verify checksum on the completed .part file before promoting
            if (!ModelIntegrity.verifySha256(partFile, expectedHash)) {
                return false
            }
            // Atomic rename: .part → target
            if (!partFile.renameTo(targetFile)) {
                // Rename can fail across filesystems; fall back to copy
                partFile.copyTo(targetFile, overwrite = true)
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            connection?.disconnect()
            // Always clean up .part file
            if (partFile.exists()) {
                partFile.delete()
            }
            // If targetFile exists but hash wasn't verified (exception path), remove it
            // (only the successful path above writes targetFile via rename)
        }
    }
}
