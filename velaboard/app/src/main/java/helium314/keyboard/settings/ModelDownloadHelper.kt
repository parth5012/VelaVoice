// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * Downloads Whisper and LLM Cleaner models from HuggingFace.
 * Tracks download status via SharedPreferences.
 * Models are stored in the app's private files directory to comply
 * with Android 10+ scoped storage restrictions.
 */
object ModelDownloadHelper {

    // Model download URLs (matching ModelManager.ts in the Vela Voice app)
    const val WHISPER_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin"
    const val LLM_MODEL_URL = "https://huggingface.co/onnx-community/Llama-3.2-1B-Instruct-ONNX/resolve/main/onnx/model.onnx"

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
     * Requires both apps to be signed with the same key and have sharedUserId configured.
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
            val modelId = if (model == "whisper") "whisper-tiny-en" else "cleaner-llama-1b"

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
     * Imports a model by pointing at a file the user selected. When the file is
     * reachable at a real filesystem path (legacy external-storage access) the
     * preference simply points at it — no copy. On scoped-storage devices the file
     * is copied into the app-private working location so transcription can open it.
     * Returns the active path, or null on failure.
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
        val directPath = resolveContentPath(context, uri)
        val activePath = if (directPath != null) {
            directPath
        } else {
            val destFile = getResolvedDownloadPath(context, model)
                ?: return@withContext null
            copyUriToFile(context, uri, destFile)?.absolutePath
        }
        if (activePath != null) {
            prefs.edit {
                putString(statusKey, activePath)
                putBoolean(statusKey + "_downloaded", true)
            }
        }
        activePath
    }

    /** Streams [urlStr] into [destFile], reporting progress 0f..1f. */
    private suspend fun downloadToFile(
        urlStr: String,
        destFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
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
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(destFile)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead: Long = 0
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalBytes > 0) {
                    onProgress(totalRead.toFloat() / totalBytes.toFloat())
                }
            }
            outputStream.close()
            inputStream.close()
            connection.disconnect()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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

    /** Best-effort maps a picked content/document URI to a real filesystem path. */
    private fun resolveContentPath(context: Context, uri: Uri): String? {
        return try {
            val path = when {
                uri.scheme?.equals("file", true) == true -> uri.path
                uri.authority == "com.android.externalstorage.documents" ->
                    resolveExternalStoragePath(uri)
                uri.authority == "com.android.providers.downloads.documents" ->
                    resolveDownloadsPath(context, uri)
                uri.authority == "com.android.providers.media.documents" ->
                    resolveMediaPath(context, uri)
                else -> querySingleColumn(context, uri, MediaStore.MediaColumns.DATA)
            }
            if (path != null && File(path).isFile) path else null
        } catch (e: Exception) {
            android.util.Log.w("ModelDownloadHelper", "resolveContentPath failed: ${e.message}")
            null
        }
    }

    private fun resolveExternalStoragePath(uri: Uri): String? {
        val docId = DocumentsContract.getDocumentId(uri)
        val split = docId.split(":")
        if (split.isEmpty()) return null
        val root = split[0]
        val rel = split.drop(1).joinToString("/")
        val base = if (root.equals("primary", true)) {
            Environment.getExternalStorageDirectory()
        } else {
            // Secondary volume (SD card), usually mounted under /storage/<root>
            val sd = File(File.separator + "storage", root)
            if (sd.exists()) sd else return null
        }
        return File(base, rel).absolutePath
    }

    private fun resolveDownloadsPath(context: Context, uri: Uri): String? {
        // DownloadsProvider document IDs (e.g. "msf:1234") carry no filename, so use DISPLAY_NAME
        val name = querySingleColumn(context, uri, OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment?.substringAfterLast(":")
        if (name.isNullOrEmpty()) return null
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, name).absolutePath
    }

    private fun resolveMediaPath(context: Context, uri: Uri): String? {
        // Older devices may expose _data directly
        val data = querySingleColumn(context, uri, MediaStore.MediaColumns.DATA)
        if (data != null && File(data).isFile) return data
        // API 29+: use RELATIVE_PATH + DISPLAY_NAME to reconstruct the real location
        val relative = querySingleColumn(context, uri, MediaStore.MediaColumns.RELATIVE_PATH)
        val name = querySingleColumn(context, uri, MediaStore.MediaColumns.DISPLAY_NAME)
        if (relative != null && name != null) {
            val base = Environment.getExternalStorageDirectory()
            val candidate = File(base, relative.trimStart('/') + name)
            if (candidate.isFile) return candidate.absolutePath
        }
        return null
    }

    private fun querySingleColumn(context: Context, uri: Uri, column: String): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun copyUriToFile(context: Context, uri: Uri, destFile: File): File? {
        return try {
            destFile.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { i ->
                FileOutputStream(destFile).use { o -> i.copyTo(o) }
            }
            destFile
        } catch (e: Exception) {
            android.util.Log.w("ModelDownloadHelper", "copyUriToFile failed: ${e.message}")
            null
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