package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.PersonalEventDraft
import cn.edu.haut.campus.core.timeline.PersonalEventStore
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineFilter
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import cn.edu.haut.campus.core.timeline.TimelineService
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinAutomationRuntimeTest {
    private val scope = AutomationTestFixtures.scope
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Instant = Instant.parse("2026-03-02T00:00:00Z") // 周一 08:00 +08

    @Test
    fun atTimePlannerUsesCampusZoneAndSkipsPastInstances() {
        val rule = AutomationTestFixtures.rule(
            trigger = TriggerSpec.AtTime(
                local = LocalTime.of(12, 30),
                days = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
            )
        )

        val planned = BuiltinTriggerPlanner.planAtTime(rule, now, zone, Duration.ofHours(36))

        assertEquals(2, planned.size)
        assertEquals(Instant.parse("2026-03-02T04:30:00Z"), planned[0].fireAt)
        assertEquals(Instant.parse("2026-03-03T04:30:00Z"), planned[1].fireAt)
        assertTrue(planned[0].triggerInstanceKey.startsWith("at-time/v1/" + rule.id + "/2026-03-02T12:30"))
    }

    @Test
    fun beforeEventPlannerUsesTimelineStartMinusOffsetAndStableDigestKey() = runTest {
        val event = timelineEvent(start = Instant.parse("2026-03-02T06:00:00Z"))
        val service = StaticTimelineService(listOf(event))
        val rule = AutomationTestFixtures.rule(
            trigger = TriggerSpec.BeforeEvent(setOf(TimelineKind.COURSE), Duration.ofMinutes(20))
        )

        val planned = BuiltinTriggerPlanner.planBeforeEvent(rule, service, now, Duration.ofHours(12))

        assertEquals(1, planned.size)
        assertEquals(Instant.parse("2026-03-02T05:40:00Z"), planned.single().fireAt)
        assertTrue(planned.single().triggerInstanceKey.startsWith("before-event/v1/" + rule.id + "/"))
        assertFalse(planned.single().triggerInstanceKey.contains(event.id.value))
    }

    @Test
    fun utilityThresholdAndGeofenceEventsMapToSameEventDerivedTriggerInstance() = runTest {
        val thresholdRule = AutomationTestFixtures.rule(
            trigger = TriggerSpec.Threshold(
                metric = MetricRef(BuiltinAutomationTypes.METRIC_UTILITY_BALANCE_CENTS),
                op = CompareOp.LESS_THAN,
                value = BigDecimal("2000")
            )
        )
        val utilityEvent = DomainEvent.UtilityBalanceSampled(
            eventId = "utility-low-1",
            scope = scope,
            occurredAt = now,
            meterRef = EntityRef(EntityType.UTILITY_METER, "meter-1", scope),
            balanceCents = 1999L,
            estimatedDaysLeft = 2.0,
            provenance = provenance()
        )
        val geofenceRule = AutomationTestFixtures.rule(
            trigger = TriggerSpec.Geofence(campusId = scope.campusId!!, enter = true)
        )
        val geofenceEvent = DomainEvent.GeofenceTransition(
            eventId = "geo-enter-1",
            scope = scope,
            occurredAt = now,
            campusId = scope.campusId!!,
            enter = true,
            accuracyMeters = 80f
        )

        val threshold = BuiltinTriggerPlanner.instanceForDomainEvent(thresholdRule, utilityEvent)
        val geofence = BuiltinTriggerPlanner.instanceForDomainEvent(geofenceRule, geofenceEvent)

        assertNotNull(threshold)
        assertNotNull(geofence)
        assertEquals(utilityEvent, threshold?.event)
        assertEquals(geofenceEvent, geofence?.event)
        assertEquals(DomainEventTriggerSupport.triggerInstanceKey(utilityEvent), threshold?.triggerInstanceKey)
        assertNull(BuiltinTriggerPlanner.instanceForDomainEvent(geofenceRule, utilityEvent))
    }

    @Test
    fun hasClassTodayAndDataFreshnessFollowCertaintyRules() = runTest {
        val classEvent = timelineEvent(start = Instant.parse("2026-03-02T01:00:00Z"))
        val service = StaticTimelineService(listOf(classEvent))
        val hasClass = HasClassTodayConditionEvaluator { zone }
        val fresh = DataFreshnessConditionEvaluator(
            object : DataFreshnessPort {
                override suspend fun lastSuccessfulAt(scope: SchoolAccountScope, capability: CapabilityKind): Instant =
                    now.minusSeconds(30)
            }
        )
        val context = runContext(service)

        val classResult = hasClass.evaluate(ConditionSpec(BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY), context)
        val freshResult = fresh.evaluate(
            ConditionSpec(
                BuiltinAutomationTypes.CONDITION_DATA_FRESHNESS,
                JsonValue.Obj(
                    mapOf(
                        "capability" to JsonValue.Str(CapabilityKind.TIMETABLE_READ.name),
                        "maxAgeSeconds" to JsonValue.Num("60")
                    )
                )
            ),
            context
        )
        val missingFresh = DataFreshnessConditionEvaluator(null).evaluate(
            ConditionSpec(BuiltinAutomationTypes.CONDITION_DATA_FRESHNESS),
            context
        )

        assertTrue(classResult.effectivePassed)
        assertTrue(freshResult.effectivePassed)
        assertEquals(Certainty.UNKNOWN, missingFresh.certainty)
        assertFalse(missingFresh.effectivePassed)
    }

    @Test
    fun createPersonalEventUsesStableActionKeyAndFiredAtForAtomicIdempotency() = runTest {
        val store = RecordingPersonalEventStore()
        val executor = CreatePersonalEventActionExecutor(store) { zone }
        val actionKey = AutomationKeys.actionExecutionKey("run-test", 0)
        val context = runContext(StaticTimelineService(emptyList())).copy(
            actionIndex = 0,
            actionExecutionKey = actionKey
        )
        val spec = ActionSpec(
            BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT,
            JsonValue.Obj(
                mapOf(
                    "kind" to JsonValue.Str("TASK_DUE"),
                    "title" to JsonValue.Str("整理实验报告"),
                    "dueInMinutes" to JsonValue.Num("30")
                )
            )
        )

        val outcome = executor.execute(spec, context)

        assertEquals(ActionOutcomeStatus.SUCCESS, outcome.status)
        val draft = store.drafts.single()
        assertEquals(context.firedAt.plusSeconds(1800), draft.start)
        assertNull(draft.end)
        assertEquals(TimelineTimeSemantics.DEADLINE, draft.timeSemantics)
        assertTrue(draft.id!!.value.startsWith("personal:auto-"))
        assertEquals(actionKey, draft.provenance.sourceRecordId)
        assertEquals(Actor.AUTOMATION, store.actors.single())
    }

    @Test
    fun notificationAndRefreshActionsUsePortsWithStableKey() = runTest {
        val notifications = ArrayList<String>()
        val notification = LocalNotificationActionExecutor(
            object : LocalNotificationActionPort {
                override suspend fun post(
                    scope: SchoolAccountScope,
                    actionExecutionKey: String,
                    title: String,
                    body: String,
                    channel: String?,
                    deeplink: String?
                ): Boolean {
                    notifications += actionExecutionKey
                    return true
                }
            }
        )
        val refreshKeys = ArrayList<String>()
        val refresh = RefreshHomeActionExecutor(
            object : HomeRefreshActionPort {
                override suspend fun refresh(scope: SchoolAccountScope, reason: String): Boolean {
                    refreshKeys += reason
                    return true
                }
            }
        )
        val key = AutomationKeys.actionExecutionKey("run-notify", 0)
        val context = runContext(StaticTimelineService(emptyList())).copy(actionIndex = 0, actionExecutionKey = key)

        val notifyOutcome = notification.execute(
            ActionSpec(
                BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION,
                JsonValue.Obj(mapOf("title" to JsonValue.Str("提醒"), "body" to JsonValue.Str("内容")))
            ),
            context
        )
        val refreshOutcome = refresh.execute(ActionSpec(BuiltinAutomationTypes.ACTION_REFRESH_HOME), context)

        assertEquals(ActionOutcomeStatus.SUCCESS, notifyOutcome.status)
        assertEquals(ActionOutcomeStatus.SUCCESS, refreshOutcome.status)
        assertEquals(listOf(key), notifications)
        assertEquals(listOf("automation:rule-1"), refreshKeys)
    }

    private fun runContext(service: TimelineService): RunContext {
        val rule = AutomationTestFixtures.rule()
        return RunContext(
            scope = scope,
            rule = rule,
            triggerInstanceKey = "trigger-test",
            firedAt = now,
            now = now,
            dryRun = false,
            runId = "run-test",
            dedupeKey = "dedupe-test",
            timelineService = service
        )
    }

    private fun timelineEvent(start: Instant): TimelineEvent {
        val ref = EntityRef(EntityType.COURSE, "course-1", scope)
        val provenance = provenance()
        return TimelineEvent(
            id = TimelineEventId("course:course-1"),
            scope = scope,
            kind = TimelineKind.COURSE,
            title = "高等数学",
            start = start,
            end = start.plusSeconds(3600),
            zone = zone,
            place = null,
            source = cn.edu.haut.campus.core.model.SourceRef(ref, provenance),
            provenance = provenance,
            status = EventStatus.SCHEDULED,
            busy = BusyLevel.BUSY,
            revision = 1L
        )
    }

    private fun provenance(): DataProvenance = DataProvenance(
        providerId = "test",
        schoolId = scope.schoolId,
        accountId = scope.accountId,
        sourceSystem = "test-fixture",
        sourceRecordId = "fixture-1",
        fetchedAt = now,
        completeness = SnapshotCompleteness.COMPLETE,
        trust = VerificationLevel.MOCK_TESTED
    )

    private class StaticTimelineService(
        private val events: List<TimelineEvent>
    ) : TimelineService {
        override fun observe(scope: SchoolAccountScope, window: TimeWindow, filter: TimelineFilter): Flow<List<TimelineEvent>> =
            flowOf(events.filter { it.overlaps(window) && filter.matches(it) })

        override suspend fun busyBlocks(scope: SchoolAccountScope, window: TimeWindow, granularity: Duration) = emptyList<cn.edu.haut.campus.core.timeline.BusyBlock>()

        override suspend fun freeSlots(
            scope: SchoolAccountScope,
            window: TimeWindow,
            minLength: Duration,
            constraints: cn.edu.haut.campus.core.timeline.SlotConstraints
        ) = emptyList<cn.edu.haut.campus.core.timeline.FreeSlot>()
    }

    private class RecordingPersonalEventStore : PersonalEventStore {
        val drafts = ArrayList<PersonalEventDraft>()
        val actors = ArrayList<Actor>()

        override suspend fun upsert(event: PersonalEventDraft, actor: Actor): TimelineEventId {
            drafts += event
            actors += actor
            return event.id!!
        }

        override suspend fun delete(id: TimelineEventId, actor: Actor) = Unit
    }

}
