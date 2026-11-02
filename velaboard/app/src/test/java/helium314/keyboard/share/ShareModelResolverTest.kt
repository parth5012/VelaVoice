// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Before

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareModelResolverTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("vela", Context.MODE_PRIVATE).edit().clear().commit()
        context.prefs().edit().clear().commit()
    }

    private fun existingModelFile(): String {
        val dir = context.filesDir
        dir.mkdirs()
        return File(dir, "test-model.bin").apply { if (!exists()) writeBytes(ByteArray(4)) }.absolutePath
    }

    @Test fun legacyPathStoredInDefaultPrefsIsFound() {
        // F17: resolveModelPath used to look in a non-existent 'vela' prefs
        // file, so the value the settings screens wrote (default/device-protected
        // prefs) was never seen. It must be resolved from context.prefs().
        val path = existingModelFile()
        context.prefs().edit().putString(Settings.PREF_VELA_MODEL_PATH, path).commit()
        assertEquals(path, ShareModelResolver.resolve(context))
    }

    @Test fun valueOnlyInTheNonExistentVelaFileIsIgnored() {
        context.getSharedPreferences("vela", Context.MODE_PRIVATE)
            .edit().putString(Settings.PREF_VELA_MODEL_PATH, existingModelFile()).commit()
        assertNull(ShareModelResolver.resolve(context))
    }

    @Test fun noModelConfiguredResolvesNull() {
        assertEquals(null, ShareModelResolver.resolve(context))
    }

    @Test fun blankOrMissingLegacyFileResolvesNull() {
        context.prefs().edit().putString(Settings.PREF_VELA_MODEL_PATH, "/nonexistent/model.bin").commit()
        assertEquals(null, ShareModelResolver.resolve(context))
    }
}
