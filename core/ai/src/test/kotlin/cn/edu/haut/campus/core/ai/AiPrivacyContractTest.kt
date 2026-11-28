package cn.edu.haut.campus.core.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** C10/C27 静态隐私防线：备份 schema、日志与诊断面不得出现 AI Key 通道。 */
class AiPrivacyContractTest {
    private val repoRoot = File("../..").canonicalFile

    @Test
    fun backupCatalogHasNoAiKeyOrProviderCredentialField() {
        val catalog = File(repoRoot, "core/vault/src/main/kotlin/cn/edu/haut/campus/core/vault/BackupTableCatalog.kt")
        assertTrue("W1-04 备份列白名单目录必须存在", catalog.isFile)
        val source = catalog.readText()

        assertFalse(Regex("(?i)ai[_-]?key").containsMatchIn(source))
        assertFalse(Regex("(?i)openai|byok").containsMatchIn(source))
    }

    @Test
    fun coreAiSourcesHaveNoLoggingCrashDumpOrProcessPersistedSecretSurface() {
        val sourceRoot = File(repoRoot, "core/ai/src/main/kotlin")
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())
        val combined = files.joinToString("\n") { it.readText() }

        assertFalse(combined.contains("android.util.Log"))
        assertFalse(combined.contains("Log.d("))
        assertFalse(combined.contains("Log.e("))
        assertFalse(combined.contains("println("))
        assertFalse(combined.contains("printStackTrace("))
        assertFalse(combined.contains("rememberSaveable"))
        assertFalse(combined.contains("savedStateHandle", ignoreCase = true))
    }
}
