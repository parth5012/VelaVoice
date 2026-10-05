package helium314.keyboard.latin

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ticket 88 (map #81): Lock down transport and the permission broadcast receiver.
 *
 * Verifies:
 * 1. AndroidManifest.xml references a networkSecurityConfig that forbids cleartext.
 * 2. res/xml/network_security_config.xml exists with base-config cleartextTrafficPermitted="false"
 *    and no domain-config or debug-overrides re-permitting cleartext.
 * 3. LatinIME registers the RECORD_AUDIO-granted receiver as NOT_EXPORTED on all API levels
 *    (no pre-Tiramisu exported fallback a spoofing app could abuse).
 * 4. The permission receiver re-checks RECORD_AUDIO before opening the recording pane,
 *    so a spoofed broadcast cannot force the pane open without the mic grant.
 */
class TransportSecurityTest {

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

    private val manifestFile: File
        get() = File(projectRoot, "src/main/AndroidManifest.xml")

    private val networkSecurityConfigFile: File
        get() = File(projectRoot, "src/main/res/xml/network_security_config.xml")

    private val latinImeFile: File
        get() = File(projectRoot, "src/main/java/helium314/keyboard/latin/LatinIME.java")

    private fun parseXml(file: File): Element {
        assertTrue(file.exists(), "XML file must exist: ${file.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        return builder.parse(file).documentElement
    }

    @Test
    fun testAndroidManifestDeclaresNetworkSecurityConfig() {
        assertTrue(manifestFile.exists(), "AndroidManifest.xml must exist at ${manifestFile.absolutePath}")
        val manifestXml = parseXml(manifestFile)
        val appNodes = manifestXml.getElementsByTagName("application")
        assertTrue(appNodes.length > 0, "application element must exist in manifest")
        val appElement = appNodes.item(0) as Element

        val androidNs = "http://schemas.android.com/apk/res/android"
        val networkSecurityConfig = appElement.getAttributeNS(androidNs, "networkSecurityConfig")
        assertEquals(
            "@xml/network_security_config",
            networkSecurityConfig,
            "networkSecurityConfig must point to @xml/network_security_config"
        )
    }

    @Test
    fun testNetworkSecurityConfigForbidsCleartext() {
        val root = parseXml(networkSecurityConfigFile)
        assertEquals("network-security-config", root.tagName)

        val baseConfigs = root.getElementsByTagName("base-config")
        assertTrue(baseConfigs.length > 0, "Must declare a <base-config>")
        val baseConfig = baseConfigs.item(0) as Element
        assertEquals(
            "false",
            baseConfig.getAttribute("cleartextTrafficPermitted"),
            "base-config must set cleartextTrafficPermitted=\"false\""
        )

        val domainConfigs = root.getElementsByTagName("domain-config")
        for (i in 0 until domainConfigs.length) {
            val domainConfig = domainConfigs.item(i) as Element
            assertFalse(
                domainConfig.getAttribute("cleartextTrafficPermitted") == "true",
                "No <domain-config> may re-permit cleartext traffic"
            )
        }

        val debugOverrides = root.getElementsByTagName("debug-overrides")
        for (i in 0 until debugOverrides.length) {
            val trustAnchors = (debugOverrides.item(i) as Element).getElementsByTagName("trust-anchors")
            assertEquals(
                0,
                trustAnchors.length,
                "No <debug-overrides> trust-anchors that could weaken release transport policy"
            )
        }
    }

    @Test
    fun testPermissionReceiverIsNotExportedOnAllApiLevels() {
        assertTrue(latinImeFile.exists(), "LatinIME.java must exist at ${latinImeFile.absolutePath}")
        val source = latinImeFile.readText()

        assertTrue(
            source.contains("ContextCompat.registerReceiver(this, mPermissionReceiver, permissionFilter, ContextCompat.RECEIVER_NOT_EXPORTED)"),
            "mPermissionReceiver must be registered NOT_EXPORTED via ContextCompat on all API levels"
        )
        assertFalse(
            source.contains("registerReceiver(mPermissionReceiver, permissionFilter);"),
            "The pre-Tiramisu exported registerReceiver fallback for mPermissionReceiver must be gone"
        )
    }

    @Test
    fun testPermissionReceiverRechecksRecordAudioBeforeShowingPane() {
        val source = latinImeFile.readText()
        val receiverBlock = source.substringAfter("BroadcastReceiver mPermissionReceiver")
        val onReceiveBlock = receiverBlock.substringAfter("onReceive").substringBefore("mRingerModeChangeReceiver")

        assertTrue(
            onReceiveBlock.contains("showVelaVoicePane"),
            "mPermissionReceiver.onReceive must still open the pane on legitimate grants"
        )
        val grantsPermissionCheck = onReceiveBlock.contains("checkAllPermissionsGranted") ||
            onReceiveBlock.contains("checkSelfPermission")
        assertTrue(
            grantsPermissionCheck,
            "mPermissionReceiver.onReceive must perform a runtime RECORD_AUDIO permission check " +
                "(checkAllPermissionsGranted/checkSelfPermission), not just match the intent action"
        )
        val checkIndex = maxOf(
            onReceiveBlock.indexOf("checkAllPermissionsGranted"),
            onReceiveBlock.indexOf("checkSelfPermission")
        )
        val showPaneIndex = onReceiveBlock.indexOf("showVelaVoicePane")
        assertTrue(
            checkIndex >= 0 && checkIndex < showPaneIndex,
            "The RECORD_AUDIO permission check must run before showVelaVoicePane is called"
        )
    }
}
