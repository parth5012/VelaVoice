package com.velavoice.sdk

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ModelDownloadService {
    
    fun verifySha256(file: File, expectedHash: String): Boolean {
        if (!file.exists()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
        return computedHash.equals(expectedHash, ignoreCase = true)
    }

    fun downloadModelWithChecksum(
        urlString: String,
        targetFile: File,
        expectedHash: String,
        onProgress: ((progress: Float) -> Unit)? = null
    ): Boolean {
        targetFile.parentFile?.mkdirs()
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

            val fileLength = connection.contentLength
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(targetFile)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int

            while (inputStream.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    onProgress?.invoke((total.toFloat() / fileLength))
                }
                outputStream.write(data, 0, count)
            }

            outputStream.flush()
            outputStream.close()
            inputStream.close()

            val isHashValid = verifySha256(targetFile, expectedHash)
            if (!isHashValid) {
                targetFile.delete()
                return false
            }
            return true
        } catch (e: Exception) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            return false
        } finally {
            connection?.disconnect()
        }
    }
}
