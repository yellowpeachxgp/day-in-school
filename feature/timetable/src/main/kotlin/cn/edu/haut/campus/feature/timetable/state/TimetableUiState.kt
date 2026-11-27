package cn.edu.haut.campus.feature.timetable.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term

/** 统一状态矩阵。运行期沿袭旧课表页默认 CONTENT；其余状态由 fixtures/截图矩阵显式固定。 */
enum class TimetableScreenStatus {
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

/** 课表页本地写操作种类；用于把耐用写入的完成结果回接到仍存在的对话框。 */
enum class TimetableWriteKind {
    RestoreCanceledGroup,
    DeleteLegacyOccurrenceOverride,
    SelectCurrentTerm,
    UpdateTermStartDate,
    ResetSingleDayOccurrenceEdit,
    CancelClassOccurrence,
    DeleteCourseScheduleRules,
    SaveSingleDayOccurrenceEdit,
    SaveMergedCourseRuleEdit,
    SaveMasterCourseRuleEdit,
    DeleteCourseScheduleRule,
    SchedulePracticalCourse,
    AddManualCourse
}

@Immutable
data class TimetableWriteOperation(
    val id: Long,
    val kind: TimetableWriteKind
)

/** id 单调递增；UI 只在已发起同 kind 写入后响应最新完成结果，避免旧结果误关新对话框。 */
@Immutable
data class TimetableWriteCompletion(
    val id: Long,
    val kind: TimetableWriteKind,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean get() = errorMessage == null
}

/** 课表页不可变渲染快照；周次、编辑表单、展开与输入草稿等纯界面瞬态继续留在 ui/ 的 remember 中。 */
@Immutable
data class TimetableUiState(
    val status: TimetableScreenStatus = TimetableScreenStatus.CONTENT,
    val terms: List<Term> = emptyList(),
    val currentTerm: Term? = null,
    val courses: List<Course> = emptyList(),
    val scheduleRules: List<CourseScheduleRule> = emptyList(),
    val periodTimes: List<PeriodTime> = emptyList(),
    val calendarOverrides: List<CalendarOverride> = emptyList(),
    val occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
    val buildings: List<Building> = emptyList(),
    val unscheduledCourses: List<UnscheduledAcademicCourseEntity> = emptyList(),

    // 恢复命令与本地写入的异步结果状态（不是纯界面输入/展开状态）。
    val restoreNotice: String? = null,
    val pendingLegacyReviewTargets: List<OccurrenceOverride> = emptyList(),
    val pendingWrites: List<TimetableWriteOperation> = emptyList(),
    val latestWriteCompletion: TimetableWriteCompletion? = null
)
