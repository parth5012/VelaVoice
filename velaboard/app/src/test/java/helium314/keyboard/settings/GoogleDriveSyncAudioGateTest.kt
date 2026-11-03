// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import java.io.File
import java.io.Reader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F25 (map #212 ticket #243): a failed audio upload must NOT mark the
 * transcription synced. With the single-payload backend proxy design the
 * defect manifests as silent audio omission: when a transcription references
 * its audio file but that file is missing on disk, the text used to go up
 * without audio and the record was still marked synced — the audio is then
 * permanently absent from every future sync.
 *
 * Uses a tiny ServerSocket HTTP stub since com.sun.net.httpserver is not on
 * the unit-test compile classpath.
 */
/** Reads stream head until a blank CRLF line (end of HTTP headers) — byte-wise
 *  so the reader can never consume POST body bytes beyond the header block. */
private fun java.io.InputStream.readTextUntilBlankLine(): String {
    val sb = StringBuilder()
    var prevWasCr = false
    while (true) {
        val b = read()
        if (b < 0) break
        when {
            b == '\r'.code -> prevWasCr = true
            b == '\n'.code -> {
                val line = sb.toString()
                if (line.isEmpty() && prevWasCr) { sb.clear(); break }
                sb.append('\n'); prevWasCr = false
                // "\n\n" also ends the header block
                if (sb.endsWith("\n\n")) break
            }
            else -> { sb.append(b.toChar()); prevWasCr = false }
        }
    }
    return sb.toString()
}

private fun ByteArray.takeInto(stream: java.io.InputStream) {
    stream.readFully(this)
}

private fun java.io.InputStream.readFully(buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) break
        off += n
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleDriveSyncAudioGateTest {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @After fun stopServer() {
        serverSocket?.close()
        serverThread?.interrupt()
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    /** Fake proxy endpoint: answers [behavior](status) per request, recording bodies. */
    private class FakeProxy(val port: Int, val respond: (attempt: Int, body: String) -> Int) : Thread() {
        val bodies = mutableListOf<String>()
        val socket = ServerSocket(port, 16, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        @Volatile var running = true
        override fun run() {
            while (running) {
                try {
                    socket.accept().use { client ->
                        val headers = client.getInputStream().readTextUntilBlankLine()
                        val contentLength = Regex("(?i)content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
                        val body = if (contentLength > 0) ByteArray(contentLength).also { it.takeInto(client.getInputStream()) }.toString(Charsets.UTF_8) else ""
                        val attempt = bodies.size + 1
                        bodies.add(body)
                        val code = respond(attempt, body)
                        client.getOutputStream().write(("HTTP/1.1 $code OK\r\nContent-Length: 5\r\n\r\nok!").toByteArray())
                    }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
    }

    private fun startProxy(port: Int, respond: (attempt: Int, body: String) -> Int): FakeProxy =
        FakeProxy(port, respond).also { it.isDaemon = true; it.start(); this.serverThread = it }

    /** Seeds one unsynced transcription via the real storage API; returns its file. */
    private fun seedTranscription(context: Context, withAudio: Boolean): File {
        val file = TranscriptionStorage.save(
            context,
            raw = "hello world",
            cleaned = "hello world",
            durationMs = 1500L,
            audioBytes = if (withAudio) byteArrayOf(1, 2, 3, 4, 5) else null,
            privacySensitive = false
        )
        assertNotNull("storage save must produce a transcription file", file)
        return file!!
    }

    /** The sync-sidecar marker name used by TranscriptionStorage. */
    private fun markerFor(file: File): File = File(file.parent, file.name + ".synced")

    private fun configuredPrefs(port: Int): android.content.SharedPreferences =
        context().getSharedPreferences("f25-audio-gate-test", Context.MODE_PRIVATE).apply {
            edit {
                putString("drive_app_key", "key")
                putString("drive_backend_endpoint", "http://127.0.0.1:$port/save-recommendation")
            }
        }

    @Test
    fun missingReferencedAudioIsNotUploadedAndStaysUnsynced() {
        val proxy = startProxy(19081) { _, _ -> 200 }

        val record = seedTranscription(context(), withAudio = true)
        // delete the referenced audio to simulate a lost/corrupt local copy
        val audioFile = File(record.parent, record.name.replace(".json", ".wav"))
        assertTrue(audioFile.isFile)
        assertTrue(audioFile.delete())

        val result = GoogleDriveSync.syncToDrive(context(), configuredPrefs(19081))

        assertEquals("missing audio must fail the record, not silently upload text", 1, result.failed)
        assertEquals(0, result.uploaded)
        assertEquals("no HTTP request may be sent for a record whose audio is missing", 0, proxy.bodies.size)
        assertFalse("missing audio must NOT mark the record synced", markerFor(record).exists())

        // once the audio file returns, the very next sync retries BOTH text
        // and audio, then marks synced
        audioFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val retry = GoogleDriveSync.syncToDrive(context(), configuredPrefs(19081))
        assertEquals(1, retry.uploaded)
        assertTrue(markerFor(record).exists())
        assertEquals(1, proxy.bodies.size)
    }

    @Test
    fun failedUploadRetriesWithStableNameAndWithoutDuplicates() {
        val proxy = startProxy(19082) { attempt, _ -> if (attempt == 1) 500 else 200 }

        val record = seedTranscription(context(), withAudio = true)

        assertEquals(1, GoogleDriveSync.syncToDrive(context(), configuredPrefs(19082)).failed)
        assertFalse(markerFor(record).exists())

        assertEquals(1, GoogleDriveSync.syncToDrive(context(), configuredPrefs(19082)).uploaded)
        assertTrue(markerFor(record).exists())

        // both attempts carry audio, and each retry posts the SAME stable
        // fileName (server upserts by name — no duplicate text rows)
        assertEquals(2, proxy.bodies.size)
        val first = org.json.JSONObject(proxy.bodies[0])
        val second = org.json.JSONObject(proxy.bodies[1])
        assertEquals(first.getString("fileName"), second.getString("fileName"))
        assertEquals(second.getString("fileName"), record.name)
        assertNotNull(second.optString("audioBase64", null))
        // a third sync has nothing to do — the record is already synced
        assertEquals(0, GoogleDriveSync.syncToDrive(context(), configuredPrefs(19082)).total)
        assertEquals("already-synced record must not re-upload", 2, proxy.bodies.size)
    }

    @Test
    fun successfulUploadCarriesAudioAndMarksSynced() {
        val proxy = startProxy(19083) { _, _ -> 200 }

        val record = seedTranscription(context(), withAudio = true)
        val result = GoogleDriveSync.syncToDrive(context(), configuredPrefs(19083))

        assertEquals(1, result.uploaded)
        assertTrue(markerFor(record).exists())
        assertEquals(1, proxy.bodies.size)
        val payload = org.json.JSONObject(proxy.bodies[0])
        assertEquals(record.name, payload.getString("fileName"))
        assertTrue("audio must ride along in the upload payload", payload.has("audioBase64"))
    }
}
