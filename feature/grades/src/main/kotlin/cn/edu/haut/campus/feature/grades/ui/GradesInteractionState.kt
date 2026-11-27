package cn.edu.haut.campus.feature.grades.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import cn.edu.haut.campus.core.model.CourseCategory

/**
 * 学业页纯界面交互状态（筛选/分段/展开/模拟器输入）。
 * 与拆分前一致：随组合存在而存在，底栏切换或 Profile 切换离开组合后即重置，
 * 不进入 ViewModel，也不跨组合存活。
 */
internal class GradesInteractionState {
    val selectedTermFilter = mutableStateOf<String?>(null)
    val selectedCategoryFilter = mutableStateOf<CourseCategory?>(null)
    val activeSubTab = mutableIntStateOf(0)
    val trendMetricIndex = mutableIntStateOf(0)
    val showTrendSection = mutableStateOf(false)
    val showSimulatorSection = mutableStateOf(false)
    val simulatedNewCredits = mutableFloatStateOf(22f)
    val simulatedNewGpa = mutableFloatStateOf(3.8f)

    /** 「全部学期 + 全部性质」筛选重置；语义与拆分前底栏重按一致。 */
    fun resetFilters() {
        selectedTermFilter.value = null
        selectedCategoryFilter.value = null
    }
}

@Composable
internal fun rememberGradesInteractionState(): GradesInteractionState =
    remember { GradesInteractionState() }
