// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.quicktile

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DictationTileServiceCompatibilityTest {

    @Test
    fun legacyPackageAliasSubclassesCanonicalService() {
        val legacyClass = Class.forName("helium314.keyboard.latin.quicktile.DictationTileService")
        assertTrue(
            DictationTileService::class.java.isAssignableFrom(legacyClass),
            "helium314.keyboard.latin.quicktile.DictationTileService must extend DictationTileService for backwards compatibility"
        )
    }

    @Test
    fun manifestDeclaresBothCanonicalAndLegacyServices() {
        var dir: File = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var manifest: File? = null
        repeat(5) {
            val candidate = File(dir, "src/main/AndroidManifest.xml")
            if (candidate.exists()) {
                manifest = candidate
                return@repeat
            }
            val subCandidate = File(dir, "velaboard/app/src/main/AndroidManifest.xml")
            if (subCandidate.exists()) {
                manifest = subCandidate
                return@repeat
            }
            dir = dir.parentFile ?: return@repeat
        }

        assertTrue(manifest != null && manifest.exists(), "AndroidManifest.xml must exist")
        val content = manifest.readText()

        assertTrue(
            content.contains("""android:name="helium314.keyboard.quicktile.DictationTileService""""),
            "Manifest must declare the canonical DictationTileService"
        )
        assertTrue(
            content.contains("""android:name="helium314.keyboard.latin.quicktile.DictationTileService""""),
            "Manifest must declare the backward-compatibility alias helium314.keyboard.latin.quicktile.DictationTileService"
        )
    }
}
