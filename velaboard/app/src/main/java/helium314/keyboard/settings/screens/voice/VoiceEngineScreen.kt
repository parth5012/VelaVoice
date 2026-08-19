// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.TextInputPreference

@Composable
fun VoiceEngineScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val context = LocalContext.current

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "Transcription Engine",
        settings = emptyList(),
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                Column(
                    Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
                ) {
                    Text(
                        text = "Transcription Mode",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )

                    TranscriptionModeSelector(prefs)

                    Text(
                        text = stringResource(R.string.voice_streaming_mode_title),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.voice_streaming_mode_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp)
                    )

                    StreamingModeSelector(prefs)

                    val mode = prefs.getString(Settings.PREF_VELA_TRANSCRIPTION_MODE, Defaults.PREF_VELA_TRANSCRIPTION_MODE) ?: Defaults.PREF_VELA_TRANSCRIPTION_MODE
                    if (mode == "groq") {
                        Setting(context, Settings.PREF_VELA_GROQ_API_KEY, R.string.voice_groq_api_key_title, R.string.voice_groq_api_key_summary) {
                            TextInputPreference(setting = it, default = Defaults.PREF_VELA_GROQ_API_KEY)
                        }.Preference()
                        Setting(context, Settings.PREF_VELA_GROQ_MODEL, R.string.voice_groq_model_title, R.string.voice_groq_model_summary) {
                            TextInputPreference(setting = it, default = Defaults.PREF_VELA_GROQ_MODEL)
                        }.Preference()
                    }
                    if (mode == "openai") {
                        Setting(context, Settings.PREF_VELA_OPENAI_API_KEY, R.string.voice_openai_api_key_title, R.string.voice_openai_api_key_summary) {
                            TextInputPreference(setting = it, default = Defaults.PREF_VELA_OPENAI_API_KEY)
                        }.Preference()
                        Setting(context, Settings.PREF_VELA_OPENAI_MODEL, R.string.voice_openai_model_title, R.string.voice_openai_model_summary) {
                            TextInputPreference(setting = it, default = Defaults.PREF_VELA_OPENAI_MODEL)
                        }.Preference()
                        Setting(context, Settings.PREF_VELA_OPENAI_ENDPOINT, R.string.voice_openai_endpoint_title, R.string.voice_openai_endpoint_summary) {
                            TextInputPreference(setting = it, default = Defaults.PREF_VELA_OPENAI_ENDPOINT)
                        }.Preference()
                    }
                    if (mode == "custom") {
                        Setting(context, Settings.PREF_VELA_CUSTOM_API_KEY, R.string.voice_custom_api_key_title, R.string.voice_custom_api_key_summary) {
                            TextInputPreference(setting = it, default = Defaults.PREF_VELA_CUSTOM_API_KEY)
                        }.Preference()
                        Setting(context, Settings.PREF_VELA_CUSTOM_MODEL, R.string.voice_custom_model_title, R.string.voice_custom_model_summary) {
                            TextInputPreference(setting = it, default = Defaults.PREF_VELA_CUSTOM_MODEL)
                        }.Preference()
                        Setting(context, Settings.PREF_VELA_CUSTOM_ENDPOINT, R.string.voice_custom_endpoint_title, R.string.voice_custom_endpoint_summary) {
                            TextInputPreference(setting = it, default = Defaults.PREF_VELA_CUSTOM_ENDPOINT)
                        }.Preference()
                    }

                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    )
}

@Composable
private fun TranscriptionModeSelector(prefs: android.content.SharedPreferences) {
    var currentMode by remember { mutableStateOf(
        prefs.getString(Settings.PREF_VELA_TRANSCRIPTION_MODE, Defaults.PREF_VELA_TRANSCRIPTION_MODE) ?: "local"
    ) }
    val modes = listOf("local" to "On-Device (Offline)", "groq" to "Groq API", "openai" to "OpenAI API", "custom" to "Custom Provider")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        modes.forEach { (value, label) ->
            FilterChip(
                selected = currentMode == value,
                onClick = {
                    prefs.edit { putString(Settings.PREF_VELA_TRANSCRIPTION_MODE, value) }
                    currentMode = value
                },
                label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StreamingModeSelector(prefs: android.content.SharedPreferences) {
    var currentMode by remember { mutableStateOf(
        prefs.getString(Settings.PREF_VELA_STREAMING_MODE, Defaults.PREF_VELA_STREAMING_MODE) ?: Defaults.PREF_VELA_STREAMING_MODE
    ) }
    val modes = listOf(
        "instant" to stringResource(R.string.voice_streaming_mode_instant),
        "streamed" to stringResource(R.string.voice_streaming_mode_streamed)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        modes.forEach { (value, label) ->
            FilterChip(
                selected = currentMode == value,
                onClick = {
                    prefs.edit { putString(Settings.PREF_VELA_STREAMING_MODE, value) }
                    currentMode = value
                },
                label = { Text(label, style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
