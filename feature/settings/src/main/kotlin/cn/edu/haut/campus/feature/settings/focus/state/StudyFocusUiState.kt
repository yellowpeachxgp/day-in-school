package cn.edu.haut.campus.feature.settings.focus.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 自习专注不可变渲染快照。 */
@Immutable
data class StudyFocusUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val prefs: AppPreferencesState = AppPreferencesState(),
    val activeSession: FocusSessionDomain? = null,
    val completedSessions: List<FocusSessionDomain> = emptyList(),
    val currentTerm: Term? = null,
    val courses: List<Course> = emptyList(),
    val rules: List<CourseScheduleRule> = emptyList(),
    val periodTimes: List<PeriodTime> = emptyList(),
    val calendarOverrides: List<CalendarOverride> = emptyList(),
    val occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
    val buildings: List<Building> = emptyList(),
    val nowMs: Long = 0L,
    val customMinutesInput: String = "25",
    val statusBanner: String? = null,
    val diagnosticSummary: String = ""
)
