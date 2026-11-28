package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1-05 Timeline 适配器等价性测试：消费端经「Timeline 投影 → 元数据还原」读取的
 * 课程事件必须与 engine（已收敛到同一展开内核）的输出逐字段一致——
 * 差异路径只剩事件信封的编码/解码往返，本测试正是验证往返无损。
 */
class TimelineScheduleAdapterEquivalenceTest {

    private val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 8, 31),
        weekCount = 20,
        timeTableId = "haut_default",
        current = true
    )

    private val periods = listOf(
        PeriodTime("p1", "haut_default", 1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
        PeriodTime("p2", "haut_default", 2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
        PeriodTime("p3", "haut_default", 3, LocalTime.of(10, 10), LocalTime.of(10, 55)),
        PeriodTime("p4", "haut_default", 4, LocalTime.of(11, 5), LocalTime.of(11, 50)),
        PeriodTime("p5", "haut_default", 5, LocalTime.of(14, 30), LocalTime.of(15, 15)),
        PeriodTime("p6", "haut_default", 6, LocalTime.of(15, 25), LocalTime.of(16, 10))
    )

    private val buildings = listOf(
        Building(
            id = "b-1",
            name = "1号教学楼",
            shortName = "1教",
            latitude = 34.829710,
            longitude = 113.547850,
            category = cn.edu.haut.campus.core.model.CampusCategory.TEACHING_BUILDING,
            entrances = listOf("北门"),
            description = "1号教学楼",
            verifiedAt = LocalDate.of(2026, 11, 5)
        )
    )

    private val courses = listOf(
        Course(id = "c-os", externalId = "JW02", name = "操作系统原理", teacher = "李娜", colorArgb = 0xFFEA580C),
        Course(id = "c-algo", externalId = "JW01", name = "数据结构与算法", teacher = "张伟", colorArgb = 0xFF2563EB),
        Course(id = "c-prac", externalId = "sjk:exp-1", name = "金工实习", teacher = "王五", colorArgb = 0xFF059669)
    )

    // 2026-09-07 是第 2 周周一
    private val monday = LocalDate.of(2026, 9, 7)

    private fun baseRules(): List<CourseScheduleRule> = listOf(
        CourseScheduleRule(
            id = "r-os-1", courseId = "c-os", termId = term.id, weekday = DayOfWeek.MONDAY,
            startPeriod = 1, endPeriod = 2, weeks = (1..16).toSet(), rawRoom = "1号教学楼1204", buildingId = "b-1"
        ),
        CourseScheduleRule(
            id = "r-os-2", courseId = "c-os", termId = term.id, weekday = DayOfWeek.MONDAY,
            startPeriod = 3, endPeriod = 4, weeks = (1..16).toSet(), rawRoom = "1号教学楼1204", buildingId = "b-1"
        ),
        CourseScheduleRule(
            id = "r-algo", courseId = "c-algo", termId = term.id, weekday = DayOfWeek.MONDAY,
            startPeriod = 3, endPeriod = 4, weeks = (1..16).toSet(), rawRoom = "32号楼32204"
        ),
        CourseScheduleRule(
            id = "r-prac", courseId = "c-prac", termId = term.id, weekday = DayOfWeek.MONDAY,
            startPeriod = 5, endPeriod = 6, weeks = setOf(2), rawRoom = "工程训练中心"
        )
    )

    private fun assertBothPathsEqual(
        date: LocalDate,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime> = periods,
        occurrenceOverrides: List<OccurrenceOverride> = emptyList()
    ) {
        val engineOut = ScheduleEngine.resolveOccurrencesForDate(
            date = date, term = term, courses = courses, rules = rules, periodTimes = periodTimes,
            occurrenceOverrides = occurrenceOverrides, buildings = buildings
        )
        val timelineOut = TimelineScheduleAdapter.resolveOccurrencesForDate(
            date = date, term = term, courses = courses, rules = rules, periodTimes = periodTimes,
            occurrenceOverrides = occurrenceOverrides, buildings = buildings
        )
        assertEquals("active 展开在 Timeline 往返后必须与 engine 逐字段一致", engineOut, timelineOut)
        val engineCancelled = ScheduleEngine.resolveCanceledOccurrencesForDate(
            date = date, term = term, courses = courses, rules = rules, periodTimes = periodTimes,
            occurrenceOverrides = occurrenceOverrides, buildings = buildings
        )
        val timelineCancelled = TimelineScheduleAdapter.resolveCanceledOccurrencesForDate(
            date = date, term = term, courses = courses, rules = rules, periodTimes = periodTimes,
            occurrenceOverrides = occurrenceOverrides, buildings = buildings
        )
        assertEquals("停课投影在 Timeline 往返后必须与 engine 逐字段一致", engineCancelled, timelineCancelled)
    }

    @Test
    fun mergedAdjacentAndConflictingCoursesRoundTripIdentically() {
        // r-os-1(1-2节) + r-os-2(3-4节) 同课同教室相邻 → 连堂合并；r-algo(3-4节) 与之冲突 → 分栏
        assertBothPathsEqual(monday, baseRules())
        val engineOut = ScheduleEngine.resolveOccurrencesForDate(
            date = monday, term = term, courses = courses, rules = baseRules(), periodTimes = periods, buildings = buildings
        )
        assertTrue("连堂应合并", engineOut.count { it.courseId == "c-os" } == 1)
        assertTrue("冲突应分栏", engineOut.any { it.conflictColumnCount > 1 })
        assertEquals("b-1", engineOut.first { it.courseId == "c-os" }.buildingId)
        assertEquals("1号教学楼", engineOut.first { it.courseId == "c-os" }.buildingName)
    }

    @Test
    fun practiceCourseEventsSurviveRoundTrip() {
        assertBothPathsEqual(monday, baseRules())
        val timelineOut = TimelineScheduleAdapter.resolveOccurrencesForDate(
            date = monday, term = term, courses = courses, rules = baseRules(), periodTimes = periods, buildings = buildings
        )
        assertTrue("实践课必须经 PRACTICE kind 投影还原", timelineOut.any { it.courseId == "c-prac" })
    }

    @Test
    fun cancelledOccurrencesRoundTripIdentically() {
        val cancel = OccurrenceOverride(
            id = "ov-cancel-1", termId = term.id, courseRuleId = "r-os-1", date = monday,
            canceled = true, reason = "单次停课", mutationToken = "t-1"
        )
        assertBothPathsEqual(monday, baseRules(), occurrenceOverrides = listOf(cancel))
        val cancelled = TimelineScheduleAdapter.resolveCanceledOccurrencesForDate(
            date = monday, term = term, courses = courses, rules = baseRules(), periodTimes = periods,
            occurrenceOverrides = listOf(cancel), buildings = buildings
        )
        assertTrue(cancelled.isNotEmpty())
    }

    @Test
    fun overriddenOccurrenceRoundTripsIdentically() {
        val move = OccurrenceOverride(
            id = "ov-move-1", termId = term.id, courseRuleId = "r-algo", date = monday,
            startPeriod = 5, endPeriod = 6, room = "1号教学楼2301", buildingId = "b-1",
            reason = "调课", mutationToken = "t-2"
        )
        assertBothPathsEqual(monday, baseRules(), occurrenceOverrides = listOf(move))
    }

    @Test
    fun unresolvedTimesKeepAnchorSemanticsAfterRoundTrip() {
        // 缺作息表：时间证据 UNRESOLVED，还原后绝不得冒充精确时间
        assertBothPathsEqual(monday, baseRules(), periodTimes = emptyList())
        val timelineOut = TimelineScheduleAdapter.resolveOccurrencesForDate(
            date = monday, term = term, courses = courses, rules = baseRules(), periodTimes = emptyList(), buildings = buildings
        )
        assertTrue(timelineOut.isNotEmpty())
        assertFalse("缺作息表时不得冒充精确时间", timelineOut.all { it.hasResolvedTime })
    }

    @Test
    fun longAndControlManualCourseNamesRoundTripExactly() {
        val rawName = "手工\u0001\n\u0085" + "长".repeat(300) + "\u0007\u007F"
        val teacher = "xm\u0001\u0085" + "名".repeat(4_200) + "\u007F"
        val rawRoom = "cdmc\u0007\u0085" + "室".repeat(4_200) + "\u0001"
        val weirdCourse = Course(
            id = "c-weird-manual",
            externalId = "manual-weird",
            name = rawName,
            teacher = teacher,
            colorArgb = 0xFF111111,
            source = CourseSource.MANUAL
        )
        val weirdRule = CourseScheduleRule(
            id = "r-weird-manual", courseId = weirdCourse.id, termId = term.id, weekday = DayOfWeek.MONDAY,
            startPeriod = 1, endPeriod = 2, weeks = setOf(2), rawRoom = rawRoom
        )
        val allCourses = courses + weirdCourse
        val allRules = baseRules() + weirdRule

        val engineOut = ScheduleEngine.resolveOccurrencesForDate(
            date = monday, term = term, courses = allCourses, rules = allRules, periodTimes = periods, buildings = buildings
        )
        val timelineOut = TimelineScheduleAdapter.resolveOccurrencesForDate(
            date = monday, term = term, courses = allCourses, rules = allRules, periodTimes = periods, buildings = buildings
        )

        assertEquals("含超长/控制字符课程名的展示模型必须与旧展开路径逐字段一致", engineOut, timelineOut)
        val weirdOut = timelineOut.first { it.courseId == weirdCourse.id }
        assertEquals(rawName, weirdOut.courseName)
        assertEquals("xm/cdmc 超长含控制符教师必须端到端逐字还原", teacher, weirdOut.teacher)
        assertEquals("xm/cdmc 超长含控制符教室必须端到端逐字还原", rawRoom, weirdOut.rawRoom)
    }

    @Test
    fun twoHundredThousandCharRoomUsesTruncatedMetadataAndAuthoritativeFallback() {
        val rawRoom = "cdmc" + "室".repeat(199_996)
        val hugeRoomCourse = Course(
            id = "c-huge-room-manual",
            externalId = "manual-huge-room",
            name = "超长教室课程",
            teacher = "教师",
            colorArgb = 0xFF222222,
            source = CourseSource.MANUAL
        )
        val hugeRoomRule = CourseScheduleRule(
            id = "r-huge-room-manual", courseId = hugeRoomCourse.id, termId = term.id, weekday = DayOfWeek.MONDAY,
            startPeriod = 1, endPeriod = 2, weeks = setOf(2), rawRoom = rawRoom
        )
        val allCourses = courses + hugeRoomCourse
        val allRules = baseRules() + hugeRoomRule

        val engineOut = ScheduleEngine.resolveOccurrencesForDate(
            date = monday, term = term, courses = allCourses, rules = allRules, periodTimes = periods, buildings = buildings
        )
        val timelineOut = TimelineScheduleAdapter.resolveOccurrencesForDate(
            date = monday, term = term, courses = allCourses, rules = allRules, periodTimes = periods, buildings = buildings
        )

        assertEquals(engineOut, timelineOut)
        assertEquals(
            "200000 字符教室在 metadata 截断后必须由权威输入兜底逐字还原",
            rawRoom,
            timelineOut.first { it.courseId == hugeRoomCourse.id }.rawRoom
        )
    }

    @Test
    fun emptyTermOrRulesProduceEmptyProjection() {
        val out = TimelineScheduleAdapter.resolveOccurrencesForDate(
            date = monday, term = term, courses = courses, rules = emptyList(), periodTimes = periods, buildings = buildings
        )
        assertEquals(emptyList<Any>(), out)
        val cancelledNullTerm = TimelineScheduleAdapter.resolveCanceledOccurrencesForDate(
            date = monday, term = null, courses = courses, rules = baseRules(), periodTimes = periods, buildings = buildings
        )
        assertEquals(emptyList<Any>(), cancelledNullTerm)
    }
}
