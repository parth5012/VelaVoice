// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.VelaApiKey
import helium314.keyboard.latin.settings.VelaApiKeyStore
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.TextInputDialog

/**
 * Pure helpers and constants for password-masked preferences and dialogs.
 */
object PasswordTextInputPreferenceHelper {
    val keyboardType: KeyboardType = KeyboardType.Password
    val visualTransformation: VisualTransformation = PasswordVisualTransformation()

    /**
     * Resolves the rendered UI summary for an API key.
     * Always returns the masked fingerprint, never the raw key, or null if unset.
     */
    fun getRenderedSummary(context: Context, apiKey: VelaApiKey): String? =
        VelaApiKeyStore.getMaskedFingerprint(context, apiKey).takeIf { it.isNotEmpty() }

    /**
     * Resolves the rendered UI summary for a preference key.
     * Masks the value using [VelaApiKeyStore.maskApiKey], or null if unset.
     */
    fun getRenderedSummaryForPrefKey(context: Context, prefKey: String, default: String = ""): String? {
        val apiKey = VelaApiKey.fromPrefKey(prefKey)
        if (apiKey != null) {
            return getRenderedSummary(context, apiKey)
        }
        val raw = context.prefs().getString(prefKey, default)
        return VelaApiKeyStore.maskApiKey(raw).takeIf { it.isNotEmpty() }
    }
}

/**
 * Preference composable for secret / password values with masked UI presentation
 * and password-masked input entry.
 *
 * Automatically delegates to [ApiKeyPreference] if [setting] corresponds to a [VelaApiKey].
 */
@Composable
fun PasswordTextInputPreference(
    setting: Setting,
    apiKey: VelaApiKey,
    info: String? = null,
    checkTextValid: (String) -> Boolean = { true }
) {
    ApiKeyPreference(
        setting = setting,
        apiKey = apiKey,
        info = info,
        checkTextValid = checkTextValid
    )
}

@Composable
fun PasswordTextInputPreference(
    setting: Setting,
    default: String = "",
    info: String? = null,
    checkTextValid: (String) -> Boolean = { true }
) {
    val apiKey = VelaApiKey.fromPrefKey(setting.key)
    if (apiKey != null) {
        ApiKeyPreference(
            setting = setting,
            apiKey = apiKey,
            info = info,
            checkTextValid = checkTextValid
        )
    } else {
        var showDialog by rememberSaveable { mutableStateOf(false) }
        val context = LocalContext.current
        val prefs = context.prefs()
        var maskedSummary by remember(setting.key) {
            mutableStateOf(PasswordTextInputPreferenceHelper.getRenderedSummaryForPrefKey(context, setting.key, default))
        }

        Preference(
            name = setting.title,
            onClick = { showDialog = true },
            description = maskedSummary
        )

        if (showDialog) {
            TextInputDialog(
                onDismissRequest = { showDialog = false },
                onConfirmed = {
                    prefs.edit { putString(setting.key, it) }
                    maskedSummary = PasswordTextInputPreferenceHelper.getRenderedSummaryForPrefKey(context, setting.key, default)
                    KeyboardSwitcher.getInstance().setThemeNeedsReload()
                    showDialog = false
                },
                initialText = "", // Raw password never pre-filled to prevent screen leakage
                title = { Text(setting.title) },
                description = if (info == null) null else { { Text(info) } },
                checkTextValid = checkTextValid,
                keyboardType = PasswordTextInputPreferenceHelper.keyboardType,
                visualTransformation = PasswordTextInputPreferenceHelper.visualTransformation,
                onNeutral = {
                    prefs.edit { remove(setting.key) }
                    maskedSummary = PasswordTextInputPreferenceHelper.getRenderedSummaryForPrefKey(context, setting.key, default)
                    KeyboardSwitcher.getInstance().setThemeNeedsReload()
                    showDialog = false
                },
                neutralButtonText = stringResource(R.string.button_default)
            )
        }
    }
}
