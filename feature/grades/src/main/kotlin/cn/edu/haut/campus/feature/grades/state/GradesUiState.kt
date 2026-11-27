package cn.edu.haut.campus.feature.grades.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.ExamTermRepairAuditEntity
import cn.edu.haut.campus.core.model.Course
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

/** 学业页不可变渲染快照；只包含数据驱动的渲染状态，不包含 Repository、Context 或协程；筛选/展开等纯界面状态留在组合内。 */
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
    val completedFocusSessions: List<FocusSessionDomain> = emptyList()
)
