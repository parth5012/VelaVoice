// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.UserManager
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * Supported Vela API keys for cloud speech-to-text providers.
 */
enum class VelaApiKey(val prefKey: String, val mode: String) {
    GEMINI(Settings.PREF_VELA_GEMINI_API_KEY, "gemini"),
    GROQ(Settings.PREF_VELA_GROQ_API_KEY, "groq"),
    OPENAI(Settings.PREF_VELA_OPENAI_API_KEY, "openai"),
    CUSTOM(Settings.PREF_VELA_CUSTOM_API_KEY, "custom");

    companion object {
        @JvmStatic
        fun fromPrefKey(key: String): VelaApiKey? = entries.firstOrNull { it.prefKey == key }

        @JvmStatic
        fun fromMode(mode: String?): VelaApiKey = entries.firstOrNull { it.mode == mode } ?: OPENAI
    }
}

/**
 * Typed, Keystore-backed storage for sensitive Vela API keys.
 *
 * Replaces legacy plaintext Device-Protected SharedPreferences storage with
 * AndroidX EncryptedSharedPreferences (AES-256 GCM value encryption + AES-256 SIV
 * key encryption).
 *
 * Enforces:
 * 1. Automatic transparent migration from legacy plaintext preferences.
 * 2. Purging legacy plaintext keys after migration.
 * 3. Fresh installs never store keys in legacy preferences.
 * 4. UI-facing accessors return only masked fingerprints (first4/last4 + length)
 *    so raw keys never reach the UI layer.
 * 5. All backend reads/writes go through typed accessors.
 */
object VelaApiKeyStore {

    private const val TAG = "VelaApiKeyStore"
    private const val SECURE_PREFS_FILE = "vela_encrypted_api_keys"
    private const val TEST_FALLBACK_FILE = "vela_api_keys_test_fallback"
    private const val PREF_MIGRATION_COMPLETED = "_vela_api_keys_migrated"

    @VisibleForTesting
    var testPreferences: SharedPreferences? = null

    @VisibleForTesting
    var allowTestFallback: Boolean = false

    @Volatile
    private var cachedEncryptedPrefs: SharedPreferences? = null

    /**
     * Pure function to produce a masked fingerprint for display.
     * Format: first4...last4 (length chars) for keys > 8 chars,
     * or bullet-masked length indicator for shorter keys.
     */
    @JvmStatic
    fun maskApiKey(key: String?): String {
        if (key.isNullOrBlank()) return ""
        val trimmed = key.trim()
        val length = trimmed.length
        return if (length <= 8) {
            "•".repeat(length) + " ($length chars)"
        } else {
            "${trimmed.take(4)}...${trimmed.takeLast(4)} ($length chars)"
        }
    }

    /**
     * Returns legacy shared preferences where keys were previously stored in plaintext.
     */
    @JvmStatic
    fun getLegacySharedPreferences(context: Context): SharedPreferences {
        return context.prefs()
    }

    /**
     * Initializes or returns Keystore-backed EncryptedSharedPreferences.
     * Returns null if device is locked in Direct Boot mode or if initialization fails,
     * without destructively wiping stored credentials.
     */
    @Synchronized
    @JvmStatic
    fun getEncryptedSharedPreferences(context: Context): SharedPreferences? {
        testPreferences?.let { return it }
        cachedEncryptedPrefs?.let { return it }

        val appContext = context.applicationContext ?: context

        // Direct Boot check: Keystore requires credential unlock
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val userManager = appContext.getSystemService(Context.USER_SERVICE) as? UserManager
            if (userManager != null && !userManager.isUserUnlocked) {
                Log.w(TAG, "Device is locked in Direct Boot mode; Keystore-backed prefs inaccessible.")
                return null
            }
        }

        return try {
            val prefs = createEncryptedPrefs(appContext)
            cachedEncryptedPrefs = prefs
            prefs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptedSharedPreferences", e)
            null
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: java.security.NoSuchAlgorithmException) {
            if (allowTestFallback) {
                Log.w(TAG, "AndroidKeyStore not available in test environment; using isolated test store")
                context.getSharedPreferences(TEST_FALLBACK_FILE, Context.MODE_PRIVATE)
            } else {
                throw e
            }
        } catch (e: java.security.KeyStoreException) {
            if (allowTestFallback) {
                Log.w(TAG, "KeyStore not available in test environment; using isolated test store")
                context.getSharedPreferences(TEST_FALLBACK_FILE, Context.MODE_PRIVATE)
            } else {
                throw e
            }
        }
    }

    /**
     * Executes one-time migration from legacy plaintext preferences to encrypted storage.
     */
    @Synchronized
    @JvmStatic
    fun migrateIfNeeded(context: Context) {
        val securePrefs = getEncryptedSharedPreferences(context) ?: return
        migrate(getLegacySharedPreferences(context), securePrefs)
    }

    /**
     * Pure migration logic between two SharedPreferences instances.
     */
    @Synchronized
    @JvmStatic
    fun migrate(legacyPrefs: SharedPreferences, securePrefs: SharedPreferences) {
        if (securePrefs.getBoolean(PREF_MIGRATION_COMPLETED, false)) {
            return
        }

        val secureEditor = securePrefs.edit()
        val legacyEditor = legacyPrefs.edit()
        var migratedAny = false

        for (apiKey in VelaApiKey.entries) {
            val legacyValue = legacyPrefs.getString(apiKey.prefKey, null)
            if (!legacyValue.isNullOrBlank()) {
                secureEditor.putString(apiKey.prefKey, legacyValue.trim())
                legacyEditor.remove(apiKey.prefKey)
                migratedAny = true
            }
        }

        secureEditor.putBoolean(PREF_MIGRATION_COMPLETED, true)
        secureEditor.apply()
        if (migratedAny) {
            legacyEditor.apply()
        }
    }

    // ==========================================
    // UI-Facing Accessors (Never leak raw key)
    // ==========================================

    @JvmStatic
    fun getMaskedFingerprint(context: Context, key: VelaApiKey): String {
        migrateIfNeeded(context)
        val raw = getEncryptedSharedPreferences(context)?.getString(key.prefKey, null)
        return maskApiKey(raw)
    }

    @JvmStatic
    fun getMaskedFingerprint(context: Context, prefKey: String): String {
        val apiKey = VelaApiKey.fromPrefKey(prefKey) ?: return ""
        return getMaskedFingerprint(context, apiKey)
    }

    @JvmStatic
    fun hasApiKey(context: Context, key: VelaApiKey): Boolean {
        migrateIfNeeded(context)
        val raw = getEncryptedSharedPreferences(context)?.getString(key.prefKey, null)
        return !raw.isNullOrBlank()
    }

    @JvmStatic
    fun hasApiKey(context: Context, prefKey: String): Boolean {
        val apiKey = VelaApiKey.fromPrefKey(prefKey) ?: return false
        return hasApiKey(context, apiKey)
    }

    @JvmStatic
    fun hasApiKeyForMode(context: Context, mode: String?): Boolean {
        val apiKey = VelaApiKey.fromMode(mode)
        return hasApiKey(context, apiKey)
    }

    @JvmStatic
    fun setApiKey(context: Context, key: VelaApiKey, value: String?) {
        migrateIfNeeded(context)
        val securePrefs = getEncryptedSharedPreferences(context) ?: return
        val trimmed = value?.trim()
        if (trimmed.isNullOrEmpty()) {
            securePrefs.edit().remove(key.prefKey).apply()
        } else {
            securePrefs.edit().putString(key.prefKey, trimmed).apply()
        }

        // Ensure legacy prefs never retains plaintext
        val legacyPrefs = getLegacySharedPreferences(context)
        if (legacyPrefs.contains(key.prefKey)) {
            legacyPrefs.edit().remove(key.prefKey).apply()
        }
    }

    @JvmStatic
    fun setApiKey(context: Context, prefKey: String, value: String?) {
        val apiKey = VelaApiKey.fromPrefKey(prefKey) ?: return
        setApiKey(context, apiKey, value)
    }

    @JvmStatic
    fun clearApiKey(context: Context, key: VelaApiKey) {
        setApiKey(context, key, null)
    }

    @JvmStatic
    fun clearApiKey(context: Context, prefKey: String) {
        val apiKey = VelaApiKey.fromPrefKey(prefKey) ?: return
        clearApiKey(context, apiKey)
    }

    // ==========================================
    // Internal Transcription Accessors
    // ==========================================

    /**
     * Accessor for internal voice transcription engine.
     * Raw key is only provided here and must NEVER be exposed to the UI or logs.
     */
    @JvmStatic
    fun getRawApiKey(context: Context, key: VelaApiKey): String? {
        migrateIfNeeded(context)
        return getEncryptedSharedPreferences(context)?.getString(key.prefKey, null)
    }

    @JvmStatic
    fun getRawApiKey(context: Context, prefKey: String): String? {
        val apiKey = VelaApiKey.fromPrefKey(prefKey) ?: return null
        return getRawApiKey(context, apiKey)
    }

    @JvmStatic
    fun getRawApiKeyForMode(context: Context, mode: String?): String? {
        val apiKey = VelaApiKey.fromMode(mode)
        return getRawApiKey(context, apiKey)
    }

    // ==========================================
    // Testing Utilities
    // ==========================================

    @VisibleForTesting
    fun resetForTesting(context: Context) {
        testPreferences = null
        allowTestFallback = true
        val legacyPrefs = getLegacySharedPreferences(context)
        val legacyEditor = legacyPrefs.edit()
        for (apiKey in VelaApiKey.entries) {
            legacyEditor.remove(apiKey.prefKey)
        }
        legacyEditor.apply()

        cachedEncryptedPrefs?.edit()?.clear()?.apply()
        cachedEncryptedPrefs = null

        try {
            context.getSharedPreferences(TEST_FALLBACK_FILE, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (ignored: Exception) {
        }
    }
}
