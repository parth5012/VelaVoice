package com.velavoice.sdk.audio

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedAudioCacheManager(private val cacheDir: File) {

    private val secretKey: SecretKey by lazy {
        // Generate or retrieve 256-bit AES key
        val keyBytes = ByteArray(32)
        SecureRandom().nextBytes(keyBytes)
        SecretKeySpec(keyBytes, "AES")
    }

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    fun saveEncryptedAudio(audioId: String, pcmData: ByteArray): File {
        purgeExpiredCache()
        val file = File(cacheDir, "$audioId.pcm.enc")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val encryptedBytes = cipher.doFinal(pcmData)

        FileOutputStream(file).use { fos ->
            fos.write(iv) // Write 12-byte IV header
            fos.write(encryptedBytes)
        }

        return file
    }

    fun decryptAudio(audioId: String): ByteArray? {
        val file = File(cacheDir, "$audioId.pcm.enc")
        if (!file.exists()) return null

        val fileBytes = FileInputStream(file).use { fis -> fis.readBytes() }
        if (fileBytes.size < 12) return null

        val iv = fileBytes.copyOfRange(0, 12)
        val encryptedData = fileBytes.copyOfRange(12, fileBytes.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return cipher.doFinal(encryptedData)
    }

    fun purgeExpiredCache(maxAgeDays: Int = 7, maxSizeBytes: Long = 50 * 1024 * 1024) {
        val files = cacheDir.listFiles() ?: return
        val now = System.currentTimeMillis()
        val maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000L

        // Purge expired files (> 7 days)
        files.filter { (now - it.lastModified()) > maxAgeMs }.forEach { it.delete() }

        // Evict LRU files if total size exceeds 50MB
        var currentFiles = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var totalSize = currentFiles.sumOf { it.length() }

        for (file in currentFiles) {
            if (totalSize <= maxSizeBytes) break
            totalSize -= file.length()
            file.delete()
        }
    }
}
