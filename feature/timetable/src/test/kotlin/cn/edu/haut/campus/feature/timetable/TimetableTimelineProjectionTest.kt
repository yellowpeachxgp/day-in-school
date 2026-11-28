package cn.edu.haut.campus.feature.timetable

import cn.edu.haut.campus.core.domain.TimelineScheduleAdapter
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TimeEvidence
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.CourseOccurrenceTimelineMetadata
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineSnapshot
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import cn.edu.haut.campus.feature.timetable.state.TimetableUiState
import cn.edu.haut.campus.feature.timetable.state.timelineEventsForTerm
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W1-05 切片二：Timetable reactive TimelineService 读取路径与 UNCERTAIN 呈现语义验收。 */
class TimetableTimelineProjectionTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-timetable-test"),
        profileId = ProfileId("profile-timetable-test")
    )
    private val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 8, 31),
        weekStartDay = DayOfWeek.MONDAY,
        weekCount = 20,
        timeTableId = "test-timetable",
        current = true
    )
    private val fixedNowDate = LocalDate.of(2026, 9, 7) // 第 2 周周一

    private val normalCourse = Course(
        id = "c-math",
        externalId = "math-1",
        name = "高等数学",
        teacher = "测试教师",
        colorArgb = 0xFF2563EB
    )
    private val practiceCourse = Course(
        id = "c-practice",
        externalId = "sjk:practice-1",
        name = "金工实习",
        teacher = "实践教师",
        colorArgb = 0xFF059669
    )
    private val courses = listOf(normalCourse, practiceCourse)
    private val rules = listOf(
        CourseScheduleRule(
            id = "r-math",
            courseId = normalCourse.id,
            termId = term.id,
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = setOf(2),
            rawRoom = "W101"
        ),
        CourseScheduleRule(
            id = "r-practice",
            courseId = practiceCourse.id,
            termId = term.id,
            weekday = DayOfWeek.MONDAY,
            startPeriod = 5,
            endPeriod = 6,
            weeks = setOf(2),
            rawRoom = "工程训练中心"
        )
    )
    private val periodTimes = listOf(
        PeriodTime("pt-1", term.timeTableId, 1, LocalTime.of(8, 30), LocalTime.of(9, 15)),
        PeriodTime("pt-2", term.timeTableId, 2, LocalTime.of(9, 20), LocalTime.of(10, 5)),
        PeriodTime("pt-5", term.timeTableId, 5, LocalTime.of(14, 30), LocalTime.of(15, 15)),
        PeriodTime("pt-6", term.timeTableId, 6, LocalTime.of(15, 20), LocalTime.of(16, 5))
    )

    private fun provenance(): DataProvenance = DataProvenance(
        providerId = "local.room",
        schoolId = scope.schoolId,
        accountId = scope.accountId,
        sourceSystem = "account-database"
    )

    private fun courseSnapshot(
        revision: Long,
        periodRows: List<PeriodTime> = periodTimes,
        courseRows: List<Course> = courses,
        termRow: Term = term,
        ruleRows: List<CourseScheduleRule> = rules
    ): CourseScheduleTimelineSnapshot = CourseScheduleTimelineSnapshot(
        zone = zone,
        provenance = provenance(),
        revision = revision,
        terms = listOf(termRow),
        courses = courseRows,
        rules = ruleRows,
        periodTimes = periodRows,
        calendarOverrides = emptyList(),
        occurrenceOverrides = emptyList()
    )

    private fun projection(
        courseSnapshots: MutableStateFlow<CourseScheduleTimelineSnapshot>,
        currentTermFlow: MutableStateFlow<Term?> = MutableStateFlow(term)
    ): RepositoryTimetableTimelineProjection = RepositoryTimetableTimelineProjection(
        accountScope = scope,
        zone = zone,
        currentTermFlow = currentTermFlow,
        courseSnapshots = courseSnapshots,
        nowDate = { fixedNowDate }
    )

    @Test
    fun courseAndPracticeEventsAreProjectedThroughReactiveTimeline() = runTest {
        val events = projection(MutableStateFlow(courseSnapshot(revision = 1L)))
            .observeEvents()
            .first { it.events.isNotEmpty() }
            .events

        val normal = events.single { it.kind == TimelineKind.COURSE && it.title == normalCourse.name }
        val practice = events.single { it.kind == TimelineKind.PRACTICE && it.title == practiceCourse.name }
        assertEquals(TimelineTimeSemantics.TIMED, normal.timeSemantics)
        assertEquals(TimelineTimeSemantics.TIMED, practice.timeSemantics)
        assertNotNull(practice.end)

        val restored = TimelineScheduleAdapter.resolveOccurrencesForDateFromEvents(
            date = fixedNowDate,
            events = events,
            term = term,
            periodTimes = periodTimes,
            courses = courses,
            rules = rules
        )
        assertEquals(setOf(normalCourse.id, practiceCourse.id), restored.map { it.courseId }.toSet())
        assertTrue(restored.all { it.hasResolvedTime })
    }

    @Test
    fun uncertainEventsKeepNullEndAndRestoreAnchorOnlyTimetableOccurrences() = runTest {
        val events = projection(MutableStateFlow(courseSnapshot(revision = 1L, periodRows = emptyList())))
            .observeEvents()
            .first { it.events.isNotEmpty() }
            .events

        val uncertain = events.single { it.kind == TimelineKind.COURSE && it.title == normalCourse.name }
        assertEquals(TimelineTimeSemantics.UNCERTAIN, uncertain.timeSemantics)
        assertNull("UNCERTAIN 事件不能伪造 end", uncertain.end)
        assertEquals(BusyLevel.FREE, uncertain.busy)

        val decoded = CourseOccurrenceTimelineMetadata.decode(uncertain)
        assertFalse("UNCERTAIN 还原后不得冒充精确时间", decoded.hasResolvedTime)
        assertEquals(TimeEvidence.UNRESOLVED, decoded.timeEvidence)

        val restored = TimelineScheduleAdapter.resolveOccurrencesForDateFromEvents(
            date = fixedNowDate,
            events = events,
            term = term,
            periodTimes = emptyList(),
            courses = courses,
            rules = rules
        )
        val occurrence = restored.single { it.courseId == normalCourse.id }
        assertFalse(occurrence.hasResolvedTime)
        assertEquals(TimeEvidence.UNRESOLVED, occurrence.timeEvidence)
        assertTrue(
            occurrence.formatTimeOrPeriodRange(DateTimeFormatter.ofPattern("HH:mm")).contains("作息待设")
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun snapshotRevisionUpdatesAreEmittedReactively() = runTest {
        val snapshots = MutableStateFlow(courseSnapshot(revision = 1L))
        val received = mutableListOf<TimetableTimelineEventSnapshot>()
        val collectJob = backgroundScope.launch {
            projection(snapshots).observeEvents().collect { received += it }
        }
        runCurrent()
        assertTrue(received.isNotEmpty())
        val emissionCountBeforeUpdate = received.size

        snapshots.value = courseSnapshot(
            revision = 2L,
            courseRows = listOf(normalCourse.copy(name = "高等数学（更新）"), practiceCourse)
        )
        runCurrent()

        assertTrue("输入快照 revision 更新必须产生新 Timeline emission", received.size > emissionCountBeforeUpdate)
        assertTrue(
            received.last().events.any { it.kind == TimelineKind.COURSE && it.title == "高等数学（更新）" }
        )
        collectJob.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun overlappingTermSwitchWithheldSnapshotNeverPublishesPreviousTermEventsForNewTerm() = runTest {
        val overlappingTerm = term.copy(id = "2026-2027-2", termNumber = 2)
        val overlappingRules = rules.map { it.copy(id = it.id + "-term-b", termId = overlappingTerm.id) }
        val snapshots = MutableStateFlow(courseSnapshot(revision = 1L))
        val currentTermFlow = MutableStateFlow<Term?>(term)
        val received = mutableListOf<TimetableTimelineEventSnapshot>()
        val collectJob = backgroundScope.launch {
            projection(snapshots, currentTermFlow).observeEvents().collect { received += it }
        }
        runCurrent()
        assertTrue(received.last().events.isNotEmpty())

        currentTermFlow.value = overlappingTerm
        runCurrent()

        assertEquals(overlappingTerm, received.last().term)
        assertTrue("新学期匹配快照未到前必须清空，不得继续展示旧学期课程", received.last().events.isEmpty())
        assertTrue(
            "旧学期事件绝不能贴着新学期身份发布",
            received.none { it.term == overlappingTerm && it.events.isNotEmpty() }
        )

        snapshots.value = courseSnapshot(
            revision = 2L,
            termRow = overlappingTerm,
            ruleRows = overlappingRules
        )
        runCurrent()

        assertEquals(overlappingTerm, received.last().term)
        assertTrue(received.last().events.isNotEmpty())
        collectJob.cancel()
    }

    @Test
    fun uiStateConsumesTimelineEventsOnlyForExactSameTermSnapshot() = runTest {
        val projected = projection(MutableStateFlow(courseSnapshot(revision = 1L)))
            .observeEvents()
            .first { it.events.isNotEmpty() }
        val state = TimetableUiState(
            currentTerm = term,
            timelineEvents = projected.events,
            timelineEventsTerm = projected.term
        )

        assertEquals(projected.events, state.timelineEventsForTerm(term))
        assertTrue(
            "同学期 id 但开学日期等内容不同也不能消费旧投影",
            state.timelineEventsForTerm(term.copy(startDate = term.startDate.plusDays(1))).isEmpty()
        )
        assertTrue(
            state.timelineEventsForTerm(term.copy(id = "2026-2027-2", termNumber = 2)).isEmpty()
        )
    }
}
