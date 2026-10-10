package com.velavoice.sdk.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
class EncryptedAudioCacheManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        cacheDir = tempFolder.newFolder("audio_cache")
    }

    @Test
    fun `save and decrypt round trips with same instance`() {
        val manager = EncryptedAudioCacheManager(cacheDir)
        val pcmData = "PCM_AUDIO_DATA_FOR_TESTING".toByteArray(StandardCharsets.UTF_8)
        val audioId = "test-audio-123"

        val file = manager.saveEncryptedAudio(audioId, pcmData)
        assertTrue(file.exists())
        assertEquals("$audioId.pcm.enc", file.name)

        val decrypted = manager.decryptAudio(audioId)
        assertNotNull(decrypted)
        assertArrayEquals(pcmData, decrypted)
    }

    @Test
    fun `save then process restart decrypt round trips across separate instances`() {
        val pcmData = "PERSISTENT_AUDIO_ACROSS_PROCESS_RESTARTS".toByteArray(StandardCharsets.UTF_8)
        val audioId = "session_456_rec"

        // Instance 1 saves the audio (process 1)
        val manager1 = EncryptedAudioCacheManager(cacheDir)
        val savedFile = manager1.saveEncryptedAudio(audioId, pcmData)
        assertTrue(savedFile.exists())

        // Instance 2 simulates process restart / death (process 2)
        val manager2 = EncryptedAudioCacheManager(cacheDir)
        val decrypted = manager2.decryptAudio(audioId)

        assertNotNull("Decrypted data must not be null after process restart", decrypted)
        assertArrayEquals("Decrypted data must match original PCM bytes", pcmData, decrypted)
    }

    @Test
    fun `audioId with path traversal is rejected`() {
        val manager = EncryptedAudioCacheManager(cacheDir)
        val pcmData = "MALICIOUS_PAYLOAD".toByteArray(StandardCharsets.UTF_8)

        val traversalIds = listOf(
            "../escaped",
            "../../shared_prefs/config",
            "dir/audio1",
            "dir\\audio2",
            "/absolute_id",
            "..",
            "."
        )

        for (badId in traversalIds) {
            assertThrows("saveEncryptedAudio must reject '$badId'", IllegalArgumentException::class.java) {
                manager.saveEncryptedAudio(badId, pcmData)
            }
            assertThrows("decryptAudio must reject '$badId'", IllegalArgumentException::class.java) {
                manager.decryptAudio(badId)
            }
        }
    }

    @Test
    fun `audioId with invalid characters is rejected`() {
        val manager = EncryptedAudioCacheManager(cacheDir)
        val pcmData = "TEST".toByteArray(StandardCharsets.UTF_8)

        val invalidIds = listOf(
            "audio id with spaces",
            "audio@123",
            "audio!record",
            "audio#1",
            "audio\$1",
            "audio*1",
            ""
        )

        for (badId in invalidIds) {
            assertThrows("Must reject invalid characters in '$badId'", IllegalArgumentException::class.java) {
                manager.saveEncryptedAudio(badId, pcmData)
            }
            assertThrows("Must reject invalid characters in '$badId'", IllegalArgumentException::class.java) {
                manager.decryptAudio(badId)
            }
        }
    }

    @Test
    fun `valid audioId formats are accepted`() {
        val manager = EncryptedAudioCacheManager(cacheDir)
        val pcmData = "TEST".toByteArray(StandardCharsets.UTF_8)

        val validIds = listOf(
            "simple",
            "audio-123",
            "audio_record_456",
            "UPPERCASE-lower_0123"
        )

        for (validId in validIds) {
            val file = manager.saveEncryptedAudio(validId, pcmData)
            assertTrue(file.exists())
            val decrypted = manager.decryptAudio(validId)
            assertArrayEquals(pcmData, decrypted)
        }
    }

    @Test
    fun `decryptAudio returns null for non-existent audioId`() {
        val manager = EncryptedAudioCacheManager(cacheDir)
        assertNull(manager.decryptAudio("non_existent_id"))
    }

    @Test
    fun `corrupted encrypted file returns null instead of throwing`() {
        val manager = EncryptedAudioCacheManager(cacheDir)
        val audioId = "corrupt-test"
        val file = File(cacheDir, "$audioId.pcm.enc")
        // Write random junk smaller than 12 bytes
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(manager.decryptAudio(audioId))

        // Write junk with fake IV but corrupted ciphertext
        val junkIvAndCipher = ByteArray(24) { 0x55.toByte() }
        file.writeBytes(junkIvAndCipher)
        assertNull("AEADBadTagException or cipher failure must return null", manager.decryptAudio(audioId))
    }

    @Test
    fun `decryptAudio with mismatched key returns null without throwing AEADBadTagException`() {
        val key1 = SecretKeySpec(ByteArray(32) { 1.toByte() }, "AES")
        val key2 = SecretKeySpec(ByteArray(32) { 2.toByte() }, "AES")

        val manager1 = EncryptedAudioCacheManager(cacheDir, customSecretKey = key1)
        val manager2 = EncryptedAudioCacheManager(cacheDir, customSecretKey = key2)

        val audioId = "mismatched-key-test"
        val pcmData = "SECRET_AUDIO".toByteArray(StandardCharsets.UTF_8)

        manager1.saveEncryptedAudio(audioId, pcmData)
        val decrypted = manager2.decryptAudio(audioId)

        assertNull("Different key must fail authentication tag and safely return null", decrypted)
    }

    @Test
    fun `purgeExpiredCache evicts files exceeding size limit and preserves key file`() {
        val manager = EncryptedAudioCacheManager(cacheDir)
        val payload1 = ByteArray(1024 * 1024) { 0x41 } // 1MB
        val payload2 = ByteArray(1024 * 1024) { 0x42 } // 1MB

        val file1 = manager.saveEncryptedAudio("audio_old", payload1)
        file1.setLastModified(System.currentTimeMillis() - 10000)

        val file2 = manager.saveEncryptedAudio("audio_new", payload2)

        // Purge with 1.5MB limit: file1 (older) should be deleted, file2 kept
        manager.purgeExpiredCache(maxAgeDays = 7, maxSizeBytes = 1024 * 1024 + 100)

        assertFalse("Oldest file should be evicted when cap is exceeded", file1.exists())
        assertTrue("Newest file should remain", file2.exists())
    }

    @Test
    fun `purgeExpiredCache removes files older than max age`() {
        val manager = EncryptedAudioCacheManager(cacheDir)
        val payload = "EXPIRED".toByteArray(StandardCharsets.UTF_8)

        val file = manager.saveEncryptedAudio("expired_audio", payload)
        // Set last modified to 8 days ago
        file.setLastModified(System.currentTimeMillis() - (8L * 24 * 60 * 60 * 1000L))

        manager.purgeExpiredCache(maxAgeDays = 7)
        assertFalse("Expired file (>7 days) must be deleted", file.exists())
    }
}
