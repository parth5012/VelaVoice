package helium314.keyboard.settings

import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
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
 * Map #130 ticket #134: fail-closed privacy gate inside TranscriptionStorage.save().
 *
 * Direct `save(..., privacySensitive = true)` must write zero files and return the
 * failure signal; `false` preserves the existing write behavior. The velavoice-app
 * copy (no Gradle test harness) is covered by the SDK source-contract test
 * `TranscriptionStoragePrivacyGateTest`; behavior here proves the gate works, not
 * just that the branch exists (mutation check: deleting the gate turns these red).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionStoragePrivacyGateBehaviorTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext<App>()

    private fun storedJsonFiles(): List<java.io.File> {
        val dir = java.io.File(context.filesDir, "transcriptions")
        return dir.listFiles()?.filter { it.isFile && it.extension == "json" } ?: emptyList()
    }

    private fun storedWavFiles(): List<java.io.File> {
        val dir = java.io.File(context.filesDir, "transcriptions")
        return dir.listFiles()?.filter { it.isFile && it.extension == "wav" } ?: emptyList()
    }

    @BeforeTest
    fun cleanStorage() {
        java.io.File(context.filesDir, "transcriptions").deleteRecursively()
    }

    @Test
    fun `sensitive save writes zero files and returns failure`() {
        val before = storedJsonFiles().size

        val result = TranscriptionStorage.save(
            context,
            raw = "my password is hunter2",
            cleaned = "my password is hunter2",
            durationMs = 500L,
            audioBytes = byteArrayOf(0, 1, 2, 3),
            privacySensitive = true
        )

        assertNull(result, "sensitive save must return the failure signal")
        assertEquals(before, storedJsonFiles().size, "sensitive save must write zero JSON files")
        assertTrue(storedWavFiles().isEmpty(), "sensitive save must write zero WAV files")
    }

    @Test
    fun `sensitive save without audio still writes nothing`() {
        val before = storedJsonFiles().size

        val result = TranscriptionStorage.save(
            context,
            raw = "secret",
            cleaned = "secret",
            durationMs = 100L,
            privacySensitive = true
        )

        assertNull(result, "sensitive save must return the failure signal")
        assertEquals(before, storedJsonFiles().size, "sensitive save must write zero files")
    }

    @Test
    fun `non-sensitive save preserves existing write behavior`() {
        val result = TranscriptionStorage.save(
            context,
            raw = "hello world",
            cleaned = "Hello world",
            durationMs = 500L,
            audioBytes = byteArrayOf(0, 1, 2, 3),
            privacySensitive = false
        )

        assertNotNull(result, "non-sensitive save must still succeed")
        assertEquals(1, storedJsonFiles().size, "non-sensitive save must write exactly one JSON file")
        assertEquals(1, storedWavFiles().size, "non-sensitive save must write the WAV alongside")
    }
}
