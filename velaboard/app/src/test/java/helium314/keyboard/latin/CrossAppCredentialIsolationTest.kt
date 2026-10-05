package helium314.keyboard.latin

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ticket 87 (map #81): Replace the CONTEXT_IGNORE_SECURITY cross-app credential read.
 *
 * The keyboard app owns credentials (VelaApiKeyStore, ticket 82). The legacy
 * `createPackageContext("com.velavoice.app", CONTEXT_IGNORE_SECURITY)` fallback
 * was dead-or-broken (cross-UID MODE_PRIVATE reads fail with EACCES, swallowed
 * by catch-ignore) and doubled secret exposure via write-back copies, so the
 * credential-sharing path was removed entirely. Model-file sharing in
 * ModelDownloadHelper is a separate, non-credential feature and keeps its
 * best-effort lookup with a corrected comment.
 *
 * Verifies:
 * 1. No CONTEXT_IGNORE_SECURITY / cross-app package context remains in the
 *    credential resolution paths (RichInputMethodManager, KeyboardSwitcher).
 * 2. No companionPrefs fallback (and no write-back key copies) remains.
 * 3. The misleading sharedUserId claim in ModelDownloadHelper is corrected.
 * 4. No exported ContentProvider could serve credential prefs to other apps.
 *
 * Documented probe (manual, cannot run on JVM): install a second app signed
 * with a different key, call createPackageContext(keyboardPackage, 0) and try
 * to open the EncryptedSharedPreferences file — the kernel enforces the
 * app-private directory (0700, Keystore-bound) and the open fails with
 * EACCES. Re-run after any change that re-introduces cross-app credential
 * access: `adb shell run-as <other.app> cat /data/data/<ime>/shared_prefs/vela_encrypted_api_keys.xml`
 * must print "Permission denied".
 */
class CrossAppCredentialIsolationTest {

    private val projectRoot: File by lazy {
        var dir: File = File(System.getProperty("user.dir") ?: ".")
        while (dir.parentFile != null && !File(dir, "velaboard").exists() && !File(dir, "app/src/main/AndroidManifest.xml").exists()) {
            dir = dir.parentFile!!
        }
        if (File(dir, "velaboard/app/src/main/AndroidManifest.xml").exists()) {
            File(dir, "velaboard/app")
        } else if (File(dir, "app/src/main/AndroidManifest.xml").exists()) {
            File(dir, "app")
        } else {
            dir
        }
    }

    private fun mainSource(relativePath: String): File {
        val file = File(projectRoot, "src/main/java/$relativePath")
        assertTrue(file.exists(), "Source file must exist: ${file.absolutePath}")
        return file
    }

    private val richInputMethodManagerSource: String
        get() = stripLineComments(mainSource("helium314/keyboard/latin/RichInputMethodManager.kt").readText())

    private val keyboardSwitcherSource: String
        get() = stripLineComments(mainSource("helium314/keyboard/keyboard/KeyboardSwitcher.java").readText())

    private val modelDownloadHelperSource: String
        get() = mainSource("helium314/keyboard/settings/ModelDownloadHelper.kt").readText()

    /** Removes `//` line comments so assertions detect code usage, not documentation mentions. */
    private fun stripLineComments(source: String): String =
        source.lineSequence()
            .map { line ->
                val commentStart = line.indexOf("//")
                if (commentStart >= 0) line.substring(0, commentStart) else line
            }
            .joinToString("\n")

    @Test
    fun testNoCrossAppCredentialContextInKeyResolution() {
        for ((name, source) in listOf(
            "RichInputMethodManager.kt" to richInputMethodManagerSource,
            "KeyboardSwitcher.java" to keyboardSwitcherSource
        )) {
            assertFalse(
                source.contains("CONTEXT_IGNORE_SECURITY"),
                "$name must not use CONTEXT_IGNORE_SECURITY for credential resolution"
            )
            assertFalse(
                source.contains("com.velavoice.app"),
                "$name must not open a cross-app package context for credentials"
            )
        }
    }

    @Test
    fun testNoCompanionPrefsFallbackOrKeyWriteBack() {
        assertFalse(
            keyboardSwitcherSource.contains("companionPrefs"),
            "KeyboardSwitcher must not keep a companion-prefs credential fallback"
        )
        assertFalse(
            richInputMethodManagerSource.contains("companionPrefs"),
            "RichInputMethodManager must not keep a companion-prefs credential fallback"
        )
    }

    @Test
    fun testMisleadingSharedUserIdClaimCorrected() {
        assertFalse(
            modelDownloadHelperSource.contains("have sharedUserId configured"),
            "ModelDownloadHelper must not claim a sharedUserId setup that exists in neither manifest"
        )
        assertTrue(
            modelDownloadHelperSource.contains("no sharedUserId"),
            "ModelDownloadHelper must document that no sharedUserId is configured"
        )
    }

    @Test
    fun testNoExportedProviderCanServeCredentialPrefs() {
        val manifestFile = File(projectRoot, "src/main/AndroidManifest.xml")
        assertTrue(manifestFile.exists(), "AndroidManifest.xml must exist")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val root = factory.newDocumentBuilder().parse(manifestFile).documentElement
        val androidNs = "http://schemas.android.com/apk/res/android"

        val providers = root.getElementsByTagName("provider")
        for (i in 0 until providers.length) {
            val provider = providers.item(i) as Element
            assertFalse(
                provider.getAttributeNS(androidNs, "exported") == "true",
                "ContentProvider ${provider.getAttributeNS(androidNs, "name")} must not be exported"
            )
        }
        assertTrue(providers.length > 0, "Expected to find providers in the manifest (sanity check)")
    }
}
