package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineFilter
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineService
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinAutomationTemplatesTest {
    private val scope = AutomationTestFixtures.scope
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val mondayMorning: Instant = Instant.parse("2026-03-02T00:00:00Z") // 周一 08:00 +08

    @Test
    fun fourBuiltinTemplatesArePresentAndInstantiateDisabled() {
        assertEquals(
            setOf(
                BuiltinAutomationTemplates.TEMPLATE_EARLY_RAIN_UMBRELLA,
                BuiltinAutomationTemplates.TEMPLATE_CROSS_CAMPUS_COMMUTE,
                BuiltinAutomationTemplates.TEMPLATE_FRIDAY_UTILITY_LOW_BALANCE,
                BuiltinAutomationTemplates.TEMPLATE_COURSE_CANCELLED_STUDY
            ),
            BuiltinAutomationTemplates.all.map { it.id }.toSet()
        )
        BuiltinAutomationTemplates.all.forEach { template ->
            val rule = template.instantiate(scope)
            assertFalse("模板必须默认关闭，等待用户显式启用", rule.enabled)
            assertEquals(template.id, rule.templateId)
            assertTrue(rule.actions.isNotEmpty())
        }
    }

    @Test
    fun earlyRainUmbrellaTemplateDryRunPassesWithFreshAttributedWeatherEvidence() = runTest {
        val template = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_EARLY_RAIN_UMBRELLA)!!
        val rule = acceptWeatherAndEnable(template.instantiate(scope))
        val engine = engineAt(
            at = mondayMorning,
            timelineService = StaticTimelineService(listOf(timelineEvent(mondayMorning.plusSeconds(3600)))),
            weatherProvider = StaticWeatherProvider(WeatherProviderResult.Success(weatherSnapshot(mondayMorning)))
        )

        val run = engine.dryRun(rule, mondayMorning)

        assertEquals(RunStatus.WOULD_EXECUTE, run.status)
        assertTrue(run.evaluations.all { it.effectivePassed })
        assertTrue(run.evaluations.flatMap { it.evidence }.any { it.source == "weather-attribution" && it.detail.contains("MET Norway") })
    }

    @Test
    fun crossCampusCommuteTemplateDryRunUsesCommuteEvidence() = runTest {
        val template = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_CROSS_CAMPUS_COMMUTE)!!
        val rule = template.instantiate(scope).copy(enabled = true)
        val engine = engineAt(
            at = mondayMorning,
            timelineService = StaticTimelineService(emptyList()),
            commuteMinutes = 35
        )

        val run = engine.dryRun(rule, mondayMorning)

        assertEquals(RunStatus.WOULD_EXECUTE, run.status)
        assertTrue(run.evaluations.single { it.conditionType == BuiltinAutomationTypes.CONDITION_COMMUTE_TIME }.effectivePassed)
    }

    @Test
    fun fridayUtilityLowBalanceTemplateDryRunUsesFreshBalanceEvidence() = runTest {
        val fridayEvening = Instant.parse("2026-03-06T09:30:00Z") // 周五 17:30 +08
        val template = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_FRIDAY_UTILITY_LOW_BALANCE)!!
        val rule = template.instantiate(scope).copy(enabled = true)
        val engine = engineAt(
            at = fridayEvening,
            timelineService = StaticTimelineService(emptyList()),
            utilityReadings = listOf(utilityReading(fridayEvening.minusSeconds(3600), balanceCents = 1999L))
        )

        val run = engine.dryRun(rule, fridayEvening)

        assertEquals(RunStatus.WOULD_EXECUTE, run.status)
        assertTrue(run.evaluations.single { it.conditionType == BuiltinAutomationTypes.CONDITION_DAY_OF_WEEK }.effectivePassed)
        assertTrue(run.evaluations.single { it.conditionType == BuiltinAutomationTypes.CONDITION_UTILITY_BALANCE }.effectivePassed)
    }

    @Test
    fun courseCancelledStudyTemplateDryRunRequiresExplicitCancellationEvidence() = runTest {
        val template = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_COURSE_CANCELLED_STUDY)!!
        val rule = template.instantiate(scope).copy(enabled = true)
        val event = courseCancellationEvent(mondayMorning)
        val engine = engineAt(at = mondayMorning, timelineService = StaticTimelineService(emptyList()))

        val run = engine.dryRun(rule, mondayMorning, triggerEvent = event)

        assertEquals(RunStatus.WOULD_EXECUTE, run.status)
        assertTrue(run.evaluations.single { it.conditionType == BuiltinAutomationTypes.CONDITION_COURSE_CANCELLATION }.effectivePassed)
    }

    @Test
    fun ordinaryCourseUpdateDoesNotPassCancellationTemplateCondition() = runTest {
        val template = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_COURSE_CANCELLED_STUDY)!!
        val rule = template.instantiate(scope).copy(enabled = true)
        val ordinary = courseCancellationEvent(mondayMorning).copy(
            diff = FieldDiff(
                listOf(
                    FieldChange(
                        path = "/room",
                        kind = FieldChangeKind.MODIFIED,
                        before = JsonValue.Str("W111"),
                        after = JsonValue.Str("W112")
                    )
                )
            )
        )
        val engine = engineAt(at = mondayMorning, timelineService = StaticTimelineService(emptyList()))

        val run = engine.dryRun(rule, mondayMorning, triggerEvent = ordinary)

        assertEquals(RunStatus.CONDITIONS_NOT_MET, run.status)
        assertFalse(run.evaluations.single().effectivePassed)
    }

    @Test
    fun unavailableWeatherIsUnknownAndDoesNotTriggerTemplate() = runTest {
        val template = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_EARLY_RAIN_UMBRELLA)!!
        val rule = acceptWeatherAndEnable(template.instantiate(scope))
        val engine = engineAt(
            at = mondayMorning,
            timelineService = StaticTimelineService(listOf(timelineEvent(mondayMorning.plusSeconds(3600)))),
            weatherProvider = StaticWeatherProvider(
                WeatherProviderResult.Unavailable(
                    providerId = "met-norway",
                    reason = WeatherUnavailableReason.NETWORK_ERROR,
                    detail = "合成网络失败"
                )
            )
        )

        val run = engine.dryRun(rule, mondayMorning)

        assertEquals(RunStatus.CONDITIONS_NOT_MET, run.status)
        assertTrue(run.outcomes.isEmpty())
        val weather = run.evaluations.single { it.conditionType == BuiltinAutomationTypes.CONDITION_WEATHER_PRECIPITATION }
        assertEquals(Certainty.UNKNOWN, weather.certainty)
        assertFalse(weather.effectivePassed)
    }

    private fun acceptWeatherAndEnable(rule: AutomationRule): AutomationRule = rule.copy(
        enabled = true,
        conditions = rule.conditions.map { spec ->
            if (spec.type == BuiltinAutomationTypes.CONDITION_WEATHER_PRECIPITATION) {
                spec.copy(config = JsonValue.Obj(spec.config.fields + ("termsAccepted" to JsonValue.Bool(true))))
            } else {
                spec
            }
        }
    )

    private fun engineAt(
        at: Instant,
        timelineService: TimelineService,
        weatherProvider: WeatherProvider? = null,
        commuteMinutes: Int? = null,
        utilityReadings: List<UtilityBalanceReading> = emptyList()
    ): DefaultAutomationEngine {
        val conditions = BuiltinConditionEvaluators(
            zoneProvider = { zone },
            campusPresencePort = null,
            focusStatePort = null,
            commuteDurationPort = object : CommuteDurationPort {
                override suspend fun estimateMinutes(
                    scope: SchoolAccountScope,
                    fromCampus: cn.edu.haut.campus.core.model.CampusId?,
                    toCampus: cn.edu.haut.campus.core.model.CampusId?,
                    at: Instant
                ): Int? = commuteMinutes
            },
            dataFreshnessPort = null,
            weatherProvider = weatherProvider,
            weatherLocationPort = object : WeatherLocationPort {
                override suspend fun referencePoint(scope: SchoolAccountScope, campusId: cn.edu.haut.campus.core.model.CampusId?): GeoPoint =
                    GeoPoint(34.8301, 113.5406)
            },
            utilityBalanceReadPort = object : UtilityBalanceReadPort {
                override suspend fun latestSamples(scope: SchoolAccountScope): List<UtilityBalanceReading> = utilityReadings
            }
        )
        val actions = BuiltinActionExecutors(
            notificationPort = null,
            personalEventStore = null,
            refreshPort = null,
            zoneProvider = { zone }
        )
        val registry = AutomationExtensionRegistry(
            initialTriggerProviders = builtinTriggerProviders(),
            initialConditionEvaluators = conditions.all(),
            initialActionExecutors = actions.all()
        )
        return DefaultAutomationEngine(
            ruleStore = InMemoryAutomationRuleStore(),
            runStore = InMemoryAutomationRunStore(),
            dedupeStore = InMemoryAutomationDedupeStore(),
            registry = registry,
            clock = Clock.fixed(at, ZoneOffset.UTC),
            timelineService = timelineService
        )
    }

    private fun weatherSnapshot(at: Instant): WeatherSnapshot {
        val provenance = provenance(at)
        return WeatherSnapshot(
            providerId = "met-norway",
            location = GeoPoint(34.8301, 113.5406),
            fetchedAt = at.minusSeconds(300),
            validFrom = at.minusSeconds(600),
            validUntil = at.plusSeconds(6 * 3600),
            cacheExpiresAt = at.plusSeconds(3600),
            attributionLines = listOf("Data from MET Norway"),
            licenseUrl = "https://api.met.no/doc/License",
            termsUrl = "https://api.met.no/doc/TermsOfService",
            periods = listOf(
                WeatherPeriodForecast(
                    start = at.minusSeconds(600),
                    end = at.plusSeconds(3000),
                    precipitationProbabilityPercent = 80,
                    precipitationAmountMillimeters = BigDecimal("1.2"),
                    weatherSymbol = "rain",
                    temperatureCelsius = BigDecimal("8.5")
                )
            ),
            provenance = provenance.copy(providerId = "met-norway")
        )
    }

    private fun utilityReading(at: Instant, balanceCents: Long): UtilityBalanceReading = UtilityBalanceReading(
        meterId = "online/electricity/room-1/device-1",
        balanceCents = balanceCents,
        sampledAt = at,
        provenance = provenance(at)
    )

    private fun courseCancellationEvent(at: Instant): DomainEvent.CourseChanged {
        val ref = EntityRef(EntityType.COURSE, "course-1", scope)
        return DomainEvent.CourseChanged(
            eventId = "course-cancel-1",
            scope = scope,
            occurredAt = at,
            courseRef = ref,
            change = ChangeKind.UPDATED,
            diff = FieldDiff(
                listOf(
                    FieldChange(
                        path = "/occurrenceOverride/canceled",
                        kind = FieldChangeKind.MODIFIED,
                        before = JsonValue.Bool(false),
                        after = JsonValue.Bool(true)
                    )
                )
            )
        )
    }

    private fun timelineEvent(start: Instant): TimelineEvent {
        val ref = EntityRef(EntityType.COURSE, "course-1", scope)
        val provenance = provenance(mondayMorning)
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

    private fun provenance(at: Instant): DataProvenance = DataProvenance(
        providerId = "test-provider",
        schoolId = scope.schoolId,
        accountId = scope.accountId,
        sourceSystem = "test-fixture",
        sourceRecordId = "fixture-1",
        fetchedAt = at,
        completeness = SnapshotCompleteness.COMPLETE,
        trust = VerificationLevel.MOCK_TESTED
    )

    private class StaticWeatherProvider(
        private val result: WeatherProviderResult
    ) : WeatherProvider {
        override val info: WeatherProviderInfo = WeatherProviderInfo(
            providerId = "met-norway",
            displayName = "MET Norway fixture",
            authenticationRequirement = WeatherAuthenticationRequirement.NONE,
            attributionLines = listOf("Data from MET Norway"),
            licenseUrl = "https://api.met.no/doc/License",
            termsUrl = "https://api.met.no/doc/TermsOfService"
        )

        override suspend fun forecast(request: WeatherForecastRequest): WeatherProviderResult = result
    }

    private class StaticTimelineService(
        private val events: List<TimelineEvent>
    ) : TimelineService {
        override fun observe(scope: SchoolAccountScope, window: TimeWindow, filter: TimelineFilter): Flow<List<TimelineEvent>> =
            flowOf(events.filter { it.overlaps(window) && filter.matches(it) })

        override suspend fun busyBlocks(scope: SchoolAccountScope, window: TimeWindow, granularity: Duration) =
            emptyList<cn.edu.haut.campus.core.timeline.BusyBlock>()

        override suspend fun freeSlots(
            scope: SchoolAccountScope,
            window: TimeWindow,
            minLength: Duration,
            constraints: cn.edu.haut.campus.core.timeline.SlotConstraints
        ) = emptyList<cn.edu.haut.campus.core.timeline.FreeSlot>()
    }
}
