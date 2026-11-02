// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.share

import android.content.Context
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.ModelDownloadHelper
import java.io.File

/**
 * Resolves the local Whisper model for the share-transcribe path.
 *
 * F17: ShareTranscribeActivity used to read getSharedPreferences("vela")
 * — a file that never exists — so the configured model path was never found
 * and valid installs kept showing "Model required". This reads the same
 * device-protected default prefs store the settings screens write to, exactly
 * like [helium314.keyboard.quicktile.DictationTileService].
 */
object ShareModelResolver {

    fun resolve(context: Context): String? {
        val shared = ModelDownloadHelper.getSharedModelPath(context, "whisper")
        if (shared != null) return shared
        val legacy = context.prefs().getString(Settings.PREF_VELA_MODEL_PATH, Defaults.PREF_VELA_MODEL_PATH)
        if (!legacy.isNullOrBlank() && File(legacy).exists()) return legacy
        return null
    }
}
