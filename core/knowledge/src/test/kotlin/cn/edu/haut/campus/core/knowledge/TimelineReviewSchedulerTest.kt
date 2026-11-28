package cn.edu.haut.campus.core.knowledge

import androidx.room.Room
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.PersonalEventEntity
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.SourceRef
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.textextract.ExtractedText
import cn.edu.haut.campus.core.textextract.ImageExtractionRequest
import cn.edu.haut.campus.core.textextract.PdfExtractionRequest
import cn.edu.haut.campus.core.textextract.TextExtractPort
import cn.edu.haut.campus.core.timeline.BusyBlock
import cn.edu.haut.campus.core.timeline.BusyFreeCalculator
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.FreeSlot
import cn.edu.haut.campus.core.timeline.SlotConstraints
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineFilter
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineService
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class TimelineReviewSchedulerTest {
    private lateinit var database: HautDatabase
    private lateinit var repository: KnowledgeRepository
    private var now: Instant = Instant.parse("2026-03-02T00:00:00Z") // 周一 08:00 Asia/Shanghai

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_review_scheduler"),
        profileId = ProfileId("prof_review_scheduler")
    )
    private val course = CourseAssociation(
        courseId = "linear-algebra",
        courseName = "线性代数",
        courseCode = "MATH201",
        termId = "2025-autumn",
        teachingWeek = 7
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = KnowledgeRuntimeFactory.create(database, scope, UnusedTextExtractPort, clock = { now })
    }

    @After
    fun tearDown() {
        repository.close()
        database.close()
    }

    @Test
    fun schedulesSpacedReviewBlocksIntoFreeSlotsWithoutCourseOrExamConflicts() = runBlocking {
        val dueAt = Instant.parse("2026-03-11T01:00:00Z")
        insertTask(id = "review-linear", masteryPercent = 20, estimatedMinutes = 120, dueAt = dueAt)
        val courseBusy = timedEvent(
            id = "course:linear-monday",
            kind = TimelineKind.COURSE,
            title = "线性代数",
            start = Instant.parse("2026-03-02T00:00:00Z"),
            end = Instant.parse("2026-03-02T02:00:00Z"),
            entityType = EntityType.COURSE,
            entityId = "linear-algebra"
        )
        val examBusy = timedEvent(
            id = "exam:linear-midterm",
            kind = TimelineKind.EXAM,
            title = "大学物理",
            start = Instant.parse("2026-03-04T01:00:00Z"),
            end = Instant.parse("2026-03-04T03:00:00Z"),
            entityType = EntityType.EXAM,
            entityId = "linear-midterm"
        )
        val scheduler = schedulerWith(staticEvents = listOf(courseBusy, examBusy))

        val result = scheduler.schedule(request())

        assertEquals(ReviewScheduleResultStatus.SCHEDULED, result.status)
        assertEquals(4, result.placements.size)
        assertTrue(result.unscheduledTasks.isEmpty())
        val courseBusyEnd = checkNotNull(courseBusy.end)
        val examBusyEnd = checkNotNull(examBusy.end)
        assertTrue("首个复习块必须避开周一课程", result.placements[0].start >= courseBusyEnd)
        result.placements.forEach { placement ->
            assertTrue("复习块不得与课程冲突", !overlaps(placement.start, placement.end, courseBusy.start, courseBusyEnd))
            assertTrue("复习块不得与考试冲突", !overlaps(placement.start, placement.end, examBusy.start, examBusyEnd))
            assertTrue("复习块必须在截止前完成", !placement.end.isAfter(dueAt))
            val row = database.vaultDao().getPersonalEvent(placement.timelineEventId.value)
            assertNotNull(row)
            assertEquals(TimelineKind.REVIEW_BLOCK.name, row?.kind)
            assertEquals(BusyLevel.BUSY.name, row?.busy)
            assertEquals("review-linear", row?.sourceEntityId)
        }
        val targets = result.scheduledTasks.single().schedule!!.blocks.sortedBy { it.repetitionIndex }.map { it.targetStart }
        assertEquals(now, targets[0])
        assertEquals(now.plus(Duration.ofDays(1)), targets[1])
        assertEquals(now.plus(Duration.ofDays(3)), targets[2])
        assertEquals(now.plus(Duration.ofDays(7)), targets[3])
    }

    @Test
    fun lowerMasteryProducesMoreSpacedRepetitionBlocksThanHighMastery() = runBlocking {
        insertTask(id = "review-low", masteryPercent = 10, estimatedMinutes = 120, dueAt = null)
        insertTask(id = "review-high", masteryPercent = 95, estimatedMinutes = 120, dueAt = null)
        val scheduler = schedulerWith(staticEvents = emptyList())

        val result = scheduler.schedule(request())

        assertEquals(ReviewScheduleResultStatus.SCHEDULED, result.status)
        val low = result.scheduledTasks.single { it.id == "review-low" }
        val high = result.scheduledTasks.single { it.id == "review-high" }
        assertEquals(4, low.schedule!!.blocks.count { it.state == ReviewBlockState.PLANNED })
        assertEquals(2, high.schedule!!.blocks.count { it.state == ReviewBlockState.PLANNED })
        val allPlanned = result.placements.sortedBy { it.start }
        var latestEnd: Instant? = null
        for (placement in allPlanned) {
            assertTrue(
                "不同任务的复习块也不能互相重叠",
                latestEnd == null || !placement.start.isBefore(latestEnd)
            )
            latestEnd = if (latestEnd == null || placement.end.isAfter(latestEnd)) placement.end else latestEnd
        }
    }

    @Test
    fun secondRunIsIdempotentAndDoesNotDuplicateTimelineRows() = runBlocking {
        insertTask(id = "review-linear", masteryPercent = 20, estimatedMinutes = 120, dueAt = null)
        val scheduler = schedulerWith(staticEvents = emptyList())

        val first = scheduler.schedule(request())
        val firstIds = first.placements.map { it.timelineEventId }.sortedBy { it.value }
        val firstRowCount = database.vaultDao().countPersonalEvents()
        val firstRevision = repository.listReviewTasks(scope).single().revision

        val second = scheduler.schedule(request())

        assertEquals(ReviewScheduleResultStatus.SCHEDULED, second.status)
        assertEquals(firstIds, second.placements.map { it.timelineEventId }.sortedBy { it.value })
        assertEquals(firstRowCount, database.vaultDao().countPersonalEvents())
        assertEquals(firstRevision, repository.listReviewTasks(scope).single().revision)
    }

    @Test
    fun expiredPlannedBlockBecomesMissedAndReplacementKeepsTaskAndHistory() = runBlocking {
        insertTask(id = "review-linear", masteryPercent = 20, estimatedMinutes = 120, dueAt = null)
        val scheduler = schedulerWith(staticEvents = emptyList())
        val first = scheduler.schedule(request())
        assertEquals(ReviewScheduleResultStatus.SCHEDULED, first.status)
        val firstBlock = first.placements.minByOrNull { it.start }!!

        now = firstBlock.end.plusSeconds(60)
        val second = scheduler.schedule(request())

        assertEquals(ReviewScheduleResultStatus.SCHEDULED, second.status)
        val task = repository.listReviewTasks(scope).single { it.id == "review-linear" }
        assertEquals(ReviewTaskState.TODO, task.state)
        assertEquals(1, task.lapseCount)
        val blocks = task.schedule!!.blocks
        assertTrue("过期块必须保留为 MISSED 历史", blocks.any {
            it.state == ReviewBlockState.MISSED && it.start == firstBlock.start && it.end == firstBlock.end
        })
        assertTrue("漏做后必须生成未来替代块", blocks.any { it.state == ReviewBlockState.PLANNED && it.start.isAfter(now) })
        assertNotNull("漏做历史仍保留在 Timeline 中", database.vaultDao().getPersonalEvent(firstBlock.timelineEventId.value))
        val futureRows = database.vaultDao().getPersonalEventsSnapshot().filter {
            it.kind == TimelineKind.REVIEW_BLOCK.name && it.startAtEpochMs >= now.toEpochMilli()
        }
        assertEquals(
            blocks.count { it.state == ReviewBlockState.PLANNED && it.start.isAfter(now) },
            futureRows.size
        )
    }

    private suspend fun insertTask(id: String, masteryPercent: Int, estimatedMinutes: Int, dueAt: Instant?): ReviewTask {
        val taskCourse = when (id) {
            "review-high" -> course.copy(courseId = "advanced-algebra", courseName = "高等代数")
            else -> course
        }
        return repository.upsertReviewTask(
            ReviewTask(
                id = id,
                scope = scope,
                course = taskCourse,
                materialId = null,
                title = if (id == "review-high") "复习高等代数" else "复习特征值",
                note = null,
                state = ReviewTaskState.TODO,
                dueAt = dueAt,
                estimatedMinutes = estimatedMinutes,
                revision = 1,
                createdAt = now,
                updatedAt = now,
                masteryPercent = masteryPercent
            ),
            actor = Actor.USER
        )
    }

    private fun schedulerWith(staticEvents: List<TimelineEvent>): TimelineReviewScheduler =
        TimelineReviewScheduler(
            scope = scope,
            repository = repository,
            timelineService = DatabaseBackedTimelineService(staticEvents),
            clock = { now }
        )

    private fun request(): ReviewScheduleRequest = ReviewScheduleRequest(
        scope = scope,
        window = TimeWindow(now, now.plus(Duration.ofDays(10))),
        constraints = SlotConstraints(zone = zone)
    )

    private fun timedEvent(
        id: String,
        kind: TimelineKind,
        title: String,
        start: Instant,
        end: Instant,
        entityType: EntityType,
        entityId: String
    ): TimelineEvent {
        val provenance = fixtureProvenance(entityId)
        val entity = EntityRef(entityType, entityId, scope)
        return TimelineEvent(
            id = TimelineEventId(id),
            scope = scope,
            kind = kind,
            title = title,
            start = start,
            end = end,
            timeSemantics = TimelineTimeSemantics.TIMED,
            zone = zone,
            place = null,
            source = SourceRef(entity, provenance),
            provenance = provenance,
            status = EventStatus.SCHEDULED,
            busy = BusyLevel.BUSY,
            revision = 1,
            links = listOf(entity)
        )
    }

    private fun fixtureProvenance(recordId: String): DataProvenance = DataProvenance(
        providerId = "review-scheduler-test",
        schoolId = scope.schoolId,
        accountId = scope.accountId,
        sourceSystem = "fixture",
        sourceRecordId = recordId,
        fetchedAt = now,
        completeness = SnapshotCompleteness.COMPLETE,
        trust = VerificationLevel.UNVERIFIED,
        schemaVersion = 1
    )

    private fun overlaps(leftStart: Instant, leftEnd: Instant, rightStart: Instant, rightEnd: Instant): Boolean =
        leftStart.isBefore(rightEnd) && rightStart.isBefore(leftEnd)

    private inner class DatabaseBackedTimelineService(
        private val staticEvents: List<TimelineEvent>
    ) : TimelineService {
        override fun observe(scope: SchoolAccountScope, window: TimeWindow, filter: TimelineFilter): Flow<List<TimelineEvent>> {
            require(scope == this@TimelineReviewSchedulerTest.scope) { "测试 Timeline 不支持跨 scope" }
            return flow {
                emit(snapshotEvents().filter { it.overlaps(window) && filter.matches(it) })
            }
        }

        override suspend fun busyBlocks(scope: SchoolAccountScope, window: TimeWindow, granularity: Duration): List<BusyBlock> =
            BusyFreeCalculator.busyBlocks(window, busyIntervals(snapshotEvents()), granularity)

        override suspend fun freeSlots(
            scope: SchoolAccountScope,
            window: TimeWindow,
            minLength: Duration,
            constraints: SlotConstraints
        ): List<FreeSlot> = BusyFreeCalculator.freeSlots(window, busyIntervals(snapshotEvents()), minLength, constraints)

        private suspend fun snapshotEvents(): List<TimelineEvent> =
            staticEvents + database.vaultDao().getPersonalEventsSnapshot()
                .filter { it.inTestScope() }
                .map { it.toTimelineEvent() }

        private fun busyIntervals(events: List<TimelineEvent>): List<BusyBlock> = events.mapNotNull { event ->
            if (event.busy == BusyLevel.FREE || event.status == EventStatus.CANCELLED_TODAY) {
                null
            } else {
                event.windowOrNull?.let { BusyBlock(it.start, it.end, event.busy) }
            }
        }

        private fun PersonalEventEntity.inTestScope(): Boolean =
            scopeSchoolId == scope.schoolId.value &&
                scopeAccountId == scope.accountId.value &&
                scopeProfileId == scope.profileId.value &&
                scopeCampusId == scope.campusId?.value

        private fun PersonalEventEntity.toTimelineEvent(): TimelineEvent {
            val provenance = decodeProvenance(Json.parse(provenanceJson))
            val entity = EntityRef(EntityType.PERSONAL_EVENT, id, scope)
            return TimelineEvent(
                id = TimelineEventId(id),
                scope = scope,
                kind = TimelineKind.valueOf(kind),
                title = title,
                start = Instant.ofEpochMilli(startAtEpochMs),
                end = endAtEpochMs?.let(Instant::ofEpochMilli),
                timeSemantics = TimelineTimeSemantics.valueOf(timeSemantics),
                zone = ZoneId.of(zoneId),
                place = null,
                source = SourceRef(entity, provenance),
                provenance = provenance,
                status = EventStatus.valueOf(status),
                busy = BusyLevel.valueOf(busy),
                revision = revision,
                links = emptyList()
            )
        }
    }

    private object UnusedTextExtractPort : TextExtractPort {
        override suspend fun extractImage(request: ImageExtractionRequest): ExtractedText {
            throw UnsupportedOperationException("复习排程测试不抽取图片")
        }

        override suspend fun extractPdf(request: PdfExtractionRequest): ExtractedText {
            throw UnsupportedOperationException("复习排程测试不抽取 PDF")
        }

        override fun close() = Unit
    }
}
