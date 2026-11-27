package cn.edu.haut.campus.feature.grades

import androidx.compose.runtime.SideEffect
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.feature.grades.ui.GradesInteractionState
import cn.edu.haut.campus.feature.grades.ui.rememberGradesInteractionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W0-06a 审阅阻断回归：底栏切换离开组合后，筛选/分段/展开/模拟器输入等
 * 纯界面状态必须重置（与拆分前 remember 生命周期一致，不得随 Activity 级 VM 保留）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GradesInteractionStateResetTest {

    @Test
    fun bottomNavSwitchAwayAndBackResetsInteractionState() {
        val harness = GradesCompositionHarness()
        var first: GradesInteractionState? = null
        harness.compose {
            val state = rememberGradesInteractionState()
            SideEffect { first = state }
        }
        assertNotNull(first)
        val mutated = first!!

        // 模拟用户交互：学期/性质筛选、分段、展开、模拟器输入
        mutated.selectedTermFilter.value = "2024-2025-2"
        mutated.selectedCategoryFilter.value = CourseCategory.COMPULSORY
        mutated.activeSubTab.intValue = 2
        mutated.trendMetricIndex.intValue = 1
        mutated.showTrendSection.value = true
        mutated.showSimulatorSection.value = true
        mutated.simulatedNewCredits.floatValue = 30f
        mutated.simulatedNewGpa.floatValue = 4.2f

        // 底栏切换离开学业页：组合销毁；再回到学业页：全新组合
        harness.dispose()
        var second: GradesInteractionState? = null
        harness.compose {
            val state = rememberGradesInteractionState()
            SideEffect { second = state }
        }
        assertNotNull(second)
        val reset = second!!
        assertNotSame("重新进入必须是全新的界面状态持有者", mutated, reset)
        assertNull("学期筛选必须重置", reset.selectedTermFilter.value)
        assertNull("性质筛选必须重置", reset.selectedCategoryFilter.value)
        assertEquals("分段必须重置", 0, reset.activeSubTab.intValue)
        assertEquals("趋势指标必须重置", 0, reset.trendMetricIndex.intValue)
        assertFalse("趋势展开必须重置", reset.showTrendSection.value)
        assertFalse("模拟器展开必须重置", reset.showSimulatorSection.value)
        assertEquals("模拟新增学分必须重置", 22f, reset.simulatedNewCredits.floatValue, 0.001f)
        assertEquals("模拟预期绩点必须重置", 3.8f, reset.simulatedNewGpa.floatValue, 0.001f)
    }
}
