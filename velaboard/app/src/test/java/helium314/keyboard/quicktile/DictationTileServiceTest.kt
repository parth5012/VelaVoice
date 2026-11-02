package helium314.keyboard.quicktile

import com.velavoice.sdk.TranscriptionResult
import com.velavoice.sdk.VelaError
import com.velavoice.sdk.VelaTranscriber
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ticket #237 / Finding F30:
 * Dictation tile clipboard wipe + wrong-session release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationTileServiceTest {

    @After
    fun tearDown() {
        DictationTileService.activeSession = null
    }

    @Test
    fun `blank result leaves clipboard untouched, no toast`() {
        val session = mock(VelaTranscriber::class.java)
        DictationTileService.activeSession = session

        var copyCalled = false
        var toastCalled = false

        val blankResult = TranscriptionResult(rawTranscript = "   ", cleanedTranscript = "", durationMs = 500L)
        DictationTileService.handleResult(
            session = session,
            result = blankResult,
            onCopy = { copyCalled = true },
            onToast = { toastCalled = true }
        )

        assertFalse("Blank result must not touch the clipboard", copyCalled)
        assertFalse("Blank result must not toast 'Copied to clipboard'", toastCalled)
        verify(session).release()
        assertEquals(null, DictationTileService.activeSession)
    }

    @Test
    fun `non-blank result copies to clipboard and toasts`() {
        val session = mock(VelaTranscriber::class.java)
        DictationTileService.activeSession = session

        var copiedText: String? = null
        var toastedMessage: String? = null

        val result = TranscriptionResult(rawTranscript = "hello", cleanedTranscript = "Hello world.", durationMs = 1200L)
        DictationTileService.handleResult(
            session = session,
            result = result,
            onCopy = { copiedText = it },
            onToast = { toastedMessage = it }
        )

        assertEquals("Hello world.", copiedText)
        assertEquals("Copied to clipboard", toastedMessage)
        verify(session).release()
        assertEquals(null, DictationTileService.activeSession)
    }

    @Test
    fun `stale callback cannot release a newer session`() {
        val olderSession = mock(VelaTranscriber::class.java)
        val newerSession = mock(VelaTranscriber::class.java)

        // Rapid restart: activeSession is now newerSession
        DictationTileService.activeSession = newerSession

        var copyCalled = false
        var toastCalled = false

        val result = TranscriptionResult(rawTranscript = "old result", cleanedTranscript = "old result", durationMs = 2000L)
        DictationTileService.handleResult(
            session = olderSession,
            result = result,
            onCopy = { copyCalled = true },
            onToast = { toastCalled = true }
        )

        // Newer session remains active and is NOT released
        assertSame("Newer activeSession must not be clobbered by older session callback", newerSession, DictationTileService.activeSession)
        verify(newerSession, never()).release()

        // Older session released itself
        verify(olderSession).release()
    }

    @Test
    fun `stale error callback cannot release a newer session`() {
        val olderSession = mock(VelaTranscriber::class.java)
        val newerSession = mock(VelaTranscriber::class.java)

        DictationTileService.activeSession = newerSession

        var toastMessage: String? = null
        val error = VelaError("Session timed out")

        DictationTileService.handleError(
            session = olderSession,
            error = error,
            onToast = { toastMessage = it }
        )

        assertSame("Newer activeSession must not be clobbered by older error callback", newerSession, DictationTileService.activeSession)
        verify(newerSession, never()).release()
        verify(olderSession).release()
        assertEquals("Session timed out", toastMessage)
    }
}
