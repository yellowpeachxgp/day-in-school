package cn.edu.haut.campus.core.reminder

import android.content.Context
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.toDomain
import cn.edu.haut.campus.core.database.toEntity
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.ReminderPlanner
import cn.edu.haut.campus.core.domain.ReminderPolicyEngine
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderSchedulerState
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import cn.edu.haut.campus.core.model.SnoozeChoice
import cn.edu.haut.campus.core.model.SnoozeOptionEvaluation
import cn.edu.haut.campus.core.model.SnoozeOutcome
import cn.edu.haut.campus.core.model.SnoozeRejectReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ReconcileReport(
    val reason: String,
    val activeScheduledCount: Int,
    val exactScheduledCount: Int,
    val inexactScheduledCount: Int,
    val cancelledCount: Int,
    val nextTriggerEpochMs: Long?
)

class ReminderCoordinator internal constructor(
    private val appContext: Context,
    private val repository: CampusRepository,
    private val alarmScheduler: AlarmScheduler,
    private val accountScope: AccountBackgroundScope
) {
    @Volatile private var active = true
    private fun requireActive() {
        check(active && repository.isSessionActive) { "账号已切换，请返回当前账号重试" }
    }
    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reconcileMutex = Mutex()
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    fun initialize() {
        ReminderNotificationFactory.ensureChannels(appContext)

        repository.onScheduleOrPolicyChangedListener = { reason ->
            coordinatorScope.launch {
                if (active && repository.isSessionActive) reconcileNow(reason = reason, forceRescheduleAlarms = false)
            }
        }

        repository.onBeforeWipePersonalDataHook = {
            cancelAllActiveAlarmsAndNotifications()
        }

        ReminderReconcileWorker.enqueuePeriodic(appContext, accountScope)
        coordinatorScope.launch {
            if (active && repository.isSessionActive) reconcileNow(reason = "APP_STARTUP", forceRescheduleAlarms = true)
        }
    }

    suspend fun reconcileNow(
        reason: String,
        forceRescheduleAlarms: Boolean = false,
        now: Instant = Instant.now()
    ): ReconcileReport = reconcileMutex.withLock {
        requireActive()
        val nowMs = now.toEpochMilli()
        val reminderDao = repository.reminderDao()

        // 1. 回收超过 60 秒未完成的 CLAIMED 孤儿任务
        reminderDao.recoverStaleClaimedTasks(
            staleBeforeMs = nowMs - 60_000L,
            nowMs = nowMs
        )

        // 2. 读取课表、考试、全局配置、单课程覆盖规则与已持久化任务
        val scheduleSnapshot = repository.getDirectScheduleDataSnapshot()
        val exams = repository.getExamSchedulesSnapshot()
        val globalConfig = repository.getReminderConfigSnapshot()
        val overrides = repository.getReminderEventOverridesSnapshot()
            .associateBy { it.eventScopeKey }

        val windowStartMs = now.minus(Duration.ofDays(1)).toEpochMilli()
        val windowEndMs = now.plus(Duration.ofDays(10)).toEpochMilli()
        val tasksInWindow = reminderDao.getTasksInEventWindow(windowStartMs, windowEndMs).map { it.toDomain() }
        val activeTasks = reminderDao.getAllActiveTasksSnapshot().map { it.toDomain() }
        val persistedTasks = (tasksInWindow + activeTasks).distinctBy { it.id }

        val currentEvents = buildCurrentWindowEvents(
            now = now,
            scheduleSnapshot = scheduleSnapshot,
            exams = exams
        )
        val desiredPlans = ReminderPlanner.planAllEvents(
            events = currentEvents,
            globalConfig = globalConfig,
            overridesByScope = overrides,
            now = now
        )

        val diff = ReminderPlanner.computeReconcileDiff(
            currentEvents = currentEvents,
            desiredPlans = desiredPlans,
            persistedTasks = persistedTasks,
            remindersEnabled = globalConfig.enabled,
            now = now
        )

        // 3. 取消已失效的旧代闹钟与旧通知
        for ((taskId, generation) in diff.alarmsToCancel) {
            alarmScheduler.cancel(taskId, generation)
        }
        for (taskId in diff.notificationsToCancelTaskIds) {
            ReminderNotificationFactory.cancelNotificationForTask(appContext, taskId)
        }

        // 4. 持久化对账状态变更
        if (diff.tasksToUpsert.isNotEmpty()) {
            reminderDao.upsertTasks(diff.tasksToUpsert.map { it.toEntity() })
        }

        // 5. 计算需要向 AlarmManager 注册的任务集合
        val tasksToScheduleMap = linkedMapOf<String, ReminderTaskDomain>()
        for (task in diff.tasksToSchedule) {
            if (task.scheduledAtEpochMs > nowMs) {
                tasksToScheduleMap[task.id] = task
            }
        }
        if (forceRescheduleAlarms && globalConfig.enabled) {
            val latestActive = reminderDao.getAllActiveTasksSnapshot().map { it.toDomain() }
            for (task in latestActive) {
                if (task.status in setOf(ReminderTaskStatus.PENDING, ReminderTaskStatus.SNOOZED) &&
                    task.scheduledAtEpochMs > nowMs
                ) {
                    tasksToScheduleMap[task.id] = task
                }
            }
        }

        var exactCount = 0
        var inexactCount = 0
        for (task in tasksToScheduleMap.values) {
            when (val outcome = alarmScheduler.schedule(task, preferExact = globalConfig.preciseReminderPreferred)) {
                is ScheduleOutcome.Scheduled -> {
                    if (outcome.mode == ReminderAccuracyMode.EXACT) exactCount++ else inexactCount++
                    reminderDao.markScheduleResultIfGenerationMatches(
                        taskId = task.id,
                        expectedGeneration = task.generation,
                        schedulerState = outcome.schedulerState.name,
                        scheduleMode = outcome.mode.name,
                        errorCode = outcome.fallbackNote,
                        nowMs = nowMs
                    )
                }
                is ScheduleOutcome.Failed -> {
                    reminderDao.markScheduleResultIfGenerationMatches(
                        taskId = task.id,
                        expectedGeneration = task.generation,
                        schedulerState = ReminderSchedulerState.ERROR.name,
                        scheduleMode = ReminderAccuracyMode.NONE.name,
                        errorCode = outcome.errorCode,
                        nowMs = nowMs
                    )
                }
            }
        }

        // 6. 清理 14 天前的历史终态记录
        reminderDao.pruneHistoricalTasks(
            olderThanEpochMs = now.minus(Duration.ofDays(14)).toEpochMilli()
        )

        val remainingActive = reminderDao.getAllActiveTasksSnapshot()
            .map { it.toDomain() }
            .filter { it.status in setOf(ReminderTaskStatus.PENDING, ReminderTaskStatus.SNOOZED) }
            .sortedBy { it.scheduledAtEpochMs }

        val nextTriggerMs = remainingActive.firstOrNull()?.scheduledAtEpochMs
        val nextTriggerText = nextTriggerMs?.let {
            Instant.ofEpochMilli(it).atZone(HAUT_ZONE_ID).format(timeFormatter)
        } ?: "暂无"

        val summary = "待提醒 ${remainingActive.size} 项 · 下次：$nextTriggerText"
        repository.appendReminderDiagnosticLog(
            message = "已更新提醒计划($reason)：待提醒 ${remainingActive.size} 项，变更 ${diff.tasksToUpsert.size} 项，取消 ${diff.alarmsToCancel.size} 项",
            reconcileSummary = summary
        )

        runCatching {
            syncLiveCourseNotification(
                now = now,
                currentEvents = currentEvents,
                globalConfig = globalConfig
            )
        }

        ReconcileReport(
            reason = reason,
            activeScheduledCount = remainingActive.size,
            exactScheduledCount = exactCount,
            inexactScheduledCount = inexactCount,
            cancelledCount = diff.alarmsToCancel.size,
            nextTriggerEpochMs = nextTriggerMs
        )
    }

    suspend fun onAlarmFired(
        taskId: String,
        expectedGeneration: Int,
        now: Instant = Instant.now()
    ) {
        if (taskId == LIVE_COURSE_BOUNDARY_TASK_ID) {
            runCatching {
                val scheduleSnapshot = repository.getDirectScheduleDataSnapshot()
                val exams = repository.getExamSchedulesSnapshot()
                val globalConfig = repository.getReminderConfigSnapshot()
                val currentEvents = buildCurrentWindowEvents(now, scheduleSnapshot, exams)
                syncLiveCourseNotification(now, currentEvents, globalConfig)
            }
            return
        }
        val nowMs = now.toEpochMilli()
        val reminderDao = repository.reminderDao()

        // 1. 原子 CAS 认领（防重放、防旧代广播误响）
        val claimedRows = reminderDao.claimFire(
            taskId = taskId,
            expectedGeneration = expectedGeneration,
            nowMs = nowMs
        )
        if (claimedRows == 0) {
            repository.appendReminderDiagnosticLog("忽略过期或重复广播：task=$taskId gen=$expectedGeneration")
            return
        }

        val task = reminderDao.getById(taskId)?.toDomain() ?: return
        val globalConfig = repository.getReminderConfigSnapshot()
        if (!globalConfig.enabled) {
            reminderDao.markTerminal(
                taskId = taskId,
                terminalStatus = ReminderTaskStatus.CANCELLED.name,
                reason = "GLOBAL_REMINDERS_DISABLED",
                nowMs = nowMs
            )
            return
        }

        val scheduleSnapshot = repository.getDirectScheduleDataSnapshot()
        val exams = repository.getExamSchedulesSnapshot()
        val currentEvents = buildCurrentWindowEvents(now, scheduleSnapshot, exams)
        val currentEvent = currentEvents.firstOrNull { it.eventKey == task.eventKey }

        if (currentEvent == null || currentEvent.isCancelled || currentEvent.revision != task.eventRevision) {
            reminderDao.markTerminal(
                taskId = taskId,
                terminalStatus = ReminderTaskStatus.CANCELLED.name,
                reason = "EVENT_CHANGED_OR_CANCELLED_AT_FIRE",
                nowMs = nowMs
            )
            ReminderNotificationFactory.cancelNotificationForTask(appContext, taskId)
            repository.appendReminderDiagnosticLog("事件已变更或取消，跳过通知：${task.eventKey}")
            return
        }

        val eventEndInstant = currentEvent.endAt ?: currentEvent.startAt.plus(Duration.ofMinutes(95))
        if (!now.isBefore(eventEndInstant)) {
            reminderDao.markTerminal(
                taskId = taskId,
                terminalStatus = ReminderTaskStatus.EXPIRED.name,
                reason = "EVENT_ALREADY_ENDED",
                nowMs = nowMs
            )
            return
        }

        // 检查宽限窗口（仅针对未延后的首次提醒，防止关机很久后补发已无意义的旧提醒）
        if (task.snoozeCount == 0 && !now.isBefore(currentEvent.startAt)) {
            reminderDao.markTerminal(
                taskId = taskId,
                terminalStatus = ReminderTaskStatus.EXPIRED.name,
                reason = "MISSED_PAST_EVENT_START",
                nowMs = nowMs
            )
            return
        }

        val permSnapshot = ReminderPermissions.inspect(appContext)
        if (!permSnapshot.canPostNotifications) {
            reminderDao.markTerminal(
                taskId = taskId,
                terminalStatus = ReminderTaskStatus.SUPPRESSED.name,
                reason = "NOTIFICATION_PERMISSION_DENIED",
                nowMs = nowMs
            )
            repository.appendReminderDiagnosticLog("提醒被静默抑制（未授予通知权限）：${currentEvent.displayTitle}")
            return
        }

        val overrides = repository.getReminderEventOverridesSnapshot().associateBy { it.eventScopeKey }
        val effectivePolicy = ReminderPolicyEngine.resolveEffectivePolicy(
            event = currentEvent,
            globalConfig = globalConfig,
            overridesByScope = overrides
        )
        if (!effectivePolicy.enabled) {
            reminderDao.markTerminal(
                taskId = taskId,
                terminalStatus = ReminderTaskStatus.CANCELLED.name,
                reason = "EVENT_POLICY_DISABLED",
                nowMs = nowMs
            )
            return
        }

        if (!active || !repository.isSessionActive) return
        val posted = ReminderNotificationFactory.postReminderNotification(
            context = appContext,
            accountScope = accountScope,
            task = task,
            event = currentEvent,
            policy = effectivePolicy,
            lockscreenPrivate = globalConfig.lockscreenPrivate,
            now = now
        )
        if (posted) {
            reminderDao.markFired(
                taskId = taskId,
                expectedGeneration = expectedGeneration,
                nowMs = nowMs
            )
            repository.appendReminderDiagnosticLog(
                message = "已发送提醒：${currentEvent.displayTitle} (第${task.snoozeCount + 1}次提醒)"
            )
            runCatching {
                syncLiveCourseNotification(
                    now = now,
                    currentEvents = currentEvents,
                    globalConfig = globalConfig
                )
            }
        } else {
            reminderDao.markTerminal(
                taskId = taskId,
                terminalStatus = ReminderTaskStatus.SUPPRESSED.name,
                reason = "NOTIFICATION_POST_FAILED",
                nowMs = nowMs
            )
        }
    }

    suspend fun snoozeTask(
        taskId: String,
        expectedGeneration: Int?,
        choice: SnoozeChoice,
        now: Instant = Instant.now()
    ): SnoozeOutcome {
        val nowMs = now.toEpochMilli()
        val reminderDao = repository.reminderDao()
        val existing = reminderDao.getById(taskId)?.toDomain()
            ?: return SnoozeOutcome.NotFound

        if (expectedGeneration != null && existing.generation != expectedGeneration) {
            return SnoozeOutcome.ConcurrentlyHandled
        }

        val globalConfig = repository.getReminderConfigSnapshot()
        val overrides = repository.getReminderEventOverridesSnapshot().associateBy { it.eventScopeKey }
        val scheduleSnapshot = repository.getDirectScheduleDataSnapshot()
        val exams = repository.getExamSchedulesSnapshot()
        val currentEvents = buildCurrentWindowEvents(now, scheduleSnapshot, exams)
        val currentEvent = currentEvents.firstOrNull { it.eventKey == existing.eventKey }

        if (currentEvent != null && currentEvent.revision != existing.eventRevision) {
            return SnoozeOutcome.EventChanged
        }

        val effectivePolicy = if (currentEvent != null) {
            ReminderPolicyEngine.resolveEffectivePolicy(currentEvent, globalConfig, overrides)
        } else {
            ReminderPolicyEngine.resolveEffectivePolicy(
                event = ReminderEvent(
                    eventKey = existing.eventKey,
                    type = existing.eventType,
                    displayTitle = existing.eventKey,
                    startAt = Instant.ofEpochMilli(existing.eventStartAtEpochMs),
                    endAt = null,
                    locationText = null,
                    buildingId = null,
                    revision = existing.eventRevision
                ),
                globalConfig = globalConfig,
                overridesByScope = overrides
            )
        }

        val permSnapshot = ReminderPermissions.inspect(appContext)
        val candidateTime = ReminderPolicyEngine.resolveRequestedTime(
            choice = choice,
            now = now,
            eventStartAt = currentEvent?.startAt ?: Instant.ofEpochMilli(existing.eventStartAtEpochMs)
        )

        val rejectReason = ReminderPolicyEngine.validateSnooze(
            task = existing,
            currentEvent = currentEvent,
            policy = effectivePolicy,
            candidateTime = candidateTime,
            now = now,
            notificationsAllowed = permSnapshot.canPostNotifications
        )
        if (rejectReason != null) {
            return SnoozeOutcome.Rejected(rejectReason)
        }

        val candidateMs = candidateTime.toEpochMilli()
        val updatedRows = reminderDao.compareAndSnooze(
            taskId = existing.id,
            expectedGeneration = existing.generation,
            expectedEventRevision = existing.eventRevision,
            newTriggerMs = candidateMs,
            nowMs = nowMs
        )
        if (updatedRows == 0) {
            return SnoozeOutcome.ConcurrentlyHandled
        }

        // 撤销旧代闹钟并收起当前通知
        alarmScheduler.cancel(existing.id, existing.generation)
        ReminderNotificationFactory.cancelNotificationForTask(appContext, existing.id)

        val updatedTask = reminderDao.getById(existing.id)?.toDomain()
            ?: return SnoozeOutcome.InternalError

        return when (val scheduleOutcome = alarmScheduler.schedule(updatedTask, preferExact = globalConfig.preciseReminderPreferred)) {
            is ScheduleOutcome.Scheduled -> {
                reminderDao.markScheduleResultIfGenerationMatches(
                    taskId = updatedTask.id,
                    expectedGeneration = updatedTask.generation,
                    schedulerState = scheduleOutcome.schedulerState.name,
                    scheduleMode = scheduleOutcome.mode.name,
                    errorCode = scheduleOutcome.fallbackNote,
                    nowMs = nowMs
                )
                val formattedTime = candidateTime.atZone(HAUT_ZONE_ID).toLocalTime().toString()
                repository.appendReminderDiagnosticLog(
                    message = "稍后提醒已设定：${currentEvent?.displayTitle ?: existing.eventKey} -> $formattedTime (已延后 ${updatedTask.snoozeCount}/${updatedTask.maxSnoozes} 次)"
                )
                SnoozeOutcome.Accepted(
                    triggerAt = candidateTime,
                    exact = scheduleOutcome.mode == ReminderAccuracyMode.EXACT,
                    snoozeCount = updatedTask.snoozeCount,
                    remainingSnoozes = (updatedTask.maxSnoozes - updatedTask.snoozeCount).coerceAtLeast(0)
                )
            }
            is ScheduleOutcome.Failed -> {
                reminderDao.markScheduleResultIfGenerationMatches(
                    taskId = updatedTask.id,
                    expectedGeneration = updatedTask.generation,
                    schedulerState = ReminderSchedulerState.ERROR.name,
                    scheduleMode = ReminderAccuracyMode.NONE.name,
                    errorCode = scheduleOutcome.errorCode,
                    nowMs = nowMs
                )
                ReminderReconcileWorker.enqueueImmediate(
                    context = appContext,
                    accountScope = accountScope,
                    reason = "SNOOZE_SCHEDULE_REPAIR"
                )
                SnoozeOutcome.AcceptedPendingRepair(
                    triggerAt = candidateTime,
                    reason = scheduleOutcome.errorCode
                )
            }
        }
    }

    /**
     * 从 App 内（如「今天」页课程卡片）直接为某节课/某场考试设置「稍后提醒」：
     * 若数据库中尚无活跃任务（例如用户在首次提醒触发前或首次提醒被跳过后主动点按），自动创建或激活任务并执行延后。
     */
    suspend fun snoozeEventFromUi(
        event: ReminderEvent,
        kind: ReminderKind = ReminderKind.CLASS_BEFORE,
        choice: SnoozeChoice,
        now: Instant = Instant.now()
    ): SnoozeOutcome {
        val nowMs = now.toEpochMilli()
        val reminderDao = repository.reminderDao()
        val taskKey = "${event.eventKey}|${kind.code}"
        val globalConfig = repository.getReminderConfigSnapshot()
        if (!globalConfig.enabled) {
            // 若用户主动在课程上点「稍后提醒」，自动开启全局提醒开关以响应用户意图
            repository.updateReminderGlobalConfig { it.copy(enabled = true) }
        }
        val latestGlobal = repository.getReminderConfigSnapshot()
        val overrides = repository.getReminderEventOverridesSnapshot().associateBy { it.eventScopeKey }
        val effectivePolicy = ReminderPolicyEngine.resolveEffectivePolicy(event, latestGlobal, overrides)

        val existing = reminderDao.getByTaskKey(taskKey)?.toDomain()
        val targetTaskId = if (existing == null ||
            existing.status in setOf(
                ReminderTaskStatus.CANCELLED,
                ReminderTaskStatus.DONE,
                ReminderTaskStatus.EXPIRED,
                ReminderTaskStatus.SUPPRESSED
            ) ||
            existing.eventRevision != event.revision
        ) {
            val freshTask = ReminderTaskDomain(
                id = existing?.id ?: UUID.randomUUID().toString(),
                taskKey = taskKey,
                eventKey = event.eventKey,
                eventType = event.type,
                reminderKind = kind,
                eventRevision = event.revision,
                policyRevision = effectivePolicy.revision,
                eventStartAtEpochMs = event.startAt.toEpochMilli(),
                originalScheduledAtEpochMs = nowMs,
                scheduledAtEpochMs = nowMs,
                status = ReminderTaskStatus.FIRED,
                schedulerState = ReminderSchedulerState.NONE,
                generation = (existing?.generation ?: 0) + 1,
                snoozeCount = 0,
                maxSnoozes = effectivePolicy.maxSnoozes,
                createdAtEpochMs = existing?.createdAtEpochMs ?: nowMs,
                updatedAtEpochMs = nowMs
            )
            reminderDao.upsertTask(freshTask.toEntity())
            freshTask.id
        } else {
            existing.id
        }

        return snoozeTask(
            taskId = targetTaskId,
            expectedGeneration = null,
            choice = choice,
            now = now
        )
    }

    /**
     * 为 UI 弹层评估指定事件的全部「稍后提醒」候选项状态。
     */
    suspend fun evaluateSnoozeOptionsForEvent(
        event: ReminderEvent,
        kind: ReminderKind = ReminderKind.CLASS_BEFORE,
        now: Instant = Instant.now()
    ): List<SnoozeOptionEvaluation> {
        val reminderDao = repository.reminderDao()
        val taskKey = "${event.eventKey}|${kind.code}"
        val globalConfig = repository.getReminderConfigSnapshot().copy(enabled = true)
        val overrides = repository.getReminderEventOverridesSnapshot().associateBy { it.eventScopeKey }
        val policy = ReminderPolicyEngine.resolveEffectivePolicy(event, globalConfig, overrides)
        val existing = reminderDao.getByTaskKey(taskKey)?.toDomain()

        val syntheticOrExistingTask = if (existing != null &&
            existing.eventRevision == event.revision &&
            existing.status in setOf(ReminderTaskStatus.PENDING, ReminderTaskStatus.SNOOZED, ReminderTaskStatus.FIRED)
        ) {
            existing
        } else {
            ReminderTaskDomain(
                id = existing?.id ?: "preview",
                taskKey = taskKey,
                eventKey = event.eventKey,
                eventType = event.type,
                reminderKind = kind,
                eventRevision = event.revision,
                policyRevision = policy.revision,
                eventStartAtEpochMs = event.startAt.toEpochMilli(),
                originalScheduledAtEpochMs = now.toEpochMilli(),
                scheduledAtEpochMs = now.toEpochMilli(),
                status = ReminderTaskStatus.FIRED,
                schedulerState = ReminderSchedulerState.NONE,
                generation = 0,
                snoozeCount = 0,
                maxSnoozes = policy.maxSnoozes,
                createdAtEpochMs = now.toEpochMilli(),
                updatedAtEpochMs = now.toEpochMilli()
            )
        }

        val permSnapshot = ReminderPermissions.inspect(appContext)
        return ReminderPolicyEngine.evaluateSnoozeOptions(
            task = syntheticOrExistingTask,
            currentEvent = event,
            policy = policy,
            now = now,
            notificationsAllowed = permSnapshot.canPostNotifications
        )
    }

    suspend fun dismissTask(
        taskId: String,
        expectedGeneration: Int?,
        now: Instant = Instant.now()
    ) {
        val nowMs = now.toEpochMilli()
        val reminderDao = repository.reminderDao()
        val existing = reminderDao.getById(taskId)?.toDomain() ?: return
        if (expectedGeneration != null && existing.generation != expectedGeneration) return
        if (existing.status == ReminderTaskStatus.FIRED) {
            reminderDao.markTerminal(
                taskId = taskId,
                terminalStatus = ReminderTaskStatus.DONE.name,
                reason = "USER_DISMISSED_NOTIFICATION",
                nowMs = nowMs
            )
        }
    }

    suspend fun triggerTestReminder(): Boolean {
        val config = repository.getReminderConfigSnapshot()
        val ok = ReminderNotificationFactory.postTestNotification(
            context = appContext,
            accountScope = accountScope,
            lockscreenPrivate = config.lockscreenPrivate
        )
        runCatching {
            val now = Instant.now()
            val scheduleSnapshot = repository.getDirectScheduleDataSnapshot()
            val exams = repository.getExamSchedulesSnapshot()
            val events = buildCurrentWindowEvents(now, scheduleSnapshot, exams)
                .filter { !it.isCancelled }
                .sortedBy { it.startAt }
            val liveCandidate = events.firstOrNull { event ->
                val endAt = event.endAt ?: event.startAt.plus(Duration.ofMinutes(95))
                now.isBefore(endAt)
            } ?: ReminderEvent(
                eventKey = "demo_live_course",
                courseId = "demo_live_course",
                type = cn.edu.haut.campus.core.model.ReminderEventType.CLASS,
                displayTitle = "算法设计与分析（实况预览）",
                startAt = now.plus(Duration.ofMinutes(18)),
                endAt = now.plus(Duration.ofMinutes(113)),
                locationText = "莲花街校区 1号教学楼1104",
                buildingId = "b_t1",
                seatNumber = null,
                isCancelled = false,
                revision = "v1"
            )
            ReminderNotificationFactory.postOrUpdateLiveCourseNotification(
                context = appContext,
                accountScope = accountScope,
                event = liveCandidate,
                nextEventTitleAndRoom = "计算机网络 · 2号教学楼2302",
                lockscreenPrivate = config.lockscreenPrivate,
                now = now
            )
        }
        repository.appendReminderDiagnosticLog(
            if (ok) "已发送测试提醒通知与课程实时实况倒计时通知" else "发送测试提醒失败：请检查系统通知权限"
        )
        return ok
    }

    /**
     * 同步刷新「课程实时实况通知」：
     * - 若当前有正在进行的课程（startAt <= now < endAt）或即将开始的课程（距离 startAt <= max(45min, classLeadMinutes)），
     *   调用原生 Notification Chronometer 发布/更新实时倒计时实况卡片，并在边界点（startAt / endAt）注册精确切换闹钟。
     */
    private fun syncLiveCourseNotification(
        now: Instant,
        currentEvents: List<ReminderEvent>,
        globalConfig: cn.edu.haut.campus.core.model.ReminderGlobalConfig
    ) {
        if (!globalConfig.enabled) {
            ReminderNotificationFactory.cancelLiveCourseNotification(appContext)
            alarmScheduler.cancel(LIVE_COURSE_BOUNDARY_TASK_ID, 1)
            return
        }
        val leadWindowMinutes = maxOf(45L, globalConfig.classLeadMinutes.toLong())
        val activeOrUpcoming = currentEvents
            .filter { !it.isCancelled }
            .sortedBy { it.startAt }
            .firstOrNull { event ->
                val endAt = event.endAt ?: event.startAt.plus(Duration.ofMinutes(95))
                val inClass = !now.isBefore(event.startAt) && now.isBefore(endAt)
                val preClass = now.isBefore(event.startAt) &&
                    Duration.between(now, event.startAt).toMinutes() <= leadWindowMinutes
                inClass || preClass
            }

        if (activeOrUpcoming == null) {
            ReminderNotificationFactory.cancelLiveCourseNotification(appContext)
            alarmScheduler.cancel(LIVE_COURSE_BOUNDARY_TASK_ID, 1)
            return
        }

        val nextCourse = currentEvents
            .filter { !it.isCancelled && it.startAt.isAfter(activeOrUpcoming.startAt) }
            .minByOrNull { it.startAt }
            ?.let { nxt ->
                val t = nxt.startAt.atZone(HAUT_ZONE_ID).toLocalTime()
                val rm = nxt.locationText?.takeIf { it.isNotBlank() } ?: "教室待定"
                "${nxt.displayTitle} (${"%02d:%02d".format(t.hour, t.minute)} · $rm)"
            }

        ReminderNotificationFactory.postOrUpdateLiveCourseNotification(
            context = appContext,
            accountScope = accountScope,
            event = activeOrUpcoming,
            nextEventTitleAndRoom = nextCourse,
            lockscreenPrivate = globalConfig.lockscreenPrivate,
            now = now
        )

        val endInstant = activeOrUpcoming.endAt ?: activeOrUpcoming.startAt.plus(Duration.ofMinutes(95))
        val nextBoundaryInstant = if (now.isBefore(activeOrUpcoming.startAt)) {
            activeOrUpcoming.startAt
        } else {
            endInstant
        }
        if (nextBoundaryInstant.isAfter(now)) {
            val boundaryTask = ReminderTaskDomain(
                id = LIVE_COURSE_BOUNDARY_TASK_ID,
                taskKey = LIVE_COURSE_BOUNDARY_TASK_ID,
                eventKey = activeOrUpcoming.eventKey,
                eventType = activeOrUpcoming.type,
                reminderKind = ReminderKind.CLASS_BEFORE,
                eventRevision = activeOrUpcoming.revision,
                policyRevision = "v1",
                eventStartAtEpochMs = activeOrUpcoming.startAt.toEpochMilli(),
                originalScheduledAtEpochMs = nextBoundaryInstant.toEpochMilli(),
                scheduledAtEpochMs = nextBoundaryInstant.toEpochMilli(),
                status = ReminderTaskStatus.PENDING,
                schedulerState = ReminderSchedulerState.NONE,
                generation = 1,
                snoozeCount = 0,
                maxSnoozes = 0,
                createdAtEpochMs = now.toEpochMilli(),
                updatedAtEpochMs = now.toEpochMilli()
            )
            alarmScheduler.schedule(boundaryTask, preferExact = globalConfig.preciseReminderPreferred)
        }
    }

    suspend fun cancelAllActiveAlarmsAndNotifications() {
        val reminderDao = repository.reminderDao()
        val activeTasks = reminderDao.getAllActiveTasksSnapshot().map { it.toDomain() }
        for (task in activeTasks) {
            alarmScheduler.cancel(task.id, task.generation)
            ReminderNotificationFactory.cancelNotificationForTask(appContext, task.id)
        }
        ReminderNotificationFactory.cancelAllNotifications(appContext)
        reminderDao.cancelAllActiveTasks(
            reason = "WIPED_OR_CANCELLED_ALL",
            nowMs = System.currentTimeMillis()
        )
    }

    private fun buildCurrentWindowEvents(
        now: Instant,
        scheduleSnapshot: cn.edu.haut.campus.core.database.DirectScheduleData,
        exams: List<cn.edu.haut.campus.core.model.ExamScheduleItem>
    ): List<ReminderEvent> {
        val baseEvents = ReminderPlanner.buildWindowEvents(
            now = now,
            horizonDays = ReminderPlanner.DEFAULT_PLANNING_HORIZON_DAYS,
            term = scheduleSnapshot.term,
            courses = scheduleSnapshot.courses,
            rules = scheduleSnapshot.rules,
            periodTimes = scheduleSnapshot.periodTimes,
            calendarOverrides = scheduleSnapshot.calendarOverrides,
            occurrenceOverrides = scheduleSnapshot.occurrenceOverrides,
            buildings = scheduleSnapshot.buildings,
            exams = emptyList()
        )
        val startDate = now.atZone(HAUT_ZONE_ID).toLocalDate()
        val maxExamDate = startDate.plusDays(ReminderPlanner.DEFAULT_PLANNING_HORIZON_DAYS + 2L)
        val examEvents = exams.mapNotNull { exam ->
            val examDate = exam.examDate ?: return@mapNotNull null
            if (examDate.isBefore(startDate) || examDate.isAfter(maxExamDate)) {
                return@mapNotNull null
            }
            val resolvedBuildingId = ClassroomBuildingResolver.resolve(
                rawRoom = exam.rawRoom,
                buildings = scheduleSnapshot.buildings,
                aliases = emptyList()
            ).building?.id
            ReminderPlanner.projectExamToEvent(exam, resolvedBuildingId)
        }
        return baseEvents + examEvents
    }

    suspend fun deactivate() {
        active = false
        repository.onScheduleOrPolicyChangedListener = null
        repository.onBeforeWipePersonalDataHook = null
        coordinatorScope.cancel()
        ReminderReconcileWorker.cancelPeriodicAndImmediate(appContext, accountScope)
        reconcileMutex.withLock {
            repository.reminderDao().getAllActiveTasksSnapshot().forEach { task ->
                alarmScheduler.cancel(task.id, task.generation)
            }
            ReminderNotificationFactory.cancelAllNotifications(appContext)
        }
    }

    companion object {
        const val LIVE_COURSE_BOUNDARY_TASK_ID = "__live_course_boundary__"
        @Volatile private var INSTANCE: ReminderCoordinator? = null

        fun bind(context: Context, session: ActiveSession): ReminderCoordinator = synchronized(this) {
            val scope = AccountBackgroundScope.from(session)
            val scheduler = AndroidAlarmScheduler(context.applicationContext, scope) { session.repository.isSessionActive }
            ReminderCoordinator(context.applicationContext, session.repository, scheduler, scope).also { INSTANCE = it }
        }

        suspend fun deactivateCurrent() {
            val old = synchronized(this) { INSTANCE.also { INSTANCE = null } }
            old?.deactivate()
        }

        fun getInstance(context: Context): ReminderCoordinator {
            val session = AccountSessionCoordinator.getInstance(context).currentSnapshot()
                ?: error("账号正在初始化或切换，请稍后重试")
            return INSTANCE?.takeIf { it.active && it.accountScope.matches(session) }
                ?: error("提醒尚未绑定当前账号")
        }
    }
}
