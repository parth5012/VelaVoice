package helium314.keyboard.latin.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.App
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ticket 82: Move Vela API keys to EncryptedSharedPreferences with migration.
 *
 * Verifies:
 * 1. Plaintext legacy keys migrate transparently to encrypted preferences.
 * 2. Legacy keys are purged from plaintext shared preferences after migration.
 * 3. Fresh installs never store plaintext strings under vela_*_api_key.
 * 4. Masked fingerprint format (first4/last4 + length) is returned for UI display and never leaks the raw key.
 * 5. All reads/writes route through the typed accessor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VelaApiKeyStoreTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext<App>()

    @BeforeTest
    fun setup() {
        // Reset preferences before each test
        VelaApiKeyStore.resetForTesting(context)
    }

    @Test
    fun `maskApiKey returns empty string for null or blank input`() {
        assertEquals("", VelaApiKeyStore.maskApiKey(null))
        assertEquals("", VelaApiKeyStore.maskApiKey(""))
        assertEquals("", VelaApiKeyStore.maskApiKey("   "))
    }

    @Test
    fun `maskApiKey formats normal key with first4 last4 and length`() {
        // NOTE: fixtures must stay obviously synthetic (plain TEST words,
        // no real-looking key material) so secret scanners don't flag test data.
        val rawKey = "TEST_SYNTHETIC_KEY_1234"
        val masked = VelaApiKeyStore.maskApiKey(rawKey)

        assertTrue(masked.startsWith("TEST..."))
        assertTrue(masked.contains("...1234"))
        assertTrue(masked.contains("23 chars"))
        assertFalse(masked.contains("SYNTHETIC"))
        assertNotEquals(rawKey, masked)
    }

    @Test
    fun `maskApiKey masks short key without leaking middle characters`() {
        val shortKey = "abcdef"
        val masked = VelaApiKeyStore.maskApiKey(shortKey)

        assertFalse(masked.contains(shortKey))
        assertTrue(masked.contains("6 chars"))
    }

    @Test
    fun `legacy plaintext keys migrate transparently to encrypted prefs and clear legacy storage`() {
        val legacyPrefs = VelaApiKeyStore.getLegacySharedPreferences(context)
        legacyPrefs.edit()
            .putString(Settings.PREF_VELA_GEMINI_API_KEY, "TEST_SYNTHETIC_LEGACY_GEMINI")
            .putString(Settings.PREF_VELA_GROQ_API_KEY, "TEST_SYNTHETIC_LEGACY_GROQ")
            .putString(Settings.PREF_VELA_OPENAI_API_KEY, "TEST_SYNTHETIC_LEGACY_OPENAI")
            .putString(Settings.PREF_VELA_CUSTOM_API_KEY, "TEST_SYNTHETIC_LEGACY_CUSTOM")
            .apply()

        // Trigger migration by accessing the store
        val geminiKey = VelaApiKeyStore.getRawApiKey(context, VelaApiKey.GEMINI)
        val groqKey = VelaApiKeyStore.getRawApiKey(context, VelaApiKey.GROQ)
        val openaiKey = VelaApiKeyStore.getRawApiKey(context, VelaApiKey.OPENAI)
        val customKey = VelaApiKeyStore.getRawApiKey(context, VelaApiKey.CUSTOM)

        // Raw keys should be recovered from encrypted store
        assertEquals("TEST_SYNTHETIC_LEGACY_GEMINI", geminiKey)
        assertEquals("TEST_SYNTHETIC_LEGACY_GROQ", groqKey)
        assertEquals("TEST_SYNTHETIC_LEGACY_OPENAI", openaiKey)
        assertEquals("TEST_SYNTHETIC_LEGACY_CUSTOM", customKey)

        // Legacy plaintext keys must be cleared
        assertFalse(legacyPrefs.contains(Settings.PREF_VELA_GEMINI_API_KEY))
        assertFalse(legacyPrefs.contains(Settings.PREF_VELA_GROQ_API_KEY))
        assertFalse(legacyPrefs.contains(Settings.PREF_VELA_OPENAI_API_KEY))
        assertFalse(legacyPrefs.contains(Settings.PREF_VELA_CUSTOM_API_KEY))
    }

    @Test
    fun `fresh install stores no plaintext under vela api keys`() {
        val legacyPrefs = VelaApiKeyStore.getLegacySharedPreferences(context)

        // Set keys on clean install
        VelaApiKeyStore.setApiKey(context, VelaApiKey.GEMINI, "TEST_SYNTHETIC_FRESH_GEMINI")
        VelaApiKeyStore.setApiKey(context, VelaApiKey.GROQ, "TEST_SYNTHETIC_FRESH_GROQ")

        // Keys accessible via store
        assertEquals("TEST_SYNTHETIC_FRESH_GEMINI", VelaApiKeyStore.getRawApiKey(context, VelaApiKey.GEMINI))
        assertEquals("TEST_SYNTHETIC_FRESH_GROQ", VelaApiKeyStore.getRawApiKey(context, VelaApiKey.GROQ))

        // No plaintext key written to legacy preferences
        assertFalse(legacyPrefs.contains(Settings.PREF_VELA_GEMINI_API_KEY))
        assertFalse(legacyPrefs.contains(Settings.PREF_VELA_GROQ_API_KEY))
        assertNull(legacyPrefs.getString(Settings.PREF_VELA_GEMINI_API_KEY, null))
        assertNull(legacyPrefs.getString(Settings.PREF_VELA_GROQ_API_KEY, null))
    }

    @Test
    fun `masked fingerprint retained for display and raw key never reaches UI API`() {
        val rawKey = "TEST_SYNTHETIC_KEY_ABCDX"
        VelaApiKeyStore.setApiKey(context, VelaApiKey.OPENAI, rawKey)

        val displayFingerprint = VelaApiKeyStore.getMaskedFingerprint(context, VelaApiKey.OPENAI)
        assertNotEquals(rawKey, displayFingerprint)
        assertTrue(displayFingerprint.startsWith("TEST..."))
        assertTrue(displayFingerprint.contains("BCDX"))
        assertTrue(displayFingerprint.contains("${rawKey.length} chars"))
        assertFalse(displayFingerprint.contains("SYNTHETIC"))
    }

    @Test
    fun `clearApiKey removes key from encrypted store`() {
        VelaApiKeyStore.setApiKey(context, VelaApiKey.CUSTOM, "TEST_SYNTHETIC_CLEAR_CUSTOM")
        assertTrue(VelaApiKeyStore.hasApiKey(context, VelaApiKey.CUSTOM))

        VelaApiKeyStore.clearApiKey(context, VelaApiKey.CUSTOM)
        assertFalse(VelaApiKeyStore.hasApiKey(context, VelaApiKey.CUSTOM))
        assertNull(VelaApiKeyStore.getRawApiKey(context, VelaApiKey.CUSTOM))
        assertEquals("", VelaApiKeyStore.getMaskedFingerprint(context, VelaApiKey.CUSTOM))
    }

    @Test
    fun `accessor resolves keys by mode string`() {
        VelaApiKeyStore.setApiKey(context, VelaApiKey.GEMINI, "TEST_SYNTHETIC_MODE_GEMINI")
        VelaApiKeyStore.setApiKey(context, VelaApiKey.GROQ, "TEST_SYNTHETIC_MODE_GROQ")
        VelaApiKeyStore.setApiKey(context, VelaApiKey.OPENAI, "TEST_SYNTHETIC_MODE_OPENAI")
        VelaApiKeyStore.setApiKey(context, VelaApiKey.CUSTOM, "TEST_SYNTHETIC_MODE_CUSTOM")

        assertEquals("TEST_SYNTHETIC_MODE_GEMINI", VelaApiKeyStore.getRawApiKeyForMode(context, "gemini"))
        assertEquals("TEST_SYNTHETIC_MODE_GROQ", VelaApiKeyStore.getRawApiKeyForMode(context, "groq"))
        assertEquals("TEST_SYNTHETIC_MODE_OPENAI", VelaApiKeyStore.getRawApiKeyForMode(context, "openai"))
        assertEquals("TEST_SYNTHETIC_MODE_CUSTOM", VelaApiKeyStore.getRawApiKeyForMode(context, "custom"))
        // Unknown mode falls back to openai
        assertEquals("TEST_SYNTHETIC_MODE_OPENAI", VelaApiKeyStore.getRawApiKeyForMode(context, "other"))
    }

    @Test
    fun `accessor supports String prefKey overloads for interoperability`() {
        VelaApiKeyStore.setApiKey(context, Settings.PREF_VELA_GEMINI_API_KEY, "TEST_SYNTHETIC_PREFKEY_GEMINI")
        assertTrue(VelaApiKeyStore.hasApiKey(context, Settings.PREF_VELA_GEMINI_API_KEY))
        assertEquals("TEST_SYNTHETIC_PREFKEY_GEMINI", VelaApiKeyStore.getRawApiKey(context, Settings.PREF_VELA_GEMINI_API_KEY))

        val fingerprint = VelaApiKeyStore.getMaskedFingerprint(context, Settings.PREF_VELA_GEMINI_API_KEY)
        assertTrue(fingerprint.startsWith("TEST..."))
        assertTrue(fingerprint.contains("MINI"))

        VelaApiKeyStore.clearApiKey(context, Settings.PREF_VELA_GEMINI_API_KEY)
        assertFalse(VelaApiKeyStore.hasApiKey(context, Settings.PREF_VELA_GEMINI_API_KEY))
        assertNull(VelaApiKeyStore.getRawApiKey(context, Settings.PREF_VELA_GEMINI_API_KEY))
    }

    @Test
    fun `migration is idempotent and does not overwrite existing encrypted keys`() {
        val legacyPrefs = VelaApiKeyStore.getLegacySharedPreferences(context)
        legacyPrefs.edit()
            .putString(Settings.PREF_VELA_GEMINI_API_KEY, "initial-legacy-key")
            .apply()

        // First migration
        assertEquals("initial-legacy-key", VelaApiKeyStore.getRawApiKey(context, VelaApiKey.GEMINI))

        // Update encrypted key
        VelaApiKeyStore.setApiKey(context, VelaApiKey.GEMINI, "updated-encrypted-key")

        // Put stale value into legacy
        legacyPrefs.edit().putString(Settings.PREF_VELA_GEMINI_API_KEY, "stale-legacy-key").apply()

        // Migration shouldn't overwrite because migration flag is already set
        VelaApiKeyStore.migrateIfNeeded(context)
        assertEquals("updated-encrypted-key", VelaApiKeyStore.getRawApiKey(context, VelaApiKey.GEMINI))
    }

    @Test
    fun `blank or whitespace legacy keys are ignored during migration`() {
        val legacyPrefs = VelaApiKeyStore.getLegacySharedPreferences(context)
        legacyPrefs.edit()
            .putString(Settings.PREF_VELA_GROQ_API_KEY, "   ")
            .putString(Settings.PREF_VELA_OPENAI_API_KEY, "")
            .apply()

        VelaApiKeyStore.migrateIfNeeded(context)

        assertNull(VelaApiKeyStore.getRawApiKey(context, VelaApiKey.GROQ))
        assertNull(VelaApiKeyStore.getRawApiKey(context, VelaApiKey.OPENAI))
        assertFalse(VelaApiKeyStore.hasApiKey(context, VelaApiKey.GROQ))
        assertFalse(VelaApiKeyStore.hasApiKey(context, VelaApiKey.OPENAI))
    }

    @Test
    fun `production mode rejects unencrypted fallback when keystore is missing`() {
        VelaApiKeyStore.resetForTesting(context)
        VelaApiKeyStore.allowTestFallback = false
        VelaApiKeyStore.testPreferences = null

        // In JVM test environment without AndroidKeyStore and with allowTestFallback=false,
        // getEncryptedSharedPreferences fails safely and returns null rather than creating plaintext fallback
        val prefs = VelaApiKeyStore.getEncryptedSharedPreferences(context)
        assertNull(prefs)
    }
}
