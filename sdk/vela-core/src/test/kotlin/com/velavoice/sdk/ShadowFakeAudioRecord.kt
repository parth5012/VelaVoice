package com.velavoice.sdk

import android.media.AudioRecord
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Minimal AudioRecord double for unit tests: Robolectric's android-all jar has no
 * shadow for AudioRecord, so the real constructor rejects the JVM's invalid
 * min-buffer-size result ("Invalid audio buffer size"). This fake makes the
 * capture lifecycle deterministic: STATE_INITIALIZED, read() returns 0 (no data).
 *
 * Test hooks:
 * - [readResult]: how many samples read() reports (0 = no data, >0 = writes bytes
 *   into the recorder's buffer, looped to satisfy the requested size).
 * - [blockReads]: read() parks on [readLatch] to simulate a capture thread stuck
 *   in a blocking read while cancel()/stop() runs on the caller thread.
 */
@Implements(AudioRecord::class)
class ShadowFakeAudioRecord {

    companion object {
        /** Number of samples read() reports per call (0 keeps buffers empty). */
        @JvmStatic
        @Volatile
        var readResult: Int = 0

        /** When true, read() parks on [readLatch] before returning [readResult]. */
        @JvmStatic
        @Volatile
        var blockReads: Boolean = false

        /** Counted down on entry to a blocking read() so tests can wait deterministically. */
        @JvmStatic
        @Volatile
        var readEntered: CountDownLatch = CountDownLatch(1)

        @JvmStatic
        @Volatile
        var readLatch: CountDownLatch = CountDownLatch(1)

        /** Unblock any parked read() calls (call from @After so threads can exit). */
        @JvmStatic
        fun releaseBlockedReads() {
            readLatch.countDown()
        }

        /** Restore defaults between tests. */
        @JvmStatic
        fun resetHooks() {
            readResult = 0
            blockReads = false
            readLatch = CountDownLatch(1)
            readEntered = CountDownLatch(1)
        }

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
    fun read(audioData: ShortArray?, offsetInShorts: Int, sizeInShorts: Int): Int {
        if (blockReads) {
            readEntered.countDown()
            try {
                readLatch.await(10, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val n = readResult
        if (n > 0 && audioData != null) {
            val count = minOf(n, sizeInShorts - offsetInShorts)
            for (i in 0 until count) {
                audioData[offsetInShorts + i] = 0
            }
            return count
        }
        return n
    }

    @Implementation
    fun stop() {
    }

    @Implementation
    fun release() {
    }
}
