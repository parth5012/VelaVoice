package helium314.keyboard.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Backend-proxy Drive sync verification (Finding F08, map #211 tickets #235/#255).
 *
 * Verifies:
 * 1. Zero OAuth material in app (no client_secret, refresh_token in prefs or BuildConfig).
 * 2. Static embedded app key and cron-system backend endpoint resolution.
 * 3. Configuration check logic.
 * 4. Fail-closed airgap killswitch behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackendProxyDriveSyncTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun prefs(): android.content.SharedPreferences =
        context().getSharedPreferences("backend-proxy-test", Context.MODE_PRIVATE)

    @Test
    fun defaultBackendProxyConfiguration() {
        val p = prefs()
        p.edit { clear() }

        assertEquals(
            "https://cron.velavoice.internal/save-recommendation",
            GoogleDriveSync.getEndpoint(p)
        )
        assertEquals(
            "vela-static-app-key-bearer",
            GoogleDriveSync.getAppKey(p)
        )
        assertTrue(
            "Default backend proxy sync must be configured out-of-the-box",
            GoogleDriveSync.isConfigured(p)
        )
    }

    @Test
    fun customBackendProxyOverrides() {
        val p = prefs()
        p.edit {
            putString("drive_backend_endpoint", "https://custom.endpoint/sync")
            putString("drive_app_key", "custom-secret-key")
        }

        assertEquals("https://custom.endpoint/sync", GoogleDriveSync.getEndpoint(p))
        assertEquals("custom-secret-key", GoogleDriveSync.getAppKey(p))
        assertTrue(GoogleDriveSync.isConfigured(p))
    }

    @Test
    fun isConfiguredReturnsFalseWhenBlank() {
        val p = prefs()
        p.edit {
            putString("drive_backend_endpoint", "   ")
            putString("drive_app_key", "")
        }

        assertFalse(GoogleDriveSync.isConfigured(p))
    }

    @Test
    fun legacyOAuthPreferencesAreIgnored() {
        val p = prefs()
        p.edit {
            putString("drive_client_id", "stale-client-id")
            putString("drive_client_secret", "stale-client-secret")
            putString("drive_refresh_token", "stale-refresh-token")
        }

        // Even with legacy OAuth keys present, GoogleDriveSync uses backend proxy architecture
        assertEquals("https://cron.velavoice.internal/save-recommendation", GoogleDriveSync.getEndpoint(p))
        assertEquals("vela-static-app-key-bearer", GoogleDriveSync.getAppKey(p))
        assertTrue(GoogleDriveSync.isConfigured(p))
    }

    @Test
    fun syncToDriveFailsClosedWhenAirgapped() {
        val p = prefs()
        p.edit {
            putBoolean(Settings.PREF_VELA_AIRGAP, true)
        }

        val result = GoogleDriveSync.syncToDrive(context(), p)
        assertEquals(0, result.uploaded)
        assertEquals(0, result.total)
        assertTrue(result.message.contains("refused"))
    }
}
