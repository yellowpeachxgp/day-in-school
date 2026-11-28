package cn.edu.haut.campus.core.automation.android

import android.content.Context
import androidx.room.withTransaction
import cn.edu.haut.campus.core.automation.AutomationEngine
import cn.edu.haut.campus.core.automation.AutomationExtensionRegistry
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.BuiltinActionExecutors
import cn.edu.haut.campus.core.automation.BuiltinConditionEvaluators
import cn.edu.haut.campus.core.automation.BuiltinTriggerPlanner
import cn.edu.haut.campus.core.automation.CommuteDurationPort
import cn.edu.haut.campus.core.automation.DataFreshnessPort
import cn.edu.haut.campus.core.automation.DefaultAutomationEngine
import cn.edu.haut.campus.core.automation.FocusStatePort
import cn.edu.haut.campus.core.automation.HomeRefreshActionPort
import cn.edu.haut.campus.core.automation.PlannedTriggerInstance
import cn.edu.haut.campus.core.automation.TriggerExecutionResult
import cn.edu.haut.campus.core.automation.TriggerInstance
import cn.edu.haut.campus.core.automation.TriggerSpec
import cn.edu.haut.campus.core.automation.builtinTriggerProviders
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.automationDatabase
import cn.edu.haut.campus.core.database.automationEntityWritePort
import cn.edu.haut.campus.core.database.automationScope
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.reminder.AccountBackgroundScope
import cn.edu.haut.campus.core.timeline.CourseTimelineSource
import cn.edu.haut.campus.core.timeline.DefaultTimelineService
import cn.edu.haut.campus.core.timeline.ExamTimelineSource
import cn.edu.haut.campus.core.timeline.PersonalTimelineSource
import cn.edu.haut.campus.core.timeline.PracticeTimelineSource
import cn.edu.haut.campus.core.timeline.TimelineService
import cn.edu.haut.campus.core.vault.EntityWriter
import cn.edu.haut.campus.core.vault.RoomEventOutbox
import cn.edu.haut.campus.core.vault.RoomPersonalEventStore
import cn.edu.haut.campus.core.vault.RoomSearchIndex
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

class AutomationOutboxConsumptionException(eventId: String, cause: Throwable? = null) :
    IllegalStateException("自动化 outbox 事件处理失败，保留未 ack 等待补处理: " + eventId, cause)

data class AutomationWakeSummary(
    val geofenceEventsPublished: Int,
    val utilityEventsPublished: Int,
    val outboxEventsConsumed: Int,
    val scheduledTriggersFired: Int,
    val nextWakeAt: Instant?
)

/** 当前账号的自动化运行时组合根：Room store、纯 JVM 引擎、内置 SPI、批量调度与 outbox 消费。 */
class AutomationRuntime private constructor(
    private val appContext: Context,
    private val session: ActiveSession,
    private val homeRefreshPort: HomeRefreshActionPort?,
    private val clock: Clock = Clock.systemUTC()
) {
    private val repository: CampusRepository = session.repository
    private val database: HautDatabase = repository.automationDatabase
    val scope: SchoolAccountScope = repository.automationScope

    private val ruleStore = RoomAutomationRuleStore(database, scope)
    private val runStore = RoomAutomationRunStore(database)
    private val dedupeStore = RoomAutomationDedupeStore(database)
    private val checkpointStore = RoomAutomationActionCheckpointStore(database)
    private val outbox = RoomEventOutbox(database)
    private val geofenceMonitor = CoarseGeofenceMonitor(appContext, database, scope)
    private val utilityBalanceProjector = UtilityBalanceEventProjector(database, scope)
    private val utilityBalanceHistoryProvider = RoomUtilityBalanceHistoryProvider(database, scope)
    private val wakeRunner = ExclusiveWakeRunner()

    private val personalEventStore: RoomPersonalEventStore? =
        (repository.automationEntityWritePort as? EntityWriter)?.let { writer ->
            RoomPersonalEventStore(
                database = database,
                scope = scope,
                writer = writer,
                searchIndex = RoomSearchIndex(database)
            )
        }

    private val zoneProvider: (cn.edu.haut.campus.core.automation.RunContext) -> java.time.ZoneId = {
        repository.timeZone
    }
    private val timelineService: TimelineService = run {
        val courseInput = RepositoryCourseScheduleTimelineInputProvider(repository, scope, repository.timeZone)
        val examInput = RepositoryExamTimelineInputProvider(repository, scope, repository.timeZone)
        val sources = buildList {
            add(CourseTimelineSource(courseInput))
            add(PracticeTimelineSource(courseInput))
            add(ExamTimelineSource(examInput))
            personalEventStore?.let { add(PersonalTimelineSource(it)) }
        }
        DefaultTimelineService(sources)
    }

    private val registry = AutomationExtensionRegistry().also { registry ->
        builtinTriggerProviders().forEach(registry::registerTriggerProvider)
        BuiltinConditionEvaluators(
            zoneProvider = zoneProvider,
            campusPresencePort = geofenceMonitor,
            focusStatePort = RepositoryFocusStatePort(repository),
            commuteDurationPort = InsufficientEvidenceCommuteDurationPort,
            dataFreshnessPort = RepositoryDataFreshnessPort(repository)
        ).all().forEach(registry::registerConditionEvaluator)
        BuiltinActionExecutors(
            notificationPort = AutomationNotificationPoster(appContext),
            personalEventStore = personalEventStore,
            refreshPort = homeRefreshPort,
            zoneProvider = zoneProvider
        ).all().forEach(registry::registerActionExecutor)
    }

    val engine: AutomationEngine = DefaultAutomationEngine(
        ruleStore = ruleStore,
        runStore = runStore,
        dedupeStore = dedupeStore,
        registry = registry,
        clock = clock,
        timelineService = timelineService,
        checkpointStore = checkpointStore
    )

    fun observeRules(): Flow<List<AutomationRule>> = ruleStore.observeRules()

    suspend fun upsertRule(rule: AutomationRule): AutomationWakeSummary {
        require(rule.scope == scope) { "只能写入当前账号 scope 的自动化规则" }
        ruleStore.upsert(rule)
        return processWake("rule-upsert")
    }

    suspend fun deleteRule(ruleId: String): AutomationWakeSummary {
        if (ruleStore.get(ruleId) != null) {
            val dao = database.automationDao()
            database.withTransaction {
                dao.deleteCheckpointsForRule(ruleId)
                dao.deleteDedupeForRule(ruleId)
                dao.deleteRunsForRule(ruleId)
                ruleStore.delete(ruleId)
            }
        }
        return processWake("rule-delete")
    }

    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean): AutomationWakeSummary {
        val rule = ruleStore.get(ruleId) ?: return processWake("rule-missing")
        ruleStore.upsert(rule.copy(enabled = enabled))
        return processWake(if (enabled) "rule-enable" else "rule-disable")
    }

    suspend fun dryRun(rule: AutomationRule, at: Instant = clock.instant()) =
        engine.dryRun(rule, at)

    /**
     * 批量唤醒入口：低频粗围栏采样 → outbox at-least-once 消费 → 到期时间触发补处理 → 下一次 inexact 唤醒。
     * 进程在任意点被杀都不会重复副作用：事件不提前 ack、dedupe/checkpoint/run 三层对账。
     */
    suspend fun processWake(reason: String): AutomationWakeSummary = wakeRunner.call {
        processWakeExclusive(reason)
    }

    private suspend fun processWakeExclusive(reason: String): AutomationWakeSummary {
        val now = clock.instant()
        val publishedGeofence = runCatching { geofenceMonitor.sampleAndPublish() }.getOrDefault(0)
        val publishedUtility = runCatching { utilityBalanceProjector.publishChangedBalances() }.getOrDefault(0)
        val consumed = consumeOutboxExclusive()
        val planned = planEnabledRules(now)
        val due = planned.filter { !it.second.fireAt.isAfter(now) }
        var fired = 0
        for ((rule, trigger) in due) {
            val result = engine.executeTrigger(
                TriggerInstance(
                    ruleId = rule.id,
                    triggerInstanceKey = trigger.triggerInstanceKey,
                    firedAt = trigger.fireAt,
                    source = trigger.source + ":" + reason.take(24)
                )
            )
            if (result is TriggerExecutionResult.Executed || result is TriggerExecutionResult.Duplicate) fired++
        }
        val replanned = planEnabledRules(clock.instant())
        val next = replanned.filter { it.second.fireAt.isAfter(clock.instant()) }
            .minByOrNull { it.second.fireAt }?.second
        AutomationAlarmScheduler.synchronize(appContext, accountScope(), next)
        return AutomationWakeSummary(
            geofenceEventsPublished = publishedGeofence,
            utilityEventsPublished = publishedUtility,
            outboxEventsConsumed = consumed,
            scheduledTriggersFired = fired,
            nextWakeAt = next?.fireAt
        )
    }

    /** 消费一批当前未 ack 事件；与完整 processWake 共用同一互斥，避免并发 ack/执行交错。 */
    suspend fun consumeOutboxOnce(): Int = wakeRunner.call { consumeOutboxExclusive() }

    private suspend fun consumeOutboxExclusive(): Int = AutomationOutboxProcessor(
        outbox = outbox,
        ruleStore = ruleStore,
        engine = engine,
        consumerGroup = CONSUMER_GROUP,
        utilitySamplesProvider = { utilityBalanceHistoryProvider.samples() }
    ).consumeOnce()

    private suspend fun planEnabledRules(now: Instant): List<Pair<AutomationRule, PlannedTriggerInstance>> {
        val horizon = Duration.ofHours(36)
        val lookbehind = Duration.ofHours(2)
        return ruleStore.list()
            .filter { it.enabled }
            .flatMap { rule ->
                when (rule.trigger) {
                    is TriggerSpec.AtTime -> BuiltinTriggerPlanner.planAtTime(rule, now, repository.timeZone, horizon, lookbehind)
                        .map { rule to it }
                    is TriggerSpec.BeforeEvent -> BuiltinTriggerPlanner.planBeforeEvent(rule, timelineService, now, horizon, lookbehind)
                        .map { rule to it }
                    else -> emptyList()
                }
            }
            .sortedBy { it.second.fireAt }
    }

    private fun accountScope(): AccountBackgroundScope = AccountBackgroundScope.from(session)

    companion object {
        const val CONSUMER_GROUP: String = "automation"

        @Volatile private var instance: AutomationRuntime? = null

        fun bind(
            context: Context,
            session: ActiveSession,
            homeRefreshPort: HomeRefreshActionPort? = null
        ): AutomationRuntime = synchronized(this) {
            val expected = AccountBackgroundScope.from(session)
            instance?.takeIf { AccountBackgroundScope.from(it.session) == expected }?.let { return@synchronized it }
            AutomationRuntime(context.applicationContext, session, homeRefreshPort).also {
                instance = it
                session.repository.registerBeforeWipeHook("automation_runtime_wipe") {
                    AutomationRuntimeCoordinator.cancelAll(context.applicationContext, expected)
                }
                AutomationRuntimeCoordinator.schedulePeriodic(context.applicationContext, expected)
            }
        }

        fun current(session: ActiveSession): AutomationRuntime? = synchronized(this) {
            instance?.takeIf { AccountBackgroundScope.from(it.session) == AccountBackgroundScope.from(session) }
        }

        suspend fun deactivateCurrent(context: Context) {
            val old = synchronized(this) { instance.also { instance = null } } ?: return
            AutomationRuntimeCoordinator.cancelAll(context.applicationContext, AccountBackgroundScope.from(old.session))
            AutomationAlarmScheduler.cancel(context.applicationContext, AccountBackgroundScope.from(old.session))
        }
    }
}

private class RepositoryFocusStatePort(
    private val repository: CampusRepository
) : FocusStatePort {
    override suspend fun isFocusActive(scope: SchoolAccountScope, now: Instant): Boolean =
        repository.activeFocusSessionFlow.first() != null
}

private object InsufficientEvidenceCommuteDurationPort : CommuteDurationPort {
    override suspend fun estimateMinutes(
        scope: SchoolAccountScope,
        fromCampus: cn.edu.haut.campus.core.model.CampusId?,
        toCampus: cn.edu.haut.campus.core.model.CampusId?,
        at: Instant
    ): Int? = if (fromCampus != null && fromCampus == toCampus) 0 else null
}

private class RepositoryDataFreshnessPort(
    private val repository: CampusRepository
) : DataFreshnessPort {
    override suspend fun lastSuccessfulAt(scope: SchoolAccountScope, capability: CapabilityKind): Instant? {
        val prefs = repository.preferencesFlow.first()
        val epochMs = when (capability) {
            CapabilityKind.TIMETABLE_READ,
            CapabilityKind.GRADES_READ,
            CapabilityKind.EXAMS_READ,
            CapabilityKind.ACADEMIC_CALENDAR_READ,
            CapabilityKind.ACADEMIC_TERMS_READ -> prefs.lastAutoSyncEpochMillis
            CapabilityKind.UTILITIES -> prefs.lastUtilitySampleEpochMillis
            else -> null
        }
        return epochMs?.let(Instant::ofEpochMilli)
    }
}
