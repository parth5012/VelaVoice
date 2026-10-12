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

        /** Number of AudioRecord instances constructed (leak detection, ticket #98). */
        @JvmStatic
        @Volatile
        var constructorCount: Int = 0

        /** Number of AudioRecord.release() calls (every constructed instance must be released exactly once). */
        @JvmStatic
        @Volatile
        var releaseCount: Int = 0

        /** Value returned by getState(); STATE_UNINITIALIZED simulates mic init failure (ticket #99). */
        @JvmStatic
        @Volatile
        var stateResult: Int = AudioRecord.STATE_INITIALIZED

        /** When true, startRecording() throws IllegalStateException (ticket #99 error path). */
        @JvmStatic
        @Volatile
        var throwOnStartRecording: Boolean = false

        /** Counted down on entry to a blocking read() so tests can wait deterministically. */
        @JvmStatic
        @Volatile
        var readEntered: CountDownLatch = CountDownLatch(1)

        @JvmStatic
        @Volatile
        var readLatch: CountDownLatch = CountDownLatch(1)

        /**
         * When set to a constructed-instance index, only reads on that AudioRecord
         * park on [readLatch] (ticket #102: park the FIRST session's capture
         * thread while a second session installs a replacement recorder).
         */
        @JvmStatic
        @Volatile
        var blockReadsOnInstance: Int = -1

        /**
         * Per constructed-instance identity set of threads that called read()
         * (ticket #102: detects an abandoned capture thread reading the
         * replacement session's AudioRecord — two readers on one recorder).
         */
        @JvmStatic
        private val readersByInstance = HashMap<Int, MutableSet<Thread>>()

        /** Distinct threads that ever called read() on the AudioRecord built at [instanceIndex]. */
        @JvmStatic
        fun threadsThatRead(instanceIndex: Int): Set<Thread> = synchronized(readersByInstance) {
            readersByInstance[instanceIndex]?.let { HashSet(it) } ?: emptySet()
        }

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
            blockReadsOnInstance = -1
            readLatch = CountDownLatch(1)
            readEntered = CountDownLatch(1)
            constructorCount = 0
            releaseCount = 0
            stateResult = AudioRecord.STATE_INITIALIZED
            throwOnStartRecording = false
            synchronized(readersByInstance) { readersByInstance.clear() }
        }

        @JvmStatic
        @Implementation
        fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = 4096
    }

    /** 0-based index of the AudioRecord this shadow backs (constructorCount at construction). */
    private var myInstanceIndex: Int = -1

    @Implementation
    fun __constructor__(
        audioSource: Int,
        sampleRateInHz: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSizeInBytes: Int
    ) {
        myInstanceIndex = constructorCount
        constructorCount++
    }

    @Implementation
    fun getState(): Int = stateResult

    @Implementation
    fun startRecording() {
        if (throwOnStartRecording) {
            throw IllegalStateException("startRecording failed (test hook)")
        }
    }

    @Implementation
    fun read(audioData: ShortArray?, offsetInShorts: Int, sizeInShorts: Int): Int {
        if (myInstanceIndex >= 0) {
            synchronized(readersByInstance) {
                readersByInstance.getOrPut(myInstanceIndex) { HashSet() }
                    .add(Thread.currentThread())
            }
        }
        if (blockReads || myInstanceIndex == blockReadsOnInstance) {
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
        releaseCount++
    }
}
