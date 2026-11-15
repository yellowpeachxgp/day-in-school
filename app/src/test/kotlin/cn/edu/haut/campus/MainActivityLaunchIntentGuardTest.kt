package cn.edu.haut.campus

import android.content.ComponentName
import android.content.pm.PackageManager
import cn.edu.haut.campus.core.designsystem.LauncherIconAliasReconciler
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * A5 (#18) 桌面小组件与通知点击启动目标的静态+运行时双重守卫：
 * - 所有 widget/notification 的 launch intent 必须直达 MainActivity 本体组件，
 *   绝不允许落到可被开关禁用的 <activity-alias> 组件上；
 * - 源码级守卫：任何 src/main Kotlin 文件不得引用别名组件名或 getLaunchIntentForPackage。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MainActivityLaunchIntentGuardTest {

    private val realActivityClass = "cn.edu.haut.campus.MainActivity"
    private val aliasClasses = setOf(
        LauncherIconAliasReconciler.DEFAULT_ALIAS_CLASS_NAME,
        LauncherIconAliasReconciler.ANIME_ALIAS_CLASS_NAME
    )

    @Test
    fun widgetLaunchIntentTargetsRealActivityNotAlias() {
        val context = RuntimeEnvironment.getApplication()
        val intent = WidgetRefreshCoordinator.createMainActivityLaunchIntent(context)

        assertNotNull(intent.component)
        assertEquals(context.packageName, intent.component!!.packageName)
        assertEquals(realActivityClass, intent.component!!.className)
        assertFalse("Launch intent must never target an activity-alias", intent.component!!.className in aliasClasses)
        assertEquals(realActivityClass, WidgetRefreshCoordinator.MAIN_ACTIVITY_CLASS_NAME)

        // MainActivity 本体不是 alias：targetActivity 必须为空
        val pm = context.packageManager
        val realInfo = pm.getActivityInfo(ComponentName(context.packageName, realActivityClass), 0)
        assertNotNull(realInfo)
        assertNull("MainActivity itself must be a real <activity>, not an alias", realInfo.targetActivity)

        // 反向佐证：两个桌面入口确实是 alias 且指向 MainActivity
        for (alias in aliasClasses) {
            // 当前未被选中的别名处于 DISABLED 状态，需显式请求禁用组件
            val info = pm.getActivityInfo(
                ComponentName(context.packageName, alias),
                PackageManager.GET_DISABLED_COMPONENTS
            )
            assertNotNull(info)
            assertEquals(realActivityClass, info.targetActivity)
        }
    }

    @Test
    fun staticGuardNoLaunchPathReferencesAliasComponents() {
        // :app 单元测试工作目录为 app/ 模块目录，repo 根目录为 ".."
        val repoRoot = File("..").canonicalFile
        assertTrue(
            "repo root must contain core/ and widget/",
            File(repoRoot, "core").isDirectory && File(repoRoot, "widget").isDirectory
        )

        val violators = mutableListOf<String>()
        val producersMissingExplicitTarget = mutableListOf<String>()
        val scanRoots = listOf("app", "core", "feature", "widget").map { File(repoRoot, it) }
        // 带左括号才视为真实调用，命中 KDoc 注释中的函数名引用属误报
        val aliasTokens = aliasClasses + "getLaunchIntentForPackage("
        var scannedKt = 0

        for (root in scanRoots) {
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/") }
                .forEach { f ->
                    scannedKt++
                    val text = f.readText()
                    // 别名协调器是唯一允许引用别名类名的文件（其职责即切换别名）
                    if (f.name != "LauncherIconAliasReconciler.kt") {
                        for (token in aliasTokens) {
                            if (text.contains(token)) {
                                violators += f.relativeTo(repoRoot).toString() + " -> " + token
                            }
                        }
                    }
                }
        }
        assertTrue("Expected to scan main Kotlin sources", scannedKt >= 150)
        assertTrue(
            "No launch path may reference activity-alias components or getLaunchIntentForPackage:" +
                violators.joinToString(" | "),
            violators.isEmpty()
        )

        // 已知的 widget/通知启动 intent 生产者必须显式引用 MainActivity 本体
        val producers = listOf(
            "widget/common/src/main/kotlin/cn/edu/haut/campus/widget/common/ScheduleSnapshotUseCase.kt",
            "core/reminder/src/main/kotlin/cn/edu/haut/campus/core/reminder/ReminderNotificationFactory.kt",
            "core/reminder/src/main/kotlin/cn/edu/haut/campus/core/reminder/focus/FocusAlarmSubsystem.kt",
            "core/database/src/main/kotlin/cn/edu/haut/campus/core/database/LibrarySeatAlarmSubsystem.kt",
            "core/database/src/main/kotlin/cn/edu/haut/campus/core/database/DormElectricityNotificationSubsystem.kt"
        )
        for (rel in producers) {
            val f = File(repoRoot, rel)
            assertTrue("Producer file must exist: " + rel, f.isFile)
            val text = f.readText()
            if (!text.contains(realActivityClass)) {
                producersMissingExplicitTarget += rel
            }
            assertFalse(
                "Producer must not route via PackageManager launch intent: " + rel,
                text.contains("getLaunchIntentForPackage(")
            )
        }
        assertTrue(
            "Every known widget/notification launch producer must name the explicit MainActivity component:" +
                producersMissingExplicitTarget.joinToString(" | "),
            producersMissingExplicitTarget.isEmpty()
        )

        // 小组件 provider 统一经 createMainActivityLaunchIntent 构造启动意图
        val widgetProviders = listOf(
            "widget/nextclass/src/main/kotlin/cn/edu/haut/campus/widget/nextclass/NextClassAppWidgetProvider.kt",
            "widget/today/src/main/kotlin/cn/edu/haut/campus/widget/today/TodayAppWidgetProvider.kt",
            "widget/today/src/main/kotlin/cn/edu/haut/campus/widget/today/ParcelAppWidgetProvider.kt",
            "widget/timeline/src/main/kotlin/cn/edu/haut/campus/widget/timeline/TimelineAppWidgetProvider.kt",
            "widget/week/src/main/kotlin/cn/edu/haut/campus/widget/week/WeekAppWidgetProvider.kt"
        )
        for (rel in widgetProviders) {
            val f = File(repoRoot, rel)
            assertTrue("Widget provider must exist: " + rel, f.isFile)
            val text = f.readText()
            assertTrue(
                "Widget provider must route through createMainActivityLaunchIntent: " + rel,
                text.contains("createMainActivityLaunchIntent")
            )
        }
    }
}
