// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.provider.UserDictionary
import android.util.Log
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import org.json.JSONArray

/**
 * Cross-populates the keyboard personal dictionary (Android UserDictionary) and the voice
 * personal dictionary (SharedPreferences JSON keywords).
 *
 * Direction 1: Voice keyword added → also insert into keyboard UserDictionary
 * Direction 2: Keyboard word added → also append to voice keywords
 *
 * Replacements (original → replacement) are NOT synced because the keyboard has no equivalent.
 */
object DictionaryCrossSync {

    private const val TAG = "DictionaryCrossSync"
    private const val WEIGHT_FOR_CROSS_SYNC = 250

    // ---- Voice → Keyboard ----

    /** Insert [word] into the system UserDictionary (all locales, weight 250). No-op if it already exists. */
    fun addToKeyboardDictionary(context: Context, word: String) {
        if (word.isBlank()) return
        runCatching {
            if (!doesWordExistInUserDict(context, word)) {
                UserDictionary.Words.addWord(context, word, WEIGHT_FOR_CROSS_SYNC, null, null)
            }
        }.onFailure { Log.e(TAG, "Failed to cross-sync to keyboard dictionary: ${it.message}") }
    }

    /** Remove [word] from the system UserDictionary (all locales only). */
    fun removeFromKeyboardDictionary(context: Context, word: String) {
        if (word.isBlank()) return
        runCatching {
            context.contentResolver.delete(
                UserDictionary.Words.CONTENT_URI,
                "${UserDictionary.Words.WORD}=? AND ${UserDictionary.Words.LOCALE} is null",
                arrayOf(word)
            )
        }.onFailure { Log.e(TAG, "Failed to remove from keyboard dictionary: ${it.message}") }
    }

    private fun doesWordExistInUserDict(context: Context, word: String): Boolean {
        val cursor = context.contentResolver.query(
            UserDictionary.Words.CONTENT_URI,
            arrayOf(UserDictionary.Words.WORD),
            "${UserDictionary.Words.WORD}=?",
            arrayOf(word),
            null
        )
        cursor?.use { return it.count > 0 }
        return false
    }

    // ---- Keyboard → Voice ----

    /** Append [keyword] to the voice keywords JSON if not already present (case-insensitive). */
    fun addToVoiceKeywords(prefs: android.content.SharedPreferences, keyword: String) {
        if (keyword.isBlank()) return
        runCatching {
            val key = Settings.PREF_VELA_DICT_PREFIX + "keywords"
            val json = prefs.getString(key, "[]") ?: "[]"
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                if (arr.getString(i).equals(keyword, ignoreCase = true)) return // already present
            }
            arr.put(keyword)
            prefs.edit { putString(key, arr.toString()) }
        }.onFailure { Log.e(TAG, "Failed to cross-sync to voice keywords: ${it.message}") }
    }

    /** Remove [keyword] from the voice keywords JSON (case-insensitive match). */
    fun removeFromVoiceKeywords(prefs: android.content.SharedPreferences, keyword: String) {
        if (keyword.isBlank()) return
        runCatching {
            val key = Settings.PREF_VELA_DICT_PREFIX + "keywords"
            val json = prefs.getString(key, "[]") ?: "[]"
            val arr = JSONArray(json)
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                if (!arr.getString(i).equals(keyword, ignoreCase = true)) {
                    newArr.put(arr.getString(i))
                }
            }
            prefs.edit { putString(key, newArr.toString()) }
        }.onFailure { Log.e(TAG, "Failed to remove from voice keywords: ${it.message}") }
    }
}
