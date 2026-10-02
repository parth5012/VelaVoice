package com.velavoice.sdk

import android.media.AudioRecord
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Minimal AudioRecord double for unit tests: Robolectric's android-all jar has no
 * shadow for AudioRecord, so the real constructor rejects the JVM's invalid
 * min-buffer-size result ("Invalid audio buffer size"). This fake makes the
 * capture lifecycle deterministic: STATE_INITIALIZED, read() returns 0 (no data).
 */
@Implements(AudioRecord::class)
class ShadowFakeAudioRecord {

    companion object {
        @JvmStatic
        @Implementation
        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 4096
    }

    @Implementation
    fun __constructor__(
        audioSource: Int,
        sampleRateInHz: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSizeInBytes: Int
    ) {
    }

    @Implementation
    fun getState(): Int = AudioRecord.STATE_INITIALIZED

    @Implementation
    fun startRecording() {
    }

    @Implementation
    fun read(audioData: ShortArray?, offsetInShorts: Int, sizeInShorts: Int): Int = 0

    @Implementation
    fun stop() {
    }

    @Implementation
    fun release() {
    }
}
