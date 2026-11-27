package cn.edu.haut.campus.feature.settings.emptyclassroom.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 空教室外部数据不可变渲染快照。 */
@Immutable
data class EmptyClassroomUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val currentTerm: Term? = null,
    val courses: List<Course> = emptyList(),
    val rules: List<CourseScheduleRule> = emptyList(),
    val periodTimes: List<PeriodTime> = emptyList(),
    val calendarOverrides: List<CalendarOverride> = emptyList(),
    val occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
    val prefs: AppPreferencesState = AppPreferencesState()
)
