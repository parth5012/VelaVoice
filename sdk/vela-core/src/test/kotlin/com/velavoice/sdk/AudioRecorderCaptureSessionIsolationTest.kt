package com.velavoice.sdk

import com.velavoice.sdk.whisper.WhisperEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Ticket #102 — capture-thread bleed: a cancelled session's capture thread
 * must never keep reading the replacement session's AudioRecord (two readers
 * on one recorder → interleaved/corrupted PCM fed to the transcriber).
 *
 * The bleed happens when the SAME AudioRecorder instance is restarted after
 * cancel()/stop() while the previous capture thread was still parked in a
 * blocking read(): start() installs a new AudioRecord and flips isRecording
 * back to true, so the orphaned thread's next loop iteration reads the new
 * recorder — two readers on one AudioRecord.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowFakeAudioRecord::class])
class AudioRecorderCaptureSessionIsolationTest {

    @Before
    fun setUp() {
        ShadowFakeAudioRecord.resetHooks()
    }

    @After
    fun tearDown() {
        ShadowFakeAudioRecord.releaseBlockedReads()
        ShadowFakeAudioRecord.resetHooks()
    }

    private val engine: WhisperEngine = mock(WhisperEngine::class.java)

    private fun recordingThreadOf(recorder: AudioRecorder): Thread? {
        val field = AudioRecorder::class.java.getDeclaredField("recordingThread")
        field.isAccessible = true
        return field.get(recorder) as? Thread
    }

    @Test
    fun `restart after cancel does not let the orphaned capture thread read the replacement AudioRecord`() {
        val recorder = AudioRecorder()
        val callback = mock(VelaRecordingCallback::class.java)

        // Session 1: construct AudioRecord #0 and park its capture thread in read().
        ShadowFakeAudioRecord.blockReadsOnInstance = 0
        recorder.start(engine, null, callback)
        assertTrue(
            "session-1 capture thread never entered read",
            ShadowFakeAudioRecord.readEntered.await(3, TimeUnit.SECONDS)
        )
        val orphan = recordingThreadOf(recorder)
        assertTrue(orphan != null && orphan!!.isAlive)

        // cancel() releases AudioRecord #0 and joins the capture thread with the
        // ANR budget — the join times out because the thread is parked, leaving
        // it orphaned on purpose for this test.
        recorder.cancel()
        assertTrue("orphan must still be parked (join timed out)", orphan!!.isAlive)

        // Session 2 (SAME recorder): constructs AudioRecord #1; isRecording flips
        // back to true, so the orphan's next loop iteration would wrongly read it.
        ShadowFakeAudioRecord.blockReadsOnInstance = -1 // do not park session-2's reads
        recorder.start(engine, null, callback)

        // Wait until session 2's (new) thread reads instance #1.
        val deadline = System.currentTimeMillis() + 3000
        while (ShadowFakeAudioRecord.threadsThatRead(1).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        // Wake the orphan and give it a chance to wrongly read instance #1.
        ShadowFakeAudioRecord.releaseBlockedReads()
        Thread.sleep(300)

        val readersOfInstance1 = ShadowFakeAudioRecord.threadsThatRead(1)
        assertEquals(
            "replacement AudioRecord must be read by exactly one thread (its own session) — " +
                "the orphaned capture thread must not bleed into the new session",
            1,
            readersOfInstance1.size
        )
        // The orphan must never appear among instance #1's readers.
        assertTrue(
            "orphaned capture thread must not read the replacement AudioRecord",
            readersOfInstance1.none { it === orphan }
        )

        recorder.cancel()
    }

    @Test
    fun `restart after stop does not let the orphaned capture thread pollute the new session buffer`() {
        val recorder = AudioRecorder()
        val callback = mock(VelaRecordingCallback::class.java)

        ShadowFakeAudioRecord.blockReadsOnInstance = 0
        ShadowFakeAudioRecord.readResult = 2
        recorder.start(engine, null, callback)
        assertTrue(ShadowFakeAudioRecord.readEntered.await(3, TimeUnit.SECONDS))
        val orphan = recordingThreadOf(recorder)

        // stop() joins the capture thread with the ANR budget; the parked thread
        // times out and is orphaned. isRecording flips false.
        recorder.stop(clean = false)
        assertTrue(orphan != null && orphan!!.isAlive)

        // start() again on the SAME recorder: new AudioRecord #1, isRecording true.
        ShadowFakeAudioRecord.blockReadsOnInstance = -1
        ShadowFakeAudioRecord.readResult = 0 // new session must not write real data yet
        recorder.start(engine, null, callback)
        val deadline = System.currentTimeMillis() + 3000
        while (ShadowFakeAudioRecord.threadsThatRead(1).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        ShadowFakeAudioRecord.releaseBlockedReads()
        Thread.sleep(200)

        assertEquals(
            "only the new session's thread may read the replacement AudioRecord",
            1,
            ShadowFakeAudioRecord.threadsThatRead(1).size
        )
        assertTrue(
            "orphaned capture thread must not read the replacement AudioRecord",
            ShadowFakeAudioRecord.threadsThatRead(1).none { it === orphan }
        )
        recorder.cancel()
    }
}
