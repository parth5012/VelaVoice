package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import com.velavoice.sdk.AirgapGate
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Backend-proxy synchronization for transcription pairs (Finding F08, map #211 tickets #235/#255).
 *
 * Architecture:
 * - Single-user backend-mediated sync via admin cron-system service.
 * - Zero OAuth material in app: client_secret and refresh_token live server-side.
 * - Calls authenticated POST /save-recommendation endpoint with static embedded app key (Bearer token).
 * - Airgap: Enforces fail-closed AirgapGate contract before network operations.
 *
 * All network operations happen on the calling thread — call from a background thread.
 */
object GoogleDriveSync {

    private const val TAG = "GoogleDriveSync"
    const val DEFAULT_BACKEND_ENDPOINT = "https://cron.velavoice.internal/save-recommendation"
    const val STATIC_APP_KEY = "vela-static-app-key-bearer"

    fun getEndpoint(prefs: SharedPreferences? = null): String {
        return prefs?.getString("drive_backend_endpoint", null) ?: DEFAULT_BACKEND_ENDPOINT
    }

    fun getAppKey(prefs: SharedPreferences? = null): String {
        return prefs?.getString("drive_app_key", null) ?: STATIC_APP_KEY
    }

    /**
     * Check if cloud sync is configured.
     * With backend proxy architecture, default endpoint and embedded app key are provided.
     */
    fun isConfigured(prefs: SharedPreferences): Boolean {
        return getEndpoint(prefs).isNotBlank() && getAppKey(prefs).isNotBlank()
    }

    /**
     * Sync result data class.
     *
     * [skipped] counts files the scanner listed but the pre-upload re-verify
     * refused (sensitive verdict, or raced into an unverifiable state between
     * scan and upload — fail-closed).
     */
    data class SyncResult(
        val uploaded: Int,
        val failed: Int,
        val total: Int,
        val message: String,
        val skipped: Int = 0
    )

    /**
     * Upload all unsynced transcription files to the backend proxy /save-recommendation endpoint.
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
        // Airgap gate (tickets #158, #233, #255): Drive upload is a network feature
        // and must refuse when the kill-switch is on or indeterminate (fail closed).
        if (AirgapGate.isAirgapped(prefs)) {
            return SyncResult(0, 0, 0, "${AirgapGate.MESSAGE} — Google Drive sync refused.")
        }

        val endpoint = getEndpoint(prefs)
        val appKey = getAppKey(prefs)

        if (endpoint.isBlank() || appKey.isBlank()) {
            return SyncResult(0, 0, 0, "Backend proxy credentials not configured.")
        }

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
                    val audioFile = if (pair.audioFileName != null) {
                        TranscriptionStorage.getAudioFile(context, file.name)
                    } else null
                    val audioBytes = audioFile?.takeIf { it.exists() }?.readBytes()

                    // F25 (map #212): the referenced audio MUST ship with the
                    // transcription. A missing (or zero-byte) local audio copy
                    // used to go up as a text-only payload and the record was
                    // still marked synced — its audio was then permanently
                    // lost from every future sync. Fail the record instead:
                    // it stays unsynced (retriable on the next run, same
                    // stable fileName, no text duplicates) until the audio is
                    // present and non-empty again.
                    if (pair.audioFileName != null && audioBytes?.isEmpty() != false) {
                        failed++
                        errors.add("Referenced audio missing for ${file.name} — record not marked synced")
                        continue
                    }

                    val success = uploadToBackendProxy(endpoint, appKey, file, pair, audioBytes)
                    if (success) {
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
                android.util.Log.d(TAG, "Uploaded transcription to backend proxy: ${file.name}")
                true
            } else {
                val errorReader = conn.errorStream?.bufferedReader(Charsets.UTF_8)
                val errorMsg = errorReader?.readText() ?: "HTTP $responseCode"
                errorReader?.close()
                val sanitizedMsg = com.velavoice.sdk.sanitizeApiErrorBody(errorMsg)
                android.util.Log.e(TAG, "Backend proxy upload failed for ${file.name}: $sanitizedMsg")
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Backend proxy upload error for ${file.name}", e)
            false
        }
    }
}
