package cn.edu.haut.campus.feature.today

import androidx.compose.runtime.Applier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import kotlin.coroutines.EmptyCoroutineContext

/** 纯 JVM 组合测试基座：同步驱动初始组合与 dispose，覆盖离开组合即清理场景。 */
internal class TodayCompositionHarness {
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

    fun compose(content: @Composable () -> Unit) {
        dispose()
        val next = Composition(UnitApplier, recomposer)
        composition = next
        next.setContent(content)
    }

    fun dispose() {
        composition?.dispose()
        composition = null
    }
}
