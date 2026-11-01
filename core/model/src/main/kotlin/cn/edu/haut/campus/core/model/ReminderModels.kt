package cn.edu.haut.campus.core.model

import java.time.Instant

enum class ReminderEventType {
    CLASS,
    EXAM
}

enum class ReminderKind(val code: String, val label: String) {
    CLASS_BEFORE("CLASS_BEFORE", "课前提醒"),
    EXAM_DAY_BEFORE("EXAM_DAY_BEFORE", "考前1天提醒"),
    EXAM_HOUR_BEFORE("EXAM_HOUR_BEFORE", "考前1小时提醒");

    companion object {
        fun fromCode(code: String): ReminderKind =
            entries.firstOrNull { it.code == code } ?: CLASS_BEFORE
    }
}

enum class ReminderTaskStatus {
    PENDING,
    SNOOZED,
    CLAIMED,
    FIRED,
    DONE,
    CANCELLED,
    EXPIRED,
    SUPPRESSED;

    companion object {
        fun fromName(raw: String): ReminderTaskStatus =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

enum class ReminderSchedulerState {
    NEEDS_SCHEDULE,
    SCHEDULED,
    ERROR,
    NONE;

    companion object {
        fun fromName(raw: String): ReminderSchedulerState =
            entries.firstOrNull { it.name == raw } ?: NONE
    }
}

enum class ReminderAccuracyMode(val label: String) {
    EXACT("精确闹钟"),
    INEXACT("普通提醒"),
    NONE("未设置");

    companion object {
        fun fromName(raw: String): ReminderAccuracyMode =
            entries.firstOrNull { it.name == raw } ?: NONE
    }
}

/**
 * 纯领域提醒事件模型：
 * 由 ScheduleEngine 解析出的真实单次课程（ClassOccurrence）或有效考试安排（ExamScheduleItem）投影而成。
 */
data class ReminderEvent(
    val eventKey: String,
    val courseId: String? = null,
    val examId: String? = null,
    val type: ReminderEventType,
    val displayTitle: String,
    val startAt: Instant,
    val endAt: Instant?,
    val locationText: String?,
    val buildingId: String?,
    val seatNumber: String? = null,
    val examStageLabel: String? = null,
    val hasConflict: Boolean = false,
    val revision: String,
    val isCancelled: Boolean = false
)

sealed interface SnoozeChoice {
    val actionCode: String
    val displayLabel: String

    data object Plus5Minutes : SnoozeChoice {
        override val actionCode: String = "SNOOZE_5M"
        override val displayLabel: String = "5 分钟后"
    }

    data object Plus10Minutes : SnoozeChoice {
        override val actionCode: String = "SNOOZE_10M"
        override val displayLabel: String = "10 分钟后"
    }

    data object BeforeClass5Minutes : SnoozeChoice {
        override val actionCode: String = "BEFORE_START_5M"
        override val displayLabel: String = "上课前 5 分钟"
    }

    data object ExamPlus15Minutes : SnoozeChoice {
        override val actionCode: String = "EXAM_SNOOZE_15M"
        override val displayLabel: String = "15 分钟后"
    }

    data object ExamPlus30Minutes : SnoozeChoice {
        override val actionCode: String = "EXAM_SNOOZE_30M"
        override val displayLabel: String = "30 分钟后"
    }

    data object ExamPlus60Minutes : SnoozeChoice {
        override val actionCode: String = "EXAM_SNOOZE_60M"
        override val displayLabel: String = "60 分钟后"
    }

    companion object {
        fun fromActionCode(code: String): SnoozeChoice? = when (code) {
            Plus5Minutes.actionCode -> Plus5Minutes
            Plus10Minutes.actionCode -> Plus10Minutes
            BeforeClass5Minutes.actionCode -> BeforeClass5Minutes
            ExamPlus15Minutes.actionCode -> ExamPlus15Minutes
            ExamPlus30Minutes.actionCode -> ExamPlus30Minutes
            ExamPlus60Minutes.actionCode -> ExamPlus60Minutes
            else -> null
        }
    }
}

enum class SnoozeRejectReason(val userMessage: String) {
    INVALID_STATE("当前提醒状态不可延后"),
    TOO_LATE("已接近或超过开始时间，无法延后"),
    TOO_SOON("已超过该提醒时间节点"),
    EVENT_CANCELLED("课程或考试已停课/取消"),
    EVENT_CHANGED("课程或考试安排已变更，旧提醒已失效"),
    MAX_REACHED("已达到本次提醒最大延后次数（2次）"),
    POLICY_DISABLED("提醒功能已关闭"),
    NOTIFICATION_DISABLED("系统通知权限已关闭")
}

sealed interface SnoozeOutcome {
    data class Accepted(
        val triggerAt: Instant,
        val exact: Boolean,
        val snoozeCount: Int,
        val remainingSnoozes: Int
    ) : SnoozeOutcome

    data class AcceptedPendingRepair(
        val triggerAt: Instant,
        val reason: String
    ) : SnoozeOutcome

    data object ConcurrentlyHandled : SnoozeOutcome
    data object NotFound : SnoozeOutcome
    data object EventChanged : SnoozeOutcome
    data class Rejected(val reason: SnoozeRejectReason) : SnoozeOutcome
    data object InternalError : SnoozeOutcome
}

data class SnoozeOptionEvaluation(
    val choice: SnoozeChoice,
    val label: String,
    val targetInstant: Instant,
    val available: Boolean,
    val disabledReason: SnoozeRejectReason? = null
)

data class ReminderGlobalConfig(
    val enabled: Boolean = false,
    val classLeadMinutes: Int = 20,
    val classSnoozeDefaultMinutes: Int = 5,
    val classSnoozeMaxCount: Int = 2,
    val classSnoozeCutoffMinutes: Int = 2,
    val examDayBeforeEnabled: Boolean = true,
    val examHourBeforeEnabled: Boolean = true,
    val examSnoozeMaxCount: Int = 2,
    val examSnoozeCutoffMinutes: Int = 10,
    val preciseReminderPreferred: Boolean = true,
    val lockscreenPrivate: Boolean = true,
    val quietMode: String = "FOLLOW_SYSTEM",
    val policyRevision: Long = 1L
)

data class ReminderEventOverride(
    val eventScopeKey: String,
    val enabledOverride: Boolean? = null,
    val leadMinutesOverride: Int? = null,
    val maxSnoozesOverride: Int? = null,
    val updatedAtEpochMs: Long = 0L
)

data class EffectiveReminderPolicy(
    val enabled: Boolean,
    val classLeadMinutes: Int,
    val maxSnoozes: Int,
    val cutoffMinutesBeforeStart: Int,
    val examDayEnabled: Boolean,
    val examHourEnabled: Boolean,
    val revision: String
)

data class DesiredReminderPlan(
    val taskKey: String,
    val eventKey: String,
    val eventType: ReminderEventType,
    val reminderKind: ReminderKind,
    val eventRevision: String,
    val policyRevision: String,
    val eventStartAt: Instant,
    val scheduledAt: Instant,
    val maxSnoozes: Int
)

data class ReminderTaskDomain(
    val id: String,
    val taskKey: String,
    val eventKey: String,
    val eventType: ReminderEventType,
    val reminderKind: ReminderKind,
    val eventRevision: String,
    val policyRevision: String,
    val eventStartAtEpochMs: Long,
    val originalScheduledAtEpochMs: Long,
    val scheduledAtEpochMs: Long,
    val status: ReminderTaskStatus,
    val schedulerState: ReminderSchedulerState,
    val generation: Int,
    val snoozeCount: Int,
    val maxSnoozes: Int,
    val claimedAtEpochMs: Long? = null,
    val deliveredAtEpochMs: Long? = null,
    val lastActionAtEpochMs: Long? = null,
    val scheduleMode: ReminderAccuracyMode = ReminderAccuracyMode.NONE,
    val lastErrorCode: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
) {
    /**
     * 基于稳定 UUID 计算固定正整数 Notification ID（保证同一逻辑任务通知幂等更新，不同任务互不覆盖）。
     */
    val stableNotificationId: Int
        get() = (id.hashCode() and 0x7FFFFFFF).coerceAtLeast(1000)
}

data class WidgetReminderBadge(
    val eventKey: String,
    val taskId: String?,
    val isEnabled: Boolean,
    val status: ReminderTaskStatus?,
    val scheduledAt: Instant?,
    val snoozeCount: Int = 0,
    val maxSnoozes: Int = 2,
    val mode: ReminderAccuracyMode = ReminderAccuracyMode.NONE,
    val statusText: String
)
