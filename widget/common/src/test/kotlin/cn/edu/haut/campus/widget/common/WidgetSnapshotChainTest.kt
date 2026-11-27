package cn.edu.haut.campus.widget.common

import cn.edu.haut.campus.core.database.DirectScheduleData
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import cn.edu.haut.campus.core.model.WidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 小组件课表链全链回归：DirectScheduleData（仓储直连快照）→ composeWidgetSnapshot →
 * 日时间轴 / 整周排课 / 倒计时基准 / 导入标记。覆盖双课表（两门并列）与三套课表
 * （三门链式并列）场景下四款小组件共用数据源的一致性行为——
 * 生产接缝仅限 composeWidgetSnapshot 纯函数提取，组装口径与 loadForWidget 完全一致。
 */
class WidgetSnapshotChainTest {

    private val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 9, 7), // 周一，第 1 周
        weekCount = 20,
        timeTableId = "tt_chain_test",
        current = true
    )

    private val periods = listOf(
        PeriodTime("p1", "tt_chain_test", 1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
        PeriodTime("p2", "tt_chain_test", 2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
        PeriodTime("p3", "tt_chain_test", 3, LocalTime.of(10, 10), LocalTime.of(10, 55)),
        PeriodTime("p4", "tt_chain_test", 4, LocalTime.of(11, 5), LocalTime.of(11, 50)),
        PeriodTime("p5", "tt_chain_test", 5, LocalTime.of(14, 30), LocalTime.of(15, 15)),
        PeriodTime("p6", "tt_chain_test", 6, LocalTime.of(15, 25), LocalTime.of(16, 10))
    )

    private val courseA = Course("c_alpha", "JW-A", "高等数学A", "甲老师", 0xFF2563EB, BigDecimal("4.0"))
    private val courseB = Course("c_beta", "JW-B", "大学物理B", "乙老师", 0xFFEA580C, BigDecimal("3.5"))
    private val courseC = Course("c_gamma", "JW-C", "离散数学C", "丙老师", 0xFF059669, BigDecimal("2.0"))
    private val courses = listOf(courseA, courseB, courseC)

    private val monday = LocalDate.of(2026, 9, 7)
    private val config = WidgetInstanceConfig(appWidgetId = 7, widgetType = WidgetType.NEXT_CLASS_2X2)

    private fun rule(id: String, courseId: String, startPeriod: Int, endPeriod: Int, termId: String = term.id) =
        CourseScheduleRule(
            id = id,
            courseId = courseId,
            termId = termId,
            weekday = DayOfWeek.MONDAY,
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            weeks = (1..16).toSet(),
            rawRoom = "3号教学楼3101"
        )

    private fun chainRules() = listOf(
        rule("r_a", courseA.id, 1, 2),
        rule("r_b", courseB.id, 2, 3),
        rule("r_c", courseC.id, 3, 4)
    )

    private fun data(
        term: Term? = this.term,
        rules: List<CourseScheduleRule> = chainRules(),
        occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
        periodTimes: List<PeriodTime> = periods
    ) = DirectScheduleData(
        term = term,
        courses = courses,
        rules = rules,
        periodTimes = periodTimes,
        calendarOverrides = emptyList(),
        occurrenceOverrides = occurrenceOverrides,
        buildings = emptyList()
    )

    private fun compose(
        data: DirectScheduleData,
        now: ZonedDateTime,
        elapsedRealtime: Long = 1_000_000L
    ) = ScheduleSnapshotUseCase.composeWidgetSnapshot(
        data = data,
        config = config,
        themeAccentArgb = 0xFF053968.toInt(),
        profileId = "prof_test",
        generation = 3L,
        now = now,
        elapsedRealtime = elapsedRealtime
    )

    @Test
    fun tripleChain_daySnapshotExposesThreeColumnClusterAndWeekOccurrences() {
        val now = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val snapshot = compose(data(), now)

        assertEquals("prof_test", snapshot.profileId)
        assertEquals(3L, snapshot.generation)
        assertTrue(snapshot.hasImportedCourses)
        assertEquals(1, snapshot.daySnapshot.teachingWeek)

        // 日链：三门链式并列全部出现在时间轴且分栏计数为 3
        assertEquals(3, snapshot.daySnapshot.items.size)
        for (item in snapshot.daySnapshot.items) {
            assertEquals(3, item.occurrence.conflictColumnCount)
        }

        // 周链（WEEK_4X4 数据源）：周一三门并列，整周 7 天齐全
        assertEquals(7, snapshot.weekOccurrences.size)
        val mondayOccurrences = snapshot.weekOccurrences.getValue(monday)
        assertEquals(3, mondayOccurrences.size)
        assertEquals(
            listOf(courseA.id, courseB.id, courseC.id),
            mondayOccurrences.map { it.courseId }
        )

        // 倒计时链：activeOrNext 是 08:00 的 A，基准 = elapsed + 30 分钟
        val active = snapshot.daySnapshot.activeOrNextClass
        assertNotNull(active)
        assertEquals(courseA.id, active!!.occurrence.courseId)
        assertEquals(ClassStatus.UPCOMING, active.status)
        assertEquals(1_000_000L + 30 * 60 * 1000L, snapshot.chronometerTargetElapsedRealtime)
    }

    @Test
    fun doubleChain_afterMiddleCancel_columnsCollapseAndWeekMatchesDay() {
        val cancelB = OccurrenceOverride(
            id = "ov_cancel_b",
            termId = term.id,
            courseRuleId = "r_b",
            date = monday,
            canceled = true,
            reason = "单次停课"
        )
        val now = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val snapshot = compose(data(occurrenceOverrides = listOf(cancelB)), now)

        // 链环拆除：A 与 C 不再桥接，全部回退无分栏
        assertEquals(2, snapshot.daySnapshot.items.size)
        for (item in snapshot.daySnapshot.items) {
            assertEquals(1, item.occurrence.conflictColumnCount)
        }
        // 周链与日链同口径：周一也是 2 门且无分栏
        val mondayOccurrences = snapshot.weekOccurrences.getValue(monday)
        assertEquals(2, mondayOccurrences.size)
        assertTrue(mondayOccurrences.all { it.conflictColumnCount == 1 })
    }

    @Test
    fun unresolvedTime_neverFabricatesChronometerCountdown() {
        // 作息表缺失（timeTableId 不匹配 → 节次无法解析）：倒计时必须为 null，绝不伪造
        val now = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val snapshot = compose(data(periodTimes = emptyList()), now)

        assertEquals(3, snapshot.daySnapshot.items.size)
        val active = snapshot.daySnapshot.activeOrNextClass
        assertNotNull(active)
        assertFalse(active!!.occurrence.hasResolvedTime)
        assertNull(snapshot.chronometerTargetElapsedRealtime)
    }

    @Test
    fun inProgressClass_chronometerTargetsEndTime() {
        // 09:50 时 B（08:55-10:55）进行中：倒计时基准 = elapsed + 到 10:55 的差值（65 分钟）
        val now = ZonedDateTime.of(monday, LocalTime.of(9, 50), HAUT_ZONE_ID)
        val snapshot = compose(data(), now)

        val active = snapshot.daySnapshot.activeOrNextClass
        assertNotNull(active)
        assertEquals(courseB.id, active!!.occurrence.courseId)
        assertEquals(ClassStatus.IN_PROGRESS, active.status)
        assertEquals(1_000_000L + 65 * 60 * 1000L, snapshot.chronometerTargetElapsedRealtime)
    }

    @Test
    fun afterAllClassesFinished_chronometerNullAndHintPointsToNextWeek() {
        // 20:00 全部结课：倒计时为 null；下周同规则仍有课 → hint 指向下一周周一（+7 天）
        val now = ZonedDateTime.of(monday, LocalTime.of(20, 0), HAUT_ZONE_ID)
        val snapshot = compose(data(), now)

        assertNull(snapshot.daySnapshot.activeOrNextClass)
        assertNull(snapshot.chronometerTargetElapsedRealtime)
        val hint = snapshot.daySnapshot.nextClassDayHint
        assertNotNull(hint)
        assertEquals(7L, hint!!.daysUntil)
        assertEquals(monday.plusDays(7), hint.nextDate)
    }

    @Test
    fun noRulesForCurrentTerm_reportsNotImported() {
        // 只有其他学期的规则：当前学期无课 → 未导入分支（小组件显示「点击导入课表」）
        val foreignRules = chainRules().map { it.copy(termId = "2025-2026-2") }
        val now = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val snapshot = compose(data(rules = foreignRules), now)

        assertFalse(snapshot.hasImportedCourses)
        assertEquals(0, snapshot.daySnapshot.items.size)
    }

    @Test
    fun emptySchedule_reportsNotImportedAndNoChronometer() {
        val now = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val snapshot = compose(data(rules = emptyList()), now)

        assertFalse(snapshot.hasImportedCourses)
        assertNull(snapshot.daySnapshot.activeOrNextClass)
        assertNull(snapshot.chronometerTargetElapsedRealtime)
    }

    @Test
    fun nullTerm_weekOccurrencesEmptyAndGracefulSnapshot() {
        val now = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val snapshot = compose(data(term = null, rules = emptyList()), now)

        assertNull(snapshot.daySnapshot.term)
        assertTrue(snapshot.weekOccurrences.isEmpty())
        assertNull(snapshot.daySnapshot.activeOrNextClass)
    }

    @Test
    fun configAndTheme_passThroughUnmodified() {
        val now = ZonedDateTime.of(monday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val snapshot = compose(data(), now)

        assertEquals(config, snapshot.config)
        assertEquals(0xFF053968.toInt(), snapshot.themeAccentArgb)
    }

    @Test
    fun weekOccurrences_currentWeekDerivedFromTeachingWeekNotCalendarWeek() {
        // 周三查看时 teachingWeek 仍为 1，周链仍产出第 1 周周一至周日（绝不按自然周重锚）
        val wednesday = monday.plusDays(2)
        val now = ZonedDateTime.of(wednesday, LocalTime.of(7, 30), HAUT_ZONE_ID)
        val snapshot = compose(data(), now)

        assertEquals(1, snapshot.daySnapshot.teachingWeek)
        assertEquals(7, snapshot.weekOccurrences.size)
        assertTrue(snapshot.weekOccurrences.containsKey(monday))
        // 周三无规则 → 空列表；周一三门并列不变
        assertEquals(0, snapshot.weekOccurrences.getValue(wednesday).size)
        assertEquals(3, snapshot.weekOccurrences.getValue(monday).size)
    }
}
