package com.velavoice.sdk.audio

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedAudioCacheManager @JvmOverloads constructor(
    private val cacheDir: File,
    private val customSecretKey: SecretKey? = null
) {
    companion object {
        private const val TAG = "EncryptedAudioCache"
        private const val KEY_ALIAS = "com.velavoice.sdk.audio.cache"
        private const val KEY_FILE_NAME = ".cache_key"
        private const val IV_SIZE = 12
        private const val TAG_LENGTH_BITS = 128
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private val AUDIO_ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")
    }

    private val lock = Any()

    private val secretKey: SecretKey by lazy {
        getOrCreateSecretKey()
    }

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        if (customSecretKey != null) {
            return customSecretKey
        }
        return try {
            getKeyFromAndroidKeyStore()
        } catch (e: Throwable) {
            Log.w(TAG, "AndroidKeyStore unavailable; using stable fallback key file: ${e.message}")
            getFallbackFileKey()
        }
    }

    private fun getKeyFromAndroidKeyStore(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    private fun getFallbackFileKey(): SecretKey {
        synchronized(lock) {
            val keyFile = File(cacheDir, KEY_FILE_NAME)
            if (keyFile.exists() && keyFile.length() == 32L) {
                val keyBytes = keyFile.readBytes()
                return SecretKeySpec(keyBytes, "AES")
            }
            val keyBytes = ByteArray(32)
            SecureRandom().nextBytes(keyBytes)
            val tempKeyFile = File(cacheDir, "$KEY_FILE_NAME.tmp")
            tempKeyFile.writeBytes(keyBytes)
            if (!tempKeyFile.renameTo(keyFile)) {
                keyFile.writeBytes(keyBytes)
                tempKeyFile.delete()
            }
            return SecretKeySpec(keyBytes, "AES")
        }
    }

    private fun resolveAudioFile(audioId: String): File {
        require(AUDIO_ID_REGEX.matches(audioId)) {
            "Invalid audioId: '$audioId'. Must match ^[a-zA-Z0-9_-]+$"
        }
        val targetFile = File(cacheDir, "$audioId.pcm.enc")
        val canonicalCacheDir = cacheDir.canonicalFile
        val canonicalTarget = targetFile.canonicalFile
        if (!canonicalTarget.path.startsWith(canonicalCacheDir.path + File.separator)) {
            throw SecurityException("Path traversal attempt detected for audioId: $audioId")
        }
        return targetFile
    }

    fun saveEncryptedAudio(audioId: String, pcmData: ByteArray): File = synchronized(lock) {
        val targetFile = resolveAudioFile(audioId)
        val partFile = File(cacheDir, "$audioId.pcm.enc.part")

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val encryptedBytes = cipher.doFinal(pcmData)

        try {
            FileOutputStream(partFile).use { fos ->
                fos.write(iv)
                fos.write(encryptedBytes)
                fos.flush()
            }
            if (!partFile.renameTo(targetFile)) {
                targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
            }
        } catch (e: Exception) {
            partFile.delete()
            throw e
        }

        purgeExpiredCache()
        return targetFile
    }

    fun decryptAudio(audioId: String): ByteArray? = synchronized(lock) {
        val targetFile = try {
            resolveAudioFile(audioId)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            return null
        }

        if (!targetFile.exists()) return null

        return try {
            val fileBytes = FileInputStream(targetFile).use { fis -> fis.readBytes() }
            if (fileBytes.size < IV_SIZE) return null

            val iv = fileBytes.copyOfRange(0, IV_SIZE)
            val encryptedData = fileBytes.copyOfRange(IV_SIZE, fileBytes.size)

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt audio for audioId: $audioId", e)
            null
        }
    }

    fun purgeExpiredCache(maxAgeDays: Int = 7, maxSizeBytes: Long = 50 * 1024 * 1024) = synchronized(lock) {
        val allFiles = cacheDir.listFiles() ?: return
        val audioFiles = allFiles.filter { it.name.endsWith(".pcm.enc") || it.name.endsWith(".part") }
        val now = System.currentTimeMillis()
        val maxAgeMs = maxAgeDays * 24 * 60 * 60 * 1000L

        // Purge expired files (> 7 days) and orphan part files
        for (file in audioFiles) {
            if ((now - file.lastModified()) > maxAgeMs || file.name.endsWith(".part")) {
                file.delete()
            }
        }

        // Evict LRU files if total size exceeds maxSizeBytes
        val currentFiles = cacheDir.listFiles()
            ?.filter { it.name.endsWith(".pcm.enc") }
            ?.sortedBy { it.lastModified() }
            ?: return

        var totalSize = currentFiles.sumOf { it.length() }
        for (file in currentFiles) {
            if (totalSize <= maxSizeBytes) break
            totalSize -= file.length()
            file.delete()
        }
    }
}
