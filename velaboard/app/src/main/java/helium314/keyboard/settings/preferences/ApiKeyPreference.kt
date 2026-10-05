// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.VelaApiKey
import helium314.keyboard.latin.settings.VelaApiKeyStore
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.TextInputDialog

/**
 * Preference composable for sensitive Vela API keys.
 * Reads and writes exclusively through [VelaApiKeyStore].
 *
 * Exposes only the masked fingerprint for UI display so raw credentials never
 * enter the UI presentation layer.
 */
@Composable
fun ApiKeyPreference(
    setting: Setting,
    apiKey: VelaApiKey = requireNotNull(VelaApiKey.fromPrefKey(setting.key)) {
        "No VelaApiKey mapping found for setting key: ${setting.key}"
    },
    info: String? = null,
    checkTextValid: (String) -> Boolean = { true }
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var maskedFingerprint by remember(apiKey) {
        mutableStateOf(VelaApiKeyStore.getMaskedFingerprint(context, apiKey))
    }

    Preference(
        name = setting.title,
        onClick = { showDialog = true },
        description = maskedFingerprint.takeIf { it.isNotEmpty() }
    )

    if (showDialog) {
        TextInputDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = {
                VelaApiKeyStore.setApiKey(context, apiKey, it)
                maskedFingerprint = VelaApiKeyStore.getMaskedFingerprint(context, apiKey)
                showDialog = false
            },
            initialText = "", // Raw key never pre-filled to prevent screen leakage
            title = { Text(setting.title) },
            description = if (info == null) null else { { Text(info) } },
            checkTextValid = checkTextValid,
            onNeutral = {
                VelaApiKeyStore.clearApiKey(context, apiKey)
                maskedFingerprint = ""
                showDialog = false
            },
            neutralButtonText = stringResource(R.string.button_default)
        )
    }
}
