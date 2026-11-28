package cn.edu.haut.campus.core.automation.android

import androidx.room.Room
import cn.edu.haut.campus.core.automation.AutomationExtensionRegistry
import cn.edu.haut.campus.core.automation.BuiltinActionExecutors
import cn.edu.haut.campus.core.automation.BuiltinAutomationTemplates
import cn.edu.haut.campus.core.automation.BuiltinConditionEvaluators
import cn.edu.haut.campus.core.automation.DomainEventTriggerSupport
import cn.edu.haut.campus.core.automation.InMemoryAutomationDedupeStore
import cn.edu.haut.campus.core.automation.InMemoryAutomationRuleStore
import cn.edu.haut.campus.core.automation.InMemoryAutomationRunStore
import cn.edu.haut.campus.core.automation.DefaultAutomationEngine
import cn.edu.haut.campus.core.automation.RunStatus
import cn.edu.haut.campus.core.automation.TriggerExecutionResult
import cn.edu.haut.campus.core.automation.builtinTriggerProviders
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.database.CourseEntity
import cn.edu.haut.campus.core.database.CourseScheduleRuleEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.OccurrenceOverrideEntity
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
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
class RoomCourseCancellationStatePortTest {
    private lateinit var database: HautDatabase
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct-cancel-state"),
        profileId = ProfileId("prof-cancel-state"),
        campusId = CampusId("haut-lianhuajie")
    )
    private val courseRef = EntityRef(EntityType.COURSE, "course-1", scope)

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            database.campusDao().upsertCourse(
                CourseEntity(
                    id = courseRef.id,
                    externalId = null,
                    name = "高等数学",
                    teacher = "测试教师",
                    colorArgb = 0xFF053968,
                    creditPlain = null,
                    source = "MANUAL",
                    userModified = true,
                    lastSyncEpochMilli = null
                )
            )
            database.campusDao().upsertRules(
                listOf(
                    CourseScheduleRuleEntity(
                        id = "rule-1",
                        courseId = courseRef.id,
                        termId = "2026-2027-1",
                        weekdayIso = 1,
                        startPeriod = 1,
                        endPeriod = 2,
                        weeksCsv = "1,2",
                        rawRoom = "W111",
                        buildingId = null,
                        customStartTimeIso = null,
                        customEndTimeIso = null,
                        userModified = false
                    )
                )
            )
        }
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun exactAttributedRowsMustRemainCanceledAtExecutionTime() = runBlocking {
        upsertOverride(id = "ov_cancel", canceled = true)
        upsertOverride(id = "ov_other", canceled = true)
        val port = RoomCourseCancellationStatePort(database, scope)

        assertEquals(true, port.hasActiveCancellation(scope, courseRef, setOf("ov_cancel")))

        upsertOverride(id = "ov_cancel", canceled = false)

        assertEquals(false, port.hasActiveCancellation(scope, courseRef, setOf("ov_cancel")))
        assertEquals("课程级 status fallback 仍应看到其它 active cancellation", true, port.hasActiveCancellation(scope, courseRef, emptySet()))
    }

    @Test
    fun automationDoesNotExecuteFabricatedPositiveEventWhenCurrentRowsAreMoves() = runBlocking {
        upsertOverride(
            id = "ov_move_source",
            canceled = true,
            moveId = "move-1",
            moveRole = "SOURCE",
            reason = "已调至 2026-10-13"
        )
        val at = Instant.parse("2026-10-12T01:00:00Z")
        val template = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_COURSE_CANCELLED_STUDY)!!
        val rule = template.instantiate(scope).copy(enabled = true)
        val ruleStore = InMemoryAutomationRuleStore()
        ruleStore.upsert(rule)
        val registry = AutomationExtensionRegistry(
            initialTriggerProviders = builtinTriggerProviders(),
            initialConditionEvaluators = BuiltinConditionEvaluators(
                zoneProvider = { ZoneId.of("Asia/Shanghai") },
                campusPresencePort = null,
                focusStatePort = null,
                commuteDurationPort = null,
                dataFreshnessPort = null,
                courseCancellationStatePort = RoomCourseCancellationStatePort(database, scope)
            ).all(),
            initialActionExecutors = BuiltinActionExecutors(
                notificationPort = null,
                personalEventStore = null,
                refreshPort = null,
                zoneProvider = { ZoneId.of("Asia/Shanghai") }
            ).all()
        )
        val engine = DefaultAutomationEngine(
            ruleStore = ruleStore,
            runStore = InMemoryAutomationRunStore(),
            dedupeStore = InMemoryAutomationDedupeStore(),
            registry = registry,
            clock = Clock.fixed(at, ZoneOffset.UTC)
        )
        val fabricatedPositiveMoveEvent = DomainEvent.CourseChanged(
            eventId = "fabricated-move-positive",
            scope = scope,
            occurredAt = at,
            courseRef = courseRef,
            change = ChangeKind.UPDATED,
            diff = FieldDiff(
                listOf(
                    FieldChange(
                        path = "/occurrenceOverrides/byId/ov_move_source/canceled",
                        kind = FieldChangeKind.ADDED,
                        before = null,
                        after = JsonValue.Bool(true)
                    )
                )
            )
        )

        val result = engine.executeTrigger(DomainEventTriggerSupport.triggerInstance(rule.id, fabricatedPositiveMoveEvent))

        val executed = result as TriggerExecutionResult.Executed
        assertEquals(RunStatus.CONDITIONS_NOT_MET, executed.run.status)
        assertTrue(executed.run.outcomes.isEmpty())
        assertTrue(executed.run.evaluations.single().reason?.contains("当前已无有效停课") == true)
    }

    @Test
    fun moveAndMergedSuppressionRowsAreNotIndependentCancellations() = runBlocking {
        upsertOverride(
            id = "ov_move_source",
            canceled = true,
            moveId = "move-1",
            moveRole = "SOURCE",
            reason = "已调至 2026-10-13"
        )
        upsertOverride(
            id = "ov_move_target",
            canceled = true,
            moveId = "move-1",
            moveRole = "TARGET",
            reason = "暂停调入课"
        )
        upsertOverride(
            id = "ov_merged_suppression",
            canceled = true,
            reason = "MERGED_INTO:rule-1"
        )
        val port = RoomCourseCancellationStatePort(database, scope)

        assertEquals(false, port.hasActiveCancellation(scope, courseRef, setOf("ov_move_source", "ov_move_target", "ov_merged_suppression")))
        assertEquals(false, port.hasActiveCancellation(scope, courseRef, emptySet()))

        upsertOverride(id = "ov_real_cancel", canceled = true, reason = "单次停课")

        assertEquals(true, port.hasActiveCancellation(scope, courseRef, setOf("ov_real_cancel")))
    }

    private suspend fun upsertOverride(
        id: String,
        canceled: Boolean,
        moveId: String? = null,
        moveRole: String? = null,
        reason: String? = if (canceled) "单次停课" else null
    ) {
        database.campusDao().upsertOccurrenceOverrides(
            listOf(
                OccurrenceOverrideEntity(
                    id = id,
                    termId = "2026-2027-1",
                    courseRuleId = "rule-1",
                    courseId = courseRef.id,
                    dateIso = "2026-10-12",
                    startPeriod = 1,
                    endPeriod = 2,
                    startAtIso = null,
                    endAtIso = null,
                    room = "W111",
                    buildingId = null,
                    canceled = canceled,
                    reason = reason,
                    moveId = moveId,
                    moveRole = moveRole,
                    mutationToken = "token-$id-$canceled"
                )
            )
        )
    }
}
