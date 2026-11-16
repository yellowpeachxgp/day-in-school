package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TimeEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * R5-D4 领域层全矩阵回归：
 * A) 课表依据单遍聚合——混合输入绝不再短路 Valid；usable/unresolved/reasonCodes
 *    必须如实盘点（正常取消不算损坏、孤立取消不等于完整依据、零周次模板+
 *    合法单日补课不因模板判坏、外学期关联报告原因、输入顺序交换结果不变、
 *    Today 与 Hub 消费同一判定结果）。
 * B) 独立时间证据等级（EXPLICIT_VALID/TIMETABLE_VALID/ESTIMATED/UNRESOLVED/INVALID）——
 *    反向/推算时间不再冒充已解析；非法时间事件保留并提示"时间待确认"绝不删除。
 */
class ScheduleBasisAggregationTest {

    private val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 8, 31),
        weekStartDay = DayOfWeek.MONDAY,
        weekCount = 20,
        timeTableId = "tt_main",
        current = true
    )

    private val courseA = Course("c_a", "EXT_A", "数据结构", "张老师", 0xFF2563EB, BigDecimal("4.0"))
    private val courseB = Course("c_b", "EXT_B", "操作系统", "李老师", 0xFFEA580C, BigDecimal("3.5"), CourseSource.MANUAL)
    private val courses = listOf(courseA, courseB)

    private fun pt(no: Int, start: String, end: String) =
        PeriodTime("pt_no", "tt_main", no, LocalTime.parse(start), LocalTime.parse(end))

    /** 标准作息：覆盖 1..10 节（11-12 节缺失用于"缺时间"场景）。 */
    private val periodTimes = listOf(
        pt(1, "08:30", "09:15"), pt(2, "09:20", "10:05"),
        pt(3, "10:25", "11:10"), pt(4, "11:15", "12:00"),
        pt(5, "14:30", "15:15"), pt(6, "15:20", "16:05"),
        pt(7, "16:25", "17:10"), pt(8, "17:15", "18:00"),
        pt(9, "19:30", "20:15"), pt(10, "20:25", "21:10")
    )

    private fun rule(
        id: String,
        courseId: String = courseA.id,
        termId: String = term.id,
        weekday: DayOfWeek = DayOfWeek.MONDAY,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
        weeks: Set<Int> = (1..20).toSet(),
        customStart: LocalTime? = null,
        customEnd: LocalTime? = null
    ) = CourseScheduleRule(
        id = id, courseId = courseId, termId = termId, weekday = weekday,
        startPeriod = startPeriod, endPeriod = endPeriod, weeks = weeks,
        rawRoom = "5号教学楼5101", customStartTime = customStart, customEndTime = customEnd
    )

    private fun ov(
        id: String,
        termId: String = term.id,
        courseRuleId: String? = null,
        courseId: String? = null,
        startPeriod: Int? = null,
        endPeriod: Int? = null,
        startAt: LocalTime? = null,
        endAt: LocalTime? = null,
        canceled: Boolean = false
    ) = OccurrenceOverride(
        id = id, termId = termId, courseRuleId = courseRuleId, courseId = courseId,
        date = LocalDate.of(2026, 9, 7), startPeriod = startPeriod, endPeriod = endPeriod,
        startAt = startAt, endAt = endAt, canceled = canceled
    )

    private fun aggregate(
        rules: List<CourseScheduleRule> = emptyList(),
        overrides: List<OccurrenceOverride> = emptyList(),
        periods: List<PeriodTime> = periodTimes,
        termArg: Term? = term,
        courseArg: List<Course> = courses
    ) = CampusStudyRoomCatalog.aggregateCurrentTermScheduleBasis(
        term = termArg, courses = courseArg, rules = rules,
        periodTimes = periods, occurrenceOverrides = overrides
    )

    private fun evaluate(
        rules: List<CourseScheduleRule> = emptyList(),
        overrides: List<OccurrenceOverride> = emptyList(),
        periods: List<PeriodTime> = periodTimes
    ) = aggregate(rules, overrides, periods).status

    // ---------- 方案表：Valid 行 ----------

    @Test
    fun fullyCompleteInputsAreValid() {
        // 全完整：常规规则 + 作息表覆盖
        val agg = aggregate(rules = listOf(rule("r1")))
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Valid, agg.status)
        assertEquals(1, agg.usableCount)
        assertEquals(0, agg.unresolvedCount)
        assertTrue(agg.reasonCodes.isEmpty())
    }

    @Test
    fun legalCustomTimesWithoutPeriodTimeStillExplicitValid() {
        // 合法 custom 无 PeriodTime：EXPLICIT_VALID 不回退"必须作息表"旧误判
        val customRule = rule("r_custom", customStart = LocalTime.of(19, 0), customEnd = LocalTime.of(20, 30))
        val periodMap = emptyMap<Int, PeriodTime>()
        assertEquals(
            TimeEvidence.EXPLICIT_VALID,
            ScheduleEngine.ruleSchedulePeriodWindow(customRule, periodMap).evidence
        )
        val agg = aggregate(rules = listOf(customRule), periods = emptyList())
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Valid, agg.status)
        assertEquals(1, agg.usableCount)
    }

    @Test
    fun mixedExplicitAndTimetableEndsOrderedIsTimetableValid() {
        // 混合合法：customStart + 作息表 endPeriod 端点，两端明确且顺序合法 → TIMETABLE_VALID
        val mixedRule = rule(
            "r_mixed", startPeriod = 9, endPeriod = 10,
            customStart = LocalTime.of(19, 0)
        )
        val window = ScheduleEngine.ruleSchedulePeriodWindow(
            mixedRule, periodTimes.associateBy { it.periodNo }
        )
        assertEquals(TimeEvidence.TIMETABLE_VALID, window.evidence)
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Valid, evaluate(rules = listOf(mixedRule)))
    }

    @Test
    fun normalCancellationDoesNotCountAsDamage() {
        // 正常取消：指向本学期现存规则的停课覆盖只屏蔽事件，不算损坏 → Valid
        val agg = aggregate(
            rules = listOf(rule("r1")),
            overrides = listOf(ov("ov_cancel", courseRuleId = "r1", canceled = true))
        )
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Valid, agg.status)
        assertEquals(1, agg.usableCount)
        assertEquals(0, agg.unresolvedCount)
    }

    @Test
    fun zeroWeekTemplateConsumedByLegalMakeupIsNotJudgedBroken() {
        // 零周次模板 + 合法单日补课：模板被有效覆盖消费，不因模板判坏 → Valid
        val agg = aggregate(
            rules = listOf(rule("r_template", weeks = emptySet())),
            overrides = listOf(
                ov("ov_makeup", courseRuleId = "r_template", startPeriod = 3, endPeriod = 4)
            )
        )
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Valid, agg.status)
        assertEquals(1, agg.usableCount)
        assertEquals(0, agg.unresolvedCount)
    }

    // ---------- 方案表：Incomplete 行 ----------

    @Test
    fun validRulePlusMissingCourseRuleIsIncomplete() {
        // 完整 + 缺课程：可用行绝不短路掩盖缺课行
        val agg = aggregate(
            rules = listOf(rule("r_ok"), rule("r_ghost", courseId = "c_missing"))
        )
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(1, agg.usableCount)
        assertEquals(1, agg.unresolvedCount)
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.MISSING_COURSE, status.reason)
        assertTrue(status.reasonCodes.contains(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.MISSING_COURSE))
    }

    @Test
    fun validRulePlusMissingTimeRuleIsIncomplete() {
        // 完整 + 缺时间：11-12 节无作息定义且无自定义时间 → UNRESOLVED
        val agg = aggregate(
            rules = listOf(rule("r_ok"), rule("r_no_time", startPeriod = 11, endPeriod = 12))
        )
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(1, agg.usableCount)
        assertEquals(1, agg.unresolvedCount)
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.TIME_UNRESOLVED, status.reason)
    }

    @Test
    fun validRulePlusIllegalOverrideIsIncomplete() {
        // 完整 rule + 非法 override：依据评估必须查 override 实际效果（startAt/endAt 反向）
        val agg = aggregate(
            rules = listOf(rule("r_ok")),
            overrides = listOf(
                ov("ov_bad_time", courseRuleId = "r_ok",
                    startAt = LocalTime.of(10, 0), endAt = LocalTime.of(9, 0))
            )
        )
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(1, agg.usableCount)
        assertEquals(1, agg.unresolvedCount)
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.TIME_INVALID, status.reason)
    }

    @Test
    fun customStartOnlyIsEstimatedAndIncomplete() {
        // 仅 customStart：终点推算补全 → ESTIMATED，可展示但不足以作依据 → Incomplete
        val startOnlyRule = rule(
            "r_start_only", startPeriod = 11, endPeriod = 12,
            customStart = LocalTime.of(19, 0)
        )
        val window = ScheduleEngine.ruleSchedulePeriodWindow(
            startOnlyRule, periodTimes.associateBy { it.periodNo }
        )
        assertEquals(TimeEvidence.ESTIMATED, window.evidence)
        assertTrue("ESTIMATED 维持展示/提醒布尔兼容", window.hasResolvedTime)
        val agg = aggregate(rules = listOf(startOnlyRule))
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.TIME_UNRESOLVED,
            (agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete).reason)
    }

    @Test
    fun reversedCustomTimesAreInvalidAndIncomplete() {
        // end<=start 非法时间：INVALID → Incomplete（不再被 customStart!=null 冒充已解析）
        val badRule = rule("r_bad", customStart = LocalTime.of(10, 0), customEnd = LocalTime.of(9, 0))
        val window = ScheduleEngine.ruleSchedulePeriodWindow(badRule, periodTimes.associateBy { it.periodNo })
        assertEquals(TimeEvidence.INVALID, window.evidence)
        assertFalse(window.hasResolvedTime)
        val status = evaluate(rules = listOf(badRule)) as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.TIME_INVALID, status.reason)
    }

    @Test
    fun reversedTimetablePeriodIsInvalid() {
        // 作息反向：periodMap 内 end<=start → INVALID
        val reversedPeriods = listOf(pt(1, "10:00", "09:00"), pt(2, "09:00", "08:00"))
        val window = ScheduleEngine.ruleSchedulePeriodWindow(
            rule("r_rev"), reversedPeriods.associateBy { it.periodNo }
        )
        assertEquals(TimeEvidence.INVALID, window.evidence)
        val status = evaluate(rules = listOf(rule("r_rev")), periods = reversedPeriods)
                as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.TIME_INVALID, status.reason)
    }

    @Test
    fun allWeeksOutOfRangeIsUnexpandable() {
        // 全周超期：周次全部越出学期范围(1..20) → INPUTS_UNEXPANDABLE
        val status = evaluate(rules = listOf(rule("r_far", weeks = setOf(25, 30))))
                as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.INPUTS_UNEXPANDABLE, status.reason)
    }

    @Test
    fun orphanCancellationIsNotCompleteBasis() {
        // 孤立取消：指向不存在规则的停课行 = 关联问题 → Incomplete，绝不等于完整依据
        val agg = aggregate(overrides = listOf(ov("ov_orphan", courseRuleId = "r_gone", canceled = true)))
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(0, agg.usableCount)
        assertEquals(1, agg.unresolvedCount)
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.ASSOCIATION_BROKEN, status.reason)
    }

    @Test
    fun crossTermAssociationReportsReason() {
        // 外学期关联：本学期覆盖行关联外学期规则 → 关联问题必须报告原因
        val foreignRule = rule("r_foreign", termId = "old_term_1")
        val agg = aggregate(
            rules = listOf(foreignRule),
            overrides = listOf(ov("ov_cross", courseRuleId = foreignRule.id, courseId = courseA.id))
        )
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.ASSOCIATION_BROKEN, status.reason)
        assertTrue(status.reasonCodes.contains(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.ASSOCIATION_BROKEN))
    }

    // ---------- R6-3：模板豁免仅限"零产出"规则，常规规则问题绝不被覆盖抹掉 ----------

    @Test
    fun regularRuleInvalidTimeIsNotErasedByValidMakeupOverride() {
        // R6-3 回归：常规规则（weeks∩学期非空）自身时间非法 + 合法单日补课覆盖指向它——
        // 绝不允许借覆盖豁免抹掉 TIME_INVALID，其他日期的无效课程必须如实上报 → Incomplete
        val ruleA = rule(
            "r_a_bad_time",
            customStart = LocalTime.of(10, 0), customEnd = LocalTime.of(9, 0)
        )
        val legalMakeup = ov(
            "ov_legal_makeup", courseRuleId = "r_a_bad_time",
            startAt = LocalTime.of(14, 0), endAt = LocalTime.of(15, 35)
        )
        val agg = aggregate(rules = listOf(ruleA), overrides = listOf(legalMakeup))
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(1, agg.usableCount)   // 覆盖行自身合法、独立按 evidence 判为可用
        assertEquals(1, agg.unresolvedCount)
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.TIME_INVALID, status.reason)
        assertTrue(status.reasonCodes.contains(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.TIME_INVALID))
    }

    @Test
    fun outOfTermWeeksTemplateIsStillExemptedByLegalMakeup() {
        // R6-3：模板豁免的"零产出"口径包含"周次与学期有效周零相交"——
        // weeks 全部越界(25,30 ∉ 1..20)的借字段载体被合法单日补课引用仍判纯模板，
        // 其自身 INPUTS_UNEXPANDABLE 不判坏 → Valid（保持既有豁免语义）
        val template = rule("r_template_far", weeks = setOf(25, 30))
        val agg = aggregate(
            rules = listOf(template),
            overrides = listOf(
                ov("ov_makeup_far", courseRuleId = "r_template_far", startPeriod = 3, endPeriod = 4)
            )
        )
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Valid, agg.status)
        assertEquals(1, agg.usableCount)
        assertEquals(0, agg.unresolvedCount)
    }

    @Test
    fun regularRuleUnorderedPeriodsNotErasedByValidOverride() {
        // R6-3：weeks 与学期相交但节次区间倒序的常规规则被合法覆盖引用——
        // INPUTS_UNEXPANDABLE 必须保留为 unresolved，绝不被覆盖消费抹掉
        val ruleB = rule("r_b_bad_periods", startPeriod = 5, endPeriod = 3)
        val agg = aggregate(
            rules = listOf(ruleB),
            overrides = listOf(
                ov("ov_fix_periods", courseRuleId = "r_b_bad_periods", startPeriod = 3, endPeriod = 4)
            )
        )
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(1, agg.usableCount)
        assertEquals(1, agg.unresolvedCount)
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.INPUTS_UNEXPANDABLE, status.reason)
    }

    @Test
    fun regularRuleMissingCourseNotErasedByValidOverride() {
        // R6-3：常规规则缺课程（weeks 与学期相交）+ 覆盖自带合法课程/节次——
        // 覆盖行合法计为可用，但 MISSING_COURSE 必须保留，绝不借豁免冒充完整依据
        val orphanRule = rule("r_no_course", courseId = "c_ghost")
        val agg = aggregate(
            rules = listOf(orphanRule),
            overrides = listOf(
                ov("ov_own_course", courseRuleId = "r_no_course", courseId = courseB.id,
                    startPeriod = 5, endPeriod = 6)
            )
        )
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(1, agg.usableCount)
        assertEquals(1, agg.unresolvedCount)
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.MISSING_COURSE, status.reason)
    }

    @Test
    fun invalidBaseRuleAndBrokenOverrideAreBothReported() {
        // R6-3：覆盖引用一条无效常规规则且覆盖自身缺课程——
        // 规则与覆盖行各自的问题都如实上报，绝不互相吞没
        val ruleA = rule(
            "r_a_invalid", customStart = LocalTime.of(10, 0), customEnd = LocalTime.of(9, 0)
        )
        val brokenOv = ov(
            "ov_missing_course", courseRuleId = "r_a_invalid",
            courseId = "c_ghost", startPeriod = 3, endPeriod = 4
        )
        val agg = aggregate(rules = listOf(ruleA), overrides = listOf(brokenOv))
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(0, agg.usableCount)
        assertEquals(2, agg.unresolvedCount)
        assertTrue(status.reasonCodes.contains(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.TIME_INVALID))
        assertTrue(status.reasonCodes.contains(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.MISSING_COURSE))
    }

    @Test
    fun canceledOverrideDoesNotConsumeZeroWeekTemplate() {
        // R6-3：停课覆盖（canceled=true）只屏蔽事件、自身绝不产出事件——
        // 不消费模板规则，零周次模板自身的 INPUTS_UNEXPANDABLE 必须保留
        val template = rule("r_template_cancel", weeks = emptySet())
        val agg = aggregate(
            rules = listOf(template),
            overrides = listOf(
                ov("ov_cancel_t", courseRuleId = "r_template_cancel", canceled = true)
            )
        )
        val status = agg.status as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete
        assertEquals(0, agg.usableCount)
        assertEquals(1, agg.unresolvedCount)
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Reason.INPUTS_UNEXPANDABLE, status.reason)
    }

    // ---------- 方案表：Absent 行 / 顺序不变 / 同结果消费 ----------

    @Test
    fun absentWhenNoCurrentTermInput() {
        assertEquals(
            CampusStudyRoomCatalog.ScheduleBasisEvaluation.Absent,
            aggregate(termArg = null).status
        )
        assertEquals(CampusStudyRoomCatalog.ScheduleBasisEvaluation.Absent, evaluate())
        // 其他学期残留不算本学期输入
        assertEquals(
            CampusStudyRoomCatalog.ScheduleBasisEvaluation.Absent,
            evaluate(rules = listOf(rule("r_old", termId = "old_term_1")))
        )
        // 仅剩中性停课行（自带可解析课程的独立取消）→ 无任何可用排课依据 → Absent
        assertEquals(
            CampusStudyRoomCatalog.ScheduleBasisEvaluation.Absent,
            evaluate(overrides = listOf(ov("ov_solo_cancel", courseId = courseA.id, canceled = true)))
        )
    }

    @Test
    fun inputOrderSwapDoesNotChangeResult() {
        // 有效/无效输入顺序交换：结果必须不变（顺序无关聚合）
        val valid = rule("r_ok")
        val invalid = rule("r_ghost", courseId = "c_missing")
        val forward = evaluate(rules = listOf(valid, invalid))
        val swapped = evaluate(rules = listOf(invalid, valid))
        assertEquals(forward, swapped)

        val validOv = ov("ov_makeup", courseId = courseB.id, startPeriod = 5, endPeriod = 6)
        val badOv = ov("ov_bad", courseRuleId = "r_ghost2")
        val f2 = evaluate(overrides = listOf(validOv, badOv))
        val s2 = evaluate(overrides = listOf(badOv, validOv))
        assertEquals(f2, s2)
        assertTrue(f2 is CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete)
    }

    @Test
    fun todayAndHubConsumeSameEvaluationResult() {
        // Today 自习室卡片与校园 Hub 自习教室页消费同一 evaluateCurrentTermScheduleBasis 结果：
        // 同一 fixture → 同一三态对象与原因文案，绝不两套口径
        val rules = listOf(rule("r_ok"), rule("r_ghost", courseId = "c_missing"))
        val first = CampusStudyRoomCatalog.evaluateCurrentTermScheduleBasis(
            term = term, courses = courses, rules = rules,
            periodTimes = periodTimes, occurrenceOverrides = emptyList()
        )
        val second = CampusStudyRoomCatalog.evaluateCurrentTermScheduleBasis(
            term = term, courses = courses, rules = rules,
            periodTimes = periodTimes, occurrenceOverrides = emptyList()
        )
        assertEquals(first, second)
        assertEquals(first.hasBasis, CampusStudyRoomCatalog.hasCurrentTermScheduleBasis(
            term = term, courses = courses, rules = rules,
            periodTimes = periodTimes, occurrenceOverrides = emptyList()
        ))
        assertEquals(
            (first as CampusStudyRoomCatalog.ScheduleBasisEvaluation.Incomplete).reason.label,
            first.reasonLabel
        )
        // 聚合结果与状态一致：status/hasBasis 始终由同一聚合产出
        val agg = aggregate(rules = rules)
        assertEquals(agg.status, first)
        assertEquals(agg.usableCount > 0 && agg.unresolvedCount == 0, first.hasBasis)
    }

    // ---------- Part B：引擎证据等级矩阵 + 布尔兼容盘点 ----------

    @Test
    fun timeEvidenceLevelsOnRuleWindows() {
        val pm = periodTimes.associateBy { it.periodNo }
        // EXPLICIT_VALID：两端显式且合法（含无作息表）
        assertEquals(
            TimeEvidence.EXPLICIT_VALID,
            ScheduleEngine.ruleSchedulePeriodWindow(
                rule("r_e", customStart = LocalTime.of(19, 0), customEnd = LocalTime.of(20, 0)), pm
            ).evidence
        )
        assertEquals(
            TimeEvidence.EXPLICIT_VALID,
            ScheduleEngine.ruleSchedulePeriodWindow(
                rule("r_e2", customStart = LocalTime.of(19, 0), customEnd = LocalTime.of(20, 0)),
                emptyMap()
            ).evidence
        )
        // TIMETABLE_VALID：纯作息表双端
        assertEquals(
            TimeEvidence.TIMETABLE_VALID,
            ScheduleEngine.ruleSchedulePeriodWindow(rule("r_t"), pm).evidence
        )
        // TIMETABLE_VALID：混合（作息起点 + 自定义合法终点）
        assertEquals(
            TimeEvidence.TIMETABLE_VALID,
            ScheduleEngine.ruleSchedulePeriodWindow(
                rule("r_m", customEnd = LocalTime.of(10, 30)), pm
            ).evidence
        )
        // ESTIMATED：仅自定义起点，无任何终点来源 → 推算补全
        assertEquals(
            TimeEvidence.ESTIMATED,
            ScheduleEngine.ruleSchedulePeriodWindow(
                rule("r_s", startPeriod = 11, endPeriod = 12, customStart = LocalTime.of(19, 0)), pm
            ).evidence
        )
        // UNRESOLVED：作息缺节次且无自定义起点
        assertEquals(
            TimeEvidence.UNRESOLVED,
            ScheduleEngine.ruleSchedulePeriodWindow(rule("r_u", startPeriod = 11, endPeriod = 12), pm).evidence
        )
        // INVALID：反向自定义 / 反向作息 / 混合反向
        assertEquals(
            TimeEvidence.INVALID,
            ScheduleEngine.ruleSchedulePeriodWindow(
                rule("r_i", customStart = LocalTime.of(10, 0), customEnd = LocalTime.of(9, 0)), pm
            ).evidence
        )
        assertEquals(
            TimeEvidence.INVALID,
            ScheduleEngine.ruleSchedulePeriodWindow(
                rule("r_i2", startPeriod = 1, endPeriod = 1, customEnd = LocalTime.of(8, 0)), pm
            ).evidence
        )
    }

    @Test
    fun hasResolvedTimeBooleanStaysCompatibleForDisplayAndReminder() {
        val pm = periodTimes.associateBy { it.periodNo }
        // 原布尔兼容：已解析为 true（EXPLICIT_VALID/TIMETABLE_VALID/ESTIMATED）
        assertTrue(ScheduleEngine.ruleSchedulePeriodWindow(rule("a", customStart = LocalTime.of(19, 0), customEnd = LocalTime.of(20, 0)), pm).hasResolvedTime)
        assertTrue(ScheduleEngine.ruleSchedulePeriodWindow(rule("b"), pm).hasResolvedTime)
        assertTrue("customStart-only 维持展示兼容", ScheduleEngine.ruleSchedulePeriodWindow(
            rule("c", startPeriod = 11, endPeriod = 12, customStart = LocalTime.of(19, 0)), pm).hasResolvedTime)
        // 未解析/非法为 false（UNRESOLVED/INVALID）
        assertFalse(ScheduleEngine.ruleSchedulePeriodWindow(rule("d", startPeriod = 11, endPeriod = 12), pm).hasResolvedTime)
        assertFalse("反向时间不再冒充已解析", ScheduleEngine.ruleSchedulePeriodWindow(
            rule("e", customStart = LocalTime.of(10, 0), customEnd = LocalTime.of(9, 0)), pm).hasResolvedTime)
    }

    @Test
    fun invalidTimeOccurrenceIsKeptAndMarkedPendingConfirm() {
        // 回归：非法/反向时间的事件保留在课表显示"时间待确认"，绝不删除用户课程
        val monday = LocalDate.of(2026, 9, 7) // 学期第2周周一（term.startDate=2026-08-31）
        val badRule = rule(
            "r_reversed", customStart = LocalTime.of(10, 0), customEnd = LocalTime.of(9, 0)
        )
        val occs = ScheduleEngine.resolveOccurrencesForDate(
            date = monday, term = term, courses = courses,
            rules = listOf(badRule), periodTimes = periodTimes
        )
        assertEquals("非法时间事件必须保留在课表，绝不删除用户课程", 1, occs.size)
        val occ = occs.single()
        assertFalse(occ.hasResolvedTime)
        assertEquals(TimeEvidence.INVALID, occ.timeEvidence)
        assertTrue(
            occ.formatTimeOrPeriodRange(DateTimeFormatter.ofPattern("HH:mm")).contains("时间待确认")
        )

        val snapshot = ScheduleEngine.buildDayTimelineSnapshot(
            date = monday,
            now = ZonedDateTime.of(monday, LocalTime.of(8, 45), HAUT_ZONE_ID),
            term = term, courses = courses, rules = listOf(badRule), periodTimes = periodTimes
        )
        val item = snapshot.items.single { it.occurrence.ruleId == "r_reversed" }
        assertTrue(item.statusText.contains("时间待确认"))
        assertEquals(ClassStatus.UPCOMING, item.status)
    }

    @Test
    fun timetableOnlyStartStaysUnresolvedAnchor() {
        // 作息表仅覆盖起始节次、终点缺失：锚点排序但不可信（旧布尔 false 兼容保持）
        val pm = periodTimes.associateBy { it.periodNo }
        val window = ScheduleEngine.ruleSchedulePeriodWindow(
            rule("r_partial", startPeriod = 10, endPeriod = 12), pm
        )
        assertEquals(TimeEvidence.UNRESOLVED, window.evidence)
        assertFalse(window.hasResolvedTime)
        assertEquals(LocalTime.of(20, 25), window.startTime) // 已知第10节起点作锚点
    }
}
