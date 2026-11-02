package com.velavoice.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.velavoice.sdk.AirgapGate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * React Native native bridge module for Google Drive synchronization.
 *
 * Architecture (Finding F08, map #211 tickets #233/#235/#255):
 * - Backend-proxy mediated Drive sync: zero OAuth material on device.
 * - Enforces fail-closed AirgapGate contract.
 * - Calls authenticated POST /save-recommendation endpoint with static embedded app key.
 */
class GoogleDriveSyncModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val DEFAULT_BACKEND_ENDPOINT = "https://cron.velavoice.internal/save-recommendation"
        const val STATIC_APP_KEY = "vela-static-app-key-bearer"
    }

    private val prefs: SharedPreferences
        get() = reactApplicationContext.getSharedPreferences(
            "com.velavoice.app_preferences", Context.MODE_PRIVATE
        )
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun getName(): String = "GoogleDriveSync"

    // ──────────────────────────────────────────────
    // Preferences: credentials management
    // ──────────────────────────────────────────────

    @ReactMethod
    fun setDriveCredentials(clientId: String, clientSecret: String, refreshToken: String, promise: Promise) {
        // Zero OAuth material on device (F08, ticket #255)
        promise.resolve(true)
    }

    @ReactMethod
    fun hasDriveCredentials(promise: Promise) {
        // Built-in backend proxy is configured
        promise.resolve(true)
    }

    @ReactMethod
    fun clearDriveCredentials(promise: Promise) {
        promise.resolve(true)
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

    @ReactMethod
    fun getTotalCount(promise: Promise) = getTotalTranscriptionCount(promise)

    @ReactMethod
    fun getRecentTranscriptions(limit: Int, promise: Promise) {
        try {
            val context = reactApplicationContext
            val jsonArray = JSONArray()
            val sharedDir = File(context.filesDir, "transcriptions")
            val sharedFiles = if (sharedDir.exists()) {
                sharedDir.listFiles()?.filter { it.isFile && it.extension == "json" } ?: emptyList()
            } else {
                emptyList()
            }
            val merged = (sharedFiles.map { it to false } +
                    TranscriptionStorage.getQuarantinedFiles(context).map { it to true })
                .sortedByDescending { (file, _) -> file.name }
            for ((file, isQuarantined) in merged) {
                if (limit > 0 && jsonArray.length() >= limit) break
                putTranscriptionJson(jsonArray, file, isQuarantined = isQuarantined)
            }
            promise.resolve(jsonArray.toString())
        } catch (e: Exception) {
            promise.reject("FETCH_ERROR", e.message)
        }
    }

    private fun putTranscriptionJson(
        jsonArray: JSONArray,
        file: File,
        isQuarantined: Boolean
    ) {
        val pair = TranscriptionStorage.readTranscriptionFile(file) ?: return
        val jsonObj = JSONObject()
        jsonObj.put("fileName", pair.fileName)
        jsonObj.put("raw", pair.raw)
        jsonObj.put("cleaned", pair.cleaned)
        jsonObj.put("durationMs", pair.durationMs)
        jsonObj.put("createdAt", pair.createdAt)
        jsonObj.put("audioFileName", pair.audioFileName)

        val isSynced = if (isQuarantined) {
            false
        } else {
            File(file.parent, "${file.name}.synced").exists()
        }
        jsonObj.put("isSynced", isSynced)
        jsonObj.put("isQuarantined", isQuarantined)

        jsonArray.put(jsonObj)
    }

    // ──────────────────────────────────────────────
    // Sync: upload all unsynced transcriptions to backend proxy
    //
    // Belt-and-braces (map #130 ticket #135): the scanner is scoped to the
    // shared dir and already filters verdicts, and each file is re-verified
    // via TranscriptionStorage.isUploadable immediately pre-upload so a file
    // that raced into a sensitive/unverifiable state between scan and upload
    // is skipped fail-closed — never uploaded, never marked synced. Skips are
    // logged as counts only.
    // ──────────────────────────────────────────────

    @ReactMethod
    fun syncToDrive(promise: Promise) {
        Thread({
            try {
                // Airgap gate (tickets #233, #255): Fail closed if airgap is active
                if (AirgapGate.isAirgapped(reactApplicationContext)) {
                    mainHandler.post { promise.reject("AIRGAP_ON", AirgapGate.MESSAGE) }
                    return@Thread
                }

                val endpoint = prefs.getString("drive_backend_endpoint", DEFAULT_BACKEND_ENDPOINT)
                    ?: DEFAULT_BACKEND_ENDPOINT
                val appKey = prefs.getString("drive_app_key", STATIC_APP_KEY)
                    ?: STATIC_APP_KEY

                val unsyncedFiles = TranscriptionStorage.getUnsyncedFiles(reactApplicationContext)
                if (unsyncedFiles.isEmpty()) {
                    mainHandler.post { promise.resolve("No new transcriptions to sync.") }
                    return@Thread
                }

                var uploaded = 0
                var failed = 0
                var skipped = 0
                val results = JSONArray()

                for (file in unsyncedFiles) {
                    if (!TranscriptionStorage.isUploadable(file)) {
                        skipped++
                        continue
                    }
                    try {
                        val pair = TranscriptionStorage.readTranscriptionFile(file)
                        if (pair != null) {
                            val audioFile = if (pair.audioFileName != null) {
                                TranscriptionStorage.getAudioFile(reactApplicationContext, file.name)
                            } else null
                            val audioBytes = audioFile?.takeIf { it.exists() }?.readBytes()

                            val success = uploadToBackendProxy(endpoint, appKey, file, pair, audioBytes)
                            if (success) {
                                TranscriptionStorage.markSynced(file)
                                uploaded++
                                results.put(JSONObject().apply {
                                    put("fileName", file.name)
                                    put("status", "uploaded")
                                })
                            } else {
                                failed++
                                results.put(JSONObject().apply {
                                    put("fileName", file.name)
                                    put("status", "failed")
                                    put("error", "Backend proxy upload failed")
                                })
                            }
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
                    put("skipped", skipped)
                    put("total", unsyncedFiles.size)
                    put("results", results)
                }
                if (skipped > 0) {
                    android.util.Log.w("GoogleDriveSync", "Drive sync skipped $skipped of ${unsyncedFiles.size} files: sensitive or unverifiable verdict")
                }

                mainHandler.post { promise.resolve(summary.toString()) }
            } catch (e: Exception) {
                android.util.Log.e("GoogleDriveSync", "Sync failed", e)
                mainHandler.post { promise.reject("SYNC_ERROR", e.message) }
            }
        }).start()
    }

    private fun uploadToBackendProxy(
        endpointUrl: String,
        bearerToken: String,
        file: File,
        pair: TranscriptionPair,
        audioBytes: ByteArray?
    ): Boolean {
        return try {
            val url = URL(endpointUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val payload = JSONObject().apply {
                put("fileName", file.name)
                put("createdAt", pair.createdAt)
                put("durationMs", pair.durationMs)
                put("raw", pair.raw)
                put("cleaned", pair.cleaned)
                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    put("audioBase64", android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP))
                }
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            if (responseCode in 200..299) {
                android.util.Log.d("GoogleDriveSync", "Uploaded transcription to backend proxy: ${file.name}")
                true
            } else {
                val errorReader = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                val errorMsg = errorReader?.readText() ?: "HTTP $responseCode"
                errorReader?.close()
                android.util.Log.e("GoogleDriveSync", "Backend proxy upload failed: $errorMsg")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("GoogleDriveSync", "Text upload error for ${file.name}", e)
            false
        }
    }
}
