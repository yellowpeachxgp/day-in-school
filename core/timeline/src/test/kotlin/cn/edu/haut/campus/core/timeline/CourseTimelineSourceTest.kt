package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.OccurrenceOverride
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTimelineSourceTest {
    private val fixture = TimelineSourceTestFixtures
    private val scope = fixture.scope
    private val zone = fixture.zone
    private val term = fixture.term

    @Test
    fun 课程按单双周跨周展开且多次同步ID不变() = runTest {
        val snapshot = fixture.scheduleSnapshot(revision = 7)
        val source = CourseTimelineSource(
            CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot, snapshot) }
        )
        val weekThreeMonday = LocalDate.of(2026, 9, 14)

        val emissions = source.observe(scope, fixture.dayWindow(weekThreeMonday)).take(2).toList()
        val weekTwo = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 7))).first()

        assertEquals(1, emissions[0].size)
        assertEquals(emissions[0].map { it.id }, emissions[1].map { it.id })
        val event = emissions[0].single()
        assertEquals("course:2026-2027-1/r-math/2026-09-14", event.id.value)
        assertEquals(TimelineKind.COURSE, event.kind)
        assertEquals(Instant.parse("2026-09-14T00:30:00Z"), event.start)
        assertEquals(Instant.parse("2026-09-14T02:05:00Z"), event.end)
        assertEquals(zone, event.zone)
        assertEquals(7, event.revision)
        assertEquals(EventStatus.SCHEDULED, event.status)
        assertEquals(BusyLevel.BUSY, event.busy)
        assertEquals(emptyList<TimelineEvent>(), weekTwo)
    }

    @Test
    fun 相邻同课规则合并为一个稳定课程块并链接全部规则() = runTest {
        val firstRule = fixture.rule(id = "r-math-1", startPeriod = 1, endPeriod = 1, weeks = setOf(3))
        val secondRule = fixture.rule(id = "r-math-2", startPeriod = 2, endPeriod = 2, weeks = setOf(3))
        val snapshot = fixture.scheduleSnapshot(rules = listOf(firstRule, secondRule))
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })

        val events = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 14))).first()

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("course:2026-2027-1/r-math-1/2026-09-14", event.id.value)
        assertEquals(fixture.instant(LocalDate.of(2026, 9, 14), LocalTime.of(8, 30)), event.start)
        assertEquals(fixture.instant(LocalDate.of(2026, 9, 14), LocalTime.of(10, 5)), event.end)
        assertEquals(
            listOf("r-math-1", "r-math-2"),
            event.links.filter { it.type == EntityType.COURSE_SCHEDULE_RULE }.map { it.id }
        )
    }

    @Test
    fun 课程按日期命中节次方案时整套切换且不混排() = runTest {
        val schemeRows = fixture.defaultPeriodTimes().map { row ->
            when (row.periodNo) {
                1 -> row.copy(
                    id = "pt-scheme-1",
                    startLocalTime = LocalTime.of(10, 0),
                    endLocalTime = LocalTime.of(10, 45),
                    schemeId = "scheme-a",
                    termId = term.id,
                    validStartDate = LocalDate.of(2026, 9, 14),
                    validEndDate = LocalDate.of(2026, 9, 14)
                )
                2 -> row.copy(
                    id = "pt-scheme-2",
                    startLocalTime = LocalTime.of(10, 50),
                    endLocalTime = LocalTime.of(11, 35),
                    schemeId = "scheme-a",
                    termId = term.id,
                    validStartDate = LocalDate.of(2026, 9, 14),
                    validEndDate = LocalDate.of(2026, 9, 14)
                )
                else -> row.copy(
                    id = "pt-scheme-" + row.periodNo,
                    schemeId = "scheme-a",
                    termId = term.id,
                    validStartDate = LocalDate.of(2026, 9, 14),
                    validEndDate = LocalDate.of(2026, 9, 14)
                )
            }
        }
        val mondayRule = fixture.rule(weeks = setOf(3, 4))
        val snapshot = fixture.scheduleSnapshot(
            rules = listOf(mondayRule),
            periodTimes = fixture.defaultPeriodTimes() + schemeRows
        )
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })

        val schemeDay = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 14))).first().single()
        val defaultDay = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 21))).first().single()

        assertEquals(Instant.parse("2026-09-14T02:00:00Z"), schemeDay.start)
        assertEquals(Instant.parse("2026-09-14T03:35:00Z"), schemeDay.end)
        assertEquals(Instant.parse("2026-09-21T00:30:00Z"), defaultDay.start)
        assertEquals(Instant.parse("2026-09-21T02:05:00Z"), defaultDay.end)
    }

    @Test
    fun 节假日停课与调休按followDate的教学周星期投影() = runTest {
        val holiday = CalendarOverride(
            id = "cal-holiday",
            termId = term.id,
            date = LocalDate.of(2026, 9, 14),
            overrideType = CalendarOverrideType.HOLIDAY_NO_CLASS,
            title = "节假日停课"
        )
        val makeup = CalendarOverride(
            id = "cal-makeup",
            termId = term.id,
            date = LocalDate.of(2026, 9, 19),
            overrideType = CalendarOverrideType.MAKEUP_FOLLOW_DATE,
            followDate = LocalDate.of(2026, 9, 14),
            title = "调休补课"
        )
        val snapshot = fixture.scheduleSnapshot(
            rules = listOf(fixture.rule(weeks = setOf(3))),
            calendarOverrides = listOf(holiday, makeup)
        )
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })

        val holidayEvents = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 14))).first()
        val makeupEvents = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 19))).first()

        assertEquals(emptyList<TimelineEvent>(), holidayEvents)
        assertEquals(1, makeupEvents.size)
        val event = makeupEvents.single()
        assertEquals("course:2026-2027-1/r-math/2026-09-19", event.id.value)
        assertEquals(EventStatus.RESCHEDULED, event.status)
        assertEquals(Instant.parse("2026-09-19T00:30:00Z"), event.start)
        assertEquals(Instant.parse("2026-09-19T02:05:00Z"), event.end)
    }

    @Test
    fun 今日停课投影为取消状态且不参与忙闲() = runTest {
        val cancelled = OccurrenceOverride(
            id = "ov-cancel",
            termId = term.id,
            courseRuleId = "r-math",
            date = LocalDate.of(2026, 9, 14),
            canceled = true,
            reason = "单次停课"
        )
        val snapshot = fixture.scheduleSnapshot(
            rules = listOf(fixture.rule(weeks = setOf(3))),
            occurrenceOverrides = listOf(cancelled)
        )
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })
        val service = DefaultTimelineService(source)
        val window = fixture.dayWindow(LocalDate.of(2026, 9, 14))

        val events = service.observe(scope, window).first()
        val busy = service.busyBlocks(scope, window, Duration.ofMinutes(30))

        assertEquals(1, events.size)
        assertEquals("course:2026-2027-1/cancelled/ov-cancel", events.single().id.value)
        assertEquals(EventStatus.CANCELLED_TODAY, events.single().status)
        assertEquals(emptyList<BusyBlock>(), busy)
    }

    @Test
    fun 仅改本次保持原occurrence身份并投影改期状态() = runTest {
        val edit = OccurrenceOverride(
            id = "ov-edit",
            termId = term.id,
            courseRuleId = "r-math",
            date = LocalDate.of(2026, 9, 14),
            startPeriod = 3,
            endPeriod = 4,
            room = "W222",
            canceled = false,
            reason = "仅改本次"
        )
        val snapshot = fixture.scheduleSnapshot(
            rules = listOf(fixture.rule(weeks = setOf(3))),
            occurrenceOverrides = listOf(edit)
        )
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })

        val event = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 14))).first().single()

        assertEquals("course:2026-2027-1/r-math/2026-09-14", event.id.value)
        assertEquals(EventStatus.RESCHEDULED, event.status)
        assertEquals(Instant.parse("2026-09-14T02:25:00Z"), event.start)
        assertEquals(Instant.parse("2026-09-14T04:00:00Z"), event.end)
    }

    @Test
    fun 跨日调入使用覆盖行稳定ID而源停课保留取消投影() = runTest {
        val sourceCancellation = OccurrenceOverride(
            id = "ov-source-cancel",
            termId = term.id,
            courseRuleId = "r-math",
            date = LocalDate.of(2026, 9, 14),
            canceled = true,
            reason = "已调至周六",
            moveId = "move-1",
            moveRole = OccurrenceOverride.MOVE_ROLE_SOURCE
        )
        val target = OccurrenceOverride(
            id = "ov-target",
            termId = term.id,
            courseId = "c-math",
            date = LocalDate.of(2026, 9, 19),
            startPeriod = 1,
            endPeriod = 2,
            room = "W333",
            canceled = false,
            reason = "由周一调入",
            moveId = "move-1",
            moveRole = OccurrenceOverride.MOVE_ROLE_TARGET
        )
        val snapshot = fixture.scheduleSnapshot(
            rules = listOf(fixture.rule(weeks = setOf(3))),
            occurrenceOverrides = listOf(sourceCancellation, target)
        )
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })
        val weekWindow = TimeWindow(
            Instant.parse("2026-09-13T16:00:00Z"),
            Instant.parse("2026-09-20T16:00:00Z")
        )

        val events = source.observe(scope, weekWindow).first()

        assertEquals(
            listOf(
                "course:2026-2027-1/cancelled/ov-source-cancel",
                "course:2026-2027-1/override/ov-target"
            ),
            events.map { it.id.value }
        )
        assertEquals(EventStatus.CANCELLED_TODAY, events[0].status)
        assertEquals(EventStatus.RESCHEDULED, events[1].status)
    }

    @Test
    fun 时间证据不可信时保留tentative点事件且不占用忙闲() = runTest {
        val snapshot = fixture.scheduleSnapshot(
            rules = listOf(fixture.rule(weeks = setOf(3))),
            periodTimes = emptyList()
        )
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })
        val service = DefaultTimelineService(source)
        val window = fixture.dayWindow(LocalDate.of(2026, 9, 14))

        val event = service.observe(scope, window).first().single()
        val busy = service.busyBlocks(scope, window, Duration.ofMinutes(30))

        assertEquals(EventStatus.TENTATIVE, event.status)
        assertNull(event.end)
        assertEquals(BusyLevel.FREE, event.busy)
        assertEquals(emptyList<BusyBlock>(), busy)
    }

    @Test
    fun 实践课多日规则投影为实践事件且普通课程source不重复() = runTest {
        val practiceCourse = fixture.course(id = "c-prac-1", externalId = "sjk:2026-2027-1:practice-source", name = "工程训练")
        val mondayRule = fixture.rule(
            id = "r-prac-mon",
            courseId = practiceCourse.id,
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 4,
            weeks = setOf(14),
            rawRoom = "工程中心A"
        )
        val wednesdayRule = fixture.rule(
            id = "r-prac-wed",
            courseId = practiceCourse.id,
            weekday = DayOfWeek.WEDNESDAY,
            startPeriod = 5,
            endPeriod = 8,
            weeks = setOf(14),
            rawRoom = "工程中心B"
        ).copy(
            customStartTime = LocalTime.of(14, 0),
            customEndTime = LocalTime.of(17, 30)
        )
        val snapshot = fixture.scheduleSnapshot(
            courses = listOf(practiceCourse),
            rules = listOf(mondayRule, wednesdayRule)
        )
        val provider = CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) }
        val practiceSource = PracticeTimelineSource(provider)
        val courseSource = CourseTimelineSource(provider)
        val window = TimeWindow(Instant.parse("2026-11-29T16:00:00Z"), Instant.parse("2026-12-03T16:00:00Z"))

        val practiceEvents = practiceSource.observe(scope, window).first()
        val normalEvents = courseSource.observe(scope, window).first()

        assertEquals(2, practiceEvents.size)
        assertTrue(practiceEvents.all { it.kind == TimelineKind.PRACTICE })
        assertTrue(practiceEvents.all { it.id.sourceId == "practice" })
        assertEquals(
            listOf("practice:2026-2027-1/r-prac-mon/2026-11-30", "practice:2026-2027-1/r-prac-wed/2026-12-02"),
            practiceEvents.map { it.id.value }
        )
        assertEquals(Instant.parse("2026-12-02T06:00:00Z"), practiceEvents[1].start)
        assertEquals(Instant.parse("2026-12-02T09:30:00Z"), practiceEvents[1].end)
        assertEquals(emptyList<TimelineEvent>(), normalEvents)
    }

    @Test
    fun 特殊教学周覆盖使用自定义周次展开() = runTest {
        val customWeek = CalendarOverride(
            id = "cal-custom-week",
            termId = term.id,
            date = LocalDate.of(2026, 9, 14),
            overrideType = CalendarOverrideType.CUSTOM_TEACHING_WEEK,
            customTeachingWeek = 2,
            title = "特殊教学周"
        )
        val snapshot = fixture.scheduleSnapshot(
            rules = listOf(fixture.rule(weeks = setOf(2))),
            calendarOverrides = listOf(customWeek)
        )
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })

        val events = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 14))).first()

        assertEquals(1, events.size)
        assertEquals("course:2026-2027-1/r-math/2026-09-14", events.single().id.value)
        assertEquals(EventStatus.SCHEDULED, events.single().status)
    }

    @Test
    fun 教学周边界外不投影而最后教学周仍投影() = runTest {
        val snapshot = fixture.scheduleSnapshot(rules = listOf(fixture.rule(weeks = setOf(1, 20))))
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })

        val beforeTerm = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 8, 30))).first()
        val firstWeek = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 8, 31))).first()
        val lastWeekMonday = source.observe(scope, fixture.dayWindow(LocalDate.of(2027, 1, 11))).first()
        val afterTerm = source.observe(scope, fixture.dayWindow(LocalDate.of(2027, 1, 18))).first()

        assertEquals(emptyList<TimelineEvent>(), beforeTerm)
        assertEquals(1, firstWeek.size)
        assertEquals(1, lastWeekMonday.size)
        assertEquals(emptyList<TimelineEvent>(), afterTerm)
    }

    @Test
    fun 课程输入证据跨账号时failclosed() {
        val snapshot = fixture.scheduleSnapshot(
            provenance = fixture.provenance(accountId = AccountId("other-account"))
        )
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })

        assertSourceFailure<IllegalArgumentException> {
            runTest { source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 14))).first() }
        }
    }

    @Test
    fun 课程事件导出ICS后重复导入不产生重复事件() = runTest {
        val snapshot = fixture.scheduleSnapshot(rules = listOf(fixture.rule(weeks = setOf(3))))
        val source = CourseTimelineSource(CourseScheduleTimelineInputProvider { _, _ -> flowOf(snapshot) })
        val event = source.observe(scope, fixture.dayWindow(LocalDate.of(2026, 9, 14))).first().single()

        val exported = StandardIcsExporter.export(listOf(event, event), "课程")
        val imported = IcsParser.parseEvents(exported)

        assertEquals(1, imported.size)
        assertEquals(event.id.value, imported.single().uid)
    }
}
