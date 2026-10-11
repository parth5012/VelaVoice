package helium314.keyboard.settings

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Map #97 ticket #100: transcription persistence leaves the IME main thread.
 *
 * `TranscriptionStorage.save()` is synchronous JSON + file/WAV I/O and the IME
 * commit paths used to call it inline from main-thread callbacks. These tests
 * pin the behavioural half of the fix: the `persistAsync` seam every IME save
 * now goes through (the other half is the source contract in
 * `TranscriptionPersistenceOffloadTest`) never runs its task on the caller
 * thread, keeps saves FIFO on one dedicated thread, contains a failing save so
 * it can neither crash the caller nor kill later saves, and still writes the
 * file end-to-end.
 *
 * A true StrictMode `detectDiskWrites` assertion is not reachable here: the
 * Robolectric sandbox does not enforce BlockGuard for host-JVM file I/O, and
 * the velavoice-native IME has no Gradle module to instrument at all. This
 * seam-plus-contract pair is the honest proxy named in the ticket.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionPersistenceAsyncBehaviorTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext<App>()

    private fun awaitBarrier() {
        val latch = CountDownLatch(1)
        TranscriptionStorage.persistAsync(Runnable { latch.countDown() })
        assertTrue(latch.await(5, TimeUnit.SECONDS), "persistence executor must drain")
    }

    @Test
    fun `persistAsync runs its task off the caller and main thread`() {
        val recorded = AtomicReference<Thread>()
        val done = CountDownLatch(1)

        TranscriptionStorage.persistAsync(Runnable {
            recorded.set(Thread.currentThread())
            done.countDown()
        })

        assertTrue(done.await(5, TimeUnit.SECONDS), "persisted task must run")
        val worker = recorded.get()
        assertNotEquals(
            Looper.getMainLooper().thread, worker,
            "persistence must never run on the IME main thread"
        )
        assertEquals("VelaPersistence", worker.name)
    }

    @Test
    fun `persistAsync keeps every save on one dedicated thread in FIFO order`() {
        val threads = mutableListOf<Thread>()
        val order = mutableListOf<Int>()

        repeat(3) { i ->
            TranscriptionStorage.persistAsync(Runnable {
                synchronized(threads) {
                    threads.add(Thread.currentThread())
                    order.add(i)
                }
            })
        }
        awaitBarrier()

        assertEquals(listOf(0, 1, 2), order, "persistence must stay FIFO")
        assertEquals(1, threads.map { it.name }.distinct().size, "one dedicated thread only")
        assertNotEquals(Looper.getMainLooper().thread, threads.first())
    }

    @Test
    fun `a failing persistence task never reaches the caller nor blocks later saves`() {
        // Must return without throwing: the seam contains the failure.
        TranscriptionStorage.persistAsync(Runnable { throw RuntimeException("disk full") })

        val after = AtomicReference<String>()
        val done = CountDownLatch(1)
        TranscriptionStorage.persistAsync(Runnable {
            after.set("still alive")
            done.countDown()
        })

        assertTrue(done.await(5, TimeUnit.SECONDS), "executor must survive a failing save")
        assertEquals("still alive", after.get())
    }

    @Test
    fun `persistAsync still writes the transcript file end-to-end`() {
        val sharedDir = java.io.File(context.filesDir, "transcriptions")
        sharedDir.deleteRecursively()

        val done = CountDownLatch(1)
        TranscriptionStorage.persistAsync(Runnable {
            TranscriptionStorage.save(
                context,
                raw = "raw text",
                cleaned = "cleaned text",
                durationMs = 500L,
                audioBytes = null,
                privacySensitive = false
            )
            done.countDown()
        })

        assertTrue(done.await(5, TimeUnit.SECONDS), "save must complete")
        val files = sharedDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        assertEquals(1, files.size, "exactly one transcript must be persisted")
    }

    @Test
    fun `a failing end-to-end save is contained and the next one still lands`() {
        val sharedDir = java.io.File(context.filesDir, "transcriptions")
        sharedDir.deleteRecursively()

        val badContext = object : android.content.ContextWrapper(context) {
            override fun getFilesDir(): java.io.File = java.io.File("/proc/definitely-not-writable")
        }
        TranscriptionStorage.persistAsync(Runnable {
            TranscriptionStorage.save(
                badContext,
                raw = "raw",
                cleaned = "cleaned",
                durationMs = 0L,
                audioBytes = null,
                privacySensitive = false
            )
        })

        val done = CountDownLatch(1)
        TranscriptionStorage.persistAsync(Runnable {
            TranscriptionStorage.save(
                context,
                raw = "raw",
                cleaned = "cleaned",
                durationMs = 0L,
                audioBytes = null,
                privacySensitive = false
            )
            done.countDown()
        })

        assertTrue(done.await(5, TimeUnit.SECONDS))
        awaitBarrier()
        val files = sharedDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
        assertEquals(1, files.size, "the good save must land after a failed one")
    }
}
