// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.velavoice.sdk.EngineKind
import com.velavoice.sdk.ModelIntegrity
import com.velavoice.sdk.ModelRegistry
import com.velavoice.sdk.ModelSelection
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Result of a download that writes both a user-visible copy and the active working copy. */
data class DownloadResult(
    val path: String?,
    val savedPublic: Boolean
)

/**
 * One file of a multi-file ONNX model bundle (Ticket 804, map #290).
 * [expectedSha256] mirrors ModelIntegrity semantics: when non-null, the bytes
 * are verified after download and before the bundle is marked complete.
 */
data class BundlePart(
    val name: String,
    val url: String,
    val expectedSha256: String?
)

/**
 * Downloads Whisper and LLM Cleaner models from HuggingFace.
 * Tracks download status via SharedPreferences.
 * Models are stored in the app's private files directory to comply
 * with Android 10+ scoped storage restrictions.
 */
object ModelDownloadHelper {

    // Model download URLs (matching ModelManager.ts in the Vela Voice app)
    const val WHISPER_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
    const val LLM_MODEL_URL = "https://huggingface.co/onnx-community/Llama-3.2-1B-Instruct-ONNX/resolve/main/onnx/model.onnx"

    // Pinned SHA-256 hashes (must match ModelManager.ts / ModelIntegrity.EXPECTED_HASHES)
    private const val WHISPER_EXPECTED_HASH = "921e4cf8686fdd993dcd081a5da5b6c365bfde1162e72b08d75ac75289920b1f"
    private const val LLM_EXPECTED_HASH = "3002ec321434a9ac3e6e9b5e05b1e9e6eb751a2b560ecb898538f9cf7c1ae203"

    // Subdirectory under app files dir for downloaded models
    private const val MODELS_DIR = "vela_models"

    // Default filenames
    private const val WHISPER_FILENAME = "ggml-tiny.en.bin"
    private const val LLM_FILENAME = "llama-cleaner.onnx"

    // User-Agent required by HuggingFace to serve downloads
    private const val USER_AGENT = "HeliBoard-vela/4.0"

    /**
     * Returns the app-private directory for model storage.
     */
    private fun getModelsDir(context: Context): File {
        val dir = File(context.filesDir, MODELS_DIR)
        dir.mkdirs()
        return dir
    }

    /**
     * Maps the picker-level model name ("whisper"/"llm") to the canonical model id
     * stored by the main Vela Voice app (com.velavoice.app) in models.db.
     */
    internal fun sharedModelIdFor(model: String): String =
        if (model == "whisper") "whisper-tiny-en" else "cleaner-llama-3b"

    /**
     * Returns the current download status for a model by checking
     * both the legacy shared storage path, the modern app-private path, and the shared Vela Voice app storage.
     */
    fun getStatus(prefs: SharedPreferences, context: Context, model: String): String {
        val resolvedPath = getResolvedDownloadPath(context, model)
        if (resolvedPath != null && resolvedPath.exists()) return STATUS_COMPLETED

        // Also check the legacy preference path for backward compatibility
        val legacyPath = if (model == "whisper") {
            prefs.getString(Settings.PREF_VELA_MODEL_PATH, Defaults.PREF_VELA_MODEL_PATH)
        } else {
            prefs.getString(Settings.PREF_VELA_LLM_MODEL_PATH, Defaults.PREF_VELA_LLM_MODEL_PATH)
        }
        if (legacyPath != null && File(legacyPath).exists()) return STATUS_COMPLETED

        // Check if the model is downloaded in the shared Vela Voice app
        val sharedPath = getSharedModelPath(context, model)
        if (sharedPath != null) return STATUS_COMPLETED

        // Check download flag in preferences
        val statusKey = if (model == "whisper") Settings.PREF_VELA_MODEL_PATH else Settings.PREF_VELA_LLM_MODEL_PATH
        return if (prefs.getBoolean(statusKey + "_downloaded", false))
            STATUS_COMPLETED else STATUS_PENDING
    }

    /**
     * Resolves the path of a model file downloaded by the main Vela Voice application (com.velavoice.app).
     * Works by accessing com.velavoice.app's databases/models.db directly.
     *
     * Ticket 87 (map #81) correction: there is no sharedUserId configured in
     * either app's manifest, so this is strictly a best-effort lookup that
     * silently returns null on normal installs (cross-UID file access fails).
     * Model files only — this path must never be used for credentials, which
     * live solely in this app's VelaApiKeyStore.
     */
    @JvmStatic
    fun getSharedModelPath(context: Context, model: String): String? {
        try {
            val otherContext = context.createPackageContext("com.velavoice.app", Context.CONTEXT_IGNORE_SECURITY)
            val dbName = "models.db"
            val dbPaths = listOf(
                otherContext.getDatabasePath(dbName),
                File(otherContext.filesDir, "SQLite/$dbName"),
                File(otherContext.filesDir, "databases/$dbName")
            )
            val modelId = sharedModelIdFor(model)

            for (dbFile in dbPaths) {
                if (dbFile != null && dbFile.exists()) {
                    var db: android.database.sqlite.SQLiteDatabase? = null
                    try {
                        db = android.database.sqlite.SQLiteDatabase.openDatabase(
                            dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                        )
                        val cursor = db.rawQuery(
                            "SELECT path FROM models WHERE (id = ? OR name = ?) AND status = 'completed' LIMIT 1",
                            arrayOf(modelId, modelId)
                        )
                        var path: String? = null
                        if (cursor.moveToFirst()) {
                            path = cursor.getString(0)
                        }
                        cursor.close()
                        if (path != null) {
                            val file = File(path)
                            if (file.exists() && file.isFile) {
                                return path
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ModelDownloadHelper", "Error querying database file ${dbFile.absolutePath}", e)
                    } finally {
                        db?.close()
                    }
                }
            }

            // Fallback to checking default filesDir path directly
            val defaultFileName = if (model == "whisper") "ggml-tiny.en.bin" else "llama-cleaner.onnx"
            val defaultFile = File(otherContext.filesDir, defaultFileName)
            if (defaultFile.exists() && defaultFile.isFile) {
                return defaultFile.absolutePath
            }
        } catch (e: Exception) {
            android.util.Log.d("ModelDownloadHelper", "Vela Voice app context or database not accessible: ${e.message}")
        }
        return null
    }

    /**
     * Reads ALL completed model rows from the companion app's models.db
     * (Ticket 806, map #290): the registry-driven counterpart to the
     * single-model [getSharedModelPath]. Only rows whose files exist on disk
     * are returned, so callers can hand the list straight to
     * [ModelSelection.resolve]. Read-only best-effort lookup (map #81 #95
     * constraints apply — never credentials).
     */
    fun getCompletedSharedModels(context: Context): List<ModelSelection.CompletedModel> {
        val out = mutableListOf<ModelSelection.CompletedModel>()
        try {
            val otherContext = context.createPackageContext("com.velavoice.app", Context.CONTEXT_IGNORE_SECURITY)
            val dbPaths = listOf(
                otherContext.getDatabasePath("models.db"),
                File(otherContext.filesDir, "SQLite/models.db"),
                File(otherContext.filesDir, "databases/models.db")
            )
            for (dbFile in dbPaths) {
                if (dbFile == null || !dbFile.exists()) continue
                var db: android.database.sqlite.SQLiteDatabase? = null
                try {
                    db = android.database.sqlite.SQLiteDatabase.openDatabase(
                        dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                    )
                    val cursor = db.rawQuery(
                        "SELECT id, path FROM models WHERE status = 'completed'", arrayOf<String>()
                    )
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val path = cursor.getString(1) ?: continue
                        val file = File(path)
                        if (!file.exists() || !file.isFile) continue
                        val engine = ModelRegistry.find(id)?.engine
                            ?: if (id.startsWith("whisper")) EngineKind.WHISPER else continue
                        out.add(ModelSelection.CompletedModel(id, engine, path))
                    }
                    cursor.close()
                    if (out.isNotEmpty()) return out
                } catch (e: Exception) {
                    android.util.Log.e("ModelDownloadHelper", "Error querying database file ${dbFile.absolutePath}", e)
                } finally {
                    db?.close()
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("ModelDownloadHelper", "Vela Voice app context or database not accessible: ${e.message}")
        }
        return out
    }

    /**
     * Registry-driven local model resolution for the keyboard
     * (Ticket 806, map #290): reads [Settings.PREF_VELA_SELECTED_MODEL] and
     * the completed shared models, resolving with the shared
     * [ModelSelection] fallback order (selected → same engine → first
     * registry-ordered completed → null, i.e. cloud only).
     */
    @JvmStatic
    fun resolveSharedModel(context: Context, prefs: SharedPreferences): ModelSelection.Selection? =
        ModelSelection.resolve(
            prefs.getString(Settings.PREF_VELA_SELECTED_MODEL, Defaults.PREF_VELA_SELECTED_MODEL),
            getCompletedSharedModels(context)
        )

    /**
     * Returns the resolved download path for a model in app-private storage.
     */
    private fun getResolvedDownloadPath(context: Context, model: String): File? {
        return when (model) {
            "whisper" -> File(getModelsDir(context), WHISPER_FILENAME)
            "llm" -> File(getModelsDir(context), LLM_FILENAME)
            else -> null
        }
    }

    /** 
     * Downloads a multi-file ONNX bundle (Ticket 804, map #290) into its
     * per-model directory `[filesDir]/vela_models/<dirName>/`:
     * each part streams through a `.part` file, verifies its pinned SHA-256,
     * and atomically renames; after ALL parts land,
     * [ModelIntegrity.verifyBundle] re-verifies the whole directory
     * (manifest + aggregate size cap) before success is reported. On any
     * failure the directory is deleted — a partially-verified bundle is never
     * left behind, and callers must only mark `status: 'completed'` when this
     * returns true.
     *
     * Aggregated progress is `(completedFiles + currentFileFraction) / files`.
     */
    suspend fun downloadModelBundle(
        context: Context,
        dirName: String,
        parts: List<BundlePart>,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        require(dirName.isNotBlank()) { "bundle dirName must not be blank" }
        require(parts.isNotEmpty()) { "bundle must have at least one part" }
        val bundleDir = File(getModelsDir(context), dirName)
        var ok = false
        try {
            for ((index, part) in parts.withIndex()) {
                val destFile = File(bundleDir, part.name)
                val seeded = downloadToFile(
                    urlStr = part.url,
                    destFile = destFile,
                    onProgress = { fileFraction: Float ->
                        onProgress((index + fileFraction) / parts.size)
                    },
                    expectedHash = part.expectedSha256
                )
                if (!seeded) return@withContext false
            }
            val manifest = parts.map { part -> com.velavoice.sdk.BundleFileSpec(part.name, part.expectedSha256) }
            ok = ModelIntegrity.verifyBundle(bundleDir, manifest)
        } catch (t: Throwable) {
            android.util.Log.w("ModelDownloadHelper", "downloadModelBundle failed: ${t.message}")
            ok = false
        } finally {
            if (!ok) {
                bundleDir.deleteRecursively()
            }
        }
        ok
    }

    /**
     * Downloads a model file from HuggingFace to the app's private storage.
     * Resolves the download path to [context.filesDir]/vela_models/ so the
     * download works on Android 10+ without requiring MANAGE_EXTERNAL_STORAGE.
     *
     * After a successful download, updates the corresponding SharedPreferences
     * path key so the rest of the app (KeyboardSwitcher, LatinIME) can find
     * the model via the standard preference lookup.
     *
     * Returns true on success, false on failure.
     */
    suspend fun downloadModel(
        context: Context,
        prefs: SharedPreferences,
        model: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val (urlStr, statusKey) = when (model) {
            "whisper" -> Pair(WHISPER_MODEL_URL, Settings.PREF_VELA_MODEL_PATH)
            "llm" -> Pair(LLM_MODEL_URL, Settings.PREF_VELA_LLM_MODEL_PATH)
            else -> return@withContext false
        }
        val destFile = getResolvedDownloadPath(context, model)
            ?: return@withContext false
        if (!downloadToFile(urlStr, destFile, onProgress)) {
            return@withContext false
        }
        prefs.edit {
            putString(statusKey, destFile.absolutePath)
            putBoolean(statusKey + "_downloaded", true)
        }
        true
    }

    /**
     * Downloads a model into the user-picked folder (durable, user-visible copy via
     * Storage Access Framework) and keeps a private working copy that transcription
     * actually loads. The durable copy in the user's folder survives app reinstallation.
     */
    suspend fun downloadModelToFolder(
        context: Context,
        prefs: SharedPreferences,
        model: String,
        folderUri: Uri,
        onProgress: (Float) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        val (urlStr, statusKey, filename) = when (model) {
            "whisper" -> Triple(WHISPER_MODEL_URL, Settings.PREF_VELA_MODEL_PATH, WHISPER_FILENAME)
            "llm" -> Triple(LLM_MODEL_URL, Settings.PREF_VELA_LLM_MODEL_PATH, LLM_FILENAME)
            else -> return@withContext DownloadResult(null, false)
        }
        val destFile = getResolvedDownloadPath(context, model)
            ?: return@withContext DownloadResult(null, false)
        if (!downloadToFile(urlStr, destFile, onProgress)) {
            return@withContext DownloadResult(null, false)
        }
        val savedPublic = writePublicCopy(context, folderUri, filename, destFile)
        prefs.edit {
            putString(statusKey, destFile.absolutePath)
            putBoolean(statusKey + "_downloaded", true)
        }
        DownloadResult(destFile.absolutePath, savedPublic)
    }

    /**
     * Imports a model from a file the user selected via the picker.
     *
     * Ticket #242 (F20): the imported file is ALWAYS copied into the app-private
     * working location ([context.filesDir]-backed), never referenced in place.
     * Resolved external paths (/storage/emulated/0/...) fail WhisperEngine's
     * ModelIntegrity.isInsideAllowedRoots containment check on Android 10+
     * (SecurityException: 'Model path outside allowed storage roots'). The copy
     * is streamed in chunks into a `.part` temp file and atomically renamed on
     * success; enforced under ModelIntegrity.MAX_MODEL_BYTES. On any failure the
     * preferences are left untouched and null is returned.
     * Returns the active app-private path, or null on failure.
     */
    suspend fun importModel(
        context: Context,
        prefs: SharedPreferences,
        model: String,
        uri: Uri
    ): String? = withContext(Dispatchers.IO) {
        val statusKey = when (model) {
            "whisper" -> Settings.PREF_VELA_MODEL_PATH
            "llm" -> Settings.PREF_VELA_LLM_MODEL_PATH
            else -> return@withContext null
        }
        val destFile = getResolvedDownloadPath(context, model)
            ?: return@withContext null
        val activePath = copyUriToWorkingCopy(context, uri, destFile)?.absolutePath
        if (activePath != null) {
            prefs.edit {
                putString(statusKey, activePath)
                putBoolean(statusKey + "_downloaded", true)
            }
        }
        activePath
    }

    /** Streams [urlStr] into [destFile] via a `.part` temp file, reports progress,
     *  verifies SHA-256 against pinned hash, and atomically renames on success.
     *
     *  Ticket #95: maxBytes enforced, use{} for streams, .part+rename, finally cleanup. */
    private suspend fun downloadToFile(
        urlStr: String,
        destFile: File,
        onProgress: (Float) -> Unit,
        expectedHash: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val partFile = File(destFile.parentFile, destFile.name + ".part")
        val expected = if (expectedHash != null) expectedHash else expectedHashForUrl(urlStr)
        val maxBytes = ModelIntegrity.MAX_MODEL_BYTES
        try {
            destFile.parentFile?.mkdirs()
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "*/*")
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return@withContext false
            }
            val totalBytes = connection.contentLengthLong
            // Reject unknown or oversized content-length
            if (totalBytes > maxBytes || totalBytes == -1L) {
                connection.disconnect()
                return@withContext false
            }
            try {
                connection.inputStream.use { inputStream ->
                    FileOutputStream(partFile).use { outputStream ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalRead: Long = 0
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            totalRead += bytesRead
                            if (totalRead > maxBytes) {
                                return@withContext false
                            }
                            outputStream.write(buffer, 0, bytesRead)
                            if (totalBytes > 0) {
                                onProgress(totalRead.toFloat() / totalBytes.toFloat())
                            }
                        }
                        outputStream.flush()
                    }
                }
            } finally {
                connection.disconnect()
            }
            // Verify SHA-256 against pinned hash
            if (expected != null) {
                if (!ModelIntegrity.verifySha256(partFile, expected)) {
                    return@withContext false
                }
            }
            // Atomic rename: .part → target
            if (!partFile.renameTo(destFile)) {
                partFile.copyTo(destFile, overwrite = true)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            // Always clean up .part file
            if (partFile.exists()) {
                partFile.delete()
            }
        }
    }

    /** Returns the pinned SHA-256 hash for a known model URL, or null for unknown URLs. */
    private fun expectedHashForUrl(url: String): String? = when (url) {
        WHISPER_MODEL_URL -> WHISPER_EXPECTED_HASH
        LLM_MODEL_URL -> LLM_EXPECTED_HASH
        else -> null
    }

/**
     * Writes [sourceFile] into the user-picked SAF folder as [filename], replacing
     * any previous file with the same display name inside that folder.
     */
    private fun writePublicCopy(
        context: Context,
        folderUri: Uri,
        filename: String,
        sourceFile: File
    ): Boolean {
        return try {
            val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)
            // Remove an existing document with the same name so re-downloads stay idempotent
            context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val docIdIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val docId = if (docIdIdx >= 0) cursor.getString(docIdIdx) else null
                    if (name == filename && docId != null) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                        DocumentsContract.deleteDocument(context.contentResolver, docUri)
                    }
                }
            }
            val treeUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, treeDocId)
            val docUri = DocumentsContract.createDocument(
                context.contentResolver, treeUri, "application/octet-stream", filename
            ) ?: return false
            context.contentResolver.openOutputStream(docUri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: return false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Streams [uri]'s content into [destFile] via a `.part` temp file with a
     * model-size cap (ModelIntegrity.MAX_MODEL_BYTES) and an atomic
     * `.part` → target rename, mirroring downloadToFile's failure handling.
     * Returns [destFile] on success, null if the provider can't be read, the
     * file exceeds the cap, or any error occurs — never a partial working copy.
     */
    private fun copyUriToWorkingCopy(context: Context, uri: Uri, destFile: File): File? {
        val partFile = File(destFile.parentFile, destFile.name + ".part")
        try {
            destFile.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { stream ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalRead = 0L
                    while (true) {
                        val bytesRead = stream.read(buffer)
                        if (bytesRead == -1) break
                        totalRead += bytesRead
                        if (totalRead > ModelIntegrity.MAX_MODEL_BYTES) return null
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
            // Atomic rename: .part → target (falls back to copy-on-rename failures)
            if (!partFile.renameTo(destFile)) {
                partFile.copyTo(destFile, overwrite = true)
            }
            return destFile
        } catch (e: Exception) {
            android.util.Log.w("ModelDownloadHelper", "copyUriToWorkingCopy failed: ${e.message}")
            return null
        } finally {
            // Always clean up any leftover .part file
            if (partFile.exists()) {
                partFile.delete()
            }
        }
    }

    /**
     * Deletes a downloaded model file and clears its download status.
     */
    fun deleteModel(context: Context, prefs: SharedPreferences, model: String) {
        val statusKey = when (model) {
            "whisper" -> Settings.PREF_VELA_MODEL_PATH
            "llm" -> Settings.PREF_VELA_LLM_MODEL_PATH
            else -> return
        }

        // Delete from app-private storage
        val privateFile = getResolvedDownloadPath(context, model)
        if (privateFile != null && privateFile.exists()) {
            privateFile.delete()
        }

        // Also delete legacy path if it exists
        val legacyPath = prefs.getString(statusKey, null)
        if (legacyPath != null) {
            val legacyFile = File(legacyPath)
            if (legacyFile.exists()) {
                legacyFile.delete()
            }
        }

        // Reset preferences to defaults
        prefs.edit {
            remove(statusKey + "_downloaded")
            putString(statusKey, when (model) {
                "whisper" -> Defaults.PREF_VELA_MODEL_PATH
                "llm" -> Defaults.PREF_VELA_LLM_MODEL_PATH
                else -> return@edit
            })
        }
    }

    // Legacy status constants kept for backward compatibility
    private const val STATUS_DOWNLOADING = "downloading"
    private const val STATUS_COMPLETED = "completed"
    private const val STATUS_FAILED = "failed"
    private const val STATUS_PENDING = "pending"
}