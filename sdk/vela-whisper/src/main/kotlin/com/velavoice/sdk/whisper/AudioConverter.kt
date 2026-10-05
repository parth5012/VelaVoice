package com.velavoice.sdk.whisper

object AudioConverter {
    fun convertPcmToFloat(audioBytes: ByteArray): FloatArray {
        val shortsCount = audioBytes.size / 2
        val floatBuffer = FloatArray(shortsCount)
        for (i in 0 until shortsCount) {
            val low = audioBytes[2 * i].toInt() and 0xff
            val high = audioBytes[2 * i + 1].toInt()
            val sample = ((high shl 8) or low).toShort()
            floatBuffer[i] = sample.toFloat() / 32768.0f
        }
        return floatBuffer
    }

    /** Packs the first [read] shorts of [buffer] into little-endian PCM bytes. */
    fun shortsToBytes(buffer: ShortArray, read: Int): ByteArray {
        val out = ByteArray(read * 2)
        for (i in 0 until read) {
            val shortVal = buffer[i]
            out[i * 2] = (shortVal.toInt() and 0xff).toByte()
            out[i * 2 + 1] = ((shortVal.toInt() shr 8) and 0xff).toByte()
        }
        return out
    }

    /** RMS amplitude of the first [read] shorts, normalized to 0..1. */
    fun rmsNormalized(buffer: ShortArray, read: Int): Float {
        if (read <= 0) return 0f
        var sum = 0.0
        for (i in 0 until read) {
            val shortVal = buffer[i]
            sum += shortVal * shortVal
        }
        return (kotlin.math.sqrt(sum / read) / 32768.0).toFloat()
    }

    /** True when [audioData] PCM bytes are below the silence [threshold]. */
    fun isSilent(audioData: ByteArray, threshold: Float): Boolean {
        val samples = audioData.size / 2
        if (samples == 0) return true
        var sumSquares = 0.0
        for (i in 0 until samples) {
            val low = audioData[i * 2].toInt() and 0xff
            val high = audioData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return kotlin.math.sqrt(sumSquares / samples) / 32768.0 < threshold
    }

    /** Wraps raw 16-bit PCM bytes in a 44-byte WAV header. */
    fun pcmToWav(
        pcmAudio: ByteArray,
        sampleRate: Int = 16000,
        channels: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmAudio.size
        val fileSize = 36 + dataSize

        val header = ByteArray(44)
        fun writeString(offset: Int, text: String) {
            for (i in text.indices) header[offset + i] = text[i].code.toByte()
        }
        fun writeIntLE(offset: Int, value: Int) {
            for (i in 0 until 4) header[offset + i] = ((value shr (8 * i)) and 0xff).toByte()
        }
        fun writeShortLE(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = ((value shr 8) and 0xff).toByte()
        }

        writeString(0, "RIFF")
        writeIntLE(4, fileSize)
        writeString(8, "WAVE")
        writeString(12, "fmt ")
        writeIntLE(16, 16)
        writeShortLE(20, 1)
        writeShortLE(22, channels)
        writeIntLE(24, sampleRate)
        writeIntLE(28, byteRate)
        writeShortLE(32, blockAlign)
        writeShortLE(34, bitsPerSample)
        writeString(36, "data")
        writeIntLE(40, dataSize)

        return header + pcmAudio
    }
}
