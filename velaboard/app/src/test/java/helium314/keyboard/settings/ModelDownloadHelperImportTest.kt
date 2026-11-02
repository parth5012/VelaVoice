// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloadHelperImportTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.prefs().edit().clear().commit()
        prefs = context.prefs()
    }

    /** Creates a pickable model file OUTSIDE context.filesDir (system temp) and
     *  returns its content://-style file URI, mimicking what an OpenDocument
     *  picker hands over with a resolved filesystem location. */
    private fun externalModelUri(model: String): Pair<Uri, File> {
        val src = Files.createTempFile("vela-import-$model-", ".bin").toFile()
        src.writeBytes(byteArrayOf(0x76, 0x65, 0x6C, 0x61, 0x00, 0x07))
        src.deleteOnExit()
        // Build the URI manually (not Uri.fromFile) so the decoded path keeps
        // the platform-absolute form a real filesystem resolution would see.
        val uri = Uri.Builder().scheme("file").path(src.absolutePath).build()
        return Pair(uri, src)
    }

    private fun assertPathInsideFilesDir(stored: String?) {
        val filesRoot = context.filesDir.canonicalPath
        assertNotNull(stored, "importModel must persist the active model path")
        val canonical = File(stored).canonicalPath
        assertTrue(
            canonical.startsWith(filesRoot + File.separator),
            "F20: stored path '$stored' must live inside context.filesDir ($filesRoot)"
        )
    }

    @Test fun importedWhisperModelIsCopiedIntoAppPrivateFilesDir() = runBlocking {
        val (uri, src) = externalModelUri("whisper")
        val activePath = ModelDownloadHelper.importModel(context, prefs, "whisper", uri)

        assertPathInsideFilesDir(activePath)
        assertPathInsideFilesDir(
            prefs.getString(Settings.PREF_VELA_MODEL_PATH, Defaults.PREF_VELA_MODEL_PATH)
        )
        // The private working copy must hold the imported bytes.
        val stored = prefs.getString(Settings.PREF_VELA_MODEL_PATH, null)
        assertTrue(File(stored!!).exists(), "copied working copy must exist on disk")
        assertTrue(
            File(stored).readBytes().contentEquals(src.readBytes()),
            "copied working copy must contain the imported bytes"
        )
        assertTrue(prefs.getBoolean(Settings.PREF_VELA_MODEL_PATH + "_downloaded", false))
    }

    @Test fun importedLlmModelIsCopiedIntoAppPrivateFilesDir() = runBlocking {
        val (uri, src) = externalModelUri("llm")
        val activePath = ModelDownloadHelper.importModel(context, prefs, "llm", uri)

        assertPathInsideFilesDir(activePath)
        assertPathInsideFilesDir(
            prefs.getString(Settings.PREF_VELA_LLM_MODEL_PATH, Defaults.PREF_VELA_LLM_MODEL_PATH)
        )
        val stored = prefs.getString(Settings.PREF_VELA_LLM_MODEL_PATH, null)
        assertTrue(File(stored!!).exists())
        assertTrue(File(stored).readBytes().contentEquals(src.readBytes()))
        assertTrue(prefs.getBoolean(Settings.PREF_VELA_LLM_MODEL_PATH + "_downloaded", false))
    }

    /** Default-store semantics: after clear(), the raw store yields null and
     *  Defaults ("") are resolved at read time, so both mean "not set". */
    private fun assertPrefsPathIsDefault(preferences: SharedPreferences, key: String) {
        val stored = preferences.getString(key, null)
        assertTrue(
            stored == null || stored == Defaults.PREF_VELA_MODEL_PATH,
            "expected default/$key, was: '$stored'"
        )
    }

    @Test fun importFailureDoesNotMarkModelDownloaded() = runBlocking {
        // Unresolvable provider: openInputStream must fail (SecurityException/FileNotFound)
        val dangling = Uri.parse("content://com.velavoice.absent/nonexistent.bin")
        val result = ModelDownloadHelper.importModel(context, prefs, "whisper", dangling)
        assertNull(result, "failed import must not return an active path")
        assertPrefsPathIsDefault(prefs, Settings.PREF_VELA_MODEL_PATH)
        assertFalse(prefs.getBoolean(Settings.PREF_VELA_MODEL_PATH + "_downloaded", false))
    }

    @Test fun importOversizedFileIsRejected() = runBlocking {
        // ModelIntegrity.MAX_MODEL_BYTES cap: an imported file larger than the cap
        // must be refused (streamed to disk; no oversized in-memory buffer).
        val src = Files.createTempFile("vela-import-oversized-", ".bin").toFile()
        val chunk = ByteArray(1024 * 1024) { 0x61 }
        val chunkCount = (com.velavoice.sdk.ModelIntegrity.MAX_MODEL_BYTES / chunk.size).toInt() + 1
        src.outputStream().use { out ->
            repeat(chunkCount) { _ -> out.write(chunk) }
        }
        src.deleteOnExit()
        val uri = Uri.Builder().scheme("file").path(src.absolutePath).build()

        val result = ModelDownloadHelper.importModel(context, prefs, "whisper", uri)
        assertNull(result, "oversized import must be rejected")
        assertPrefsPathIsDefault(prefs, Settings.PREF_VELA_MODEL_PATH)
        assertFalse(prefs.getBoolean(Settings.PREF_VELA_MODEL_PATH + "_downloaded", false))
    }

    /** F35: the shared-model lookup must query the canonical LLM id. */
    @Test fun sharedModelIdForLlmIsCanonicalCleanerLlama3b() {
        assertEquals("cleaner-llama-3b", ModelDownloadHelper.sharedModelIdFor("llm"))
    }

    @Test fun sharedModelIdForWhisperIsCanonicalWhisperTinyEn() {
        assertEquals("whisper-tiny-en", ModelDownloadHelper.sharedModelIdFor("whisper"))
    }
}
