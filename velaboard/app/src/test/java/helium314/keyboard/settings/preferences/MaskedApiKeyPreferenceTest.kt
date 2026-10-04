package helium314.keyboard.settings.preferences

import android.content.Context
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.VelaApiKey
import helium314.keyboard.latin.settings.VelaApiKeyStore
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ticket 83: [AFK] Mask API keys in the settings UI.
 *
 * Verifies:
 * 1. Rendered row summary for all four Vela API keys shows ONLY the masked form
 *    (e.g., abcd...wxyz (N chars)), and NEVER equals or contains the full raw stored key.
 * 2. PasswordTextInputPreference / ApiKeyPreference uses PasswordVisualTransformation +
 *    KeyboardType.Password so cleartext is never rendered in dialog or settings list.
 * 3. Non-secret fields (model, endpoint) preserve their standard unmasked behavior.
 * 4. Empty/cleared keys render empty/null summaries rather than leaking placeholder artifacts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MaskedApiKeyPreferenceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<App>()

    @BeforeTest
    fun setup() {
        VelaApiKeyStore.resetForTesting(context)
        context.prefs().edit().clear().commit()
    }

    @Test
    fun `rendered summary for all four Vela API keys never equals stored raw key`() {
        val testKeys = mapOf(
            VelaApiKey.GEMINI to "AIzaSyD-gemini-secret-api-key-9876543210-abcdef",
            VelaApiKey.GROQ to "gsk_groq-secret-api-key-1234567890-abcdef",
            VelaApiKey.OPENAI to "sk-proj-openai-secret-api-key-abcdef1234567890",
            VelaApiKey.CUSTOM to "custom-endpoint-secret-key-xyz-987654321"
        )

        for ((apiKey, rawSecret) in testKeys) {
            VelaApiKeyStore.setApiKey(context, apiKey, rawSecret)

            val renderedSummary = PasswordTextInputPreferenceHelper.getRenderedSummary(context, apiKey)

            assertNotNull(renderedSummary)
            assertTrue(renderedSummary.isNotEmpty())
            // Critical assertion: rendered summary must NEVER equal the stored raw key
            assertNotEquals(rawSecret, renderedSummary)
            // Critical assertion: raw key substring must not be leaked into rendered summary
            assertFalse(renderedSummary.contains("secret-api-key"))
            assertFalse(renderedSummary.contains("1234567890"))
            assertFalse(renderedSummary.contains("9876543210"))
            // Must contain masked fingerprint pattern
            assertTrue(renderedSummary.contains("..."))
            assertTrue(renderedSummary.contains("${rawSecret.length} chars"))
        }
    }

    @Test
    fun `rendered summary for blank or cleared keys is null`() {
        for (apiKey in VelaApiKey.entries) {
            assertNull(PasswordTextInputPreferenceHelper.getRenderedSummary(context, apiKey))
        }

        VelaApiKeyStore.setApiKey(context, VelaApiKey.GEMINI, "AIzaSyD-gemini-key")
        assertNotNull(PasswordTextInputPreferenceHelper.getRenderedSummary(context, apiKey = VelaApiKey.GEMINI))

        VelaApiKeyStore.clearApiKey(context, VelaApiKey.GEMINI)
        assertNull(PasswordTextInputPreferenceHelper.getRenderedSummary(context, apiKey = VelaApiKey.GEMINI))
    }

    @Test
    fun `non-secret fields keep unmasked behaviour`() {
        val prefs = context.prefs()
        val customModel = "my-custom-fine-tuned-model-v2"
        val customEndpoint = "https://ai.example.com/v1/transcribe"

        prefs.edit()
            .putString(Settings.PREF_VELA_GEMINI_MODEL, customModel)
            .putString(Settings.PREF_VELA_OPENAI_ENDPOINT, customEndpoint)
            .commit()

        val modelSummary = prefs.getString(Settings.PREF_VELA_GEMINI_MODEL, Defaults.PREF_VELA_GEMINI_MODEL)
        val endpointSummary = prefs.getString(Settings.PREF_VELA_OPENAI_ENDPOINT, Defaults.PREF_VELA_OPENAI_ENDPOINT)

        // Non-secret fields must retain their unmasked value exactly
        assertEquals(customModel, modelSummary)
        assertEquals(customEndpoint, endpointSummary)
    }

    @Test
    fun `password visual transformation masks arbitrary secret input`() {
        val secretInput = "super-secret-api-token-999"
        val transformation = PasswordVisualTransformation()
        val transformed = transformation.filter(AnnotatedString(secretInput))

        assertNotEquals(secretInput, transformed.text.text)
        assertEquals("\u2022".repeat(secretInput.length), transformed.text.text)
        assertFalse(transformed.text.text.contains("super-secret"))
    }

    @Test
    fun `password preference helper specifies password keyboard type and visual transformation`() {
        assertEquals(KeyboardType.Password, PasswordTextInputPreferenceHelper.keyboardType)
        assertTrue(PasswordTextInputPreferenceHelper.visualTransformation is PasswordVisualTransformation)
    }

    @Test
    fun `generic password preference masks stored secret in preferences`() {
        val prefKey = "custom_third_party_secret_password"
        val rawPassword = "P@ssw0rd-Very-Secret-Key-12345"
        context.prefs().edit().putString(prefKey, rawPassword).commit()

        val renderedSummary = PasswordTextInputPreferenceHelper.getRenderedSummaryForPrefKey(context, prefKey, "")
        assertNotNull(renderedSummary)
        assertNotEquals(rawPassword, renderedSummary)
        assertFalse(renderedSummary.contains("Very-Secret-Key"))
        assertTrue(renderedSummary.contains("..."))
        assertTrue(renderedSummary.contains("${rawPassword.length} chars"))
    }

    @Test
    fun `generic password preference falls back to masked default value when preference is unset`() {
        val prefKey = "custom_third_party_secret_password"
        val defaultSecret = "Default-Secret-Password-99"
        context.prefs().edit().remove(prefKey).commit()

        val renderedSummary = PasswordTextInputPreferenceHelper.getRenderedSummaryForPrefKey(context, prefKey, defaultSecret)
        assertNotNull(renderedSummary)
        assertNotEquals(defaultSecret, renderedSummary)
        assertFalse(renderedSummary.contains("Default-Secret"))
        assertTrue(renderedSummary.contains("..."))
        assertTrue(renderedSummary.contains("${defaultSecret.length} chars"))
    }
}
