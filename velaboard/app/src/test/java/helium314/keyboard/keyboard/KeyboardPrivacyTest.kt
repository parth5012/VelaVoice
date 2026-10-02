package helium314.keyboard.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.velavoice.sdk.ScribeInput
import helium314.keyboard.ShadowInputMethodService
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [
    ShadowInputMethodService::class,
    ShadowLooper::class,
])
class KeyboardPrivacyTest {
    private lateinit var latinIME: LatinIME

    @BeforeTest
    fun setUp() {
        ShadowInputMethodService.reset()
        latinIME = Robolectric.setupService(LatinIME::class.java)
    }

    @AfterTest
    fun tearDown() {
        ShadowInputMethodService.reset()
    }

    @Test
    fun testBuildScribeInput_passwordField_withScribeOff_returnsPrivacySensitiveTrue() {
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val prefs = Settings.getInstance().prefs
        prefs.edit().putBoolean(Settings.PREF_VELA_SCRIBE_ENABLED, false).apply()

        val scribeInput = KeyboardSwitcher.buildScribeInput(latinIME, prefs)
        assertTrue(scribeInput.privacySensitive, "Expected privacySensitive=true for password field even when Scribe is disabled")
    }

    @Test
    fun testBuildScribeInput_passwordField_withScribeOn_returnsPrivacySensitiveTrue() {
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val prefs = Settings.getInstance().prefs
        prefs.edit().putBoolean(Settings.PREF_VELA_SCRIBE_ENABLED, true).apply()

        val scribeInput = KeyboardSwitcher.buildScribeInput(latinIME, prefs)
        assertTrue(scribeInput.privacySensitive, "Expected privacySensitive=true for password field when Scribe is enabled")
        assertNull(scribeInput.contextBefore)
        assertNull(scribeInput.contextAfter)
    }

    @Test
    fun testBuildScribeInput_normalField_withScribeOff_returnsPrivacySensitiveFalse() {
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT
        val prefs = Settings.getInstance().prefs
        prefs.edit().putBoolean(Settings.PREF_VELA_SCRIBE_ENABLED, false).apply()

        val scribeInput = KeyboardSwitcher.buildScribeInput(latinIME, prefs)
        assertFalse(scribeInput.privacySensitive)
        assertNull(scribeInput.contextBefore)
    }

    @Test
    fun testBuildScribeInput_normalField_withForceScribe_returnsContextAndPrivacyFalse() {
        ShadowInputMethodService.currentInputType = InputType.TYPE_CLASS_TEXT
        ShadowInputMethodService.text = "Hello world"
        ShadowInputMethodService.selectionStart = 5
        ShadowInputMethodService.selectionEnd = 5
        val prefs = Settings.getInstance().prefs
        prefs.edit()
            .putBoolean(Settings.PREF_VELA_SCRIBE_ENABLED, false)
            .putBoolean(Settings.PREF_VELA_SCRIBE_CONTEXT_FALLBACK, true)
            .apply()

        val scribeInput = KeyboardSwitcher.buildScribeInput(latinIME, prefs, true)
        assertFalse(scribeInput.privacySensitive)
        assertEquals("Hello", scribeInput.contextBefore)
        assertEquals(" world", scribeInput.contextAfter)
    }

    @Test
    fun testBuildScribeInput_nullLatinIME_failsClosedToPrivacySensitiveTrue() {
        val prefs = Settings.getInstance().prefs
        val scribeInput = KeyboardSwitcher.buildScribeInput(null, prefs)
        assertTrue(scribeInput.privacySensitive, "Null IME must fail closed")
    }
}
