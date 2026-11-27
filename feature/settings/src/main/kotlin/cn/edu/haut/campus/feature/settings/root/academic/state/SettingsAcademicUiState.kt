package cn.edu.haut.campus.feature.settings.root.academic.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 设置二级「学期/小组件/提醒」分区不可变渲染快照。 */
@Immutable
data class SettingsAcademicUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val terms: List<Term> = emptyList(),
    val currentTerm: Term? = null,
    val rules: List<CourseScheduleRule> = emptyList(),
    val periodTimes: List<PeriodTime> = emptyList(),
    val calendarOverrides: List<CalendarOverride> = emptyList(),
    val prefs: AppPreferencesState = AppPreferencesState(),
    val activeReminderTasks: List<ReminderTaskDomain> = emptyList()
)
