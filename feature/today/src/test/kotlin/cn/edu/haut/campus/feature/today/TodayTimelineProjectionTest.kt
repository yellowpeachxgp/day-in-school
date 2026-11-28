package cn.edu.haut.campus.feature.today

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
import cn.edu.haut.campus.core.timeline.ExamTimelineSnapshot
import cn.edu.haut.campus.core.timeline.PersonalEventTimelineInputProvider
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W1-05 切片一：Today reactive TimelineService 读取路径的最小验收。 */
class TodayTimelineProjectionTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-today-test"),
        profileId = ProfileId("profile-today-test")
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
        courseRows: List<Course> = courses
    ): CourseScheduleTimelineSnapshot = CourseScheduleTimelineSnapshot(
        zone = zone,
        provenance = provenance(),
        revision = revision,
        terms = listOf(term),
        courses = courseRows,
        rules = rules,
        periodTimes = periodRows,
        calendarOverrides = emptyList(),
        occurrenceOverrides = emptyList()
    )

    private fun examSnapshot(): ExamTimelineSnapshot = ExamTimelineSnapshot(
        zone = zone,
        provenance = provenance(),
        revision = 1L,
        exams = emptyList()
    )

    private fun projection(
        courseSnapshots: MutableStateFlow<CourseScheduleTimelineSnapshot>
    ): RepositoryTodayTimelineProjection = RepositoryTodayTimelineProjection(
        accountScope = scope,
        zone = zone,
        currentTermFlow = MutableStateFlow(term),
        courseSnapshots = courseSnapshots,
        examSnapshots = MutableStateFlow(examSnapshot()),
        personalInputProvider = PersonalEventTimelineInputProvider { _, _ -> flowOf(emptyList()) },
        nowDate = { fixedNowDate }
    )

    @Test
    fun courseAndPracticeEventsAreProjectedThroughReactiveTimeline() = runTest {
        val events = projection(MutableStateFlow(courseSnapshot(revision = 1L)))
            .observeEvents()
            .first()

        val normal = events.single { it.kind == TimelineKind.COURSE && it.title == normalCourse.name }
        val practice = events.single { it.kind == TimelineKind.PRACTICE && it.title == practiceCourse.name }
        assertEquals(TimelineTimeSemantics.TIMED, normal.timeSemantics)
        assertEquals(TimelineTimeSemantics.TIMED, practice.timeSemantics)
        assertNotNull(practice.end)
        val decodedPractice = CourseOccurrenceTimelineMetadata.decode(practice)
        assertEquals(practiceCourse.id, decodedPractice.courseId)
        assertEquals(fixedNowDate, decodedPractice.date)
        assertTrue(decodedPractice.hasResolvedTime)
    }

    @Test
    fun uncertainCourseEventsKeepNullEndAndAnchorOnlyMetadata() = runTest {
        val events = projection(MutableStateFlow(courseSnapshot(revision = 1L, periodRows = emptyList())))
            .observeEvents()
            .first()

        val normal = events.single { it.kind == TimelineKind.COURSE && it.title == normalCourse.name }
        assertEquals(TimelineTimeSemantics.UNCERTAIN, normal.timeSemantics)
        assertNull("UNCERTAIN 事件不能伪造 end", normal.end)
        assertEquals(BusyLevel.FREE, normal.busy)
        val decoded = CourseOccurrenceTimelineMetadata.decode(normal)
        assertFalse("UNCERTAIN 还原后不得冒充精确时间", decoded.hasResolvedTime)
        assertEquals(TimeEvidence.UNRESOLVED, decoded.timeEvidence)
        assertEquals(1, decoded.startPeriod)
        assertEquals(2, decoded.endPeriod)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun snapshotRevisionUpdatesAreEmittedReactively() = runTest {
        val snapshots = MutableStateFlow(courseSnapshot(revision = 1L))
        val received = mutableListOf<List<TimelineEvent>>()
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
            received.last().any { it.kind == TimelineKind.COURSE && it.title == "高等数学（更新）" }
        )
        collectJob.cancel()
    }
}
