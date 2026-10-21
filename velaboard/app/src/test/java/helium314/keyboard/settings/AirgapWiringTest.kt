// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue
import androidx.test.core.app.ApplicationProvider

/**
 * Airgap wiring tests (ticket #158): GoogleDriveSync must refuse while the
 * airgap kill-switch is on, and must still reach the (pre-network) credential
 * check when the gate is open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirgapWiringTest {

    private fun prefs(): android.content.SharedPreferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("airgap-wiring-test", Context.MODE_PRIVATE)

    @Test
    fun syncToDriveRefusesWhenAirgapped() {
        prefs().edit { putBoolean(Settings.PREF_VELA_AIRGAP, true) }
        val result = GoogleDriveSync.syncToDrive(
            ApplicationProvider.getApplicationContext(), prefs())
        assertTrue(result.uploaded == 0 && result.total == 0,
            "no upload may happen while airgapped")
        assertTrue(result.message.contains("refused"),
            "refusal message must be user-facing, got: ${result.message}")
    }

    @Test
    fun syncToDriveReachesCredentialCheckWhenNetworkAllowed() {
        prefs().edit { putBoolean(Settings.PREF_VELA_AIRGAP, false) }
        val result = GoogleDriveSync.syncToDrive(
            ApplicationProvider.getApplicationContext(), prefs())
        assertTrue(result.message.contains("credentials not configured"),
            "with no credentials the pre-network credential check drives the message, got: ${result.message}")
    }
}
