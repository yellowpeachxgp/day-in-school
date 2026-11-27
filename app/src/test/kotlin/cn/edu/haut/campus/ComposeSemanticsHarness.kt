package cn.edu.haut.campus

import android.app.Application
import android.content.ComponentName
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Robolectric
import org.robolectric.Shadows
import java.io.Closeable

/** Robolectric 专用空 Activity；只在 src/test 注册，不进入生产 manifest。 */
class ComposeSemanticsHarnessActivity : ComponentActivity()

/**
 * 不依赖 compose-ui-test 的 Robolectric 语义树测试夹具。
 *
 * Compose 1.7 的 ViewRootForTest/RootForTest 是公开测试接口；本夹具只读取
 * semantics、调用 SemanticsActions.OnClick，并显式执行 measureAndLayoutForTest。
 * 这样可以验证真实 composable 的文案与点击接线，而不引入截图或设备测试。
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposeSemanticsHarness private constructor(
    private val scenario: ActivityScenario<ComposeSemanticsHarnessActivity>,
    private val rootForTest: RootForTest
) : Closeable {

    fun pump() {
        Robolectric.flushForegroundThreadScheduler()
        repeat(3) {
            rootForTest.measureAndLayoutForTest()
            Robolectric.flushForegroundThreadScheduler()
        }
    }

    fun nodes(): List<SemanticsNode> {
        pump()
        return rootForTest.semanticsOwner.getAllSemanticsNodes(
            mergingEnabled = false,
            skipDeactivatedNodes = true
        )
    }

    fun allTexts(): List<String> = nodes()
        .flatMap { directTexts(it) }
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun hasText(fragment: String): Boolean = allTexts().any { it.contains(fragment) }

    fun awaitText(fragment: String, timeoutMs: Long = 3_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (hasText(fragment)) return true
            Thread.sleep(25L)
        }
        return hasText(fragment)
    }

    fun requireText(fragment: String, timeoutMs: Long = 3_000L) {
        if (!awaitText(fragment, timeoutMs)) {
            throw AssertionError("未在 Compose 语义树中找到文本「" + fragment + "」。当前文本：\n" + dumpText())
        }
    }

    fun requireNoText(fragment: String) {
        pump()
        if (hasText(fragment)) {
            throw AssertionError("Compose 语义树中不应再出现文本「" + fragment + "」。当前文本：\n" + dumpText())
        }
    }

    /**
     * 点击语义子树中包含 [fragment] 的最具体 clickable 节点。
     * 直接调用 OnClick semantics action，验证的是 composable 接线，而非像素级触摸手势。
     */
    fun clickText(fragment: String, occurrence: Int = 1): Boolean {
        val all = nodes()
        val clickable = all.filter { node ->
            node.config.contains(SemanticsActions.OnClick) &&
                node.config[SemanticsActions.OnClick].action != null
        }
        val matches = clickable
            .filter { subtreeText(it).contains(fragment) }
            .sortedWith(
                compareBy<SemanticsNode> { subtreeText(it).length }
                    .thenBy { subtreeText(it) }
            )
        val node = matches.getOrNull(occurrence - 1)
            ?: throw AssertionError(
                "未找到可点击文本「" + fragment + "」（occurrence=" + occurrence + "）。\n" +
                    "可点击节点：\n" + clickable.joinToString("\n") { "- " + subtreeText(it).replace('\n', ' ') } +
                    "\n全部文本：\n" + dumpText()
            )
        val handled = node.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
        return handled
    }

    fun dumpText(): String = allTexts().distinct().joinToString("\n") { "- " + it }

    private fun directTexts(node: SemanticsNode): List<String> {
        val config = node.config
        val result = mutableListOf<String>()
        if (config.contains(SemanticsProperties.Text)) {
            result += config[SemanticsProperties.Text].map { it.text }
        }
        if (config.contains(SemanticsProperties.ContentDescription)) {
            result += config[SemanticsProperties.ContentDescription]
        }
        if (config.contains(SemanticsProperties.EditableText)) {
            result += config[SemanticsProperties.EditableText].text
        }
        if (config.contains(SemanticsProperties.StateDescription)) {
            result += config[SemanticsProperties.StateDescription]
        }
        return result
    }

    private fun subtreeText(node: SemanticsNode): String {
        val parts = mutableListOf<String>()
        fun visit(current: SemanticsNode) {
            parts += directTexts(current)
            current.children.forEach(::visit)
        }
        visit(node)
        return parts.filter { it.isNotBlank() }.joinToString(" ")
    }

    override fun close() {
        scenario.close()
    }

    companion object {
        fun launch(content: @Composable () -> Unit): ComposeSemanticsHarness {
            // src/test 的 manifest 合并不保证注册测试 Activity；按 Robolectric 推荐路径动态登记，
            // 与 Roborazzi 的 registerRoborazziActivityToRobolectricIfNeeded 同一规避方式。
            val appContext: Application = ApplicationProvider.getApplicationContext()
            Shadows.shadowOf(appContext.packageManager).addActivityIfNotPresent(
                ComponentName(appContext, ComposeSemanticsHarnessActivity::class.java)
            )
            val scenario = ActivityScenario.launch(ComposeSemanticsHarnessActivity::class.java)
            var root: RootForTest? = null
            scenario.onActivity { activity ->
                activity.setContent { content() }
                root = findViewRootForTest(activity.window.decorView)
            }
            val resolved = root ?: throw AssertionError("Compose ViewRootForTest 未创建")
            return ComposeSemanticsHarness(scenario, resolved).also { it.pump() }
        }

        private fun findViewRootForTest(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    findViewRootForTest(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
    }
}
