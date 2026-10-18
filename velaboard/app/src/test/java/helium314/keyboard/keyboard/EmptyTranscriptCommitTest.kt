package helium314.keyboard.keyboard

import android.text.InputType
import com.velavoice.sdk.TranscriptionResult
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.settings.TranscriptionStorage
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
    ShadowToast::class,
])
class EmptyTranscriptCommitTest {
    private lateinit var latinIME: LatinIME

    @BeforeTest
    fun setUp() {
        ShadowToast.reset()
        ShadowInputMethodService.reset()
        latinIME = Robolectric.setupService(LatinIME::class.java)
        val field = KeyboardSwitcher::class.java.getDeclaredField("mIsVelaRecordingCancelled")
        field.isAccessible = true
        field.setBoolean(KeyboardSwitcher.getInstance(), false)
    }

    @AfterTest
    fun tearDown() {
        ShadowToast.reset()
        ShadowInputMethodService.reset()
        val field = KeyboardSwitcher::class.java.getDeclaredField("mIsVelaRecordingCancelled")
        field.isAccessible = true
        field.setBoolean(KeyboardSwitcher.getInstance(), false)
    }

    private fun awaitPersisted() {
        val latch = CountDownLatch(1)
        TranscriptionStorage.persistAsync(Runnable { latch.countDown() })
        assertTrue(latch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun testOnResult_emptyTranscript_doesNotModifyFieldTextAndShowsToast() {
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT
        ShadowInputMethodService.text = "Existing field text"
        ShadowInputMethodService.selectionStart = 19
        ShadowInputMethodService.selectionEnd = 19
        val beforeCount = TranscriptionStorage.getTranscriptionCount(latinIME)

        val switcher = KeyboardSwitcher.getInstance()
        val callback = switcher.buildVelaCallbacks(latinIME, null, false, false)
        val audioBytes = byteArrayOf(0, 1, 2, 3)
        val emptyResult = TranscriptionResult("", "", 0, audioBytes)

        callback.onResult(emptyResult)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        awaitPersisted()

        assertEquals("Existing field text", ShadowInputMethodService.text)
        assertToastShown("Nothing heard", switcher)
        assertEquals(beforeCount, TranscriptionStorage.getTranscriptionCount(latinIME), "empty transcript must not be persisted")
    }

    @Test
    fun testOnResult_whitespaceTranscript_doesNotModifyFieldTextAndShowsToast() {
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT
        ShadowInputMethodService.text = "Existing field text"
        ShadowInputMethodService.selectionStart = 19
        ShadowInputMethodService.selectionEnd = 19
        val beforeCount = TranscriptionStorage.getTranscriptionCount(latinIME)

        val switcher = KeyboardSwitcher.getInstance()
        val callback = switcher.buildVelaCallbacks(latinIME, null, false, false)
        val audioBytes = byteArrayOf(0, 1, 2, 3)
        val whitespaceResult = TranscriptionResult("   ", "   ", 0, audioBytes)

        callback.onResult(whitespaceResult)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        awaitPersisted()

        assertEquals("Existing field text", ShadowInputMethodService.text)
        assertToastShown("Nothing heard", switcher)
        assertEquals(beforeCount, TranscriptionStorage.getTranscriptionCount(latinIME), "whitespace transcript must not be persisted")
    }

    private fun assertToastShown(expected: String, switcher: KeyboardSwitcher) {
        val fakeToastField = KeyboardSwitcher::class.java.getDeclaredField("mFakeToastView")
        fakeToastField.isAccessible = true
        val fakeToast = fakeToastField.get(switcher) as? android.widget.TextView
        val toastText = if (fakeToast != null && fakeToast.text?.isNotEmpty() == true) {
            fakeToast.text.toString()
        } else {
            ShadowToast.getTextOfLatestToast()
        }
        assertEquals(expected, toastText)
    }

    @Test
    fun testOnResult_nonEmptyTranscript_commitsText() {
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT
        ShadowInputMethodService.text = ""
        ShadowInputMethodService.selectionStart = 0
        ShadowInputMethodService.selectionEnd = 0

        val switcher = KeyboardSwitcher.getInstance()
        val callback = switcher.buildVelaCallbacks(latinIME, null, false, false)
        val audioBytes = byteArrayOf(0, 1, 2, 3)
        val result = TranscriptionResult("hello", "hello", 100, audioBytes)

        callback.onResult(result)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("hello", ShadowInputMethodService.text)
    }
}
