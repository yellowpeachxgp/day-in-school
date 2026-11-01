package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.DesiredReminderPlan
import cn.edu.haut.campus.core.model.EffectiveReminderPolicy
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderEventType
import cn.edu.haut.campus.core.model.ReminderGlobalConfig
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderSchedulerState
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import cn.edu.haut.campus.core.model.Term
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ReconcilePlanDiff(
    val tasksToUpsert: List<ReminderTaskDomain>,
    val alarmsToCancel: List<Pair<String, Int>>,
    val notificationsToCancelTaskIds: List<String>,
    val tasksToSchedule: List<ReminderTaskDomain>
)

object ReminderPlanner {

    const val DEFAULT_PLANNING_HORIZON_DAYS: Long = 7L

    /**
     * 将单次课程实例（ClassOccurrence）投影为标准化 ReminderEvent。
     */
    fun projectClassOccurrenceToEvent(
        termId: String,
        occurrence: ClassOccurrence
    ): ReminderEvent {
        val eventKey = "CLASS:$termId:${occurrence.occurrenceId}"
        val startAt = ReminderRevision.schoolInstant(occurrence.date, occurrence.startTime)
        val endAt = ReminderRevision.schoolInstant(occurrence.date, occurrence.endTime)
        val revision = ReminderRevision.computeEventRevision(
            eventKey = eventKey,
            type = ReminderEventType.CLASS,
            startAt = startAt,
            endAt = endAt,
            locationText = occurrence.rawRoom,
            buildingId = occurrence.buildingId,
            seatNumber = null,
            isCancelled = false
        )
        return ReminderEvent(
            eventKey = eventKey,
            courseId = occurrence.courseId,
            examId = null,
            type = ReminderEventType.CLASS,
            displayTitle = occurrence.courseName,
            startAt = startAt,
            endAt = endAt,
            locationText = occurrence.rawRoom,
            buildingId = occurrence.buildingId,
            seatNumber = null,
            examStageLabel = null,
            hasConflict = occurrence.conflictColumnCount > 1,
            revision = revision,
            isCancelled = false
        )
    }

    /**
     * 将考试安排（ExamScheduleItem）投影为标准化 ReminderEvent。
     * 数据质量守卫：若考试日期或开始时间为空（如“随堂待定”），返回 null，绝不生成错误定时任务。
     */
    fun projectExamToEvent(
        exam: ExamScheduleItem,
        resolvedBuildingId: String? = null
    ): ReminderEvent? {
        val date = exam.examDate ?: return null
        val startTime = exam.startTime ?: return null
        val eventKey = "EXAM:${exam.termId}:${exam.id}"
        val startAt = ReminderRevision.schoolInstant(date, startTime)
        val endAt = exam.endTime?.let { ReminderRevision.schoolInstant(date, it) }
        val revision = ReminderRevision.computeEventRevision(
            eventKey = eventKey,
            type = ReminderEventType.EXAM,
            startAt = startAt,
            endAt = endAt,
            locationText = exam.rawRoom,
            buildingId = resolvedBuildingId,
            seatNumber = exam.seatNumber,
            isCancelled = false
        )
        return ReminderEvent(
            eventKey = eventKey,
            courseId = null,
            examId = exam.id,
            type = ReminderEventType.EXAM,
            displayTitle = exam.courseName,
            startAt = startAt,
            endAt = endAt,
            locationText = exam.rawRoom,
            buildingId = resolvedBuildingId,
            seatNumber = exam.seatNumber,
            examStageLabel = exam.examStageLabel,
            hasConflict = false,
            revision = revision,
            isCancelled = false
        )
    }

    /**
     * 复用 ScheduleEngine.resolveOccurrencesForDate() 与考试列表，生成指定时间窗口内的全部真实事件。
     */
    fun buildWindowEvents(
        now: Instant,
        horizonDays: Long = DEFAULT_PLANNING_HORIZON_DAYS,
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride>,
        occurrenceOverrides: List<OccurrenceOverride>,
        buildings: List<Building>,
        exams: List<ExamScheduleItem> = emptyList()
    ): List<ReminderEvent> {
        val startDate = now.atZone(HAUT_ZONE_ID).toLocalDate()
        val events = mutableListOf<ReminderEvent>()

        if (term != null) {
            for (dayOffset in 0L..horizonDays) {
                val date = startDate.plusDays(dayOffset)
                val occurrences = ScheduleEngine.resolveOccurrencesForDate(
                    date = date,
                    term = term,
                    courses = courses,
                    rules = rules,
                    periodTimes = periodTimes,
                    calendarOverrides = calendarOverrides,
                    occurrenceOverrides = occurrenceOverrides,
                    buildings = buildings
                )
                for (occ in occurrences) {
                    events.add(projectClassOccurrenceToEvent(term.id, occ))
                }
            }
        }

        // 考试事件：纳入所有具备明确日期与开始时间的考试（提前 1 天提醒可能在 8 天内触发）
        val maxExamDate = startDate.plusDays(horizonDays + 2L)
        for (exam in exams) {
            val examDate = exam.examDate ?: continue
            if (!examDate.isBefore(startDate) && !examDate.isAfter(maxExamDate)) {
                val projected = projectExamToEvent(exam)
                if (projected != null) {
                    events.add(projected)
                }
            }
        }

        return events
    }

    /**
     * 将单个真实事件按有效策略投影为期望提醒计划（DesiredReminderPlan）：
     * - 已错过的首次提醒时间默认不补发（要求 scheduledAt > now）。
     */
    fun planEvent(
        event: ReminderEvent,
        policy: EffectiveReminderPolicy,
        now: Instant
    ): List<DesiredReminderPlan> {
        if (!policy.enabled || event.isCancelled || !event.startAt.isAfter(now)) {
            return emptyList()
        }

        val kindAndLeadList: List<Pair<ReminderKind, Duration>> = when (event.type) {
            ReminderEventType.CLASS -> listOf(
                ReminderKind.CLASS_BEFORE to Duration.ofMinutes(policy.classLeadMinutes.toLong())
            )
            ReminderEventType.EXAM -> buildList {
                if (policy.examDayEnabled) {
                    add(ReminderKind.EXAM_DAY_BEFORE to Duration.ofDays(1))
                }
                if (policy.examHourEnabled) {
                    add(ReminderKind.EXAM_HOUR_BEFORE to Duration.ofHours(1))
                }
            }
        }

        return kindAndLeadList.mapNotNull { (kind, leadDuration) ->
            val triggerAt = event.startAt.minus(leadDuration)
            if (!triggerAt.isAfter(now)) {
                null
            } else {
                DesiredReminderPlan(
                    taskKey = "${event.eventKey}|${kind.code}",
                    eventKey = event.eventKey,
                    eventType = event.type,
                    reminderKind = kind,
                    eventRevision = event.revision,
                    policyRevision = policy.revision,
                    eventStartAt = event.startAt,
                    scheduledAt = triggerAt,
                    maxSnoozes = policy.maxSnoozes
                )
            }
        }
    }

    /**
     * 为窗口内所有事件生成期望提醒计划集合。
     */
    fun planAllEvents(
        events: List<ReminderEvent>,
        globalConfig: ReminderGlobalConfig,
        overridesByScope: Map<String, ReminderEventOverride>,
        now: Instant
    ): List<DesiredReminderPlan> {
        if (!globalConfig.enabled) return emptyList()
        return events.flatMap { event ->
            val effectivePolicy = ReminderPolicyEngine.resolveEffectivePolicy(
                event = event,
                globalConfig = globalConfig,
                overridesByScope = overridesByScope
            )
            planEvent(event, effectivePolicy, now)
        }
    }

    /**
     * 核心幂等对账算法（Reconcile Diff）：
     * - 对比 desiredPlans、当前有效 events 与数据库已有 persistedTasks
     * - 若任务内容（eventRevision）与策略（policyRevision）未变，严格保留已有 SNOOZED / FIRED / DONE 状态，绝不重置用户已点的延后
     * - 若课程调课/改教室导致 eventRevision 改变，递增 generation、重置 snoozeCount 并取消旧代 Alarm 和旧通知
     * - 若课程停课或提醒被关闭，取消所有未完结任务并回收旧代 Alarm 和活跃通知
     */
    fun computeReconcileDiff(
        currentEvents: List<ReminderEvent>,
        desiredPlans: List<DesiredReminderPlan>,
        persistedTasks: List<ReminderTaskDomain>,
        remindersEnabled: Boolean,
        now: Instant,
        idGenerator: () -> String = { UUID.randomUUID().toString() }
    ): ReconcilePlanDiff {
        val nowMs = now.toEpochMilli()
        val tasksToUpsert = mutableListOf<ReminderTaskDomain>()
        val alarmsToCancel = mutableListOf<Pair<String, Int>>()
        val notificationsToCancel = mutableListOf<String>()
        val tasksToSchedule = mutableListOf<ReminderTaskDomain>()

        if (!remindersEnabled) {
            for (existing in persistedTasks) {
                if (existing.status in setOf(
                        ReminderTaskStatus.PENDING,
                        ReminderTaskStatus.SNOOZED,
                        ReminderTaskStatus.CLAIMED,
                        ReminderTaskStatus.FIRED
                    )
                ) {
                    alarmsToCancel.add(existing.id to existing.generation)
                    notificationsToCancel.add(existing.id)
                    tasksToUpsert.add(
                        existing.copy(
                            status = ReminderTaskStatus.CANCELLED,
                            schedulerState = ReminderSchedulerState.NONE,
                            generation = existing.generation + 1,
                            updatedAtEpochMs = nowMs
                        )
                    )
                }
            }
            return ReconcilePlanDiff(
                tasksToUpsert = tasksToUpsert,
                alarmsToCancel = alarmsToCancel,
                notificationsToCancelTaskIds = notificationsToCancel,
                tasksToSchedule = emptyList()
            )
        }

        val currentEventsByKey = currentEvents.associateBy { it.eventKey }
        val desiredByTaskKey = desiredPlans.associateBy { it.taskKey }
        val existingByTaskKey = persistedTasks.associateBy { it.taskKey }

        // 1. 处理所有期望计划
        for (plan in desiredPlans) {
            val existing = existingByTaskKey[plan.taskKey]
            val targetMs = plan.scheduledAt.toEpochMilli()
            val startMs = plan.eventStartAt.toEpochMilli()

            if (existing == null) {
                val created = ReminderTaskDomain(
                    id = idGenerator(),
                    taskKey = plan.taskKey,
                    eventKey = plan.eventKey,
                    eventType = plan.eventType,
                    reminderKind = plan.reminderKind,
                    eventRevision = plan.eventRevision,
                    policyRevision = plan.policyRevision,
                    eventStartAtEpochMs = startMs,
                    originalScheduledAtEpochMs = targetMs,
                    scheduledAtEpochMs = targetMs,
                    status = ReminderTaskStatus.PENDING,
                    schedulerState = ReminderSchedulerState.NEEDS_SCHEDULE,
                    generation = 0,
                    snoozeCount = 0,
                    maxSnoozes = plan.maxSnoozes,
                    createdAtEpochMs = nowMs,
                    updatedAtEpochMs = nowMs
                )
                tasksToUpsert.add(created)
                tasksToSchedule.add(created)
                continue
            }

            val sameEventRev = existing.eventRevision == plan.eventRevision
            val samePolicyRev = existing.policyRevision == plan.policyRevision

            if (sameEventRev && samePolicyRev) {
                // 事件与策略均未变化：保持幂等，仅当处于需要修复调度状态时补排
                if (existing.status in setOf(ReminderTaskStatus.PENDING, ReminderTaskStatus.SNOOZED) &&
                    existing.schedulerState in setOf(ReminderSchedulerState.NEEDS_SCHEDULE, ReminderSchedulerState.ERROR) &&
                    existing.scheduledAtEpochMs > nowMs
                ) {
                    tasksToSchedule.add(existing)
                }
                continue
            }

            if (!sameEventRev) {
                // 事件本身发生变化（改时间、改教室等）：撤销旧代闹钟与旧通知，重置为新计划
                alarmsToCancel.add(existing.id to existing.generation)
                notificationsToCancel.add(existing.id)
                val updated = existing.copy(
                    eventRevision = plan.eventRevision,
                    policyRevision = plan.policyRevision,
                    eventStartAtEpochMs = startMs,
                    originalScheduledAtEpochMs = targetMs,
                    scheduledAtEpochMs = targetMs,
                    status = ReminderTaskStatus.PENDING,
                    schedulerState = ReminderSchedulerState.NEEDS_SCHEDULE,
                    generation = existing.generation + 1,
                    snoozeCount = 0,
                    maxSnoozes = plan.maxSnoozes,
                    claimedAtEpochMs = null,
                    deliveredAtEpochMs = null,
                    lastErrorCode = null,
                    updatedAtEpochMs = nowMs
                )
                tasksToUpsert.add(updated)
                tasksToSchedule.add(updated)
                continue
            }

            // 仅策略变化（如修改了提前分钟数），但若用户已经主动 SNOOZED 或 FIRED，保留用户当前延后操作
            if (existing.status in setOf(ReminderTaskStatus.SNOOZED, ReminderTaskStatus.FIRED, ReminderTaskStatus.DONE)) {
                val updatedPolicyOnly = existing.copy(
                    policyRevision = plan.policyRevision,
                    maxSnoozes = plan.maxSnoozes,
                    updatedAtEpochMs = nowMs
                )
                tasksToUpsert.add(updatedPolicyOnly)
            } else {
                alarmsToCancel.add(existing.id to existing.generation)
                val updated = existing.copy(
                    policyRevision = plan.policyRevision,
                    originalScheduledAtEpochMs = targetMs,
                    scheduledAtEpochMs = targetMs,
                    status = ReminderTaskStatus.PENDING,
                    schedulerState = ReminderSchedulerState.NEEDS_SCHEDULE,
                    generation = existing.generation + 1,
                    maxSnoozes = plan.maxSnoozes,
                    lastErrorCode = null,
                    updatedAtEpochMs = nowMs
                )
                tasksToUpsert.add(updated)
                tasksToSchedule.add(updated)
            }
        }

        // 2. 处理不在 desiredPlans 中的已持久化任务（可能是首次触发时间已过但正在 SNOOZED/FIRED，或课程已停课/删除）
        for (existing in persistedTasks) {
            if (existing.taskKey in desiredByTaskKey) continue
            if (existing.status in setOf(
                    ReminderTaskStatus.CANCELLED,
                    ReminderTaskStatus.DONE,
                    ReminderTaskStatus.EXPIRED
                )
            ) {
                continue
            }

            val currentEvent = currentEventsByKey[existing.eventKey]
            val eventStillValidAndSameRevision = currentEvent != null &&
                !currentEvent.isCancelled &&
                currentEvent.revision == existing.eventRevision &&
                currentEvent.startAt.isAfter(now)

            if (eventStillValidAndSameRevision &&
                existing.status in setOf(ReminderTaskStatus.SNOOZED, ReminderTaskStatus.FIRED, ReminderTaskStatus.CLAIMED)
            ) {
                // 首次触发时间虽已过（因此不在 desiredPlans 中），但事件仍有效且任务处于已触发或延后等待中，予以保留！
                if (existing.status == ReminderTaskStatus.SNOOZED &&
                    existing.schedulerState in setOf(ReminderSchedulerState.NEEDS_SCHEDULE, ReminderSchedulerState.ERROR) &&
                    existing.scheduledAtEpochMs > nowMs
                ) {
                    tasksToSchedule.add(existing)
                }
                continue
            }

            // 若事件已不存在、已取消、或版本已变，或者 PENDING 任务已过期：
            alarmsToCancel.add(existing.id to existing.generation)
            notificationsToCancel.add(existing.id)
            val nextStatus = if (currentEvent == null || currentEvent.isCancelled || currentEvent.revision != existing.eventRevision) {
                ReminderTaskStatus.CANCELLED
            } else {
                ReminderTaskStatus.EXPIRED
            }
            tasksToUpsert.add(
                existing.copy(
                    status = nextStatus,
                    schedulerState = ReminderSchedulerState.NONE,
                    generation = existing.generation + 1,
                    updatedAtEpochMs = nowMs
                )
            )
        }

        return ReconcilePlanDiff(
            tasksToUpsert = tasksToUpsert,
            alarmsToCancel = alarmsToCancel,
            notificationsToCancelTaskIds = notificationsToCancel,
            tasksToSchedule = tasksToSchedule
        )
    }
}
