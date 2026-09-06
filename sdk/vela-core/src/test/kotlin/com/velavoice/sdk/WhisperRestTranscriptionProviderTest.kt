package com.velavoice.sdk

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class WhisperRestTranscriptionProviderTest {

    private fun createMockClient(
        responseCode: Int = 200,
        responseBody: String = """{"text": "Hello world transcription"}""",
        onRequest: ((okhttp3.Request) -> Unit)? = null,
        shouldThrowIoException: Boolean = false
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                onRequest?.invoke(request)
                if (shouldThrowIoException) {
                    throw IOException("Simulated network timeout")
                }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(if (responseCode == 200) "OK" else "Error")
                    .body(responseBody.toResponseBody("application/json; charset=utf-8".toMediaType()))
                    .build()
            })
            .build()
    }

    @Test
    fun `pcmToWav creates valid 44-byte WAV header for 16kHz mono 16-bit PCM`() {
        val provider = WhisperRestTranscriptionProvider()
        val dummyPcm = ByteArray(320) { 0 }
        val wav = provider.pcmToWav(dummyPcm)

        assertEquals(44 + 320, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
    }

    @Test
    fun `parseResponse extracts text field accurately`() {
        val provider = WhisperRestTranscriptionProvider()
        assertEquals(
            "This is a test transcript",
            provider.parseResponse("""{"text": "This is a test transcript"}""")
        )
        assertEquals("", provider.parseResponse("{}"))
        assertEquals("", provider.parseResponse(""))
    }

    @Test
    fun `transcribe sends multipart form request with Bearer authorization`() {
        var recordedAuth = ""
        var recordedUrl = ""
        val mockClient = createMockClient(
            responseCode = 200,
            responseBody = """{"text": "Groq transcription result"}""",
            onRequest = { req ->
                recordedAuth = req.header("Authorization").orEmpty()
                recordedUrl = req.url.toString()
            }
        )

        val provider = WhisperRestTranscriptionProvider(
            client = mockClient,
            endpoint = "https://api.groq.com/openai/v1/audio/transcriptions",
            model = "whisper-large-v3"
        )
        val result = provider.transcribe(ByteArray(320) { 0 }, "gsk_test_key_123")

        assertEquals("Groq transcription result", result)
        assertEquals("Bearer gsk_test_key_123", recordedAuth)
        assertTrue(recordedUrl.contains("api.groq.com/openai/v1/audio/transcriptions"))
    }

    @Test
    fun `transcribe handles HTTP 401 invalid key and 429 quota exceeded`() {
        val mockClient401 = createMockClient(responseCode = 401, responseBody = """{"error": "invalid_api_key"}""")
        val provider401 = WhisperRestTranscriptionProvider(client = mockClient401)
        try {
            provider401.transcribe(ByteArray(160) { 0 }, "bad-key")
            fail("Expected VelaException.Network on 401")
        } catch (e: VelaException.Network) {
            assertTrue(e.message?.contains("Invalid API key") == true)
        }

        val mockClient429 = createMockClient(responseCode = 429, responseBody = """{"error": "rate_limit"}""")
        val provider429 = WhisperRestTranscriptionProvider(client = mockClient429)
        try {
            provider429.transcribe(ByteArray(160) { 0 }, "test-key")
            fail("Expected VelaException.Network on 429")
        } catch (e: VelaException.Network) {
            assertTrue(e.message?.contains("Rate limit") == true)
        }
    }
}
