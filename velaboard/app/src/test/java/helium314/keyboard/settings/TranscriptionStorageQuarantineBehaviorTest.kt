package helium314.keyboard.settings

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Map #130 ticket #135: quarantine directory + sync-scanner hardening.
 *
 * `save(..., privacySensitive = true)` must land the session in the dedicated
 * quarantine dir (shared `transcriptions/` untouched, still returns the #134
 * failure signal) so quarantined sessions stay local-only; the sync scanner
 * must never list quarantine, and a sensitive-marked / corrupt / verdict-less
 * file planted in the shared dir must never surface as uploadable (fail
 * closed). Verdict-stamped non-sensitive saves keep the existing behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionStorageQuarantineBehaviorTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext<App>()

    private fun sharedDir(): java.io.File = java.io.File(context.filesDir, "transcriptions")

    private fun quarantineDir(): java.io.File {
        val siblings = (context.filesDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name != "transcriptions" }
        val named = siblings.filter { it.name.contains("quarantine", ignoreCase = true) }
        val withSessions = (if (named.isNotEmpty()) named else siblings).filter {
            it.listFiles()?.any { f -> f.isFile && f.extension == "json" } == true
        }
        assertTrue(withSessions.isNotEmpty(), "expected a quarantine dir with saved sessions under filesDir")
        return withSessions.first()
    }

    private fun sharedJsonFiles(): List<java.io.File> =
        sharedDir().listFiles()?.filter { it.isFile && it.extension == "json" } ?: emptyList()

    private fun sharedWavFiles(): List<java.io.File> =
        sharedDir().listFiles()?.filter { it.isFile && it.extension == "wav" } ?: emptyList()

    @BeforeTest
    fun cleanStorage() {
        sharedDir().deleteRecursively()
        (context.filesDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.contains("quarantine", ignoreCase = true) }
            .forEach { it.deleteRecursively() }
    }

    @Test
    fun `sensitive save lands in quarantine and leaves the shared dir untouched`() {
        val result = TranscriptionStorage.save(
            context,
            raw = "my password is hunter2",
            cleaned = "my password is hunter2",
            durationMs = 500L,
            audioBytes = byteArrayOf(0, 1, 2, 3),
            privacySensitive = true
        )

        assertNull(result, "sensitive save must keep the failure signal for the shared dir")
        assertTrue(sharedJsonFiles().isEmpty(), "sensitive save must write zero JSON files to the shared dir")
        assertTrue(sharedWavFiles().isEmpty(), "sensitive save must write zero WAV files to the shared dir")

        val q = quarantineDir()
        val jsons = q.listFiles()?.filter { it.isFile && it.extension == "json" } ?: emptyList()
        val wavs = q.listFiles()?.filter { it.isFile && it.extension == "wav" } ?: emptyList()
        assertEquals(1, jsons.size, "sensitive save must persist exactly one session in quarantine")
        assertEquals(1, wavs.size, "sensitive save must keep the audio alongside in quarantine")
        assertEquals(
            true,
            JSONObject(jsons.first().readText()).optBoolean("privacySensitive", false),
            "quarantined JSON must carry the sensitive verdict"
        )
    }

    @Test
    fun `sensitive save without audio still quarantines the transcript`() {
        val result = TranscriptionStorage.save(
            context,
            raw = "secret",
            cleaned = "secret",
            durationMs = 100L,
            privacySensitive = true
        )

        assertNull(result, "sensitive save must keep the failure signal for the shared dir")
        assertTrue(sharedJsonFiles().isEmpty(), "sensitive save must write zero files to the shared dir")
        val q = quarantineDir()
        val jsons = q.listFiles()?.filter { it.isFile && it.extension == "json" } ?: emptyList()
        assertEquals(1, jsons.size, "sensitive save must persist the transcript in quarantine")
    }

    @Test
    fun `scanner never lists the quarantine directory`() {
        TranscriptionStorage.save(
            context,
            raw = "my password is hunter2",
            cleaned = "my password is hunter2",
            durationMs = 500L,
            privacySensitive = true
        )
        TranscriptionStorage.save(
            context,
            raw = "hello world",
            cleaned = "Hello world",
            durationMs = 500L,
            privacySensitive = false
        )

        val unsynced = TranscriptionStorage.getUnsyncedFiles(context)
        assertEquals(1, unsynced.size, "only the shared non-sensitive session is uploadable")
        val q = quarantineDir()
        assertTrue(
            unsynced.none { it.absolutePath.startsWith(q.absolutePath) },
            "scanner must never surface quarantined sessions"
        )
    }

    @Test
    fun `sensitive-marked file planted in the shared dir is never uploadable`() {
        sharedDir().mkdirs()
        val planted = java.io.File(sharedDir(), "2026-01-01_00-00-00_0.json")
        planted.writeText(
            JSONObject()
                .put("raw", "my password is hunter2")
                .put("cleaned", "my password is hunter2")
                .put("durationMs", 100L)
                .put("createdAt", "2026-01-01T00:00:00.000Z")
                .put("privacySensitive", true)
                .toString(2)
        )

        assertTrue(
            TranscriptionStorage.getUnsyncedFiles(context).isEmpty(),
            "sensitive-marked shared file must be skipped fail-closed"
        )
    }

    @Test
    fun `unparseable file in the shared dir is never uploadable`() {
        sharedDir().mkdirs()
        java.io.File(sharedDir(), "2026-01-01_00-00-00_0.json").writeText("not json at all {{{")

        assertTrue(
            TranscriptionStorage.getUnsyncedFiles(context).isEmpty(),
            "unparseable shared file must be skipped fail-closed"
        )
    }

    @Test
    fun `verdict-less legacy file in the shared dir is never uploadable`() {
        sharedDir().mkdirs()
        val legacy = java.io.File(sharedDir(), "2026-01-01_00-00-00_0.json")
        legacy.writeText(
            JSONObject()
                .put("raw", "hello world")
                .put("cleaned", "Hello world")
                .put("durationMs", 100L)
                .put("createdAt", "2026-01-01T00:00:00.000Z")
                .toString(2)
        )

        assertTrue(
            TranscriptionStorage.getUnsyncedFiles(context).isEmpty(),
            "file without a verdict must be skipped fail-closed"
        )
    }

    @Test
    fun `non-sensitive save stamps its verdict and stays uploadable`() {
        val result = TranscriptionStorage.save(
            context,
            raw = "hello world",
            cleaned = "Hello world",
            durationMs = 500L,
            audioBytes = byteArrayOf(0, 1, 2, 3),
            privacySensitive = false
        )

        assertNotNull(result, "non-sensitive save must still succeed")
        assertEquals(1, sharedJsonFiles().size, "non-sensitive save must write the shared JSON")
        assertEquals(
            false,
            JSONObject(sharedJsonFiles().first().readText()).optBoolean("privacySensitive", true),
            "shared JSON must carry the non-sensitive verdict"
        )
        assertEquals(1, TranscriptionStorage.getUnsyncedFiles(context).size, "stamped session stays uploadable")
    }
}
