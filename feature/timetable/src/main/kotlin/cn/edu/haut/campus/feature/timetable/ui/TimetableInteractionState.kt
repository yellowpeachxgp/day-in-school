package cn.edu.haut.campus.feature.timetable.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.feature.timetable.state.UnscheduledPracticalCourseUiModel

/** 打开编辑对话框时捕获的 occurrence 所属学期；写回不得因后续学期切换而改挂到新学期。 */
internal data class TimetableEditingOccurrence(
    val occurrence: ClassOccurrence,
    val ownerTermId: String
)

/**
 * 课表页顶层纯界面交互状态。与拆分前一致：随组合存在而存在，底栏切换或 Profile 切换
 * 离开组合后即重置；不进入 ViewModel，也不跨组合存活。
 * manualSevenDayOverride 依赖 selectedWeek 的 remember(selectedWeek) 重置语义，仍留在 Screen 内。
 */
internal class TimetableInteractionState(initialWeek: Int) {
    val showUnscheduled = mutableStateOf(false)
    val selectedWeek = mutableIntStateOf(initialWeek)
    val editingOccurrence = mutableStateOf<TimetableEditingOccurrence?>(null)
    val editingMasterRule = mutableStateOf<CourseScheduleRule?>(null)
    val schedulingPracticalCourse = mutableStateOf<UnscheduledPracticalCourseUiModel?>(null)
    val showCanceledCoursesDialog = mutableStateOf(false)
    val showAddCourseDialog = mutableStateOf(false)
    val showTermSwitcherDialog = mutableStateOf(false)
    val showPeriodScheduleEditor = mutableStateOf(false)
    val confirmDeleteMasterRule = mutableStateOf<CourseScheduleRule?>(null)
    val viewMode = mutableIntStateOf(0)
}

@Composable
internal fun rememberTimetableInteractionState(initialWeek: Int): TimetableInteractionState =
    remember { TimetableInteractionState(initialWeek) }
