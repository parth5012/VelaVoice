package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.settings.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google Drive synchronization for transcription pairs.
 *
 * Architecture:
 * - Reads credentials from BuildConfig (injected from .env at build time)
 *   or from SharedPreferences (overridable via settings UI).
 * - Uses OAuth2 refresh token flow to obtain short-lived access tokens.
 * - Calls Google Drive REST API v3 directly (no Play Services dependency).
 *
 * All network operations happen on the calling thread — call from a background thread.
 */
object GoogleDriveSync {

    private const val TAG = "GoogleDriveSync"
    private const val DRIVE_FOLDER_NAME = "Vela Voice Transcriptions"

    // ──────────────────────────────────────────────
    // Credentials resolution
    // ──────────────────────────────────────────────

    /**
     * Resolve credentials: prefer SharedPreferences (user-set), fall back to BuildConfig (.env).
     */
    private fun getClientId(prefs: SharedPreferences): String {
        return prefs.getString(Settings.PREF_DRIVE_CLIENT_ID, BuildConfig.GOOGLE_DRIVE_CLIENT_ID) ?: BuildConfig.GOOGLE_DRIVE_CLIENT_ID
    }

    private fun getClientSecret(prefs: SharedPreferences): String {
        return prefs.getString(Settings.PREF_DRIVE_CLIENT_SECRET, BuildConfig.GOOGLE_DRIVE_CLIENT_SECRET) ?: BuildConfig.GOOGLE_DRIVE_CLIENT_SECRET
    }

    private fun getRefreshToken(prefs: SharedPreferences): String {
        return prefs.getString(Settings.PREF_DRIVE_REFRESH_TOKEN, BuildConfig.GOOGLE_DRIVE_REFRESH_TOKEN) ?: BuildConfig.GOOGLE_DRIVE_REFRESH_TOKEN
    }

    /**
     * Check if credentials are configured (either via .env or SharedPreferences).
     */
    fun isConfigured(prefs: SharedPreferences): Boolean {
        return getClientId(prefs).isNotBlank() &&
                getClientSecret(prefs).isNotBlank() &&
                getRefreshToken(prefs).isNotBlank()
    }

    // ──────────────────────────────────────────────
    // Sync: upload all unsynced transcriptions to Drive
    // ──────────────────────────────────────────────

    /**
     * Sync result data class.
     *
     * [skipped] counts files the scanner listed but the pre-upload re-verify
     * refused (sensitive verdict, or raced into an unverifiable state between
     * scan and upload — fail closed, counts only, never marked synced).
     */
    data class SyncResult(
        val uploaded: Int,
        val failed: Int,
        val total: Int,
        val message: String,
        val skipped: Int = 0
    )

    /**
     * Upload all unsynced transcription files to Google Drive.
     * Must be called from a background thread.
     *
     * Belt-and-braces (map #130 ticket #135): the scanner is scoped to the
     * shared dir and already filters verdicts, and each file is re-verified
     * via [TranscriptionStorage.isUploadable] immediately pre-upload so a file
     * that raced into a sensitive/unverifiable state between scan and upload
     * is skipped fail-closed — never uploaded, never marked synced. Skips are
     * logged as counts only.
     */
    fun syncToDrive(context: Context, prefs: SharedPreferences): SyncResult {
        val clientId = getClientId(prefs)
        val clientSecret = getClientSecret(prefs)
        val refreshToken = getRefreshToken(prefs)

        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            return SyncResult(0, 0, 0, "Google Drive credentials not configured. Set them in Voice Input Settings or add them to .env.")
        }

        // 1. Exchange refresh token for access token
        val accessToken = getAccessToken(clientId, clientSecret, refreshToken)
        if (accessToken == null) {
            return SyncResult(0, 0, 0, "Failed to obtain Google Drive access token. The refresh token may be expired.")
        }

        // 2. Find or create the Drive folder
        val folderId = getOrCreateFolder(accessToken, DRIVE_FOLDER_NAME)
        if (folderId == null) {
            return SyncResult(0, 0, 0, "Failed to create or find Drive folder.")
        }

        // 3. Upload all unsynced files
        val unsyncedFiles = TranscriptionStorage.getUnsyncedFiles(context)
        if (unsyncedFiles.isEmpty()) {
            return SyncResult(0, 0, 0, "No new transcriptions to sync.")
        }

        var uploaded = 0
        var failed = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        for (file in unsyncedFiles) {
            // Pre-upload re-verify (TOCTOU guard): the verdict is re-read at
            // upload time; anything but an explicit non-sensitive verdict is
            // skipped fail-closed — never uploaded, never marked synced.
            if (!TranscriptionStorage.isUploadable(file)) {
                skipped++
                continue
            }
            try {
                val pair = TranscriptionStorage.readTranscriptionFile(file)
                if (pair != null) {
                    // Upload text transcription
                    val textSuccess = uploadTextTranscription(accessToken, folderId, file, pair)
                    if (textSuccess) {
                        // Upload matching audio file if present
                        if (pair.audioFileName != null) {
                            val audioFile = TranscriptionStorage.getAudioFile(context, file.name)
                            if (audioFile != null) {
                                uploadAudioFile(accessToken, folderId, audioFile)
                            }
                        }
                        TranscriptionStorage.markSynced(file)
                        uploaded++
                    } else {
                        failed++
                        errors.add("Failed to upload ${file.name}")
                    }
                } else {
                    failed++
                    errors.add("Could not read ${file.name}")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to upload ${file.name}", e)
                failed++
                errors.add("${file.name}: ${e.message}")
            }
        }

        val message = buildString {
            append("Synced $uploaded of ${unsyncedFiles.size} transcription(s)")
            if (failed > 0) append(", $failed failed")
            if (skipped > 0) append(", $skipped skipped (sensitive or unverifiable)")
            append(".")
        }
        if (skipped > 0) {
            android.util.Log.w(TAG, "Drive sync skipped $skipped of ${unsyncedFiles.size} files: sensitive or unverifiable verdict")
        }
        return SyncResult(uploaded, failed, unsyncedFiles.size, message, skipped)
    }

    // ──────────────────────────────────────────────
    // OAuth2: refresh token → access token
    // ──────────────────────────────────────────────

    private fun getAccessToken(clientId: String, clientSecret: String, refreshToken: String): String? {
        return try {
            val url = URL("https://oauth2.googleapis.com/token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val body = "grant_type=refresh_token" +
                    "&client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                    "&client_secret=${URLEncoder.encode(clientSecret, "UTF-8")}" +
                    "&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}"

            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val response = reader.readText()
                reader.close()
                val json = JSONObject(response)
                json.optString("access_token", null)
            } else {
                val errorReader = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                val errorMsg = errorReader?.readText() ?: "HTTP $responseCode"
                errorReader?.close()
                android.util.Log.e(TAG, "Token refresh failed: $errorMsg")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Token refresh error", e)
            null
        }
    }

    // ──────────────────────────────────────────────
    // Drive API: folder management
    // ──────────────────────────────────────────────

    private fun getOrCreateFolder(accessToken: String, folderName: String): String? {
        // Search for existing folder
        val query = URLEncoder.encode(
            "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false",
            "UTF-8"
        )
        return try {
            val searchUrl = URL("https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id,name)")
            val searchConn = searchUrl.openConnection() as HttpURLConnection
            searchConn.requestMethod = "GET"
            searchConn.setRequestProperty("Authorization", "Bearer $accessToken")
            searchConn.connectTimeout = 10000
            searchConn.readTimeout = 10000

            val searchCode = searchConn.responseCode
            if (searchCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(searchConn.inputStream, Charsets.UTF_8))
                val response = reader.readText()
                reader.close()
                searchConn.disconnect()
                val json = JSONObject(response)
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    files.getJSONObject(0).optString("id", null)
                } else {
                    createDriveFolder(accessToken, folderName)
                }
            } else {
                searchConn.disconnect()
                null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Folder search error", e)
            null
        }
    }

    private fun createDriveFolder(accessToken: String, folderName: String): String? {
        return try {
            val url = URL("https://www.googleapis.com/drive/v3/files")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val metadata = JSONObject().apply {
                put("name", folderName)
                put("mimeType", "application/vnd.google-apps.folder")
            }

            conn.outputStream.use { os ->
                os.write(metadata.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val response = reader.readText()
                reader.close()
                val json = JSONObject(response)
                json.optString("id", null)
            } else {
                val errorReader = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                android.util.Log.e(TAG, "Folder creation failed: HTTP $responseCode")
                errorReader?.close()
                null
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Folder creation error", e)
            null
        }
    }

    // ──────────────────────────────────────────────
    // Drive API: file upload (multipart)
    // ──────────────────────────────────────────────

    // ──────────────────────────────────────────────
    // Drive API: text transcription upload (multipart)
    // ──────────────────────────────────────────────

    private fun uploadTextTranscription(
        accessToken: String,
        folderId: String,
        file: File,
        pair: TranscriptionPair
    ): Boolean {
        return try {
            val boundary = "Boundary_${System.currentTimeMillis()}"
            val lineFeed = "\r\n"

            val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            conn.connectTimeout = 30000
            conn.readTimeout = 30000

            // Build JSON metadata
            val metadata = JSONObject().apply {
                put("name", file.name.removeSuffix(".json") + ".txt")
                put("parents", JSONArray().apply { put(folderId) })
                put("description", "Vela Voice transcription pair: raw ↔ cleaned")
            }

            // Build plain-text content
            val content = buildString {
                appendLine("=== Vela Voice Transcription ===")
                appendLine("Date: ${pair.createdAt}")
                appendLine("Duration: ${pair.durationMs}ms")
                appendLine()
                appendLine("--- RAW TRANSCRIPT ---")
                appendLine(pair.raw)
                appendLine()
                appendLine("--- CLEANED TRANSCRIPT ---")
                appendLine(pair.cleaned)
                if (pair.audioFileName != null) {
                    appendLine()
                    appendLine("Audio: ${pair.audioFileName}")
                }
                appendLine()
                appendLine("=== End ===")
            }

            conn.outputStream.use { os ->
                val writer = OutputStreamWriter(os, Charsets.UTF_8)

                // First part: JSON metadata
                writer.append("--$boundary").append(lineFeed)
                writer.append("Content-Type: application/json; charset=UTF-8").append(lineFeed)
                writer.append(lineFeed)
                writer.append(metadata.toString()).append(lineFeed)
                writer.flush()

                // Second part: text content
                writer.append("--$boundary").append(lineFeed)
                writer.append("Content-Type: text/plain; charset=UTF-8").append(lineFeed)
                writer.append(lineFeed)
                writer.append(content).append(lineFeed)
                writer.flush()

                // Close boundary
                writer.append("--$boundary--").append(lineFeed)
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                android.util.Log.d(TAG, "Uploaded text: ${file.name}")
                true
            } else {
                val errorReader = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                val errorMsg = errorReader?.readText() ?: "HTTP $responseCode"
                errorReader?.close()
                android.util.Log.e(TAG, "Text upload failed for ${file.name}: $errorMsg")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Text upload error for ${file.name}", e)
            false
        }
    }

    // ──────────────────────────────────────────────
    // Drive API: audio file upload (binary)
    // ──────────────────────────────────────────────

    private fun uploadAudioFile(
        accessToken: String,
        folderId: String,
        audioFile: File
    ): Boolean {
        return try {
            val boundary = "Boundary_${System.currentTimeMillis()}"
            val lineFeed = "\r\n"
            val audioBytes = audioFile.readBytes()

            val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            conn.connectTimeout = 30000
            conn.readTimeout = 30000

            // Metadata
            val metadata = JSONObject().apply {
                put("name", audioFile.name)
                put("parents", JSONArray().apply { put(folderId) })
                put("description", "Vela Voice audio recording (16-bit 16kHz mono WAV)")
            }

            conn.outputStream.use { os ->
                val writer = OutputStreamWriter(os, Charsets.UTF_8)

                // First part: JSON metadata
                writer.append("--$boundary").append(lineFeed)
                writer.append("Content-Type: application/json; charset=UTF-8").append(lineFeed)
                writer.append(lineFeed)
                writer.append(metadata.toString()).append(lineFeed)
                writer.flush()

                // Second part: binary audio
                writer.append("--$boundary").append(lineFeed)
                writer.append("Content-Type: audio/wav").append(lineFeed)
                writer.append("Content-Transfer-Encoding: binary").append(lineFeed)
                writer.append(lineFeed)
                writer.flush()
                os.write(audioBytes)
                os.flush()
                writer.append(lineFeed).flush()

                // Close boundary
                writer.append("--$boundary--").append(lineFeed)
                writer.flush()
            }

            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                android.util.Log.d(TAG, "Uploaded audio: ${audioFile.name}")
                true
            } else {
                val errorReader = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                val errorMsg = errorReader?.readText() ?: "HTTP $responseCode"
                errorReader?.close()
                android.util.Log.e(TAG, "Audio upload failed for ${audioFile.name}: $errorMsg")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Audio upload error for ${audioFile.name}", e)
            false
        }
    }
}
