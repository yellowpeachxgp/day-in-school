package cn.edu.haut.campus.feature.today.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.LibraryBookingPlanEntity
import cn.edu.haut.campus.core.database.ParcelItemEntity
import cn.edu.haut.campus.core.database.UnscheduledAcademicCourseEntity
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.SnoozeOptionEvaluation
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain

/** 统一状态矩阵。运行期沿袭旧首页默认 CONTENT；其余状态由 fixtures/截图矩阵显式固定。 */
enum class TodayScreenStatus {
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

/** 今日页本地写操作种类；kind 只用于展示/诊断，不能作为完成结果关联键。 */
enum class TodayWriteKind {
    RestoreCanceledOccurrence,
    DeleteLegacyOccurrenceOverride,
    SelectCurrentTerm,
    CancelClassOccurrence
}

@Immutable
data class TodayWriteOperation(
    val id: Long,
    val kind: TodayWriteKind,
    val token: String
)

/** token 与一次用户发起的写入一一对应；UI 只消费自己发起的那次完成结果。 */
@Immutable
data class TodayWriteCompletion(
    val id: Long,
    val kind: TodayWriteKind,
    val token: String,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean get() = errorMessage == null
}

/**
 * 今日页不可变渲染快照。只包含 Repository/提醒协调器产生的数据与异步操作结果；
 * 日期选择、展开、筛选、输入草稿等纯界面瞬态继续留在 ui/ 的 remember 中。
 */
@Immutable
data class TodayUiState(
    val status: TodayScreenStatus = TodayScreenStatus.CONTENT,
    val terms: List<Term> = emptyList(),
    val currentTerm: Term? = null,
    val courses: List<Course> = emptyList(),
    val scheduleRules: List<CourseScheduleRule> = emptyList(),
    val periodTimes: List<PeriodTime> = emptyList(),
    val calendarOverrides: List<CalendarOverride> = emptyList(),
    val occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
    val buildings: List<Building> = emptyList(),
    val examSchedules: List<ExamScheduleItem> = emptyList(),
    val unscheduledCourses: List<UnscheduledAcademicCourseEntity> = emptyList(),
    val prefs: AppPreferencesState = AppPreferencesState(),
    val activeFocusSession: FocusSessionDomain? = null,
    val completedFocusSessions: List<FocusSessionDomain> = emptyList(),
    val onlineRooms: List<UtilityOnlineRoomDomain> = emptyList(),
    val utilityMeters: List<UtilityMeterDomain> = emptyList(),
    val utilityReadings: List<UtilityReadingDomain> = emptyList(),
    val parcelItems: List<ParcelItemEntity> = emptyList(),
    val bookingPlans: List<LibraryBookingPlanEntity> = emptyList(),
    val activeReminderTasks: List<ReminderTaskDomain> = emptyList(),
    val reminderOverrides: List<ReminderEventOverride> = emptyList(),

    // 异步操作结果状态（不是筛选/输入等纯界面瞬态）；ViewModel 仍随组合作用域销毁。
    val isDynamicSyncing: Boolean = false,
    val dynamicSyncBanner: String? = null,
    val pendingLegacyRestoreTargets: List<OccurrenceOverride> = emptyList(),
    val snoozeOptionsEventKey: String? = null,
    val snoozeOptions: List<SnoozeOptionEvaluation> = emptyList(),
    val courseReminderFeedbackEventKey: String? = null,
    val courseReminderFeedback: String? = null,
    val pendingWrites: List<TodayWriteOperation> = emptyList(),
    val writeCompletions: Map<String, TodayWriteCompletion> = emptyMap()
)
