package cn.edu.haut.campus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * W6-HOTFIX / AC-20（入口部分）静态反例守卫：
 * “添加扩展功能”手填入口必须保持隐藏，且该入口不得绕过签名清单校验直接
 * 构造 InstalledExtensionPluginEntity 写入 installed_extension_plugins。
 */
class PluginManualInstallEntryGuardTest {

    @Test
    fun manualExtensionInstallEntryStaysHiddenAndCannotWriteDatabase() {
        // :app 单元测试工作目录为 app/ 模块目录，repo 根目录为 ".."。
        val repoRoot = File("..").canonicalFile
        val screen = File(
            repoRoot,
            "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/XPeachCloudAccountScreen.kt"
        )
        assertTrue("XPeachCloudAccountScreen.kt must exist", screen.isFile)
        val text = screen.readText()

        assertFalse("manual install dialog state must be removed", text.contains("showInstallPluginDialog"))
        assertFalse("manual install entry text must stay hidden", text.contains("添加扩展"))
        assertFalse("manual form fields must be removed", text.contains("pluginNameInput"))
        assertFalse("manual form fields must be removed", text.contains("pluginIdInput"))
        assertFalse(
            "settings UI must not construct plugin entities directly",
            text.contains("InstalledExtensionPluginEntity")
        )
        assertFalse(
            "manual install path must not write installed_extension_plugins directly",
            Regex("upsertExtensionPlugin\\(\\s*InstalledExtensionPluginEntity").containsMatchIn(text)
        )
    }
}
