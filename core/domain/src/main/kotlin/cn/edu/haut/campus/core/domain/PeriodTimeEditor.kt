package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.OccurrenceRule
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PeriodTimeScheme
import java.time.LocalDate

/**
 * 节次作息编辑器唯一校验口径。
 * UI、Repository 与导入路径必须共用这些纯函数，禁止各自静默夹取或兜底非法输入。
 */
object PeriodTimeEditor {
    const val MAX_PERIOD_NO = 30
    const val MAX_WEEK_NO = 30

    data class ValidationIssue(
        val code: String,
        val message: String,
        val periodNo: Int? = null
    )

    /** 校验一组同一作息作用域内的节次：节次号唯一、起止合法、按节次号单调且不重叠。 */
    fun validatePeriods(periods: List<PeriodTime>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val byPeriod = periods.groupBy { it.periodNo }
        for ((periodNo, rows) in byPeriod) {
            if (periodNo !in 1..MAX_PERIOD_NO) {
                issues += ValidationIssue("PERIOD_OUT_OF_RANGE", "节次必须在 1-${MAX_PERIOD_NO} 之间", periodNo)
            }
            if (rows.size > 1) {
                issues += ValidationIssue("DUPLICATE_PERIOD", "第 ${periodNo} 节重复定义", periodNo)
            }
        }
        val ordered = periods.sortedBy { it.periodNo }
        for (period in ordered) {
            if (!period.endLocalTime.isAfter(period.startLocalTime)) {
                issues += ValidationIssue(
                    "REVERSED_TIME",
                    "第 ${period.periodNo} 节结束时间必须晚于开始时间",
                    period.periodNo
                )
            }
        }
        for ((previous, next) in ordered.zipWithNext()) {
            if (!next.startLocalTime.isAfter(previous.startLocalTime)) {
                issues += ValidationIssue(
                    "NON_MONOTONIC_START",
                    "第 ${next.periodNo} 节开始时间必须晚于第 ${previous.periodNo} 节开始时间",
                    next.periodNo
                )
            }
            if (previous.endLocalTime.isAfter(next.startLocalTime)) {
                issues += ValidationIssue(
                    "OVERLAPPING_PERIODS",
                    "第 ${previous.periodNo} 节与第 ${next.periodNo} 节时间重叠",
                    next.periodNo
                )
            }
        }
        return issues
    }

    fun requireValidPeriods(periods: List<PeriodTime>, emptyMessage: String = "作息方案至少需要一节"): List<PeriodTime> {
        require(periods.isNotEmpty()) { emptyMessage }
        val issues = validatePeriods(periods)
        require(issues.isEmpty()) { issues.joinToString("；") { it.message } }
        return periods.sortedBy { it.periodNo }
    }

    /**
     * 校验作息方案元数据与同学期方案的日期区间互斥。
     * 日期区间按闭区间解释；编辑既有方案时用 editingSchemeId 排除自身。
     */
    fun validateScheme(
        name: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<PeriodTime>,
        existingSchemes: List<PeriodTimeScheme>,
        editingSchemeId: String? = null
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (name.isBlank()) {
            issues += ValidationIssue("BLANK_SCHEME_NAME", "作息方案名称不能为空")
        }
        if (endDate.isBefore(startDate)) {
            issues += ValidationIssue("REVERSED_SCHEME_RANGE", "作息方案结束日期不能早于开始日期")
        }
        if (periods.isEmpty()) {
            issues += ValidationIssue("EMPTY_SCHEME_PERIODS", "作息方案至少需要一节")
        }
        issues += validatePeriods(periods)
        val overlapping = existingSchemes
            .filter { it.id != editingSchemeId }
            .firstOrNull { !startDate.isAfter(it.endDate) && !it.startDate.isAfter(endDate) }
        if (overlapping != null) {
            issues += ValidationIssue(
                "OVERLAPPING_SCHEME_RANGE",
                "与「${overlapping.name}」的生效日期重叠，请调整日期区间"
            )
        }
        return issues.distinctBy { it.code to it.periodNo }
    }

    fun requireValidScheme(
        name: String,
        startDate: LocalDate,
        endDate: LocalDate,
        periods: List<PeriodTime>,
        existingSchemes: List<PeriodTimeScheme>,
        editingSchemeId: String? = null
    ) {
        val issues = validateScheme(name, startDate, endDate, periods, existingSchemes, editingSchemeId)
        require(issues.isEmpty()) { issues.joinToString("；") { it.message } }
    }

    /** 校验实践课多日排课矩阵；完全相同的时间段重复提交视为输入错误而非静默去重。 */
    fun validateOccurrenceRules(rules: List<OccurrenceRule>): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (rules.isEmpty()) {
            issues += ValidationIssue("EMPTY_OCCURRENCES", "实践课至少需要一条排课规则")
            return issues
        }
        val seen = linkedSetOf<String>()
        for ((index, rule) in rules.withIndex()) {
            for (other in rules.drop(index + 1)) {
                val overlapsTime = rule.startPeriod <= other.endPeriod && other.startPeriod <= rule.endPeriod
                if (rule.weekday == other.weekday && overlapsTime && rule.weeks.intersect(other.weeks).isNotEmpty()) {
                    issues += ValidationIssue(
                        "OVERLAPPING_OCCURRENCE",
                        "同一星期与周次的实践课时间段重叠",
                        maxOf(rule.startPeriod, other.startPeriod)
                    )
                }
            }
        }
        for (rule in rules) {
            if (rule.startPeriod !in 1..MAX_PERIOD_NO || rule.endPeriod !in 1..MAX_PERIOD_NO) {
                issues += ValidationIssue("PERIOD_OUT_OF_RANGE", "节次必须在 1-${MAX_PERIOD_NO} 之间", rule.startPeriod)
            }
            if (rule.endPeriod < rule.startPeriod) {
                issues += ValidationIssue("REVERSED_PERIOD_RANGE", "结束节次不能小于开始节次", rule.endPeriod)
            }
            if (rule.weeks.isEmpty()) {
                issues += ValidationIssue("EMPTY_WEEKS", "周次不能为空", rule.startPeriod)
            }
            if (rule.weeks.any { it !in 1..MAX_WEEK_NO }) {
                issues += ValidationIssue("WEEK_OUT_OF_RANGE", "周次必须在 1-${MAX_WEEK_NO} 之间", rule.startPeriod)
            }
            if (rule.rawRoom.isBlank()) {
                issues += ValidationIssue("BLANK_ROOM", "上课地点不能为空", rule.startPeriod)
            }
            val customStart = rule.customStartTime
            val customEnd = rule.customEndTime
            if ((customStart == null) != (customEnd == null)) {
                issues += ValidationIssue("INCOMPLETE_CUSTOM_TIME", "自定义起止时间必须同时填写", rule.startPeriod)
            } else if (customStart != null && customEnd != null && !customEnd.isAfter(customStart)) {
                issues += ValidationIssue("REVERSED_CUSTOM_TIME", "自定义结束时间必须晚于开始时间", rule.startPeriod)
            }
            val fingerprint = listOf(
                rule.weekday.value,
                rule.startPeriod,
                rule.endPeriod,
                rule.weeks.sorted().joinToString(","),
                rule.rawRoom.trim(),
                customStart?.toString().orEmpty(),
                customEnd?.toString().orEmpty()
            ).joinToString("|")
            if (!seen.add(fingerprint)) {
                issues += ValidationIssue("DUPLICATE_OCCURRENCE", "存在完全重复的排课时间段", rule.startPeriod)
            }
        }
        return issues
    }

    fun requireValidOccurrenceRules(rules: List<OccurrenceRule>): List<OccurrenceRule> {
        val issues = validateOccurrenceRules(rules)
        require(issues.isEmpty()) { issues.joinToString("；") { it.message } }
        return rules
    }
}
