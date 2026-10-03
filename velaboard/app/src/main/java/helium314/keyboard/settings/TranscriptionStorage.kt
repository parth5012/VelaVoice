package helium314.keyboard.settings

import kotlin.jvm.JvmStatic
import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Auto-saves transcription (raw, cleaned) text pairs and their audio recordings
 * to the app's internal data folder under `transcriptions/`.
 *
 * Privacy routing (map #130 tickets #134/#135): non-sensitive sessions persist
 * in the shared layout below; sensitive sessions are quarantined under the
 * dedicated sibling dir `transcriptions_quarantine/` (same file layout) which
 * the sync scanner never enters — structural exclusion, not a naming
 * convention. Quarantined sessions are local-only and never uploadable.
 *
 * Local file layout (both dirs):
 *   YYYY-MM-DD_HH-mm-ss_<ts>.json   — text pair (raw + cleaned + verdict)
 *   YYYY-MM-DD_HH-mm-ss_<ts>.wav    — 16-bit 16 kHz mono WAV recording (optional)
 *   YYYY-MM-DD_HH-mm-ss_<ts>.json.synced — sidecar sync marker (shared dir only)
 */
object TranscriptionStorage {

    private const val TRANSCRIPTIONS_DIR = "transcriptions"
    private const val QUARANTINE_DIR = "transcriptions_quarantine"
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
     * [privacySensitive] is REQUIRED (no default — mirror of the #76
     * `TextCleaner.clean` overload removal, map #130 ticket #134): on `true`
     * the session is quarantined under [QUARANTINE_DIR] (local-only, never
     * scanned for upload) and null is returned for the shared dir (fail
     * closed), so no future caller can persist a sensitive transcript in
     * syncable storage by forgetting its own check. Both paths stamp their
     * verdict into the JSON so sync can re-verify pre-upload. Refusal and
     * quarantine events are logged with counts only — never content, names,
     * or paths.
     *
     * @param context  Android context for file paths.
     * @param raw      Raw transcript text.
     * @param cleaned  Cleaned transcript text.
     * @param durationMs Recording duration in milliseconds.
     * @param audioBytes Raw PCM audio data (16-bit, 16kHz, mono), or null to skip audio.
     * @param privacySensitive REQUIRED privacy verdict for this transcript.
     * @return The shared-dir JSON [File] on non-sensitive success, or null on
     *   failure / sensitive quarantine routing.
     */
    @JvmStatic fun save(
        context: Context,
        raw: String,
        cleaned: String,
        durationMs: Long,
        audioBytes: ByteArray? = null,
        privacySensitive: Boolean
    ): File? {
        // Fail-closed privacy gate (map #130 ticket #134): a sensitive verdict must
        // never reach the shared dir, even when a caller skips its own check. The
        // session is quarantined local-only instead (ticket #135). Counts only —
        // never transcript content, file names, or paths (cf. #79 log stripping).
        if (privacySensitive) {
            android.util.Log.w(
                "TranscriptionStorage",
                "Quarantining privacy-sensitive transcription: rawChars=" + raw.length +
                    " cleanedChars=" + cleaned.length +
                    " audioBytes=" + (audioBytes?.size ?: 0)
            )
            saveToQuarantine(context, raw, cleaned, durationMs, audioBytes)
            return null
        }
        return try {
            val dir = getTranscriptionsDir(context)
            if (!dir.exists()) dir.mkdirs()

            val now = Date()
            val baseName = "${dateFormat.format(now)}_${now.time}"

            // Save audio WAV if provided
            if (audioBytes != null) {
                saveWav(File(dir, "$baseName.wav"), audioBytes)
            }

            // Save text pair JSON, stamped with its non-sensitive verdict so the
            // sync scanner can re-verify pre-upload (ticket #135).
            val jsonFile = File(dir, "$baseName.json")
            val json = JSONObject().apply {
                put("raw", raw)
                put("cleaned", cleaned)
                put("durationMs", durationMs)
                put("createdAt", isoFormat.format(now))
                put("privacySensitive", false)
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
     * Persist a sensitive session in the quarantine dir (local-only; the sync
     * scanner never enters it). Same file layout as the shared dir, stamped
     * with the sensitive verdict. Counts-only logging, like the refusal path.
     *
     * @return The quarantine JSON [File] on success, or null on failure.
     */
    private fun saveToQuarantine(
        context: Context,
        raw: String,
        cleaned: String,
        durationMs: Long,
        audioBytes: ByteArray?
    ): File? {
        return try {
            val dir = getQuarantineDir(context)
            if (!dir.exists()) dir.mkdirs()

            val now = Date()
            val baseName = "${dateFormat.format(now)}_${now.time}"

            if (audioBytes != null) {
                saveWav(File(dir, "$baseName.wav"), audioBytes)
            }

            val jsonFile = File(dir, "$baseName.json")
            val json = JSONObject().apply {
                put("raw", raw)
                put("cleaned", cleaned)
                put("durationMs", durationMs)
                put("createdAt", isoFormat.format(now))
                put("privacySensitive", true)
                if (audioBytes != null) {
                    put("audioFileName", "$baseName.wav")
                }
            }

            jsonFile.writeText(json.toString(2), Charsets.UTF_8)
            android.util.Log.w(
                "TranscriptionStorage",
                "Quarantined privacy-sensitive transcription: rawChars=" + raw.length +
                    " cleanedChars=" + cleaned.length +
                    " audioBytes=" + (audioBytes?.size ?: 0)
            )
            jsonFile
        } catch (e: Exception) {
            android.util.Log.e("TranscriptionStorage", "Failed to quarantine transcription", e)
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
            // RIFF header
            fos.write("RIFF".toByteArray(Charsets.US_ASCII))
            fos.write(intToLittleEndian(fileSize))
            fos.write("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt chunk
            fos.write("fmt ".toByteArray(Charsets.US_ASCII))
            fos.write(intToLittleEndian(16))          // chunk size
            fos.write(shortToLittleEndian(1))         // PCM format
            fos.write(shortToLittleEndian(NUM_CHANNELS))
            fos.write(intToLittleEndian(SAMPLE_RATE))
            fos.write(intToLittleEndian(byteRate))
            fos.write(shortToLittleEndian(blockAlign))
            fos.write(shortToLittleEndian(BITS_PER_SAMPLE))

            // data chunk
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
     * Whether a transcription JSON file may leave the device. Re-reads and
     * re-verifies the stamped verdict at call time (TOCTOU guard for the sync
     * path): only an explicitly non-sensitive verdict passes. Sensitive,
     * verdict-less (legacy), or unparseable files fail closed. Single verdict
     * authority shared by the scanner and both sync loops.
     */
    fun isUploadable(file: File): Boolean {
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            !json.optBoolean("privacySensitive", true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns all unsynced transcription files (files without the `.synced` marker).
     *
     * Scoped to the shared dir — the quarantine dir is a sibling the scanner
     * never enters (structural exclusion, ticket #135) — and re-verifies each
     * JSON verdict via [isUploadable], skipping sensitive / unparseable /
     * verdict-less files fail-closed. Skips are logged as counts only, never
     * names, paths, or content.
     */
    fun getUnsyncedFiles(context: Context): List<File> {
        val dir = getTranscriptionsDir(context)
        if (!dir.exists()) return emptyList()
        val candidates = dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" && !File(it.parent, "${it.name}${SYNCED_MARKER}").exists() }
            ?: emptyList()
        if (candidates.isEmpty()) return emptyList()
        val uploadable = candidates.filter { isUploadable(it) }
        val skipped = candidates.size - uploadable.size
        if (skipped > 0) {
            android.util.Log.w(
                "TranscriptionStorage",
                "Skipping " + skipped + " of " + candidates.size +
                    " unsynced transcriptions: sensitive or unverifiable verdict"
            )
        }
        return uploadable
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
     * Delete all transcription files from the shared local storage.
     * Quarantined sessions are NOT touched — use [clearQuarantine] explicitly.
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

    /**
     * Get the matching audio file for a transcription JSON file, if it exists.
     * Shared dir only — quarantined audio is resolved via [getQuarantinedAudioFile].
     */
    fun getAudioFile(context: Context, baseFileName: String): File? {
        val wavFile = File(getTranscriptionsDir(context), baseFileName.replace(".json", ".wav"))
        return if (wavFile.exists()) wavFile else null
    }

    /**
     * List quarantined sessions (local-only; never returned by [getUnsyncedFiles]).
     */
    fun getQuarantinedFiles(context: Context): List<File> {
        val dir = getQuarantineDir(context)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?: emptyList()
    }

    /**
     * Get count of quarantined sessions.
     */
    fun getQuarantinedCount(context: Context): Int {
        return getQuarantinedFiles(context).size
    }

    /**
     * Get the matching quarantined audio file for a quarantine JSON file, if it exists.
     */
    fun getQuarantinedAudioFile(context: Context, baseFileName: String): File? {
        val wavFile = File(getQuarantineDir(context), baseFileName.replace(".json", ".wav"))
        return if (wavFile.exists()) wavFile else null
    }

    /**
     * Delete all quarantined sessions from local storage.
     */
    fun clearQuarantine(context: Context): Boolean {
        return try {
            val dir = getQuarantineDir(context)
            if (dir.exists()) dir.deleteRecursively()
            true
        } catch (e: Exception) {
            android.util.Log.e("TranscriptionStorage", "Failed to clear quarantine", e)
            false
        }
    }

    private fun getTranscriptionsDir(context: Context): File {
        return File(context.filesDir, TRANSCRIPTIONS_DIR)
    }

    private fun getQuarantineDir(context: Context): File {
        return File(context.filesDir, QUARANTINE_DIR)
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
