// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ticket 804 (map #290): multi-file bundle download.
 *
 * TDD red first. Serves bundle files from an in-process JDK HTTP server so the
 * real HttpURLConnection path is exercised without external network access.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloadHelperBundleTest {

    private lateinit var context: Context
    private lateinit var server: NanoHttpdTestServer
    private val serverPort: Int get() = server.port

    private val encoderBytes = "encoder|alpha".toByteArray(Charsets.UTF_8)
    private val decoderBytes = "decoder|beta".toByteArray(Charsets.UTF_8)
    private val encoderSha by lazy { sha256Hex(encoderBytes) }
    private val decoderSha by lazy { sha256Hex(decoderBytes) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Path → body mapping must be set up BEFORE start(): the server starts
        // accepting as soon as the listener socket is open.
        server = NanoHttpdTestServer(
            mapOf(
                "/good/encoder.onnx" to encoderBytes,
                "/good/tokenizer.json" to decoderBytes,
                "/corrupt/decoder.onnx" to "decoder|CORRUPTED".toByteArray(Charsets.UTF_8)
            )
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `downloadModelBundle fetches all files and leaves a verified bundle`() = runBlocking {
        val parts = listOf(
            BundlePart(
                name = "encoder.onnx",
                url = "http://127.0.0.1:$serverPort/good/encoder.onnx",
                expectedSha256 = encoderSha
            ),
            BundlePart(
                name = "tokenizer.json",
                url = "http://127.0.0.1:$serverPort/good/tokenizer.json",
                expectedSha256 = decoderSha
            )
        )

        val ok = ModelDownloadHelper.downloadModelBundle(
            context = context,
            dirName = "test-bundle-model",
            parts = parts
        )

        assertTrue(ok, "well-formed two-file bundle download must succeed")
        val bundleDir = File(context.filesDir, "vela_models/test-bundle-model")
        assertTrue(bundleDir.isDirectory, "bundle must live in its per-model directory")
        assertTrue(File(bundleDir, "encoder.onnx").isFile)
        assertTrue(File(bundleDir, "tokenizer.json").isFile)
        assertFalse(bundleDir.listFiles()!!.any { it.name.endsWith(".part") }, "no .part leftovers after success")
        cleanUp("test-bundle-model")
    }

    @Test
    fun `downloadModelBundle fails closed and cleans the directory on hash mismatch`() = runBlocking {
        val parts = listOf(
            BundlePart(
                name = "encoder.onnx",
                url = "http://127.0.0.1:$serverPort/good/encoder.onnx",
                expectedSha256 = encoderSha
            ),
            BundlePart(
                name = "decoder.onnx",
                url = "http://127.0.0.1:$serverPort/corrupt/decoder.onnx",
                expectedSha256 = decoderSha // served bytes do NOT match
            )
        )

        val ok = ModelDownloadHelper.downloadModelBundle(
            context = context,
            dirName = "test-bundle-corrupt",
            parts = parts
        )

        assertFalse(ok, "corrupted bundle download must fail")
        val bundleDir = File(context.filesDir, "vela_models/test-bundle-corrupt")
        assertFalse(
            bundleDir.exists() && bundleDir.listFiles()!!.isNotEmpty(),
            "failed bundle must not leave a partially-verified directory behind"
        )
        cleanUp("test-bundle-corrupt")
    }

    // ── CodeRabbit round-1 findings ─────────────────────────────

    @Test
    fun `downloadModelBundle rejects traversal dirName and escapes the models dir`() = runBlocking {
        val parts = listOf(
            BundlePart(
                name = "encoder.onnx",
                url = "http://127.0.0.1:$serverPort/good/encoder.onnx",
                expectedSha256 = encoderSha
            )
        )

        val ok = ModelDownloadHelper.downloadModelBundle(
            context = context,
            dirName = "../escape",
            parts = parts
        )
        assertFalse(ok, "traversal dirName must fail closed")
        val escaped = File(context.filesDir.parentFile, "escape")
        assertFalse(escaped.exists() && escaped.listFiles()!!.isNotEmpty(), "traversal dirName must not write outside the models dir")
        if (escaped.exists()) escaped.deleteRecursively()
    }

    @Test
    fun `downloadModelBundle rejects traversal part names`() = runBlocking {
        val parts = listOf(
            BundlePart(
                name = "../outside.onnx",
                url = "http://127.0.0.1:$serverPort/good/encoder.onnx",
                expectedSha256 = encoderSha
            )
        )

        val ok = ModelDownloadHelper.downloadModelBundle(
            context = context,
            dirName = "test-bundle-traversal-part",
            parts = parts
        )
        assertFalse(ok, "traversal part name must fail closed")
        val escaped = File(context.filesDir, "vela_models/outside.onnx")
        assertFalse(escaped.exists(), "traversal part name must not write outside the bundle dir")
        cleanUp("test-bundle-traversal-part")
    }

    @Test
    fun `downloadModelBundle reports aggregated progress for every file`() = runBlocking {
        val parts = listOf(
            BundlePart(
                "encoder.onnx",
                "http://127.0.0.1:$serverPort/good/encoder.onnx",
                encoderSha
            ),
            BundlePart(
                "tokenizer.json",
                "http://127.0.0.1:$serverPort/good/tokenizer.json",
                decoderSha
            )
        )
        val reported = mutableListOf<Float>()
        ModelDownloadHelper.downloadModelBundle(
            context = context,
            dirName = "test-bundle-progress",
            parts = parts,
            onProgress = { reported.add(it) }
        )
        assertTrue(
            reported.isNotEmpty() && reported.last() == 1.0f,
            "aggregated progress must reach 1.0 for every file"
        )
        assertTrue(reported.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(reported.all { it in 0f..1f }, "progress must stay within 0..1 bounds")
        cleanUp("test-bundle-progress")
    }

    private fun cleanUp(dirName: String) {
        File(context.filesDir, "vela_models/$dirName").deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Minimal fixed-content HTTP server for bundle-download tests. Serves bytes
 * from a path→body map over a loopback [ServerSocket]; one connection per
 * request, matching HttpURLConnection's typical behavior closely enough.
 */
class NanoHttpdTestServer(private val routes: Map<String, ByteArray>) {
    val port: Int
    private val executor = Executors.newSingleThreadExecutor()
    private val serverSocket: ServerSocket = ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress())
    @Volatile private var running = true

    init {
        port = serverSocket.localPort
    }

    fun start() {
        executor.submit {
            while (running) {
                val socket: Socket = try {
                    serverSocket.accept()
                } catch (_: java.io.IOException) {
                    break // stop() closed the listener
                }
                handle(socket)
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                val path = requestLine.split(" ").getOrNull(1) ?: return
                // drain headers
                while (reader.readLine()?.isNotEmpty() == true) { /* keep reading */ }
                val body = routes[path]
                if (body != null) {
                    s.getOutputStream().apply {
                        write("HTTP/1.0 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                } else {
                    s.getOutputStream().apply {
                        write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                }
            } catch (_: Exception) {
                // client aborted; nothing to report in tests
            }
        }
    }

    fun stop() {
        running = false
        executor.shutdownNow()
        try {
            serverSocket.close()
        } catch (_: java.io.IOException) {
            // already closed
        }
    }
}
