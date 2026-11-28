package cn.edu.haut.campus.core.automation.android

import androidx.room.Room
import cn.edu.haut.campus.core.automation.ActionExecutor
import cn.edu.haut.campus.core.automation.ActionOutcome
import cn.edu.haut.campus.core.automation.ActionOutcomeStatus
import cn.edu.haut.campus.core.automation.ActionRisk
import cn.edu.haut.campus.core.automation.ActionSpec
import cn.edu.haut.campus.core.automation.AutomationExtensionRegistry
import cn.edu.haut.campus.core.automation.AutomationRule
import cn.edu.haut.campus.core.automation.AutomationRun
import cn.edu.haut.campus.core.automation.AutomationRunStore
import cn.edu.haut.campus.core.automation.BuiltinAutomationTypes
import cn.edu.haut.campus.core.automation.BuiltinTriggerPlanner
import cn.edu.haut.campus.core.automation.CompareOp
import cn.edu.haut.campus.core.automation.DefaultAutomationEngine
import cn.edu.haut.campus.core.automation.InMemoryAutomationDedupeStore
import cn.edu.haut.campus.core.automation.JsonSchema
import cn.edu.haut.campus.core.automation.MetricRef
import cn.edu.haut.campus.core.automation.RunContext
import cn.edu.haut.campus.core.automation.TriggerSpec
import cn.edu.haut.campus.core.automation.TriggerInstance
import cn.edu.haut.campus.core.automation.TriggerExecutionResult
import cn.edu.haut.campus.core.automation.builtinTriggerProviders
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.events.EventAppendResult
import cn.edu.haut.campus.core.events.EventEnvelope
import cn.edu.haut.campus.core.events.EventOutbox
import cn.edu.haut.campus.core.events.OutboxEventRecord
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.vault.RoomEventOutbox
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AutomationOutboxProcessorTest {
    private lateinit var database: HautDatabase
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct-outbox-automation"),
        profileId = ProfileId("prof-outbox-automation"),
        campusId = CampusId("haut-lianhuajie")
    )
    private val now: Instant = Instant.parse("2026-03-02T08:00:00Z")

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun processKilledBeforeAckRedeliversWithoutRepeatingAction() = runBlocking {
        val roomOutbox = RoomEventOutbox(database)
        val flakyOutbox = FailOnceAckOutbox(roomOutbox)
        val action = RecordingAction()
        val ruleStore = RoomAutomationRuleStore(database, scope)
        val rule = AutomationRule(
            id = "utility-low-rule",
            scope = scope,
            name = "水电低余额",
            enabled = true,
            trigger = TriggerSpec.Threshold(
                metric = MetricRef(BuiltinAutomationTypes.METRIC_UTILITY_BALANCE_CENTIUNITS),
                op = CompareOp.LESS_THAN,
                value = BigDecimal("2000")
            ),
            conditions = emptyList(),
            actions = listOf(ActionSpec("test.action"))
        )
        ruleStore.upsert(rule)
        val registry = AutomationExtensionRegistry()
        builtinTriggerProviders().forEach(registry::registerTriggerProvider)
        registry.registerActionExecutor(action)
        val engine = DefaultAutomationEngine(
            ruleStore = ruleStore,
            runStore = RoomAutomationRunStore(database),
            dedupeStore = RoomAutomationDedupeStore(database),
            registry = registry,
            checkpointStore = RoomAutomationActionCheckpointStore(database)
        )
        val processor = AutomationOutboxProcessor(flakyOutbox, ruleStore, engine)
        val event = utilityEvent()
        assertEquals(rule, ruleStore.list().single())
        assertTrue(BuiltinTriggerPlanner.instanceForDomainEvent(rule, event) != null)
        flakyOutbox.append(DomainEventCodecRegistry.DEFAULT.encode(event))

        val first = runCatching { processor.consumeOnce() }
        first.exceptionOrNull()?.let {
            println("first consume error: " + it::class.qualifiedName + ": " + it.message + " cause=" + it.cause)
        }
        assertTrue(first.isFailure)
        assertEquals(1, action.calls.size)
        assertEquals(1, database.vaultDao().countDomainOutbox())
        assertTrue(!flakyOutbox.isAcked(AutomationRuntime.CONSUMER_GROUP, event.eventId))

        val consumed = processor.consumeOnce()

        assertEquals(1, consumed)
        assertEquals("outbox 补处理必须命中 dedupe/checkpoint，不得重复动作", 1, action.calls.size)
        assertTrue(flakyOutbox.isAcked(AutomationRuntime.CONSUMER_GROUP, event.eventId))
        assertEquals(0, flakyOutbox.unackedCount(AutomationRuntime.CONSUMER_GROUP))
    }

    @Test
    fun automationGeneratedVaultEventIsAckedWithoutTriggeringFeedbackLoop() = runBlocking {
        val outbox = RoomEventOutbox(database)
        val action = RecordingAction()
        val ruleStore = RoomAutomationRuleStore(database, scope)
        ruleStore.upsert(
            AutomationRule(
                id = "vault-event-rule",
                scope = scope,
                name = "不得形成反馈环",
                enabled = true,
                trigger = TriggerSpec.OnEvent("vault.entity.changed"),
                conditions = emptyList(),
                actions = listOf(ActionSpec("test.action"))
            )
        )
        val registry = AutomationExtensionRegistry()
        builtinTriggerProviders().forEach(registry::registerTriggerProvider)
        registry.registerActionExecutor(action)
        val engine = DefaultAutomationEngine(
            ruleStore = ruleStore,
            runStore = RoomAutomationRunStore(database),
            dedupeStore = RoomAutomationDedupeStore(database),
            registry = registry,
            checkpointStore = RoomAutomationActionCheckpointStore(database)
        )
        val event = DomainEvent.VaultEntityChanged(
            eventId = "automation-write-event",
            scope = scope,
            occurredAt = now,
            changeId = 1L,
            writeGroupId = "write-group-1",
            entityRef = EntityRef(EntityType.PERSONAL_EVENT, "personal:auto-test", scope),
            change = ChangeKind.CREATED,
            actor = Actor.AUTOMATION,
            actorDetail = "personal-event-upsert",
            changedPaths = listOf("/title")
        )
        outbox.append(DomainEventCodecRegistry.DEFAULT.encode(event))

        val consumed = AutomationOutboxProcessor(outbox, ruleStore, engine).consumeOnce()

        assertEquals(1, consumed)
        assertEquals(0, action.calls.size)
        assertEquals(0, outbox.unackedCount(AutomationRuntime.CONSUMER_GROUP))
    }

    @Test
    fun roomDurableCheckpointReconcilesActionAfterRunAppendCrashAcrossEngineRestart() = runBlocking {
        val action = RecordingAction()
        val ruleStore = RoomAutomationRuleStore(database, scope)
        val rule = AutomationRule(
            id = "checkpoint-durable-rule",
            scope = scope,
            name = "持久检查点",
            enabled = true,
            trigger = TriggerSpec.AtTime(java.time.LocalTime.of(8, 0), setOf(java.time.DayOfWeek.MONDAY)),
            conditions = emptyList(),
            actions = listOf(ActionSpec("test.action"))
        )
        ruleStore.upsert(rule)
        val registry = AutomationExtensionRegistry()
        builtinTriggerProviders().forEach(registry::registerTriggerProvider)
        registry.registerActionExecutor(action)
        val clock = MutableClock(now)
        val lease = Duration.ofSeconds(30)
        val failingRunStore = FailOnceAppendRunStore(RoomAutomationRunStore(database))
        fun newEngine(runStore: AutomationRunStore) = DefaultAutomationEngine(
            ruleStore = ruleStore,
            runStore = runStore,
            dedupeStore = RoomAutomationDedupeStore(database),
            registry = registry,
            clock = clock,
            checkpointStore = RoomAutomationActionCheckpointStore(database),
            pendingLease = lease
        )
        val instance = TriggerInstance(rule.id, "checkpoint-durable-instance", now)

        val first = runCatching { newEngine(failingRunStore).executeTrigger(instance) }

        assertTrue(first.isFailure)
        assertEquals(1, action.calls.size)
        assertEquals(1, database.automationDao().countCheckpoints())

        clock.advance(lease.plusSeconds(1))
        val recovered = newEngine(RoomAutomationRunStore(database)).executeTrigger(instance)

        assertTrue(recovered is TriggerExecutionResult.Executed)
        val run = (recovered as TriggerExecutionResult.Executed).run
        assertEquals(ActionOutcomeStatus.SUCCESS, run.outcomes.single().status)
        assertEquals("run 写失败后的跨进程恢复必须重放 durable checkpoint，不得重复动作", 1, action.calls.size)
        assertEquals(1, database.automationDao().countRuns())
    }

    private fun utilityEvent(): DomainEvent.UtilityBalanceSampled = DomainEvent.UtilityBalanceSampled(
        eventId = "utility-low-event",
        scope = scope,
        occurredAt = now,
        meterRef = EntityRef(EntityType.UTILITY_METER, "meter-1", scope),
        balanceCents = 1999L,
        estimatedDaysLeft = 1.5,
        provenance = DataProvenance(
            providerId = "test.utility",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "test-fixture",
            sourceRecordId = "meter-1",
            fetchedAt = now,
            completeness = SnapshotCompleteness.COMPLETE,
            trust = VerificationLevel.MOCK_TESTED
        )
    )

    private class RecordingAction : ActionExecutor {
        override val type: String = "test.action"
        override val configSchema: JsonSchema = JsonSchema.EMPTY_OBJECT
        override val risk: ActionRisk = ActionRisk.LOCAL_SILENT
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun execute(spec: ActionSpec, ctx: RunContext): ActionOutcome {
            calls += ctx.runId + "/" + ctx.actionExecutionKey
            return ActionOutcome(actionType = type, risk = risk, status = ActionOutcomeStatus.SUCCESS, detail = "ok")
        }
    }

    private class MutableClock(initial: Instant) : Clock() {
        @Volatile private var current: Instant = initial
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }

    private class FailOnceAppendRunStore(
        private val delegate: AutomationRunStore
    ) : AutomationRunStore {
        private val fail = AtomicBoolean(true)
        override suspend fun append(run: AutomationRun): Boolean {
            if (fail.compareAndSet(true, false)) throw IllegalStateException("模拟 run append 前进程死亡")
            return delegate.append(run)
        }
        override suspend fun get(runId: String): AutomationRun? = delegate.get(runId)
        override fun runs(ruleId: String) = delegate.runs(ruleId)
    }

    private class FailOnceAckOutbox(
        private val delegate: EventOutbox
    ) : EventOutbox {
        private val fail = AtomicBoolean(true)

        override suspend fun append(envelope: EventEnvelope): EventAppendResult = delegate.append(envelope)
        override suspend fun pendingUnacked(consumerGroup: String): List<OutboxEventRecord> = delegate.pendingUnacked(consumerGroup)

        override suspend fun acknowledge(consumerGroup: String, eventId: String): Boolean {
            if (fail.compareAndSet(true, false)) throw IllegalStateException("模拟 ack 前进程死亡")
            return delegate.acknowledge(consumerGroup, eventId)
        }

        override suspend fun isAcked(consumerGroup: String, eventId: String): Boolean = delegate.isAcked(consumerGroup, eventId)
        override suspend fun unackedCount(consumerGroup: String): Int = delegate.unackedCount(consumerGroup)
    }
}
