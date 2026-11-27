package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.timeline.LocalTimeWindow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAutomationEngineTest {
    private val firedAt: Instant = Instant.parse("2026-03-02T08:00:00Z")
    private val clockNow: Instant = Instant.parse("2026-03-02T08:00:01Z")

    @Test
    fun conditionsPassThenActionExecutesAndRunRecordIsPersisted() = runTest {
        val condition = AutomationTestFixtures.RecordingConditionEvaluator()
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(condition, action)
        val rule = AutomationTestFixtures.rule()
        harness.ruleStore.upsert(rule)
        val instance = TriggerInstance(rule.id, "lesson-2026-03-02-1", firedAt)

        val result = harness.engine.executeTrigger(instance)

        assertTrue(result is TriggerExecutionResult.Executed)
        val run = (result as TriggerExecutionResult.Executed).run
        assertEquals(RunStatus.EXECUTED, run.status)
        assertFalse(run.dryRun)
        assertEquals(1, run.evaluations.size)
        assertEquals(1, run.outcomes.size)
        assertEquals(ActionOutcomeStatus.SUCCESS, run.outcomes.single().status)
        assertEquals(
            AutomationKeys.dedupeKey(rule.id, instance.triggerInstanceKey, rule.dedupe, firedAt),
            run.dedupeKey
        )
        assertTrue(run.dedupeKey.contains("lesson-2026-03-02-1"))
        assertEquals(run, harness.runStore.get(run.runId))
        assertEquals(listOf(run), harness.engine.runs(rule.id).first())
        assertEquals(1, condition.calls.size)
        assertEquals(1, action.calls.size)
        assertEquals(rule.scope, action.contexts.single().scope)
        assertEquals(run.runId, action.contexts.single().runId)
        assertEquals(run.dedupeKey, action.contexts.single().dedupeKey)
    }

    @Test
    fun unknownCertaintyIsTreatedAsNotPassedAndReasonIsRecorded() = runTest {
        val condition = AutomationTestFixtures.RecordingConditionEvaluator(
            results = listOf(
                ConditionResult(
                    conditionType = "test.condition",
                    passed = true,
                    certainty = Certainty.UNKNOWN,
                    reason = "天气数据过期"
                )
            )
        )
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(condition, action)
        val rule = AutomationTestFixtures.rule()
        harness.ruleStore.upsert(rule)

        val result = harness.engine.executeTrigger(TriggerInstance(rule.id, "weather-check-1", firedAt))

        assertTrue(result is TriggerExecutionResult.Executed)
        val run = (result as TriggerExecutionResult.Executed).run
        assertEquals(RunStatus.CONDITIONS_NOT_MET, run.status)
        val evaluation = run.evaluations.single()
        assertEquals(Certainty.UNKNOWN, evaluation.certainty)
        assertFalse(evaluation.passed)
        assertFalse(evaluation.effectivePassed)
        assertTrue(evaluation.reason?.contains("天气数据过期") == true)
        assertTrue(evaluation.reason?.contains("certainty=UNKNOWN") == true)
        assertTrue(run.statusReason?.contains("certainty=UNKNOWN") == true)
        assertEquals(0, action.calls.size)
        assertEquals(emptyList<ActionOutcome>(), run.outcomes)
    }

    @Test
    fun concurrentSameTriggerInstanceExecutesOnlyOnce() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(actionExecutor = action)
        val rule = AutomationTestFixtures.rule()
        harness.ruleStore.upsert(rule)
        val instance = TriggerInstance(rule.id, "same-trigger-instance", firedAt)

        val results = (1..16).map {
            async(Dispatchers.Default) { harness.engine.executeTrigger(instance) }
        }.awaitAll()

        assertEquals(1, results.filterIsInstance<TriggerExecutionResult.Executed>().size)
        assertEquals(15, results.filterIsInstance<TriggerExecutionResult.Duplicate>().size)
        assertEquals(1, action.calls.size)
        assertEquals(1, harness.dedupeStore.count())
        assertEquals(1, harness.engine.runs(rule.id).first().size)
    }

    @Test
    fun sameTriggerKeyRemainsDuplicateEvenWhenRedeliveryTimeDiffers() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(actionExecutor = action)
        val rule = AutomationTestFixtures.rule()
        harness.ruleStore.upsert(rule)
        val firstInstance = TriggerInstance(rule.id, "stable-instance", firedAt)
        val redelivered = firstInstance.copy(firedAt = firedAt.plusSeconds(120))

        val first = harness.engine.executeTrigger(firstInstance)
        val second = harness.engine.executeTrigger(redelivered)

        assertTrue(first is TriggerExecutionResult.Executed)
        assertTrue(second is TriggerExecutionResult.Duplicate)
        assertEquals(1, action.calls.size)
        assertEquals(1, harness.dedupeStore.count())
        assertEquals(1, harness.engine.runs(rule.id).first().size)
    }

    @Test
    fun dryRunPreviewsActionsWithoutExecutorDedupeOrRunStoreSideEffects() = runTest {
        val condition = AutomationTestFixtures.RecordingConditionEvaluator()
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(condition, action)
        val rule = AutomationTestFixtures.rule()

        val run = harness.engine.dryRun(rule, firedAt)

        assertEquals(RunStatus.WOULD_EXECUTE, run.status)
        assertTrue(run.dryRun)
        assertEquals(1, condition.calls.size)
        assertEquals(0, action.calls.size)
        assertEquals(ActionOutcomeStatus.DRY_RUN_PREVIEW, run.outcomes.single().status)
        assertEquals(0, harness.dedupeStore.count())
        assertNull(harness.runStore.get(run.runId))
        assertEquals(emptyList<AutomationRun>(), harness.engine.runs(rule.id).first())
    }

    @Test
    fun invalidConditionConfigFailsClosedAsInvalidRule() = runTest {
        val requiredStringSchema = JsonSchema.objectSchema(
            properties = mapOf("mode" to JsonSchema.parse("""{"type":"string"}""").document),
            required = setOf("mode")
        )
        val condition = AutomationTestFixtures.RecordingConditionEvaluator(configSchema = requiredStringSchema)
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(condition, action)
        val rule = AutomationTestFixtures.rule()
        harness.ruleStore.upsert(rule)

        val result = harness.engine.executeTrigger(TriggerInstance(rule.id, "invalid-config", firedAt))

        val run = (result as TriggerExecutionResult.Executed).run
        assertEquals(RunStatus.INVALID_RULE, run.status)
        assertTrue(run.statusReason?.contains("required") == true)
        assertEquals(0, condition.calls.size)
        assertEquals(0, action.calls.size)
    }

    @Test
    fun needsUserConfirmActionDoesNotExecuteWithoutValidConfirmation() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor(risk = ActionRisk.NEEDS_USER_CONFIRM)
        val harness = newHarness(actionExecutor = action)
        val rule = AutomationTestFixtures.rule()
        harness.ruleStore.upsert(rule)

        val result = harness.engine.executeTrigger(TriggerInstance(rule.id, "confirm-missing", firedAt))

        val run = (result as TriggerExecutionResult.Executed).run
        assertEquals(RunStatus.SUPPRESSED, run.status)
        assertEquals(ActionOutcomeStatus.BLOCKED_CONFIRMATION_REQUIRED, run.outcomes.single().status)
        assertEquals(0, action.calls.size)
    }

    @Test
    fun validOneTimeConfirmationAllowsNeedsConfirmAction() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor(risk = ActionRisk.NEEDS_USER_CONFIRM)
        val confirmationPort = object : ActionConfirmationPort {
            override suspend fun consumeConfirmation(
                rule: AutomationRule,
                actionSpec: ActionSpec,
                ctx: RunContext
            ): ActionConfirmation = ActionConfirmation(
                confirmationId = "confirm-1",
                ruleId = rule.id,
                actionType = actionSpec.type,
                configDigest = AutomationKeys.sha256Hex(Json.canonical(actionSpec.config)),
                confirmedAt = clockNow.minusSeconds(10),
                expiresAt = clockNow.plusSeconds(10)
            )
        }
        val harness = newHarness(actionExecutor = action, confirmationPort = confirmationPort)
        val rule = AutomationTestFixtures.rule()
        harness.ruleStore.upsert(rule)

        val result = harness.engine.executeTrigger(TriggerInstance(rule.id, "confirm-valid", firedAt))

        val run = (result as TriggerExecutionResult.Executed).run
        assertEquals(RunStatus.EXECUTED, run.status)
        assertEquals(1, action.calls.size)
        assertNotNull(action.contexts.single().confirmation)
    }

    @Test
    fun oneTimeConfirmationSecondConsumeReturnsNullAndSecondTriggerIsBlocked() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor(risk = ActionRisk.NEEDS_USER_CONFIRM)
        val rule = AutomationTestFixtures.rule()
        val actionSpec = rule.actions.single()
        val confirmationPort = AtomicOneTimeConfirmationPort(
            ActionConfirmation(
                confirmationId = "confirm-once-contract",
                ruleId = rule.id,
                actionType = actionSpec.type,
                configDigest = AutomationKeys.sha256Hex(Json.canonical(actionSpec.config)),
                confirmedAt = clockNow.minusSeconds(10),
                expiresAt = clockNow.plusSeconds(60)
            )
        )
        val harness = newHarness(actionExecutor = action, confirmationPort = confirmationPort)
        harness.ruleStore.upsert(rule)

        val first = harness.engine.executeTrigger(TriggerInstance(rule.id, "confirm-instance-1", firedAt))
        val second = harness.engine.executeTrigger(TriggerInstance(rule.id, "confirm-instance-2", firedAt.plusSeconds(1)))

        assertTrue(first is TriggerExecutionResult.Executed)
        assertEquals(RunStatus.EXECUTED, (first as TriggerExecutionResult.Executed).run.status)
        assertTrue(second is TriggerExecutionResult.Executed)
        val secondRun = (second as TriggerExecutionResult.Executed).run
        assertEquals(RunStatus.SUPPRESSED, secondRun.status)
        assertEquals(ActionOutcomeStatus.BLOCKED_CONFIRMATION_REQUIRED, secondRun.outcomes.single().status)
        assertEquals(2, confirmationPort.consumeResults.size)
        assertNotNull(confirmationPort.consumeResults[0])
        assertNull(confirmationPort.consumeResults[1])
        assertEquals("一次性确认不得让第二个触发实例再次执行动作", 1, action.calls.size)
    }

    @Test
    fun longConditionFailureReasonsAreTruncatedIntoValidRunRecord() = runTest {
        val longReason = "证据不足-" + "x".repeat(500)
        val condition = AutomationTestFixtures.RecordingConditionEvaluator(
            results = listOf(
                ConditionResult(conditionType = "test.condition", passed = false, reason = longReason),
                ConditionResult(conditionType = "test.condition", passed = false, reason = longReason),
                ConditionResult(conditionType = "test.condition", passed = false, reason = longReason)
            )
        )
        val harness = newHarness(conditionEvaluator = condition)
        val rule = AutomationTestFixtures.rule(
            conditions = listOf(
                ConditionSpec("test.condition"),
                ConditionSpec("test.condition"),
                ConditionSpec("test.condition")
            )
        )
        harness.ruleStore.upsert(rule)

        val result = harness.engine.executeTrigger(TriggerInstance(rule.id, "long-reasons", firedAt))

        val run = (result as TriggerExecutionResult.Executed).run
        assertEquals(RunStatus.CONDITIONS_NOT_MET, run.status)
        assertTrue((run.statusReason?.length ?: 0) <= 1000)
    }

    @Test
    fun crashedPendingDedupeCanRecoverAfterLeaseExpiryWithStableRunIdentity() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val mutableClock = MutableClock(clockNow)
        val recoveringRunStore = FailOnceAppendRunStore(InMemoryAutomationRunStore())
        val lease = Duration.ofSeconds(30)
        val harness = newHarness(
            actionExecutor = action,
            runStore = recoveringRunStore,
            clock = mutableClock,
            pendingLease = lease
        )
        val rule = AutomationTestFixtures.rule()
        harness.ruleStore.upsert(rule)
        val instance = TriggerInstance(rule.id, "crash-recoverable-instance", firedAt)
        val dedupeKey = AutomationKeys.dedupeKey(rule.id, instance.triggerInstanceKey, rule.dedupe, instance.firedAt)

        val crash = runCatching { harness.engine.executeTrigger(instance) }.exceptionOrNull()

        assertTrue(crash is IllegalStateException)
        assertEquals(1, action.calls.size)
        assertNull(recoveringRunStore.get(AutomationKeys.runId(rule.id, instance.triggerInstanceKey)))
        val pendingRecord = harness.dedupeStore.find(dedupeKey) ?: error("崩溃后应保留 PENDING dedupe 记录")
        assertEquals(AutomationDedupeState.PENDING, pendingRecord.state)

        val beforeExpiry = harness.engine.executeTrigger(instance.copy(firedAt = firedAt.plusSeconds(5)))
        assertTrue(beforeExpiry is TriggerExecutionResult.Duplicate)
        assertNull((beforeExpiry as TriggerExecutionResult.Duplicate).existingRun)
        assertEquals("lease 未过期时不能并发恢复同一个触发实例", 1, action.calls.size)

        mutableClock.advance(lease.plusSeconds(1))
        val recoveredResult = harness.engine.executeTrigger(instance.copy(firedAt = firedAt.plusSeconds(90)))

        assertTrue(recoveredResult is TriggerExecutionResult.Executed)
        val recoveredRun = (recoveredResult as TriggerExecutionResult.Executed).run
        assertEquals(RunStatus.EXECUTED, recoveredRun.status)
        assertEquals(2, action.calls.size)
        assertEquals(
            "崩溃恢复必须保留稳定 runId，动作实现方可对副作用做幂等",
            action.contexts[0].runId,
            action.contexts[1].runId
        )
        assertEquals(action.contexts[0].dedupeKey, action.contexts[1].dedupeKey)
        assertEquals(recoveredRun, recoveringRunStore.get(recoveredRun.runId))
        val completedRecord = harness.dedupeStore.find(dedupeKey) ?: error("run append 后 dedupe 应转为终态")
        assertEquals(AutomationDedupeState.RUN_RECORDED, completedRecord.state)
        assertNotNull(completedRecord.completedAt)

        val afterCompletion = harness.engine.executeTrigger(instance.copy(firedAt = firedAt.plusSeconds(120)))
        assertTrue(afterCompletion is TriggerExecutionResult.Duplicate)
        assertEquals(recoveredRun, (afterCompletion as TriggerExecutionResult.Duplicate).existingRun)
        assertEquals(2, action.calls.size)
    }

    @Test
    fun quietHoursSuppressLocalNotifyButKeepAuditableOutcome() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor(risk = ActionRisk.LOCAL_NOTIFY)
        val harness = newHarness(actionExecutor = action)
        val rule = AutomationTestFixtures.rule(
            quietHours = QuietHours(
                window = LocalTimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)),
                zone = ZoneId.of("Asia/Shanghai")
            )
        )
        harness.ruleStore.upsert(rule)
        val nightInShanghai = Instant.parse("2026-03-02T15:30:00Z")

        val result = harness.engine.executeTrigger(TriggerInstance(rule.id, "quiet-night", nightInShanghai))

        val run = (result as TriggerExecutionResult.Executed).run
        assertEquals(RunStatus.SUPPRESSED, run.status)
        assertEquals(ActionOutcomeStatus.SKIPPED_QUIET_HOURS, run.outcomes.single().status)
        assertEquals(0, action.calls.size)
    }

    @Test
    fun domainEventTriggerCarriesEventAndUsesEventIdDerivedKey() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(
            actionExecutor = action,
            triggerTypes = setOf(TriggerSpec.OnEvent.TYPE)
        )
        val rule = AutomationTestFixtures.rule(trigger = TriggerSpec.OnEvent(eventType = "data.stale"))
        harness.ruleStore.upsert(rule)
        val event = DomainEvent.DataStale(
            eventId = "stale-event-1",
            scope = AutomationTestFixtures.scope,
            occurredAt = firedAt,
            capability = CapabilityKind.TIMETABLE_READ,
            since = firedAt.minusSeconds(3600)
        )

        val instance = DomainEventTriggerSupport.triggerInstance(rule.id, event)
        val result = harness.engine.executeTrigger(instance)

        assertTrue(result is TriggerExecutionResult.Executed)
        assertEquals(DomainEventTriggerSupport.EVENT_INSTANCE_PREFIX + event.eventId, instance.triggerInstanceKey)
        assertEquals(event, action.contexts.single().triggerEvent)
        val duplicate = harness.engine.executeTrigger(instance)
        assertTrue(duplicate is TriggerExecutionResult.Duplicate)
        assertEquals(1, action.calls.size)
    }

    @Test
    fun domainEventTriggerRewrittenKeyIsReboundToEventIdAndCannotReplay() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(
            actionExecutor = action,
            triggerTypes = setOf(TriggerSpec.OnEvent.TYPE)
        )
        val rule = AutomationTestFixtures.rule(trigger = TriggerSpec.OnEvent(eventType = "data.stale"))
        harness.ruleStore.upsert(rule)
        val event = DomainEvent.DataStale(
            eventId = "stale-event-key-binding",
            scope = AutomationTestFixtures.scope,
            occurredAt = firedAt,
            capability = CapabilityKind.TIMETABLE_READ,
            since = firedAt.minusSeconds(3600)
        )
        val canonicalInstance = DomainEventTriggerSupport.triggerInstance(rule.id, event)
        val rewritten = canonicalInstance.copy(triggerInstanceKey = "caller-controlled-replay-key")

        val first = harness.engine.executeTrigger(rewritten)
        val replay = harness.engine.executeTrigger(canonicalInstance)

        assertTrue(first is TriggerExecutionResult.Executed)
        val run = (first as TriggerExecutionResult.Executed).run
        assertEquals(canonicalInstance.triggerInstanceKey, run.triggerInstanceKey)
        assertEquals(canonicalInstance.triggerInstanceKey, action.contexts.single().triggerInstanceKey)
        assertTrue("改写 key 的重放必须命中同一个 eventId 派生键", replay is TriggerExecutionResult.Duplicate)
        assertEquals(1, action.calls.size)
        assertEquals(1, harness.engine.runs(rule.id).first().size)
    }

    private fun newHarness(
        conditionEvaluator: AutomationTestFixtures.RecordingConditionEvaluator = AutomationTestFixtures.RecordingConditionEvaluator(),
        actionExecutor: AutomationTestFixtures.RecordingActionExecutor = AutomationTestFixtures.RecordingActionExecutor(),
        triggerTypes: Set<String> = setOf(TriggerSpec.AtTime.TYPE),
        confirmationPort: ActionConfirmationPort? = null,
        runStore: AutomationRunStore = InMemoryAutomationRunStore(),
        dedupeStore: AutomationDedupeStore = InMemoryAutomationDedupeStore(),
        clock: Clock = Clock.fixed(clockNow, ZoneOffset.UTC),
        pendingLease: Duration = Duration.ofMinutes(5)
    ): Harness {
        val registry = AutomationTestFixtures.registryWith(
            triggerTypes = triggerTypes,
            conditionEvaluator = conditionEvaluator,
            actionExecutor = actionExecutor
        )
        val ruleStore = InMemoryAutomationRuleStore()
        val engine = DefaultAutomationEngine(
            ruleStore = ruleStore,
            runStore = runStore,
            dedupeStore = dedupeStore,
            registry = registry,
            clock = clock,
            confirmationPort = confirmationPort,
            pendingLease = pendingLease
        )
        return Harness(ruleStore, runStore, dedupeStore, engine)
    }

    private class AtomicOneTimeConfirmationPort(
        private val confirmation: ActionConfirmation
    ) : ActionConfirmationPort {
        private val consumed = AtomicBoolean(false)
        val consumeResults = CopyOnWriteArrayList<ActionConfirmation?>()

        override suspend fun consumeConfirmation(
            rule: AutomationRule,
            action: ActionSpec,
            ctx: RunContext
        ): ActionConfirmation? {
            val result = if (consumed.compareAndSet(false, true)) confirmation else null
            consumeResults.add(result)
            return result
        }
    }

    private class MutableClock(initial: Instant) : Clock() {
        @Volatile private var now: Instant = initial

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    private class FailOnceAppendRunStore(
        private val delegate: InMemoryAutomationRunStore
    ) : AutomationRunStore {
        private val shouldFail = AtomicBoolean(true)

        override suspend fun append(run: AutomationRun): Boolean {
            if (shouldFail.compareAndSet(true, false)) {
                throw IllegalStateException("模拟 dedupe acquire 后、run append 前崩溃")
            }
            return delegate.append(run)
        }

        override suspend fun get(runId: String): AutomationRun? = delegate.get(runId)

        override fun runs(ruleId: String) = delegate.runs(ruleId)
    }

    private data class Harness(
        val ruleStore: InMemoryAutomationRuleStore,
        val runStore: AutomationRunStore,
        val dedupeStore: AutomationDedupeStore,
        val engine: DefaultAutomationEngine
    )
}
