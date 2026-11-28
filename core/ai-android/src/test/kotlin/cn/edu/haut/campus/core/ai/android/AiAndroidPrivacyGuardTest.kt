package cn.edu.haut.campus.core.ai.android

import cn.edu.haut.campus.core.automation.BuiltinActionExecutors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class AiAndroidPrivacyGuardTest {
    private val repoRoot = File("../..").canonicalFile

    @Test
    fun builtinAutomationRegistryContainsNoAiAction() {
        val executors = BuiltinActionExecutors(
            notificationPort = null,
            personalEventStore = null,
            refreshPort = null,
            zoneProvider = { ZoneId.of("Asia/Shanghai") }
        ).all()

        assertTrue(executors.isNotEmpty())
        assertFalse(executors.any { it.type.contains("ai", ignoreCase = true) || it.type.contains("openai", ignoreCase = true) })
    }

    @Test
    fun androidAiSourcesHaveNoLoggingDiagnosticsOrSavedStateKeySurface() {
        val sourceRoot = File(repoRoot, "core/ai-android/src/main/kotlin")
        val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty())
        val combined = files.joinToString("\n") { it.readText() }

        assertFalse(combined.contains("android.util.Log"))
        assertFalse(combined.contains("println("))
        assertFalse(combined.contains("printStackTrace("))
        assertFalse(combined.contains("rememberSaveable"))
        assertFalse(combined.contains("SavedStateHandle"))
        assertFalse(combined.contains("Diagnostic"))
    }

    @Test
    fun wipeRegistrationIsRegisteredThroughBeforeWipeHook() {
        val source = File(repoRoot, "core/ai-android/src/main/kotlin/cn/edu/haut/campus/core/ai/android/AiByokWipeRegistration.kt").readText()
        assertTrue(source.contains("registerBeforeWipeHook"))
        assertTrue(source.contains("wipeAll"))
        val application = File(repoRoot, "app/src/main/kotlin/cn/edu/haut/campus/HautCampusApplication.kt").readText()
        assertTrue("应用账号生命周期必须注册 BYOK 擦除 hook", application.contains("AiByokWipeRegistration.register"))
    }
}
