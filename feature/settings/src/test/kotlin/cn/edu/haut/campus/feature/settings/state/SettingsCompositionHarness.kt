package cn.edu.haut.campus.feature.settings.state

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 纯 JVM 组合测试基座：直接驱动 compose runtime。
 * 初始组合与 dispose 均同步执行，用来覆盖底栏/页面离开时的清理语义。
 */
internal class SettingsCompositionHarness {

    /** 测试内容不发射任何界面节点，使用空 Applier 满足组合结构要求。 */
    private object UnitApplier : Applier<Unit> {
        override val current: Unit = Unit
        override fun down(node: Unit) {}
        override fun up() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
        override fun clear() {}
        override fun onEndChanges() {}
    }

    private val recomposer = Recomposer(EmptyCoroutineContext)
    private var composition: Composition? = null

    /** 同步执行初始组合；重复调用先销毁旧组合，模拟「离开后再进入」。 */
    fun compose(content: @Composable () -> Unit) {
        dispose()
        val next = Composition(UnitApplier, recomposer)
        composition = next
        next.setContent(content)
    }

    /** 同步销毁组合：DisposableEffect 的 onDispose 随调用即执行。 */
    fun dispose() {
        composition?.dispose()
        composition = null
    }
}
