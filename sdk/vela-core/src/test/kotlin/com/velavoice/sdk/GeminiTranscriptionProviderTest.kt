package com.velavoice.sdk

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class GeminiTranscriptionProviderTest {

    private fun createMockClient(
        responseCode: Int = 200,
        responseBody: String = "",
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

    private fun sampleGeminiResponse(text: String): String {
        return """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  {
                    "text": "$text"
                  }
                ],
                "role": "model"
              },
              "finishReason": "STOP"
            }
          ]
        }
        """.trimIndent()
    }

    @Test
    fun `pcmToWav creates valid 44 byte WAV header for 16kHz mono 16bit PCM`() {
        val provider = GeminiTranscriptionProvider()
        val dummyPcm = ByteArray(320) { 0 } // 10ms of 16kHz 16-bit mono audio
        val wav = provider.pcmToWav(dummyPcm)

        assertEquals(44 + 320, wav.size)
        // RIFF header
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        // WAVE identifier
        assertEquals('W'.code.toByte(), wav[8])
        assertEquals('A'.code.toByte(), wav[9])
        assertEquals('V'.code.toByte(), wav[10])
        assertEquals('E'.code.toByte(), wav[11])
        // fmt chunk
        assertEquals('f'.code.toByte(), wav[12])
        assertEquals('m'.code.toByte(), wav[13])
        assertEquals('t'.code.toByte(), wav[14])
        assertEquals(' '.code.toByte(), wav[15])
        // AudioFormat = 1 (PCM)
        assertEquals(1.toByte(), wav[20])
        // NumChannels = 1 (Mono)
        assertEquals(1.toByte(), wav[22])
        // SampleRate = 16000 (0x3E80 -> little-endian 0x80, 0x3E, 0x00, 0x00)
        assertEquals(0x80.toByte(), wav[24])
        assertEquals(0x3E.toByte(), wav[25])
        // ByteRate = 32000 (0x7D00 -> little-endian 0x00, 0x7D, 0x00, 0x00)
        assertEquals(0x00.toByte(), wav[28])
        assertEquals(0x7D.toByte(), wav[29])
        // BlockAlign = 2
        assertEquals(2.toByte(), wav[32])
        // BitsPerSample = 16
        assertEquals(16.toByte(), wav[34])
        // data chunk
        assertEquals('d'.code.toByte(), wav[36])
        assertEquals('a'.code.toByte(), wav[37])
        assertEquals('t'.code.toByte(), wav[38])
        assertEquals('a'.code.toByte(), wav[39])
    }

    @Test
    fun `encodeBase64 produces valid non-empty base64 string`() {
        val provider = GeminiTranscriptionProvider()
        val data = "test-audio-content".toByteArray(Charsets.UTF_8)
        val encoded = provider.encodeBase64(data)
        assertTrue(encoded.isNotBlank())
        val decoded = java.util.Base64.getDecoder().decode(encoded as String)
        assertEquals("test-audio-content", String(decoded, Charsets.UTF_8))
    }

    @Test
    fun `buildRequestBodyJson conforms to Gemini 2_0 Flash REST contract`() {
        val provider = GeminiTranscriptionProvider()
        val jsonStr = provider.buildRequestBodyJson("DUMMY_BASE64")
        val json = JSONObject(jsonStr as String)

        // system_instruction
        val sysInstruction = json.getJSONObject("system_instruction")
        val sysParts = sysInstruction.getJSONArray("parts")
        assertEquals(
            "You are a professional verbatim speech-to-text transcriber. Output ONLY the exact spoken transcription of the audio. Do not add notes, explanations, commentary, or conversational replies.",
            sysParts.getJSONObject(0).getString("text")
        )

        // contents
        val contents = json.getJSONArray("contents")
        assertEquals(1, contents.length())
        val firstContent = contents.getJSONObject(0)
        assertEquals("user", firstContent.getString("role"))
        val parts = firstContent.getJSONArray("parts")
        assertEquals(2, parts.length())

        val inlineDataPart = parts.getJSONObject(0).getJSONObject("inline_data")
        assertEquals("audio/wav", inlineDataPart.getString("mime_type"))
        assertEquals("DUMMY_BASE64", inlineDataPart.getString("data"))

        val textPart = parts.getJSONObject(1)
        assertEquals("Transcribe this audio verbatim.", textPart.getString("text"))

        // generationConfig
        val genConfig = json.getJSONObject("generationConfig")
        assertEquals(0.0, genConfig.getDouble("temperature"), 0.0001)
    }

    @Test
    fun `parseGeminiResponse cleans markdown fences and whitespace`() {
        val provider = GeminiTranscriptionProvider()

        // Plain text
        assertEquals(
            "Hello world",
            provider.parseGeminiResponse(sampleGeminiResponse("Hello world"))
        )

        // Markdown code block with language
        assertEquals(
            "Speech text inside code block",
            provider.parseGeminiResponse(sampleGeminiResponse("```markdown\nSpeech text inside code block\n```"))
        )

        // Raw fences
        assertEquals(
            "Raw fences transcript",
            provider.parseGeminiResponse(sampleGeminiResponse("```\nRaw fences transcript\n```"))
        )

        // Surrounding whitespace
        assertEquals(
            "Trimmed speech text",
            provider.parseGeminiResponse(sampleGeminiResponse("   Trimmed speech text   \n"))
        )

        // Empty responses
        assertEquals("", provider.parseGeminiResponse("{}"))
        assertEquals("", provider.parseGeminiResponse(""))
        assertEquals("", provider.parseGeminiResponse("""{"candidates":[]}"""))
    }

    @Test
    fun `transcribe executes successful 200 OK request and returns transcript`() {
        var recordedUrl = ""
        val mockClient = createMockClient(
            responseCode = 200,
            responseBody = sampleGeminiResponse("Testing 1 2 3"),
            onRequest = { req ->
                recordedUrl = req.url.toString()
            }
        )

        val provider = GeminiTranscriptionProvider(client = mockClient)
        val pcm = ByteArray(320) { 0 }
        val result = provider.transcribe(pcm, "my-secret-key")

        assertEquals("Testing 1 2 3", result)
        assertTrue(recordedUrl.contains("key=my-secret-key"))
        assertTrue(recordedUrl.contains("gemini-3.5-transcribe:generateContent"))
    }

    @Test
    fun `transcribe throws descriptive VelaException_Network on 429 rate limit`() {
        val mockClient = createMockClient(
            responseCode = 429,
            responseBody = """{"error": {"message": "Resource exhausted"}}"""
        )

        val provider = GeminiTranscriptionProvider(client = mockClient)
        val pcm = ByteArray(160) { 0 }

        try {
            provider.transcribe(pcm, "valid-key")
            fail("Expected VelaException.Network on 429")
        } catch (e: VelaException.Network) {
            assertTrue(e.message?.contains("Gemini rate limit exceeded. Free tier limit is 15 requests/minute. Please wait a moment.") == true)
        }
    }

    @Test
    fun `transcribe throws descriptive VelaException_Network on 400 and 403 invalid key`() {
        val mock400 = createMockClient(
            responseCode = 400,
            responseBody = """{"error": {"message": "API key not valid"}}"""
        )
        val provider400 = GeminiTranscriptionProvider(client = mock400)
        try {
            provider400.transcribe(ByteArray(160), "bad-key")
            fail("Expected VelaException.Network on 400")
        } catch (e: VelaException.Network) {
            assertTrue(e.message?.contains("Invalid Gemini API key or unauthorized access.") == true)
        }

        val mock403 = createMockClient(
            responseCode = 403,
            responseBody = """{"error": {"message": "Permission denied"}}"""
        )
        val provider403 = GeminiTranscriptionProvider(client = mock403)
        try {
            provider403.transcribe(ByteArray(160), "unauthorized-key")
            fail("Expected VelaException.Network on 403")
        } catch (e: VelaException.Network) {
            assertTrue(e.message?.contains("Invalid Gemini API key or unauthorized access.") == true)
        }
    }

    @Test
    fun `transcribe throws descriptive VelaException_Network on blank API key`() {
        val provider = GeminiTranscriptionProvider()
        try {
            provider.transcribe(ByteArray(160), "   ")
            fail("Expected VelaException.Network on blank key")
        } catch (e: VelaException.Network) {
            assertTrue(e.message?.contains("Invalid Gemini API key or unauthorized access.") == true)
        }
    }

    @Test
    fun `transcribe handles network timeout gracefully without crashing`() {
        val mockClient = createMockClient(shouldThrowIoException = true)
        val provider = GeminiTranscriptionProvider(client = mockClient)

        try {
            provider.transcribe(ByteArray(160), "test-key")
            fail("Expected VelaException.Network on timeout")
        } catch (e: VelaException.Network) {
            assertTrue(e.message?.contains("Network error communicating with Gemini") == true)
        }
    }

    @Test
    fun `StreamingTranscriber lifecycle emits callbacks correctly`() {
        val mockClient = createMockClient(
            responseCode = 200,
            responseBody = sampleGeminiResponse("Streaming completed successfully")
        )
        val provider = GeminiTranscriptionProvider(client = mockClient)

        val latch = CountDownLatch(1)
        var finalTranscript = ""
        var revisionMarker: RevisionMarker? = null
        var amplitudeReceived = false

        provider.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {
                revisionMarker = marker
            }

            override fun onFinal(text: String) {
                finalTranscript = text
                latch.countDown()
            }

            override fun onError(error: VelaException) {
                latch.countDown()
            }

            override fun onAmplitude(normalized: Float) {
                amplitudeReceived = true
            }
        })

        provider.start(StreamConfig(apiKey = "stream-api-key"))
        provider.emit(ByteArray(320) { 10 })
        provider.stop()

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Expected streaming transcription to complete within timeout", completed)
        assertEquals("Streaming completed successfully", finalTranscript)
        assertEquals("Streaming completed successfully", revisionMarker?.text)
        assertEquals("commit", revisionMarker?.type)
        assertTrue("Amplitude should have been received", amplitudeReceived)

        provider.release()
    }

    @Test
    fun `StreamingTranscriber start with blank API key triggers onError`() {
        val provider = GeminiTranscriptionProvider()
        var receivedError: VelaException? = null

        provider.setCallback(object : StreamingTranscriptionCallback {
            override fun onRevisionMarker(marker: RevisionMarker) {}
            override fun onFinal(text: String) {}
            override fun onError(error: VelaException) {
                receivedError = error
            }
            override fun onAmplitude(normalized: Float) {}
        })

        provider.start(StreamConfig(apiKey = ""))
        assertTrue("Should have reported error for blank API key", receivedError is VelaException.Network)
    }

    @Test
    fun `model parameter configures endpoint to gemini-3_5-transcribe and auto-normalizes bare model names`() {
        var recordedUrl = ""
        val mockClient = createMockClient(
            responseCode = 200,
            responseBody = sampleGeminiResponse("Model override successful"),
            onRequest = { req -> recordedUrl = req.url.toString() }
        )

        val customProvider = GeminiTranscriptionProvider(client = mockClient, rawModel = "gemini-2.0-flash")
        val result1 = customProvider.transcribe(ByteArray(320) { 0 }, "test-key")
        assertEquals("Model override successful", result1)
        assertTrue(recordedUrl.contains("gemini-2.0-flash:generateContent"))

        // Bare 3.5 model must auto-normalize to gemini-3.5-transcribe
        val normalizedProvider = GeminiTranscriptionProvider(client = mockClient, rawModel = "gemini-3.5")
        val result2 = normalizedProvider.transcribe(ByteArray(320) { 0 }, "test-key")
        assertEquals("Model override successful", result2)
        assertTrue(recordedUrl.contains("gemini-3.5-transcribe:generateContent"))
    }
}
