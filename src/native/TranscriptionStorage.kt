package com.velavoice.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto-saves transcription (raw, cleaned) text pairs to the app's internal
 * data folder under `transcriptions/` as JSON files.
 *
 * File format:  YYYY-MM-DD_HH-mm-ss_<timestamp-ms>.json
 * Content:      { "raw": "...", "cleaned": "...", "durationMs": N, "createdAt": "..." }
 */
object TranscriptionStorage {

    private const val TRANSCRIPTIONS_DIR = "transcriptions"
    private const val SYNCED_MARKER = ".synced"

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    /**
     * Save a transcription pair to local storage.
     * Returns the saved [File] on success, or null on failure.
     */
    fun save(context: Context, raw: String, cleaned: String, durationMs: Long): File? {
        return try {
            val dir = getTranscriptionsDir(context)
            if (!dir.exists()) dir.mkdirs()

            val now = Date()
            val fileName = "${dateFormat.format(now)}_${now.time}.json"
            val file = File(dir, fileName)

            val json = JSONObject().apply {
                put("raw", raw)
                put("cleaned", cleaned)
                put("durationMs", durationMs)
                put("createdAt", isoFormat.format(now))
            }

            file.writeText(json.toString(2), Charsets.UTF_8)
            file
        } catch (e: Exception) {
            android.util.Log.e("TranscriptionStorage", "Failed to save transcription", e)
            null
        }
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
            TranscriptionPair(
                raw = json.optString("raw", ""),
                cleaned = json.optString("cleaned", ""),
                durationMs = json.optLong("durationMs", 0L),
                createdAt = json.optString("createdAt", ""),
                fileName = file.name
            )
        } catch (e: Exception) {
            android.util.Log.e("TranscriptionStorage", "Failed to read ${file.name}", e)
            null
        }
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
    val fileName: String
)
