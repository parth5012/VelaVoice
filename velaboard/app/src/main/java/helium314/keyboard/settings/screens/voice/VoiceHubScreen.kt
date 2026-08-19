// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.Preference

@Composable
fun VoiceHubScreen(
    onClickModels: () -> Unit,
    onClickEngine: () -> Unit,
    onClickScribe: () -> Unit,
    onClickDictionary: () -> Unit,
    onClickSync: () -> Unit,
    onClickGeneral: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.voice),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState()).then(Modifier.padding(innerPadding))
            ) {
                Preference(
                    name = "Models",
                    onClick = onClickModels,
                ) { NextScreenIcon() }
                Preference(
                    name = "Transcription Engine",
                    onClick = onClickEngine,
                ) { NextScreenIcon() }
                Preference(
                    name = "Scribe",
                    onClick = onClickScribe,
                ) { NextScreenIcon() }
                Preference(
                    name = "Personal Dictionary",
                    onClick = onClickDictionary,
                ) { NextScreenIcon() }
                Preference(
                    name = "Sync & History",
                    onClick = onClickSync,
                ) { NextScreenIcon() }
                Preference(
                    name = "General",
                    onClick = onClickGeneral,
                ) { NextScreenIcon() }
            }
        }
    }
}
