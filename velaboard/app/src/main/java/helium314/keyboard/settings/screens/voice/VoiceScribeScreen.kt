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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.SwitchPreference

@Composable
fun VoiceScribeScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val context = LocalContext.current

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Scribe",
        settings = emptyList(),
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                androidx.compose.foundation.layout.Column(
                    Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
                ) {
                    Text(
                        text = "AI Rewrite",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )

                    Setting(context, Settings.PREF_VELA_SCRIBE_ENABLED, R.string.voice_scribe_enabled_title, R.string.voice_scribe_enabled_summary) {
                        SwitchPreference(setting = it, default = Defaults.PREF_VELA_SCRIBE_ENABLED)
                    }.Preference()

                    Setting(context, Settings.PREF_VELA_SCRIBE_STYLE, R.string.voice_scribe_style_title, R.string.voice_scribe_style_summary) {
                        ListPreference(
                            setting = it,
                            items = listOf(
                                context.getString(R.string.voice_scribe_style_professional) to "Professional",
                                context.getString(R.string.voice_scribe_style_casual) to "Casual",
                                context.getString(R.string.voice_scribe_style_bullet_points) to "Bullet Points",
                                context.getString(R.string.voice_scribe_style_email_draft) to "Email Draft",
                                context.getString(R.string.voice_scribe_style_proofread) to "Proofread"
                            ),
                            default = Defaults.PREF_VELA_SCRIBE_STYLE
                        )
                    }.Preference()

                    Setting(context, Settings.PREF_VELA_SCRIBE_CONTEXT_FALLBACK, R.string.voice_scribe_context_fallback_title, R.string.voice_scribe_context_fallback_summary) {
                        SwitchPreference(setting = it, default = Defaults.PREF_VELA_SCRIBE_CONTEXT_FALLBACK)
                    }.Preference()

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    )
}
