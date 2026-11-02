package com.velavoice.sdk

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared airgap killswitch gate contract (map #211 tickets #233/#255).
 *
 * Canonical airgap pref (name + key) owned by sdk/vela-core.
 * Fail closed: read error / null / indeterminate gate = airgap-ON -> cloud features blocked.
 */
object AirgapGate {
    const val PREFS_NAME = "com.velavoice.app_preferences"
    const val PREF_KEY = "airgap_killswitch"
    const val MESSAGE = "Airgap is on — cloud features disabled"

    val CLOUD_FEATURES: List<String> = listOf(
        "gemini", "groq", "openai", "custom", "streamed cloud", "drive sync"
    )

    fun isAirgapped(airgapEnabled: Boolean): Boolean = airgapEnabled

    fun isAirgapped(prefs: SharedPreferences?): Boolean {
        if (prefs == null) return true // Fail closed per #233
        return try {
            prefs.getBoolean(PREF_KEY, false)
        } catch (e: Exception) {
            true // Fail closed on read error
        }
    }

    fun isAirgapped(context: Context?): Boolean {
        if (context == null) return true
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            isAirgapped(prefs)
        } catch (e: Exception) {
            true // Fail closed
        }
    }
}
