package cn.edu.haut.campus.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W0-06c / W6-HOTFIX 入口守卫：未验签的手填扩展安装入口不得在设置拆分中复活。
 */
class PluginManualInstallEntryGuardTest {

    @Test
    fun manualExtensionInstallEntryStaysHiddenAfterSettingsSplit() {
        // feature/settings 单元测试工作目录是本模块目录；仓库根目录为上两级。
        val repoRoot = File("../..").canonicalFile
        val sources = listOf(
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/cloud/ui/XPeachCloudAccountScreen.kt",
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/XPeachCloudAccountScreen.kt"
        )
        val combined = sources.joinToString("\n") { relative ->
            val file = File(repoRoot, relative)
            assertTrue("$relative must exist", file.isFile)
            file.readText()
        }

        assertFalse("manual install dialog state must not reappear", combined.contains("showInstallPluginDialog"))
        assertFalse("manual install entry text must stay hidden", combined.contains("添加扩展"))
        assertFalse("manual form fields must not reappear", combined.contains("pluginNameInput"))
        assertFalse("manual form fields must not reappear", combined.contains("pluginIdInput"))
        assertFalse(
            "settings UI must not construct plugin entities directly",
            combined.contains("InstalledExtensionPluginEntity(")
        )
        assertFalse(
            "manual install path must not write installed_extension_plugins directly",
            Regex("upsertExtensionPlugin\\(\\s*InstalledExtensionPluginEntity").containsMatchIn(combined)
        )
    }
}
