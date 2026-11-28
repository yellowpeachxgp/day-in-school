package cn.edu.haut.campus.feature.timetable.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PeriodTimeScheme
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.timeline.TimelineEvent

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

/** 课表页本地写操作种类；kind 只用于展示/诊断，不能作为完成结果关联键。 */
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
    SaveBasePeriodTimes,
    SavePeriodTimeScheme,
    DeletePeriodTimeScheme,
    SchedulePracticalCourseMatrix,
    AddManualCourse
}

@Immutable
data class TimetableWriteOperation(
    val id: Long,
    val kind: TimetableWriteKind,
    val token: String
)

/** token 与一次用户发起的写入一一对应；UI 只消费自己发起的那次完成结果。 */
@Immutable
data class TimetableWriteCompletion(
    val id: Long,
    val kind: TimetableWriteKind,
    val token: String,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean get() = errorMessage == null
}

/**
 * 待排实践课的 UI 快照。字段与数据库行一一对应，但类型属于 feature 状态层：
 * ui/ 不依赖 core.database Entity；写回 Repository 时由 ViewModel 在会话边界内重建持久化输入。
 */
@Immutable
data class UnscheduledPracticalCourseUiModel(
    val termId: String,
    val sourceKey: String,
    val sourceId: String?,
    val name: String,
    val teacher: String?,
    val creditPlain: String?,
    val rawWeeks: String,
    val category: String,
    val syncedAtEpochMilli: Long
)

/** 课表页不可变渲染快照；周次、编辑表单、展开与输入草稿等纯界面瞬态继续留在 ui/ 的 remember 中。 */
@Immutable
data class TimetableUiState(
    val status: TimetableScreenStatus = TimetableScreenStatus.CONTENT,
    val terms: List<Term> = emptyList(),
    val currentTerm: Term? = null,
    val courses: List<Course> = emptyList(),
    val scheduleRules: List<CourseScheduleRule> = emptyList(),
    val periodTimes: List<PeriodTime> = emptyList(),
    val periodTimeSchemes: List<PeriodTimeScheme> = emptyList(),
    val calendarOverrides: List<CalendarOverride> = emptyList(),
    val occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
    /** 课程/实践课的统一 Timeline 投影；周网格与日清单只从该读取路径还原课程。 */
    val timelineEvents: List<TimelineEvent> = emptyList(),
    /** 产生 [timelineEvents] 的精确学期快照；重叠学期/开学日期变化时不能用旧事件冒充当前学期。 */
    val timelineEventsTerm: Term? = null,
    val buildings: List<Building> = emptyList(),
    val unscheduledCourses: List<UnscheduledPracticalCourseUiModel> = emptyList(),

    // 恢复命令与本地写入的异步结果状态（不是纯界面输入/展开状态）。
    val restoreNotice: String? = null,
    val pendingLegacyReviewTargets: List<OccurrenceOverride> = emptyList(),
    val pendingWrites: List<TimetableWriteOperation> = emptyList(),
    val writeCompletions: Map<String, TimetableWriteCompletion> = emptyMap()
)

/** 只有 projection 携带的学期快照与当前渲染学期完全一致时才允许消费事件。 */
internal fun TimetableUiState.timelineEventsForTerm(term: Term?): List<TimelineEvent> =
    if (timelineEventsTerm == term) timelineEvents else emptyList()
