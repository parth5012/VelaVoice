package com.velavoice.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto-saves transcription (raw, cleaned) text pairs and their audio recordings
 * to the app's internal data folder under `transcriptions/`.
 *
 * Local file layout:
 *   YYYY-MM-DD_HH-mm-ss_<ts>.json   — text pair (raw + cleaned)
 *   YYYY-MM-DD_HH-mm-ss_<ts>.wav    — 16-bit 16 kHz mono WAV recording (optional)
 *   YYYY-MM-DD_HH-mm-ss_<ts>.json.synced — sidecar sync marker
 */
object TranscriptionStorage {

    private const val TRANSCRIPTIONS_DIR = "transcriptions"
    private const val SYNCED_MARKER = ".synced"
    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val NUM_CHANNELS = 1

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    /**
     * Save a transcription pair to local storage, optionally with audio bytes.
     * Audio is saved as a 16-bit 16 kHz mono WAV file alongside the JSON.
     *
     * @param context    Android context for file paths.
     * @param raw        Raw transcript text.
     * @param cleaned    Cleaned transcript text.
     * @param durationMs Recording duration in milliseconds.
     * @param audioBytes Raw PCM audio data (16-bit, 16kHz, mono), or null to skip audio.
     * @return The JSON [File] on success, or null on failure.
     */
    fun save(context: Context, raw: String, cleaned: String, durationMs: Long, audioBytes: ByteArray? = null): File? {
        return try {
            val dir = getTranscriptionsDir(context)
            if (!dir.exists()) dir.mkdirs()

            val now = Date()
            val baseName = "${dateFormat.format(now)}_${now.time}"

            // Save audio WAV if provided
            if (audioBytes != null) {
                saveWav(File(dir, "$baseName.wav"), audioBytes)
            }

            // Save text pair JSON
            val jsonFile = File(dir, "$baseName.json")
            val json = JSONObject().apply {
                put("raw", raw)
                put("cleaned", cleaned)
                put("durationMs", durationMs)
                put("createdAt", isoFormat.format(now))
                if (audioBytes != null) {
                    put("audioFileName", "$baseName.wav")
                }
            }

            jsonFile.writeText(json.toString(2), Charsets.UTF_8)
            jsonFile
        } catch (e: Exception) {
            android.util.Log.e("TranscriptionStorage", "Failed to save transcription", e)
            null
        }
    }

    /**
     * Write raw PCM 16-bit 16kHz mono bytes as a proper WAV file.
     */
    private fun saveWav(file: File, pcmBytes: ByteArray) {
        val byteRate = SAMPLE_RATE * NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val dataSize = pcmBytes.size
        val fileSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            fos.write("RIFF".toByteArray(Charsets.US_ASCII))
            fos.write(intToLittleEndian(fileSize))
            fos.write("WAVE".toByteArray(Charsets.US_ASCII))
            fos.write("fmt ".toByteArray(Charsets.US_ASCII))
            fos.write(intToLittleEndian(16))
            fos.write(shortToLittleEndian(1))
            fos.write(shortToLittleEndian(NUM_CHANNELS))
            fos.write(intToLittleEndian(SAMPLE_RATE))
            fos.write(intToLittleEndian(byteRate))
            fos.write(shortToLittleEndian(blockAlign))
            fos.write(shortToLittleEndian(BITS_PER_SAMPLE))
            fos.write("data".toByteArray(Charsets.US_ASCII))
            fos.write(intToLittleEndian(dataSize))
            fos.write(pcmBytes)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte()
        )
    }

    /**
     * Returns all unsynced transcription files (files without the `.synced` marker).
     */
    fun getUnsyncedFiles(context: Context): List<File> {
        val dir = getTranscriptionsDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" && !File(it.parent, "${it.name}${SYNCED_MARKER}").exists() }
            ?: emptyList()
    }

    /**
     * Mark a transcription file as synced by creating a sidecar marker.
     */
    fun markSynced(file: File): Boolean {
        return try {
            val marker = File(file.parent, "${file.name}${SYNCED_MARKER}")
            marker.createNewFile()
        } catch (e: Exception) {
            android.util.Log.e("TranscriptionStorage", "Failed to mark synced: ${file.name}", e)
            false
        }
    }

    /**
     * Mark multiple files as synced.
     */
    fun markAllSynced(files: List<File>) {
        files.forEach { markSynced(it) }
    }

    /**
     * Get total transcription count (both synced and unsynced).
     */
    fun getTranscriptionCount(context: Context): Int {
        val dir = getTranscriptionsDir(context)
        if (!dir.exists()) return 0
        return dir.listFiles()?.count { it.isFile && it.extension == "json" } ?: 0
    }

    /**
     * Get count of unsynced transcriptions pending upload.
     */
    fun getUnsyncedCount(context: Context): Int {
        return getUnsyncedFiles(context).size
    }

    /**
     * Read the content of a transcription file as a [TranscriptionPair].
     */
    fun readTranscriptionFile(file: File): TranscriptionPair? {
        return try {
            val text = file.readText(Charsets.UTF_8)
            val json = JSONObject(text)
            val audioFileName = json.optString("audioFileName", null)
            TranscriptionPair(
                raw = json.optString("raw", ""),
                cleaned = json.optString("cleaned", ""),
                durationMs = json.optLong("durationMs", 0L),
                createdAt = json.optString("createdAt", ""),
                fileName = file.name,
                audioFileName = if (audioFileName.isNullOrBlank()) null else audioFileName
            )
        } catch (e: Exception) {
            android.util.Log.e("TranscriptionStorage", "Failed to read ${file.name}", e)
            null
        }
    }

    /**
     * Get the matching audio file for a transcription JSON file, if it exists.
     */
    fun getAudioFile(context: Context, baseFileName: String): File? {
        val wavFile = File(getTranscriptionsDir(context), baseFileName.replace(".json", ".wav"))
        return if (wavFile.exists()) wavFile else null
    }

    /**
     * Delete all transcription files from local storage.
     */
    fun clearAll(context: Context): Boolean {
        return try {
            val dir = getTranscriptionsDir(context)
            if (dir.exists()) dir.deleteRecursively()
            true
        } catch (e: Exception) {
            android.util.Log.e("TranscriptionStorage", "Failed to clear transcriptions", e)
            false
        }
    }

    private fun getTranscriptionsDir(context: Context): File {
        return File(context.filesDir, TRANSCRIPTIONS_DIR)
    }
}

/**
 * Data class representing a saved transcription pair.
 */
data class TranscriptionPair(
    val raw: String,
    val cleaned: String,
    val durationMs: Long,
    val createdAt: String,
    val fileName: String,
    val audioFileName: String? = null
)
