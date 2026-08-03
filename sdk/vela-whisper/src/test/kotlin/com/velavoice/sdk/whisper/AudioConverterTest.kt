package com.velavoice.sdk.whisper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioConverterTest {

    @Test
    fun `convertPcmToFloat empty input returns empty array`() {
        val result = AudioConverter.convertPcmToFloat(ByteArray(0))
        assertEquals(0, result.size)
    }

    @Test
    fun `convertPcmToFloat odd byte is truncated`() {
        // 3 bytes -> 1 short (2 bytes), last byte ignored
        val bytes = byteArrayOf(0x00, 0x40, 0xFF.toByte())
        val result = AudioConverter.convertPcmToFloat(bytes)
        assertEquals(1, result.size)
    }

    @Test
    fun `convertPcmToFloat positive max short maps to 1`() {
        // 0x7FFF = 32767 -> 32767 / 32768.0f ~ 0.99997
        val bytes = byteArrayOf(0xFF.toByte(), 0x7F)
        val result = AudioConverter.convertPcmToFloat(bytes)
        assertEquals(1, result.size)
        assertEquals(32767f / 32768f, result[0], 0.0001f)
    }

    @Test
    fun `convertPcmToFloat negative min short maps to -1`() {
        // 0x8000 = -32768 -> -32768 / 32768.0f = -1.0
        val bytes = byteArrayOf(0x00, 0x80.toByte())
        val result = AudioConverter.convertPcmToFloat(bytes)
        assertEquals(1, result.size)
        assertEquals(-1.0f, result[0], 0.0001f)
    }

    @Test
    fun `convertPcmToFloat zero short maps to 0`() {
        val bytes = byteArrayOf(0x00, 0x00)
        val result = AudioConverter.convertPcmToFloat(bytes)
        assertEquals(1, result.size)
        assertEquals(0.0f, result[0], 0.0001f)
    }

    @Test
    fun `convertPcmToFloat multi-sample preserves order`() {
        // Sample 1: 0x0000 (0), Sample 2: 0x0080 (32768 -> -32768 in signed), Sample 3: 0x007F (32512)
        val bytes = byteArrayOf(
            0x00, 0x00,          // sample 1: 0
            0x00, 0x80.toByte(), // sample 2: -32768 signed -> -1.0
            0x00, 0x7F           // sample 3: 32512 -> ~0.992
        )
        val result = AudioConverter.convertPcmToFloat(bytes)
        assertEquals(3, result.size)
        assertEquals(0.0f, result[0], 0.0001f)
        assertEquals(-1.0f, result[1], 0.0001f)
        assertEquals(32512f / 32768f, result[2], 0.0001f)
    }
}
