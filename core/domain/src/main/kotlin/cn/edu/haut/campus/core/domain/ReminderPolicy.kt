package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.EffectiveReminderPolicy
import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderEventType
import cn.edu.haut.campus.core.model.ReminderGlobalConfig
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import cn.edu.haut.campus.core.model.SnoozeChoice
import cn.edu.haut.campus.core.model.SnoozeOptionEvaluation
import cn.edu.haut.campus.core.model.SnoozeRejectReason
import java.time.Duration
import java.time.Instant

fun interface ClockProvider {
    fun now(): Instant
}

object SystemClockProvider : ClockProvider {
    override fun now(): Instant = Instant.now()
}

object ReminderPolicyEngine {

    /** 最小安全延后间隔（15 秒），防止刚点延后立即重复触发 */
    const val MIN_SAFE_SNOOZE_SECONDS: Long = 15L

    /** 课程首次提醒错过后的宽限窗口（2 分钟）：超过 2 分钟不再补发过期课前通知 */
    const val CLASS_GRACE_WINDOW_SECONDS: Long = 120L

    /** 考试提前 1 小时提醒宽限窗口（10 分钟） */
    const val EXAM_HOUR_GRACE_WINDOW_SECONDS: Long = 600L

    /** 考试提前 1 天提醒宽限窗口（12 小时，允许重启后当天合理补偿一次） */
    const val EXAM_DAY_GRACE_WINDOW_SECONDS: Long = 12 * 3600L

    /**
     * 合并全局配置与单课程/单考试/单次课覆盖规则：
     * 优先级：OCCURRENCE:<eventKey> > COURSE:<courseId> / EXAM:<examId> > 全局配置。
     */
    fun resolveEffectivePolicy(
        event: ReminderEvent,
        globalConfig: ReminderGlobalConfig,
        overridesByScope: Map<String, ReminderEventOverride> = emptyMap()
    ): EffectiveReminderPolicy {
        val occurrenceOverride = overridesByScope["OCCURRENCE:${event.eventKey}"]
        val parentScopeKey = when (event.type) {
            ReminderEventType.CLASS -> event.courseId?.let { "COURSE:$it" }
            ReminderEventType.EXAM -> event.examId?.let { "EXAM:$it" }
        }
        val parentOverride = parentScopeKey?.let { overridesByScope[it] }

        val enabled = occurrenceOverride?.enabledOverride
            ?: parentOverride?.enabledOverride
            ?: globalConfig.enabled

        val classLeadMinutes = (occurrenceOverride?.leadMinutesOverride
            ?: parentOverride?.leadMinutesOverride
            ?: globalConfig.classLeadMinutes).coerceIn(5, 120)

        val defaultMaxSnoozes = when (event.type) {
            ReminderEventType.CLASS -> globalConfig.classSnoozeMaxCount
            ReminderEventType.EXAM -> globalConfig.examSnoozeMaxCount
        }
        val maxSnoozes = (occurrenceOverride?.maxSnoozesOverride
            ?: parentOverride?.maxSnoozesOverride
            ?: defaultMaxSnoozes).coerceIn(0, 5)

        val cutoffMinutes = when (event.type) {
            ReminderEventType.CLASS -> globalConfig.classSnoozeCutoffMinutes.coerceIn(1, 15)
            ReminderEventType.EXAM -> globalConfig.examSnoozeCutoffMinutes.coerceIn(5, 30)
        }

        val revision = ReminderRevision.computePolicyRevision(
            enabled = enabled,
            classLeadMinutes = classLeadMinutes,
            maxSnoozes = maxSnoozes,
            cutoffMinutesBeforeStart = cutoffMinutes,
            examDayEnabled = globalConfig.examDayBeforeEnabled,
            examHourEnabled = globalConfig.examHourBeforeEnabled
        )

        return EffectiveReminderPolicy(
            enabled = enabled,
            classLeadMinutes = classLeadMinutes,
            maxSnoozes = maxSnoozes,
            cutoffMinutesBeforeStart = cutoffMinutes,
            examDayEnabled = globalConfig.examDayBeforeEnabled,
            examHourEnabled = globalConfig.examHourBeforeEnabled,
            revision = revision
        )
    }

    /**
     * 根据用户选择的 Snooze 动作计算候选提醒时刻。
     */
    fun resolveRequestedTime(
        choice: SnoozeChoice,
        now: Instant,
        eventStartAt: Instant
    ): Instant = when (choice) {
        SnoozeChoice.Plus5Minutes -> now.plus(Duration.ofMinutes(5))
        SnoozeChoice.Plus10Minutes -> now.plus(Duration.ofMinutes(10))
        SnoozeChoice.BeforeClass5Minutes -> eventStartAt.minus(Duration.ofMinutes(5))
        SnoozeChoice.ExamPlus15Minutes -> now.plus(Duration.ofMinutes(15))
        SnoozeChoice.ExamPlus30Minutes -> now.plus(Duration.ofMinutes(30))
        SnoozeChoice.ExamPlus60Minutes -> now.plus(Duration.ofMinutes(60))
    }

    /**
     * 严格校验某次 Snooze 请求是否允许：
     * 返回 null 表示允许；返回非空 SnoozeRejectReason 表示拒绝原因。
     */
    fun validateSnooze(
        task: ReminderTaskDomain,
        currentEvent: ReminderEvent?,
        policy: EffectiveReminderPolicy,
        candidateTime: Instant,
        now: Instant,
        notificationsAllowed: Boolean = true
    ): SnoozeRejectReason? {
        if (!notificationsAllowed) {
            return SnoozeRejectReason.NOTIFICATION_DISABLED
        }
        if (!policy.enabled) {
            return SnoozeRejectReason.POLICY_DISABLED
        }
        if (currentEvent == null || currentEvent.isCancelled) {
            return SnoozeRejectReason.EVENT_CANCELLED
        }
        if (task.eventRevision != currentEvent.revision) {
            return SnoozeRejectReason.EVENT_CHANGED
        }
        if (task.status !in setOf(
                ReminderTaskStatus.PENDING,
                ReminderTaskStatus.SNOOZED,
                ReminderTaskStatus.FIRED
            )
        ) {
            return SnoozeRejectReason.INVALID_STATE
        }
        if (task.snoozeCount >= task.maxSnoozes) {
            return SnoozeRejectReason.MAX_REACHED
        }
        if (!now.isBefore(currentEvent.startAt)) {
            return SnoozeRejectReason.TOO_LATE
        }
        if (!candidateTime.isAfter(now.plusSeconds(MIN_SAFE_SNOOZE_SECONDS))) {
            return SnoozeRejectReason.TOO_SOON
        }
        val cutoffInstant = currentEvent.startAt.minus(
            Duration.ofMinutes(policy.cutoffMinutesBeforeStart.toLong())
        )
        if (candidateTime.isAfter(cutoffInstant)) {
            return SnoozeRejectReason.TOO_LATE
        }
        return null
    }

    /**
     * 评估 App 内「更多稍后提醒」弹层的所有候选项可用性与不可用原因。
     */
    fun evaluateSnoozeOptions(
        task: ReminderTaskDomain,
        currentEvent: ReminderEvent?,
        policy: EffectiveReminderPolicy,
        now: Instant,
        notificationsAllowed: Boolean = true
    ): List<SnoozeOptionEvaluation> {
        val choices = when (task.eventType) {
            ReminderEventType.CLASS -> listOf(
                SnoozeChoice.Plus5Minutes,
                SnoozeChoice.Plus10Minutes,
                SnoozeChoice.BeforeClass5Minutes
            )
            ReminderEventType.EXAM -> listOf(
                SnoozeChoice.ExamPlus15Minutes,
                SnoozeChoice.ExamPlus30Minutes,
                SnoozeChoice.ExamPlus60Minutes
            )
        }
        val startAt = currentEvent?.startAt ?: Instant.ofEpochMilli(task.eventStartAtEpochMs)
        return choices.map { choice ->
            val candidate = resolveRequestedTime(choice, now, startAt)
            val rejectReason = validateSnooze(
                task = task,
                currentEvent = currentEvent,
                policy = policy,
                candidateTime = candidate,
                now = now,
                notificationsAllowed = notificationsAllowed
            )
            SnoozeOptionEvaluation(
                choice = choice,
                label = choice.displayLabel,
                targetInstant = candidate,
                available = rejectReason == null,
                disabledReason = rejectReason
            )
        }
    }
}
