package cn.edu.haut.campus.core.packages.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** AC-20 static boundary: production code has exactly one signed v2 installation path. */
class ExtensionInstallPathGuardTest {

    @Test
    fun noProductionCodeOutsideSchoolPackagesCallsLegacyPluginUpsert() {
        val repoRoot = File("../..").canonicalFile
        val offenders = mutableListOf<String>()
        for (rootName in listOf("app", "core", "feature", "widget")) {
            val root = File(repoRoot, rootName)
            if (!root.isDirectory) continue
            root.walkTopDown().forEach { file ->
                if (!file.isFile || file.extension != "kt") return@forEach
                val normalized = file.invariantSeparatorsPath
                if (!normalized.contains("/src/main/")) return@forEach
                if (file.readText().contains("upsertInstalledExtensionPlugin")) {
                    offenders += normalized.removePrefix(repoRoot.invariantSeparatorsPath + "/")
                }
            }
        }
        assertEqualsOffenders(emptyList(), offenders)

        val installer = File(
            repoRoot,
            "core/school-packages/src/main/kotlin/cn/edu/haut/campus/core/packages/v2/ExtensionPluginInstaller.kt"
        )
        assertTrue("signed installer must exist", installer.isFile)
        val installerText = installer.readText()
        assertTrue(installerText.contains("ExtensionPackageVerifier.verify"))
        assertFalse(installerText.contains("eval("))
        assertFalse(installerText.contains("DexClassLoader"))
    }

    @Test
    fun manualInstallEntryAndDirectEntityConstructionStayAbsent() {
        val repoRoot = File("../..").canonicalFile
        val settings = File(
            repoRoot,
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/cloud/ui/XPeachCloudAccountScreen.kt"
        )
        assertTrue(settings.isFile)
        val text = settings.readText()
        assertFalse(text.contains("showInstallPluginDialog"))
        assertFalse(text.contains("添加扩展"))
        assertFalse(text.contains("pluginNameInput"))
        assertFalse(text.contains("pluginIdInput"))
        assertFalse(text.contains("InstalledExtensionPluginEntity("))
    }

    private fun assertEqualsOffenders(expected: List<String>, actual: List<String>) {
        assertTrue("legacy plugin upsert offenders: " + actual.joinToString(), expected == actual)
    }
}
