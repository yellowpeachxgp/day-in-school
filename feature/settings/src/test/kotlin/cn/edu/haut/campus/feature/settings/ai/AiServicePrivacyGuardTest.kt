package cn.edu.haut.campus.feature.settings.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** AI 设置页隐私静态防线：Key 与 consent 不进入 UiState、saved state、日志或诊断。 */
class AiServicePrivacyGuardTest {
    private val repoRoot = File("../..").canonicalFile

    @Test
    fun aiSettingsSourcesHaveNoPersistedSecretOrLoggingSurface() {
        val sourceRoot = File(repoRoot, "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/ai")
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())
        val combined = files.joinToString("\n") { it.readText() }

        assertFalse(combined.contains("rememberSaveable"))
        assertFalse(combined.contains("SavedStateHandle"))
        assertFalse(combined.contains("android.util.Log"))
        assertFalse(combined.contains("println("))
        assertFalse(combined.contains("printStackTrace("))
        assertFalse(combined.contains("Diagnostic"))
    }

    @Test
    fun uiStateDoesNotDeclareKeyOrConsentFields() {
        val source = File(repoRoot, "feature/settings/src/main/kotlin/cn/edu/haut/campus/feature/settings/ai/state/AiServiceUiState.kt").readText()
        assertFalse(Regex("(?i)\bkey\s*:").containsMatchIn(source))
        assertFalse(source.contains("CharArray"))
        assertFalse(source.contains("AiConsentToken"))
    }
}
