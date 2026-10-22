// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/**
 * Airgap killswitch gate (ticket #157).
 *
 * Pure-logic helper consulted by every cloud-touching code path before it fires.
 * When airgapped, cloud transcription modes (gemini/groq/openai/custom), streamed
 * cloud sessions, and Google Drive sync must all refuse to start with a clear
 * user-facing message, while local Whisper stays completely unaffected.
 *
 * The core functions are pure (take the resolved airgap boolean) so they are
 * JVM-testable without Android. Android call sites read the pref via
 * [isAirgapped] (SharedPreferences overload) or via Settings/Defaults.
 */
object AirgapGate {

    /** Pref key for the global airgap killswitch (default OFF). */
    const val PREF_KEY = "airgap_killswitch"

    /** Centralized user-facing refusal message. */
    const val MESSAGE = "Airgap is on — cloud features disabled"

    /** Cloud feature identifiers that consult the gate. */
    val CLOUD_FEATURES: List<String> = listOf(
        "gemini", "groq", "openai", "custom", "streamed cloud", "drive sync"
    )

    /** Pure: true when airgap is enabled. */
    fun isAirgapped(airgapEnabled: Boolean): Boolean = airgapEnabled

    /** Android adapter: read the pref from SharedPreferences. */
    fun isAirgapped(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(Settings.PREF_VELA_AIRGAP, Defaults.PREF_VELA_AIRGAP)

    /**
     * Pure: returns success when network is allowed, failure (with [MESSAGE])
     * when airgapped. [feature] names the cloud feature attempting to start.
     */
    fun requireNetwork(airgapEnabled: Boolean, feature: String): Result<Unit> =
        if (airgapEnabled) Result.failure(AirgappedException(feature))
        else Result.success(Unit)

    /** Exception thrown (as a Result failure) when an airgapped cloud call is attempted. */
    class AirgappedException(val feature: String) : IllegalStateException(MESSAGE)
}
