package helium314.keyboard.settings

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Map #97 ticket #101: transcript persistence appends incrementally — each
 * save writes exactly one new per-session JSON file and never re-encodes or
 * rewrites the on-disk store of earlier sessions (the audit's "list rebuilt
 * from scratch on every insert" claim; this lock fails if a future refactor
 * converts the store into a shared list file that is re-encoded per insert).
 */
@RunWith(RobolectricTestRunner::class)
class TranscriptionStorageIncrementalSaveTest {

    @Test
    fun `each save appends a new file and never rewrites earlier ones`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, "transcriptions")
        dir.deleteRecursively()

        val first = TranscriptionStorage.save(context, "raw one", "clean one", 100, null,
            privacySensitive = false)!!
        val firstSnapshot = first.readBytes()
        Thread.sleep(2) // base names carry millisecond timestamps
        val second = TranscriptionStorage.save(context, "raw two", "clean two", 200, null,
            privacySensitive = false)!!
        Thread.sleep(2)
        TranscriptionStorage.save(context, "raw three", "clean three", 300, null,
            privacySensitive = false)!!

        assertEquals("each save must append one new file",
            3, dir.listFiles()!!.count { it.isFile && it.extension == "json" })
        assertArrayEquals("a later save must never rewrite an earlier transcript file",
            firstSnapshot, first.readBytes())
        assertEquals("raw one", JSONObject(first.readText(Charsets.UTF_8)).getString("raw"))
        assertEquals("raw two", JSONObject(second.readText(Charsets.UTF_8)).getString("raw"))

        // A sensitive save routes to quarantine and still leaves shared files untouched.
        assertNull(TranscriptionStorage.save(context, "secret", "secret", 1, null,
            privacySensitive = true))
        assertArrayEquals("quarantine routing must never rewrite shared files",
            firstSnapshot, first.readBytes())
        assertEquals("quarantine routing must leave exactly the 3 shared files",
            3, dir.listFiles()!!.count { it.isFile && it.extension == "json" })
    }
}
