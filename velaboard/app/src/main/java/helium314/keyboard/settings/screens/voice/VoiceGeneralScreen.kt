// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.voice

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.TextInputPreference

@Composable
fun VoiceGeneralScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val context = LocalContext.current

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "General",
        settings = emptyList(),
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                androidx.compose.foundation.layout.Column(
                    Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
                ) {
                    Setting(context, Settings.PREF_VELA_LANGUAGE, R.string.voice_language_title, R.string.voice_language_summary) {
                        TextInputPreference(setting = it, default = Defaults.PREF_VELA_LANGUAGE)
                    }

                    Setting(context, Settings.PREF_VELA_THREADS, R.string.voice_threads_title, R.string.voice_threads_summary) {
                        TextInputPreference(setting = it, default = Defaults.PREF_VELA_THREADS.toString())
                    }

                    Setting(context, Settings.PREF_VELA_CUSTOM_FILLERS, R.string.voice_custom_fillers_title, R.string.voice_custom_fillers_summary) {
                        TextInputPreference(setting = it, default = Defaults.PREF_VELA_CUSTOM_FILLERS)
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    )
}
