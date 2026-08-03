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
}
