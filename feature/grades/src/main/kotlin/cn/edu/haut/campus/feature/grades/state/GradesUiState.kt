package cn.edu.haut.campus.feature.grades.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.ExamTermRepairAuditEntity
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.Term

/** 统一状态矩阵。当前遗留学业页没有全部运行时来源，Route 默认处于 CONTENT；fixtures 用该枚举固定完整矩阵。 */
enum class GradesScreenStatus {
    LOADING,
    EMPTY,
    CONTENT,
    PARTIAL,
    STALE,
    ERROR_RETRYABLE,
    ERROR_TERMINAL,
    NEEDS_PERMISSION,
    NEEDS_LOGIN,
    OFFLINE,
    UNSUPPORTED
}

/** 学业页不可变渲染快照；只包含 UI 渲染与高层交互状态，不包含 Repository、Context 或协程。 */
@Immutable
data class GradesUiState(
    val status: GradesScreenStatus = GradesScreenStatus.CONTENT,
    val currentTerm: Term? = null,
    val attempts: List<GradeAttempt> = emptyList(),
    val examSchedules: List<ExamScheduleItem> = emptyList(),
    val examTermReviewAudits: List<ExamTermRepairAuditEntity> = emptyList(),
    val courses: List<Course> = emptyList(),
    val scheduleRules: List<CourseScheduleRule> = emptyList(),
    val prefs: AppPreferencesState = AppPreferencesState(),
    val studentProfile: StudentProfile? = null,
    val activeFocusSession: FocusSessionDomain? = null,
    val completedFocusSessions: List<FocusSessionDomain> = emptyList(),
    val selectedTermFilter: String? = null,
    val selectedCategoryFilter: CourseCategory? = null,
    val activeSubTab: Int = 0,
    val trendMetricIndex: Int = 0,
    val showTrendSection: Boolean = false,
    val showSimulatorSection: Boolean = false,
    val simulatedNewCredits: Float = 22f,
    val simulatedNewGpa: Float = 3.8f,
    val resetScrollSignal: Int = 0
)
