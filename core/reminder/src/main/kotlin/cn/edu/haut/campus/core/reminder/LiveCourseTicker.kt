package cn.edu.haut.campus.core.reminder

import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderEventType
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderSchedulerState
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import java.time.Duration
import java.time.Instant

/**
 * 课程实况进度节拍器：只负责用独立 synthetic task 链式注册下一次非精确唤醒。
 *
 * 节拍任务是落库提醒任务之外的运行时调度令牌；触发后接收方必须重新读取当前账号、配置与课程，
 * 不得把任务字段当作可信课程内容。eventRevision 与 policyRevision 仅作为过期令牌校验。
 */
class LiveCourseTicker(
    private val alarmScheduler: AlarmScheduler
) {
    fun scheduleNext(
        event: ReminderEvent,
        now: Instant,
        requestedFrequencyMinutes: Int
    ): ScheduleOutcome {
        val frequencyMinutes = normalizeFrequencyMinutes(requestedFrequencyMinutes)
        val frequencyMs = Duration.ofMinutes(frequencyMinutes.toLong()).toMillis()
        val triggerAt = now.plusMillis(frequencyMs)
        val task = ReminderTaskDomain(
            id = TASK_ID,
            taskKey = TASK_ID,
            eventKey = event.eventKey,
            eventType = event.type,
            reminderKind = if (event.type == ReminderEventType.EXAM) {
                ReminderKind.EXAM_HOUR_BEFORE
            } else {
                ReminderKind.CLASS_BEFORE
            },
            eventRevision = event.revision,
            policyRevision = policyRevisionFor(frequencyMinutes),
            eventStartAtEpochMs = event.startAt.toEpochMilli(),
            originalScheduledAtEpochMs = triggerAt.toEpochMilli(),
            scheduledAtEpochMs = triggerAt.toEpochMilli(),
            status = ReminderTaskStatus.PENDING,
            schedulerState = ReminderSchedulerState.NONE,
            generation = GENERATION,
            snoozeCount = 0,
            maxSnoozes = 0,
            createdAtEpochMs = now.toEpochMilli(),
            updatedAtEpochMs = now.toEpochMilli()
        )
        return alarmScheduler.scheduleInexactWindow(
            task = task,
            windowLengthMs = windowLengthMsFor(frequencyMinutes)
        )
    }

    fun cancel() {
        alarmScheduler.cancel(TASK_ID, GENERATION)
    }

    companion object {
        const val TASK_ID = "__live_course_progress_tick__"
        const val GENERATION = 1
        private const val POLICY_REVISION_PREFIX = "live-progress-frequency-"

        fun normalizeFrequencyMinutes(value: Int): Int = when (value) {
            1, 5, 10 -> value
            else -> 5
        }

        fun policyRevisionFor(frequencyMinutes: Int): String =
            POLICY_REVISION_PREFIX + normalizeFrequencyMinutes(frequencyMinutes)

        /** setWindow 的允许漂移窗口：频率的一半，最小 1 分钟，避免退化成精确闹钟。 */
        fun windowLengthMsFor(frequencyMinutes: Int): Long {
            val frequencyMs = Duration.ofMinutes(normalizeFrequencyMinutes(frequencyMinutes).toLong()).toMillis()
            return maxOf(Duration.ofMinutes(1).toMillis(), frequencyMs / 2L)
        }
    }
}
