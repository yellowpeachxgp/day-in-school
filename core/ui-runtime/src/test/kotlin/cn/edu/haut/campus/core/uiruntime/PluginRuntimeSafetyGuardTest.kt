package cn.edu.haut.campus.core.uiruntime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Static boundary proof: the renderer has no executable plugin-code path. */
class PluginRuntimeSafetyGuardTest {
    @Test
    fun uiRuntimeContainsNoScriptDexReflectionOrClassloaderExecutionPath() {
        val sourceRoot = File("src/main/kotlin/cn/edu/haut/campus/core/uiruntime")
        assertTrue(sourceRoot.isDirectory)
        val forbidden = listOf(
            "DexClassLoader",
            "PathClassLoader",
            "ScriptEngine",
            "eval(",
            "javax.script",
            "java.lang.reflect.Method",
            "Class.forName",
            "loadClass("
        )
        val offenders = mutableListOf<String>()
        sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            forbidden.forEach { token ->
                if (text.contains(token)) offenders += file.name + ":" + token
            }
        }
        assertTrue("forbidden execution paths: " + offenders.joinToString(), offenders.isEmpty())
    }

    @Test
    fun commandGateWhitelistCoversOnlyDeclarativeTypes() {
        val gate = File("src/main/kotlin/cn/edu/haut/campus/core/uiruntime/DeclarativeCommandGate.kt").readText()
        assertTrue(gate.contains("open_url"))
        assertTrue(gate.contains("open_host_route"))
        assertTrue(gate.contains("show_data"))
        assertFalse(gate.contains("Runtime.getRuntime"))
        assertFalse(gate.contains("ProcessBuilder"))
    }
}
