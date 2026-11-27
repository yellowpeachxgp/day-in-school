package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 「三课表链」重叠并列回归：排课规则 → 停课/调课覆盖 → 连堂合并 → 冲突链式分栏 →
 * 周视图/日快照的完整链路在双课表（两门并列）与三套课表（三门链式并列）场景下的
 * 确定性行为。链式分栏采用传递聚合：A 交 B、B 交 C 即使 A 与 C 不直接重叠也同属一栏簇；
 * 停课拆除链环后余下课程必须立即回退为无分栏，绝不残留过期列宽。
 */
class TripleScheduleChainOverlapTest {

    private val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 9, 7), // 周一
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

    private val courseA = Course("c_alpha", "JW-A", "高等数学A", "甲老师", 0xFF2563EB, BigDecimal("4.0"))
    private val courseB = Course("c_beta", "JW-B", "大学物理B", "乙老师", 0xFFEA580C, BigDecimal("3.5"))
    private val courseC = Course("c_gamma", "JW-C", "离散数学C", "丙老师", 0xFF059669, BigDecimal("2.0"))
    private val courses = listOf(courseA, courseB, courseC)

    /** 2026-09-07 是第 1 周周一。 */
    private val monday = LocalDate.of(2026, 9, 7)

    private fun rule(
        id: String,
        courseId: String,
        startPeriod: Int,
        endPeriod: Int,
        weekday: DayOfWeek = DayOfWeek.MONDAY
    ) = CourseScheduleRule(
        id = id,
        courseId = courseId,
        termId = term.id,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = (1..16).toSet(),
        rawRoom = "3号教学楼3101"
    )

    /** 三条链环：A=1-2 节（08:00-09:40）、B=2-3 节（08:55-10:55）、C=3-4 节（10:10-11:50）。 */
    private fun chainRules() = listOf(
        rule("r_a", courseA.id, 1, 2),
        rule("r_b", courseB.id, 2, 3),
        rule("r_c", courseC.id, 3, 4)
    )

    private fun resolve(
        rules: List<CourseScheduleRule>,
        overrides: List<OccurrenceOverride> = emptyList(),
        date: LocalDate = monday
    ) = ScheduleEngine.resolveOccurrencesForDate(
        date = date,
        term = term,
        courses = courses,
        rules = rules,
        periodTimes = periods,
        occurrenceOverrides = overrides
    )

    @Test
    fun doubleOverlap_twoCoursesSplitIntoTwoColumnsInStartOrder() {
        val occurrences = resolve(listOf(rule("r_a", courseA.id, 1, 2), rule("r_b", courseB.id, 2, 3)))

        assertEquals(2, occurrences.size)
        val first = occurrences[0]
        val second = occurrences[1]
        assertEquals(courseA.id, first.courseId)
        assertEquals(courseB.id, second.courseId)
        assertEquals(0, first.conflictColumnIndex)
        assertEquals(2, first.conflictColumnCount)
        assertEquals(1, second.conflictColumnIndex)
        assertEquals(2, second.conflictColumnCount)
    }

    @Test
    fun tripleOverlap_transitiveChainFormsSingleThreeColumnCluster() {
        // A(08:00-09:40) 与 C(10:10-11:50) 不直接重叠，但经 B(08:55-10:55) 链式桥接
        val occurrences = resolve(chainRules())

        assertEquals(3, occurrences.size)
        for ((index, occ) in occurrences.withIndex()) {
            assertEquals("chain column index", index, occ.conflictColumnIndex)
            assertEquals("chain column count", 3, occ.conflictColumnCount)
        }
        assertEquals(listOf(courseA.id, courseB.id, courseC.id), occurrences.map { it.courseId })
    }

    @Test
    fun disjointCourses_afterChainNeverInheritColumns() {
        // A=1-2、B=2-3 链内并列；C=5-6（14:30-16:10）完全不相交，必须保持默认无分栏
        val rules = listOf(
            rule("r_a", courseA.id, 1, 2),
            rule("r_b", courseB.id, 2, 3),
            rule("r_c", courseC.id, 5, 6)
        )
        val occurrences = resolve(rules)

        assertEquals(3, occurrences.size)
        val disjoint = occurrences.single { it.courseId == courseC.id }
        assertEquals(0, disjoint.conflictColumnIndex)
        assertEquals(1, disjoint.conflictColumnCount)
        assertEquals(2, occurrences.single { it.courseId == courseA.id }.conflictColumnCount)
        assertEquals(2, occurrences.single { it.courseId == courseB.id }.conflictColumnCount)
    }

    @Test
    fun cancelingMiddleLink_breaksChainAndSurvivorsLoseColumns() {
        // 停掉 B 后，A(08:00-09:40) 与 C(10:10-11:50) 之间无桥接，二者都回退无分栏
        val cancelB = OccurrenceOverride(
            id = "ov_cancel_b",
            termId = term.id,
            courseRuleId = "r_b",
            date = monday,
            canceled = true,
            reason = "单次停课"
        )
        val occurrences = resolve(chainRules(), listOf(cancelB))

        assertEquals(2, occurrences.size)
        assertTrue(occurrences.none { it.courseId == courseB.id })
        for (occ in occurrences) {
            assertEquals("no leftover column index", 0, occ.conflictColumnIndex)
            assertEquals("no leftover column count", 1, occ.conflictColumnCount)
        }
    }

    @Test
    fun cancelingMiddleLink_canceledEntryRecoverableWithOriginalPeriods() {
        // 被停课的链环仍可在恢复入口中按原始节次复原（#7 停课后可恢复的链内场景）
        val cancelB = OccurrenceOverride(
            id = "ov_cancel_b",
            termId = term.id,
            courseRuleId = "r_b",
            date = monday,
            canceled = true,
            reason = "单次停课"
        )
        val canceled = ScheduleEngine.resolveCanceledOccurrencesForDate(
            date = monday,
            term = term,
            courses = courses,
            rules = chainRules(),
            periodTimes = periods,
            occurrenceOverrides = listOf(cancelB)
        )

        assertEquals(1, canceled.size)
        assertEquals(courseB.id, canceled.single().courseId)
        assertEquals(2, canceled.single().startPeriod)
        assertEquals(3, canceled.single().endPeriod)
        assertEquals("r_b", canceled.single().ruleId)
    }

    @Test
    fun crossDateMakeup_movedIntoOccupiedSlotJoinsConflictCluster() {
        // 周二 r_tue(1-2) 跨日调入周一 1-2 节，与周一既有 r_a(1-2) 完全重叠 → 双课表并列
        val tuesdayRule = rule("r_tue", courseB.id, 1, 2, DayOfWeek.TUESDAY)
        val makeup = OccurrenceOverride(
            id = "ov_makeup",
            termId = term.id,
            courseRuleId = "r_tue",
            date = monday,
            startPeriod = 1,
            endPeriod = 2,
            reason = "跨日调入",
            moveId = "mv_1",
            moveRole = "TARGET"
        )
        val occurrences = resolve(listOf(rule("r_a", courseA.id, 1, 2), tuesdayRule), listOf(makeup))

        assertEquals(2, occurrences.size)
        val moved = occurrences.single { it.overrideId == "ov_makeup" }
        assertTrue(moved.isMakeupOrOverridden)
        assertEquals(courseB.id, moved.courseId)
        assertEquals(2, moved.conflictColumnCount)
        val original = occurrences.single { it.courseId == courseA.id }
        assertEquals(2, original.conflictColumnCount)
    }

    @Test
    fun adjacentSameCourseMerge_participatesInChainAsSingleBlock() {
        // 同课同教室相邻两节（r_a1=1 节、r_a2=2 节）先纵向合并为 1-2 整块，再与 B=2-3 并列
        val rules = listOf(
            rule("r_a1", courseA.id, 1, 1),
            rule("r_a2", courseA.id, 2, 2),
            rule("r_b", courseB.id, 2, 3)
        )
        val occurrences = resolve(rules)

        assertEquals(2, occurrences.size)
        val merged = occurrences.single { it.courseId == courseA.id }
        assertEquals(1, merged.startPeriod)
        assertEquals(2, merged.endPeriod)
        assertEquals(listOf("r_a1", "r_a2"), merged.mergedRuleIds)
        assertEquals(2, merged.conflictColumnCount)
        assertEquals(2, occurrences.single { it.courseId == courseB.id }.conflictColumnCount)
    }

    @Test
    fun weekChain_everyDateMatchesDateLevelResolution() {
        // 周视图（小组件 WEEK_4X4 数据源）逐日与单日解析完全一致——覆盖链不重算第二口径
        val cancelB = OccurrenceOverride(
            id = "ov_cancel_b",
            termId = term.id,
            courseRuleId = "r_b",
            date = monday,
            canceled = true,
            reason = "单次停课"
        )
        val overrides = listOf(cancelB)
        val weekOccurrences = ScheduleEngine.resolveOccurrencesForTeachingWeek(
            teachingWeek = 1,
            term = term,
            courses = courses,
            rules = chainRules(),
            periodTimes = periods,
            occurrenceOverrides = overrides
        )

        assertEquals(7, weekOccurrences.size)
        for ((date, dayList) in weekOccurrences) {
            val direct = resolve(chainRules(), overrides, date)
            assertEquals("date occurrence count", direct.size, dayList.size)
            assertEquals(
                "date chain fingerprint",
                direct.map { listOf(it.occurrenceId, it.courseId, it.conflictColumnIndex, it.conflictColumnCount) },
                dayList.map { listOf(it.occurrenceId, it.courseId, it.conflictColumnIndex, it.conflictColumnCount) }
            )
        }
        // 周一因停课只有 2 门
        assertEquals(2, weekOccurrences.getValue(monday).size)
    }

    @Test
    fun daySnapshot_activeOrNextPrefersInProgressThenEarliestUpcoming() {
        // 09:50 时 B(08:55-10:55) 进行中、C(10:10-11:50) 未开始：activeOrNext 必须是 B
        val nowInB = ZonedDateTime.of(monday, LocalTime.of(9, 50), HAUT_ZONE_ID)
        val snapshot = ScheduleEngine.buildDayTimelineSnapshot(
            date = monday,
            now = nowInB,
            term = term,
            courses = courses,
            rules = chainRules(),
            periodTimes = periods
        )

        assertEquals(3, snapshot.items.size)
        val active = snapshot.activeOrNextClass
        assertNotNull(active)
        assertEquals(courseB.id, active!!.occurrence.courseId)
        assertEquals(ClassStatus.IN_PROGRESS, active.status)

        // 07:30 全部未开始：取时间最早（并列链首列）的 A
        val nowEarly = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val earlySnapshot = ScheduleEngine.buildDayTimelineSnapshot(
            date = monday,
            now = nowEarly,
            term = term,
            courses = courses,
            rules = chainRules(),
            periodTimes = periods
        )
        assertEquals(courseA.id, earlySnapshot.activeOrNextClass!!.occurrence.courseId)
        assertEquals(ClassStatus.UPCOMING, earlySnapshot.activeOrNextClass!!.status)
    }

    @Test
    fun nextClassDayHint_skipsFullyCanceledDayAndLandsOnNextClassDay() {
        // 周一唯一一节课被停 → hint 必须越过周一，落到周二第一节课
        val rules = listOf(
            rule("r_mono", courseA.id, 1, 2, DayOfWeek.MONDAY),
            rule("r_tue", courseB.id, 3, 4, DayOfWeek.TUESDAY)
        )
        val cancelMonday = OccurrenceOverride(
            id = "ov_cancel_mon",
            termId = term.id,
            courseRuleId = "r_mono",
            date = monday,
            canceled = true,
            reason = "单次停课"
        )
        val nowAfterMondayClass = ZonedDateTime.of(monday, LocalTime.of(20, 0), HAUT_ZONE_ID)
        val snapshot = ScheduleEngine.buildDayTimelineSnapshot(
            date = monday,
            now = nowAfterMondayClass,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periods,
            occurrenceOverrides = listOf(cancelMonday)
        )

        assertNull(snapshot.activeOrNextClass)
        val hint = snapshot.nextClassDayHint
        assertNotNull(hint)
        assertEquals(monday.plusDays(1), hint!!.nextDate)
        assertEquals(1L, hint.daysUntil)
        assertEquals(courseB.name, hint.firstCourseName)
        assertEquals(LocalTime.of(10, 10), hint.firstCourseStartTime)
    }

    @Test
    fun nextClassDayHint_beyondTwentyOneDaysYieldsNull() {
        // 学期结束后再无排课：21 天扫描窗用尽 → null（小组件显示无课分支）
        val nowLate = ZonedDateTime.of(LocalDate.of(2027, 3, 1), LocalTime.of(20, 0), HAUT_ZONE_ID)
        val snapshot = ScheduleEngine.buildDayTimelineSnapshot(
            date = LocalDate.of(2027, 3, 1),
            now = nowLate,
            term = term,
            courses = courses,
            rules = chainRules(),
            periodTimes = periods
        )

        assertNull(snapshot.activeOrNextClass)
        assertNull(snapshot.nextClassDayHint)
    }
}
