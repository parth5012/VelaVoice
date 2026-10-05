package helium314.keyboard.latin.settings

import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ticket 84: Exclude keys and transcriptions from backup and device transfer.
 *
 * Verifies:
 * 1. AndroidManifest.xml configures allowBackup="true", dataExtractionRules, and fullBackupContent.
 * 2. res/xml/data_extraction_rules.xml exists and excludes sensitive credential prefs and transcriptions for both cloud-backup and device-transfer.
 * 3. res/xml/backup_rules.xml exists and excludes sensitive credential prefs and transcriptions for legacy full-backup.
 * 4. General keyboard preferences (e.g. layouts, themes, dictionaries) remain eligible for backup.
 * 5. Rules match against concrete file paths (simulated Android Backup Manager matching).
 */
class BackupRulesTest {

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

    private val dataExtractionRulesFile: File
        get() = File(projectRoot, "src/main/res/xml/data_extraction_rules.xml")

    private val backupRulesFile: File
        get() = File(projectRoot, "src/main/res/xml/backup_rules.xml")

    private fun parseXml(file: File): Element {
        assertTrue(file.exists(), "XML file must exist: ${file.absolutePath}")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        return builder.parse(file).documentElement
    }

    @Test
    fun testAndroidManifestDeclaresBackupRules() {
        assertTrue(manifestFile.exists(), "AndroidManifest.xml must exist at ${manifestFile.absolutePath}")
        val manifestXml = parseXml(manifestFile)
        val appNodes = manifestXml.getElementsByTagName("application")
        assertTrue(appNodes.length > 0, "application element must exist in manifest")
        val appElement = appNodes.item(0) as Element

        val androidNs = "http://schemas.android.com/apk/res/android"
        val allowBackup = appElement.getAttributeNS(androidNs, "allowBackup")
        val dataExtractionRules = appElement.getAttributeNS(androidNs, "dataExtractionRules")
        val fullBackupContent = appElement.getAttributeNS(androidNs, "fullBackupContent")

        assertEquals("true", allowBackup, "allowBackup must be true")
        assertEquals("@xml/data_extraction_rules", dataExtractionRules, "dataExtractionRules must point to @xml/data_extraction_rules")
        assertEquals("@xml/backup_rules", fullBackupContent, "fullBackupContent must point to @xml/backup_rules")
    }

    @Test
    fun testDataExtractionRulesFileExistsAndValid() {
        assertTrue(dataExtractionRulesFile.exists(), "data_extraction_rules.xml must exist")
        val root = parseXml(dataExtractionRulesFile)
        assertEquals("data-extraction-rules", root.tagName)

        val cloudBackupNodes = root.getElementsByTagName("cloud-backup")
        assertEquals(1, cloudBackupNodes.length, "Must contain exactly one <cloud-backup> section")
        val cloudBackup = cloudBackupNodes.item(0) as Element

        val deviceTransferNodes = root.getElementsByTagName("device-transfer")
        assertEquals(1, deviceTransferNodes.length, "Must contain exactly one <device-transfer> section")
        val deviceTransfer = deviceTransferNodes.item(0) as Element

        // Verify cloud-backup exclusions
        val cloudExcludes = extractRules(cloudBackup, "exclude")
        assertFalse(cloudExcludes.isEmpty(), "cloud-backup must have exclude rules")
        assertTrue(
            cloudExcludes.any { it.domain == "sharedpref" && it.path.contains("vela_encrypted_api_keys") },
            "cloud-backup must exclude encrypted API key prefs"
        )
        assertTrue(
            cloudExcludes.any { it.domain == "device_sharedpref" && it.path.contains("vela_encrypted_api_keys") },
            "cloud-backup must exclude encrypted API key prefs from device-protected storage (IME default)"
        )
        assertTrue(
            cloudExcludes.any { it.domain == "file" && it.path == "transcriptions" },
            "cloud-backup must exclude transcriptions directory"
        )
        assertTrue(
            cloudExcludes.any { it.domain == "file" && it.path == "transcriptions_quarantine" },
            "cloud-backup must exclude transcriptions_quarantine directory"
        )

        // Verify device-transfer exclusions
        val deviceExcludes = extractRules(deviceTransfer, "exclude")
        assertFalse(deviceExcludes.isEmpty(), "device-transfer must have exclude rules")
        assertTrue(
            deviceExcludes.any { it.domain == "sharedpref" && it.path.contains("vela_encrypted_api_keys") },
            "device-transfer must exclude encrypted API key prefs"
        )
        assertTrue(
            deviceExcludes.any { it.domain == "device_sharedpref" && it.path.contains("vela_encrypted_api_keys") },
            "device-transfer must exclude encrypted API key prefs from device-protected storage (IME default)"
        )
        assertTrue(
            deviceExcludes.any { it.domain == "file" && it.path == "transcriptions" },
            "device-transfer must exclude transcriptions directory"
        )
        assertTrue(
            deviceExcludes.any { it.domain == "file" && it.path == "transcriptions_quarantine" },
            "device-transfer must exclude transcriptions_quarantine directory"
        )

        // Verify general keyboard prefs are NOT excluded wholesale
        assertFalse(
            cloudExcludes.any { it.domain == "sharedpref" && (it.path == "." || it.path.isEmpty()) },
            "cloud-backup must NOT exclude all shared preferences wholesale"
        )
    }

    @Test
    fun testBackupRulesFileExistsAndValid() {
        assertTrue(backupRulesFile.exists(), "backup_rules.xml must exist")
        val root = parseXml(backupRulesFile)
        assertEquals("full-backup-content", root.tagName)

        val excludes = extractRules(root, "exclude")
        assertFalse(excludes.isEmpty(), "full-backup-content must have exclude rules")
        assertTrue(
            excludes.any { it.domain == "sharedpref" && it.path.contains("vela_encrypted_api_keys") },
            "backup_rules must exclude encrypted API key prefs"
        )
        assertTrue(
            excludes.any { it.domain == "device_sharedpref" && it.path.contains("vela_encrypted_api_keys") },
            "backup_rules must exclude encrypted API key prefs from device-protected storage (IME default)"
        )
        assertTrue(
            excludes.any { it.domain == "file" && it.path == "transcriptions" },
            "backup_rules must exclude transcriptions directory"
        )
        assertTrue(
            excludes.any { it.domain == "file" && it.path == "transcriptions_quarantine" },
            "backup_rules must exclude transcriptions_quarantine directory"
        )

        // Verify general keyboard prefs are NOT excluded wholesale
        assertFalse(
            excludes.any { it.domain == "sharedpref" && (it.path == "." || it.path.isEmpty()) },
            "backup_rules must NOT exclude all shared preferences wholesale"
        )
    }

    @Test
    fun testPathMatcherBehavior() {
        val root = parseXml(dataExtractionRulesFile)
        val cloudBackup = root.getElementsByTagName("cloud-backup").item(0) as Element
        val rules = extractRules(cloudBackup, "exclude")

        fun isExcluded(domain: String, relativePath: String): Boolean {
            return rules.any { rule ->
                if (rule.domain != domain) return@any false
                if (rule.path == ".") return@any true
                if (relativePath == rule.path) return@any true
                // Directory prefix match
                val prefix = if (rule.path.endsWith("/")) rule.path else "${rule.path}/"
                relativePath.startsWith(prefix) || relativePath == rule.path
            }
        }

        // Sensitive items MUST be excluded
        assertTrue(isExcluded("sharedpref", "vela_encrypted_api_keys.xml"))
        assertTrue(isExcluded("device_sharedpref", "vela_encrypted_api_keys.xml"))
        assertTrue(isExcluded("file", "transcriptions/2026-10-04_12-00-00.wav"))
        assertTrue(isExcluded("file", "transcriptions/2026-10-04_12-00-00.json"))
        assertTrue(isExcluded("file", "transcriptions_quarantine/2026-10-04_12-00-00.wav"))
        assertTrue(isExcluded("file", "transcriptions_quarantine/2026-10-04_12-00-00.json"))

        // Standard keyboard data MUST NOT be excluded
        assertFalse(isExcluded("sharedpref", "helium314.keyboard_preferences.xml"))
        assertFalse(isExcluded("file", "dicts/en_US.dict"))
        assertFalse(isExcluded("database", "user_history.db"))
    }

    private data class Rule(val domain: String, val path: String)

    private fun extractRules(parent: Element, tagName: String): List<Rule> {
        val list = mutableListOf<Rule>()
        val nodes = parent.getElementsByTagName(tagName)
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as Element
            // Ensure this is a direct child or relevant element
            val domain = node.getAttribute("domain")
            val path = node.getAttribute("path")
            list.add(Rule(domain, path))
        }
        return list
    }
}
