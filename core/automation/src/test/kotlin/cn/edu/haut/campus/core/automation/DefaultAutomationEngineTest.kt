package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.timeline.LocalTimeWindow
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
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

    private fun newHarness(
        conditionEvaluator: AutomationTestFixtures.RecordingConditionEvaluator = AutomationTestFixtures.RecordingConditionEvaluator(),
        actionExecutor: AutomationTestFixtures.RecordingActionExecutor = AutomationTestFixtures.RecordingActionExecutor(),
        triggerTypes: Set<String> = setOf(TriggerSpec.AtTime.TYPE),
        confirmationPort: ActionConfirmationPort? = null
    ): Harness {
        val registry = AutomationTestFixtures.registryWith(
            triggerTypes = triggerTypes,
            conditionEvaluator = conditionEvaluator,
            actionExecutor = actionExecutor
        )
        val ruleStore = InMemoryAutomationRuleStore()
        val runStore = InMemoryAutomationRunStore()
        val dedupeStore = InMemoryAutomationDedupeStore()
        val engine = DefaultAutomationEngine(
            ruleStore = ruleStore,
            runStore = runStore,
            dedupeStore = dedupeStore,
            registry = registry,
            clock = Clock.fixed(clockNow, ZoneOffset.UTC),
            confirmationPort = confirmationPort
        )
        return Harness(ruleStore, runStore, dedupeStore, engine)
    }

    private data class Harness(
        val ruleStore: InMemoryAutomationRuleStore,
        val runStore: InMemoryAutomationRunStore,
        val dedupeStore: InMemoryAutomationDedupeStore,
        val engine: DefaultAutomationEngine
    )
}
