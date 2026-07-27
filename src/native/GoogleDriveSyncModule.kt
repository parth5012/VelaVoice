package com.velavoice.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * React Native native bridge module for Google Drive synchronization.
 *
 * Architecture:
 * - Credentials (client ID, secret, refresh token) are stored in SharedPreferences,
 *   written from the React Native side (which reads them from .env).
 * - OAuth2 refresh token flow obtains short-lived access tokens.
 * - Google Drive REST API v3 is called directly (no Play Services dependency).
 */
class GoogleDriveSyncModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private val prefs: SharedPreferences
        get() = reactApplicationContext.getSharedPreferences(
            "com.velavoice.app_preferences", Context.MODE_PRIVATE
        )
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun getName(): String = "GoogleDriveSync"

    // ──────────────────────────────────────────────
    // Preferences: credentials are set from JS side
    // ──────────────────────────────────────────────

    @ReactMethod
    fun setDriveCredentials(clientId: String, clientSecret: String, refreshToken: String, promise: Promise) {
        try {
            prefs.edit()
                .putString("drive_client_id", clientId)
                .putString("drive_client_secret", clientSecret)
                .putString("drive_refresh_token", refreshToken)
                .apply()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("CRED_SAVE_ERROR", e.message)
        }
    }

    @ReactMethod
    fun hasDriveCredentials(promise: Promise) {
        val has = !prefs.getString("drive_client_id", "").isNullOrBlank() &&
                !prefs.getString("drive_client_secret", "").isNullOrBlank() &&
                !prefs.getString("drive_refresh_token", "").isNullOrBlank()
        promise.resolve(has)
    }

    @ReactMethod
    fun clearDriveCredentials(promise: Promise) {
        try {
            prefs.edit()
                .remove("drive_client_id")
                .remove("drive_client_secret")
                .remove("drive_refresh_token")
                .apply()
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("CRED_CLEAR_ERROR", e.message)
        }
    }

    // ──────────────────────────────────────────────
    // Local transcription info
    // ──────────────────────────────────────────────

    @ReactMethod
    fun getUnsyncedCount(promise: Promise) {
        try {
            val count = TranscriptionStorage.getUnsyncedCount(reactApplicationContext)
            promise.resolve(count)
        } catch (e: Exception) {
            promise.reject("COUNT_ERROR", e.message)
        }
    }

    @ReactMethod
    fun getTotalTranscriptionCount(promise: Promise) {
        try {
            val count = TranscriptionStorage.getTranscriptionCount(reactApplicationContext)
            promise.resolve(count)
        } catch (e: Exception) {
            promise.reject("COUNT_ERROR", e.message)
        }
    }

    // ──────────────────────────────────────────────
    // Sync: upload all unsynced transcriptions to Drive
    // ──────────────────────────────────────────────

    @ReactMethod
    fun syncToDrive(promise: Promise) {
        Thread({
            try {
                val clientId = prefs.getString("drive_client_id", "")
                val clientSecret = prefs.getString("drive_client_secret", "")
                val refreshToken = prefs.getString("drive_refresh_token", "")

                if (clientId.isNullOrBlank() || clientSecret.isNullOrBlank() || refreshToken.isNullOrBlank()) {
                    mainHandler.post { promise.reject("NO_CREDENTIALS", "Google Drive credentials not configured. Set them in Settings first.") }
                    return@Thread
                }

                // 1. Exchange refresh token for access token
                val accessToken = getAccessToken(clientId, clientSecret, refreshToken)
                if (accessToken == null) {
                    mainHandler.post { promise.reject("AUTH_FAILED", "Failed to obtain Google Drive access token. The refresh token may be expired.") }
                    return@Thread
                }

                // 2. Find or create the "Vela Voice Transcriptions" folder
                val folderId = getOrCreateFolder(accessToken, "Vela Voice Transcriptions")
                if (folderId == null) {
                    mainHandler.post { promise.reject("FOLDER_ERROR", "Failed to create or find Drive folder.") }
                    return@Thread
                }

                // 3. Upload all unsynced files
                val unsyncedFiles = TranscriptionStorage.getUnsyncedFiles(reactApplicationContext)
                if (unsyncedFiles.isEmpty()) {
                    mainHandler.post { promise.resolve("No new transcriptions to sync.") }
                    return@Thread
                }

                var uploaded = 0
                var failed = 0
                val results = JSONArray()

                for (file in unsyncedFiles) {
                    try {
                        val pair = TranscriptionStorage.readTranscriptionFile(file)
                        if (pair != null) {
                            uploadTranscription(accessToken, folderId, file, pair)
                            TranscriptionStorage.markSynced(file)
                            uploaded++
                            results.put(JSONObject().apply {
                                put("fileName", file.name)
                                put("status", "uploaded")
                            })
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("GoogleDriveSync", "Failed to upload ${file.name}", e)
                        failed++
                        results.put(JSONObject().apply {
                            put("fileName", file.name)
                            put("status", "failed")
                            put("error", e.message)
                        })
                    }
                }

                val summary = JSONObject().apply {
                    put("uploaded", uploaded)
                    put("failed", failed)
                    put("total", unsyncedFiles.size)
                    put("results", results)
                }

                mainHandler.post { promise.resolve(summary.toString()) }
            } catch (e: Exception) {
                android.util.Log.e("GoogleDriveSync", "Sync failed", e)
                mainHandler.post { promise.reject("SYNC_ERROR", e.message) }
            }
        }).start()
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
                    "&client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
                    "&client_secret=${java.net.URLEncoder.encode(clientSecret, "UTF-8")}" +
                    "&refresh_token=${java.net.URLEncoder.encode(refreshToken, "UTF-8")}"

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
                android.util.Log.e("GoogleDriveSync", "Token refresh failed: $errorMsg")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveSync", "Token refresh error", e)
            null
        }
    }

    // ──────────────────────────────────────────────
    // Drive API: folder management
    // ──────────────────────────────────────────────

    /**
     * Find an existing folder by name, or create a new one.
     * Returns the folder ID, or null on failure.
     */
    private fun getOrCreateFolder(accessToken: String, folderName: String): String? {
        // Search for existing folder
        val query = java.net.URLEncoder.encode(
            "name='$folderName' and mimeType='application/vnd.google-apps.folder' and trashed=false",
            "UTF-8"
        )
        val searchUrl = URL("https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id,name)")
        val searchConn = searchUrl.openConnection() as HttpURLConnection
        searchConn.requestMethod = "GET"
        searchConn.setRequestProperty("Authorization", "Bearer $accessToken")
        searchConn.connectTimeout = 10000
        searchConn.readTimeout = 10000

        return try {
            val searchCode = searchConn.responseCode
            if (searchCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(searchConn.inputStream, Charsets.UTF_8))
                val response = reader.readText()
                reader.close()
                val json = JSONObject(response)
                val files = json.optJSONArray("files")
                if (files != null && files.length() > 0) {
                    // Folder already exists
                    files.getJSONObject(0).optString("id", null)
                } else {
                    // Create new folder
                    createDriveFolder(accessToken, folderName)
                }
            } else {
                android.util.Log.e("GoogleDriveSync", "Folder search failed: HTTP $searchCode")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveSync", "Folder search error", e)
            null
        } finally {
            searchConn.disconnect()
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
                android.util.Log.e("GoogleDriveSync", "Folder creation failed: HTTP $responseCode — ${errorReader?.readText()}")
                errorReader?.close()
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveSync", "Folder creation error", e)
            null
        }
    }

    // ──────────────────────────────────────────────
    // Drive API: file upload (multipart)
    // ──────────────────────────────────────────────

    /**
     * Upload a single transcription file to Google Drive using multipart upload.
     */
    private fun uploadTranscription(
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

            // Build plain-text content: raw transcript + cleaned transcript
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
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val response = reader.readText()
                reader.close()
                android.util.Log.d("GoogleDriveSync", "Uploaded ${file.name}: $response")
                true
            } else {
                val errorReader = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                val errorMsg = errorReader?.readText() ?: "HTTP $responseCode"
                errorReader?.close()
                android.util.Log.e("GoogleDriveSync", "Upload failed: $errorMsg")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveSync", "Upload error for ${file.name}", e)
            false
        }
    }
}
