package helium314.keyboard.settings

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.Instant
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Locked timestamp contract (map #212 ticket #240, finding F18): persisted
 * transcript timestamps are true UTC — [Instant.now] stamped with
 * [java.time.format.DateTimeFormatter.ISO_INSTANT], where the trailing 'Z'
 * is truthful. The local clock is a read/display-time concern only.
 *
 * The tests force a non-UTC default timezone so a formatter that silently
 * writes local wall-clock time under a 'Z' suffix (the pre-fix
 * SimpleDateFormat behavior, F18) drifts from the truthful UTC epoch:
 * each save's file name carries the real epoch milliseconds as its final
 * `_`-separated suffix, giving every assertion a UTC ground truth.
 *
 * Historical records written before the fix stay as-is — documented as
 * approximate; there is deliberately NO migration code (locked stance).
 */
@RunWith(RobolectricTestRunner::class)
class TranscriptionStorageTimestampContractTest {

    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun forceNonUtcDefaultTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }

    @After
    fun restoreDefaultTimezone() {
        TimeZone.setDefault(originalTimeZone)
    }

    /** UTC epoch milliseconds embedded in a persisted file's base name. */
    private fun suffixEpochMillis(file: File): Long =
        file.name.removeSuffix(".json").substringAfterLast('_').toLong() / 1000

    private fun clearStores(context: Context) {
        File(context.filesDir, "transcriptions").deleteRecursively()
        File(context.filesDir, "transcriptions_quarantine").deleteRecursively()
    }

    private fun assertUtcDrift(file: File, savedFileChecked: String) {
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val createdAt = json.getString("createdAt")
        val instant = Instant.parse(createdAt) // throws unless a true UTC instant
        val drift = Math.abs(instant.toEpochMilli() - suffixEpochMillis(file))
        assertTrue(
            "$savedFileChecked createdAt must be true UTC within 5s of the epoch suffix " +
                "(drift=${drift}ms), got $createdAt",
            drift < 5000
        )
    }

    @Test
    fun `shared-dir createdAt is true UTC matching the epoch suffix`() {
        val context = RuntimeEnvironment.getApplication()
        clearStores(context)

        val file = TranscriptionStorage.save(
            context, "raw", "clean", 100L, null, privacySensitive = false)!!
        assertUtcDrift(file, "shared-dir")
    }

    @Test
    fun `quarantine-dir createdAt is true UTC matching the epoch suffix`() {
        val context = RuntimeEnvironment.getApplication()
        clearStores(context)

        TranscriptionStorage.save(
            context, "secret", "secret", 1L, null, privacySensitive = true)
        val quarantined = TranscriptionStorage.getQuarantinedFiles(context)
            .single { it.extension == "json" }
        assertUtcDrift(quarantined, "quarantine-dir")
    }

    @Test
    fun `parallel saves produce valid UTC-correct timestamps`() {
        val context = RuntimeEnvironment.getApplication()
        clearStores(context)

        val threads = 16
        val savesPerThread = 8
        val allReady = CountDownLatch(1)
        val allDone = CountDownLatch(threads)
        val failures = AtomicInteger()
        val causes = java.util.concurrent.CopyOnWriteArrayList<String>()

        repeat(threads) { t ->
            Thread {
                try {
                    allReady.await()
                    repeat(savesPerThread) { i ->
                        val file = TranscriptionStorage.save(
                            context, "raw $t-$i", "clean $t-$i", 0L, null,
                            privacySensitive = false)!!
                        val json = JSONObject(file.readText(Charsets.UTF_8))
                        val instant = Instant.parse(json.getString("createdAt"))
                        val drift = Math.abs(instant.toEpochMilli() - suffixEpochMillis(file))
                        if (drift >= 5000) {
                            causes.add("drift ${drift}ms ${file.name}")
                            failures.incrementAndGet()
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    causes.add(e.toString())
                    failures.incrementAndGet()
                } finally {
                    allDone.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }
        }
        allReady.countDown()
        assertTrue("all concurrent saves must finish", allDone.await(60, TimeUnit.SECONDS))

        assertEquals(
            "every concurrent save must stamp a valid UTC createdAt: " + causes.sorted(),
            0, failures.get()
        )
        assertEquals(
            "all parallel saves must land as individual files",
            threads * savesPerThread,
            File(context.filesDir, "transcriptions")
                .listFiles()!!.count { it.extension == "json" }
        )
    }
}
