package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.model.CapabilityKind
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineArmContextTest {
    private val firedAt: Instant = Instant.parse("2026-03-02T08:00:00Z")

    @Test
    fun fireRejectsNullEventForOnEventRuleBeforeReachingEngine() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(actionExecutor = action, triggerTypes = setOf(TriggerSpec.OnEvent.TYPE))
        val rule = AutomationTestFixtures.rule(trigger = TriggerSpec.OnEvent(eventType = "data.stale"))
        harness.ruleStore.upsert(rule)
        val armContext: ArmContext = EngineArmContext(
            rule = rule,
            engine = harness.engine,
            scope = AutomationTestFixtures.scope
        )

        val error = runCatching {
            armContext.fire("on-event-null-key", firedAt, event = null)
        }.exceptionOrNull()

        assertTrue(error is OnEventTriggerMissingEventException)
        assertEquals("fire 边界拒绝后引擎不得执行动作", 0, action.calls.size)
        assertEquals(0, harness.dedupeStore.count())
        assertEquals(emptyList<AutomationRun>(), harness.engine.runs(rule.id).first())
    }

    @Test
    fun fireAllowsNullEventForNonOnEventTriggerTypes() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(actionExecutor = action)
        val rule = AutomationTestFixtures.rule()
        harness.ruleStore.upsert(rule)
        val armContext: ArmContext = EngineArmContext(
            rule = rule,
            engine = harness.engine,
            scope = AutomationTestFixtures.scope
        )

        // 定时、阈值等非 DomainEvent 驱动的触发器 event=null 是合法语义，fire 原样转发。
        armContext.fire("at-time-alarm-instance", firedAt, event = null)

        assertEquals(1, action.calls.size)
        val run = harness.engine.runs(rule.id).first().single()
        assertEquals(RunStatus.EXECUTED, run.status)
    }

    @Test
    fun fireWithMatchingEventReachesEngineAndExecutesOnce() = runTest {
        val action = AutomationTestFixtures.RecordingActionExecutor()
        val harness = newHarness(actionExecutor = action, triggerTypes = setOf(TriggerSpec.OnEvent.TYPE))
        val rule = AutomationTestFixtures.rule(trigger = TriggerSpec.OnEvent(eventType = "data.stale"))
        harness.ruleStore.upsert(rule)
        val armContext: ArmContext = EngineArmContext(
            rule = rule,
            engine = harness.engine,
            scope = AutomationTestFixtures.scope
        )
        val event = DomainEvent.DataStale(
            eventId = "arm-context-event",
            scope = AutomationTestFixtures.scope,
            occurredAt = firedAt,
            capability = CapabilityKind.TIMETABLE_READ,
            since = firedAt.minusSeconds(300)
        )

        armContext.fire("caller-hint-key", firedAt, event = event)
        armContext.fire("another-hint-key", firedAt, event = event)

        assertEquals("同一事件经 fire 重投仍只执行一次", 1, action.calls.size)
        val run = harness.engine.runs(rule.id).first().single()
        assertEquals(DomainEventTriggerSupport.triggerInstanceKey(event), run.triggerInstanceKey)
    }

    private fun newHarness(
        actionExecutor: AutomationTestFixtures.RecordingActionExecutor = AutomationTestFixtures.RecordingActionExecutor(),
        triggerTypes: Set<String> = setOf(TriggerSpec.AtTime.TYPE)
    ): Harness {
        val registry = AutomationTestFixtures.registryWith(
            triggerTypes = triggerTypes,
            actionExecutor = actionExecutor
        )
        val ruleStore = InMemoryAutomationRuleStore()
        val dedupeStore = InMemoryAutomationDedupeStore()
        val engine = DefaultAutomationEngine(
            ruleStore = ruleStore,
            runStore = InMemoryAutomationRunStore(),
            dedupeStore = dedupeStore,
            registry = registry
        )
        return Harness(ruleStore, dedupeStore, engine)
    }

    private class Harness(
        val ruleStore: InMemoryAutomationRuleStore,
        val dedupeStore: InMemoryAutomationDedupeStore,
        val engine: DefaultAutomationEngine
    )
}
