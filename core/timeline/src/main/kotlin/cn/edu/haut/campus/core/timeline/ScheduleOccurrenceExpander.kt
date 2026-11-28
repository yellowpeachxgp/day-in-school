package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TermDateStatus
import cn.edu.haut.campus.core.model.TimeEvidence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** 与现有课表行为一致的某一天展开结果：active 为生效事件，cancelled 为被今日停课屏蔽的事件。 */
internal data class ScheduleDayExpansion(
    val active: List<ClassOccurrence>,
    val cancelled: List<ClassOccurrence>
)

internal data class EffectiveScheduleDate(
    val actualDate: LocalDate,
    val scheduleDateUsed: LocalDate,
    val teachingWeek: Int?,
    val weekdayUsed: DayOfWeek,
    val status: TermDateStatus,
    val calendarNotice: String? = null
)

/**
 * 纯 JVM 课程 occurrence 展开器。
 *
 * 这里作为过渡期镜像实现 W0/W0-04 已验证的课表语义：教学周对齐、calendar_overrides、
 * 按日期切换节次方案、单双周精确周集合、今日停课/仅改本次/跨日调入、同课相邻节次合并、
 * 非法时间证据不冒充可信时刻。由于内核不能反向依赖 engine，本卡先保持纯 JVM 镜像；
 * W1-05 切换调用方后应收敛旧 engine 展开路径，禁止两套语义长期漂移。
 */
internal object ScheduleOccurrenceExpander {

    fun expandDate(
        date: LocalDate,
        term: Term,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride>,
        occurrenceOverrides: List<OccurrenceOverride>
    ): ScheduleDayExpansion = ScheduleDayExpansion(
        active = resolveOccurrencesForDate(date, term, courses, rules, periodTimes, calendarOverrides, occurrenceOverrides),
        cancelled = resolveCanceledOccurrencesForDate(date, term, courses, rules, periodTimes, calendarOverrides, occurrenceOverrides)
    )

    fun resolveDate(date: LocalDate, term: Term, overrides: List<CalendarOverride>): EffectiveScheduleDate {
        val occurrenceOverride = overrides.firstOrNull { it.termId == term.id && it.date == date }
        if (occurrenceOverride != null) {
            when (occurrenceOverride.overrideType) {
                CalendarOverrideType.HOLIDAY_NO_CLASS -> {
                    return EffectiveScheduleDate(
                        actualDate = date,
                        scheduleDateUsed = date,
                        teachingWeek = calculateRawTeachingWeek(date, term),
                        weekdayUsed = date.dayOfWeek,
                        status = TermDateStatus.HOLIDAY_NO_CLASS,
                        calendarNotice = occurrenceOverride.title
                    )
                }
                CalendarOverrideType.MAKEUP_FOLLOW_DATE -> {
                    val follow = occurrenceOverride.followDate ?: date
                    return EffectiveScheduleDate(
                        actualDate = date,
                        scheduleDateUsed = follow,
                        teachingWeek = calculateRawTeachingWeek(follow, term),
                        weekdayUsed = follow.dayOfWeek,
                        status = TermDateStatus.MAKEUP_RESCHEDULED,
                        calendarNotice = occurrenceOverride.title
                    )
                }
                CalendarOverrideType.CUSTOM_TEACHING_WEEK -> {
                    return EffectiveScheduleDate(
                        actualDate = date,
                        scheduleDateUsed = date,
                        teachingWeek = occurrenceOverride.customTeachingWeek ?: calculateRawTeachingWeek(date, term),
                        weekdayUsed = date.dayOfWeek,
                        status = TermDateStatus.IN_TERM,
                        calendarNotice = occurrenceOverride.title
                    )
                }
            }
        }

        val alignedStart = alignToWeekStart(term.startDate, term.weekStartDay)
        val daysBetween = ChronoUnit.DAYS.between(alignedStart, date)
        if (daysBetween < 0) {
            return EffectiveScheduleDate(
                actualDate = date,
                scheduleDateUsed = date,
                teachingWeek = null,
                weekdayUsed = date.dayOfWeek,
                status = TermDateStatus.BEFORE_TERM,
                calendarNotice = "距开学还有 " + ChronoUnit.DAYS.between(date, term.startDate).coerceAtLeast(1) + " 天"
            )
        }
        val weekNo = (daysBetween / 7).toInt() + 1
        if (weekNo > term.weekCount) {
            return EffectiveScheduleDate(
                actualDate = date,
                scheduleDateUsed = date,
                teachingWeek = null,
                weekdayUsed = date.dayOfWeek,
                status = TermDateStatus.AFTER_TERM,
                calendarNotice = "本学期课程已结束"
            )
        }
        return EffectiveScheduleDate(
            actualDate = date,
            scheduleDateUsed = date,
            teachingWeek = weekNo,
            weekdayUsed = date.dayOfWeek,
            status = TermDateStatus.IN_TERM
        )
    }

    fun calculateRawTeachingWeek(date: LocalDate, term: Term): Int? {
        val alignedStart = alignToWeekStart(term.startDate, term.weekStartDay)
        val daysBetween = ChronoUnit.DAYS.between(alignedStart, date)
        if (daysBetween < 0) return null
        val weekNo = (daysBetween / 7).toInt() + 1
        return if (weekNo in 1..term.weekCount) weekNo else null
    }

    fun alignToWeekStart(startDate: LocalDate, weekStartDay: DayOfWeek): LocalDate {
        val diff = (startDate.dayOfWeek.value - weekStartDay.value + 7) % 7
        return startDate.minusDays(diff.toLong())
    }

    /** 默认作息与命中日期的用户方案整套切换；不与默认表混排。 */
    fun periodMapForDate(date: LocalDate, term: Term, periodTimes: List<PeriodTime>): Map<Int, PeriodTime> {
        val rowsForTable = periodTimes.filter { it.timeTableId == term.timeTableId }
        val baseRows = rowsForTable.filter { it.schemeId == null }
        val activeSchemeRows = rowsForTable.filter { row ->
            val start = row.validStartDate
            val end = row.validEndDate
            row.schemeId != null && row.termId == term.id &&
                start != null && end != null &&
                !date.isBefore(start) && !date.isAfter(end)
        }
        if (activeSchemeRows.isEmpty()) return baseRows.associateBy { it.periodNo }
        val selectedSchemeId = activeSchemeRows
            .groupBy { it.schemeId!! }
            .entries
            .maxWithOrNull(
                compareBy<Map.Entry<String, List<PeriodTime>>> { entry ->
                    entry.value.mapNotNull { it.validStartDate }.maxOrNull()?.toEpochDay() ?: Long.MIN_VALUE
                }.thenBy { it.key }
            )
            ?.key
        return activeSchemeRows.filter { it.schemeId == selectedSchemeId }.associateBy { it.periodNo }
    }

    fun isExpandableScheduleRule(
        rule: CourseScheduleRule,
        termId: String,
        courseIds: Set<String>,
        termWeekCount: Int
    ): Boolean = rule.termId == termId &&
        rule.weeks.any { it in 1..termWeekCount } &&
        rule.startPeriod >= 1 &&
        rule.startPeriod <= rule.endPeriod &&
        rule.courseId in courseIds

    fun sameTermBaseRule(
        occurrenceOverride: OccurrenceOverride,
        termId: String,
        rulesById: Map<String, CourseScheduleRule>
    ): CourseScheduleRule? = occurrenceOverride.courseRuleId
        ?.let { rulesById[it] }
        ?.takeIf { it.termId == termId }

    fun isExpandableOccurrenceOverride(
        occurrenceOverride: OccurrenceOverride,
        termId: String,
        rulesById: Map<String, CourseScheduleRule>,
        courseIds: Set<String>
    ): Boolean {
        if (occurrenceOverride.termId != termId || occurrenceOverride.canceled) return false
        val baseRule = sameTermBaseRule(occurrenceOverride, termId, rulesById)
        val targetCourseId = occurrenceOverride.courseId ?: baseRule?.courseId ?: return false
        return targetCourseId in courseIds
    }

    fun expandableOverrideTimeWindow(
        occurrenceOverride: OccurrenceOverride,
        termId: String,
        rulesById: Map<String, CourseScheduleRule>,
        courseIds: Set<String>,
        periodMap: Map<Int, PeriodTime>
    ): ResolvedPeriodWindow? {
        if (!isExpandableOccurrenceOverride(occurrenceOverride, termId, rulesById, courseIds)) return null
        val baseRule = sameTermBaseRule(occurrenceOverride, termId, rulesById)
        val startPeriod = occurrenceOverride.startPeriod ?: baseRule?.startPeriod ?: 1
        val endPeriod = occurrenceOverride.endPeriod ?: baseRule?.endPeriod ?: (startPeriod + 1)
        return resolvePeriodWindow(
            startPeriod = startPeriod,
            endPeriod = endPeriod,
            customStart = occurrenceOverride.startAt ?: baseRule?.customStartTime,
            customEnd = occurrenceOverride.endAt ?: baseRule?.customEndTime,
            periodMap = periodMap
        )
    }

    private fun resolveOccurrencesForDate(
        date: LocalDate,
        term: Term,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride>,
        occurrenceOverrides: List<OccurrenceOverride>
    ): List<ClassOccurrence> {
        val courseMap = courses.associateBy { it.id }
        val periodMap = periodMapForDate(date, term, periodTimes)
        val effectiveDate = resolveDate(date, term, calendarOverrides)
        val dayOverrides = occurrenceOverrides.filter { it.termId == term.id && it.date == date }
        val overridesByRuleId = dayOverrides.filter { it.courseRuleId != null }.associateBy { it.courseRuleId!! }
        val absorbedCompanionsByPrimaryRuleId = dayOverrides
            .filter { it.canceled && !it.courseRuleId.isNullOrBlank() && it.reason?.startsWith("MERGED_INTO:") == true }
            .groupBy(
                keySelector = { it.reason!!.removePrefix("MERGED_INTO:").trim() },
                valueTransform = { it.courseRuleId!! }
            )
        val rawOccurrences = mutableListOf<ClassOccurrence>()

        if (effectiveDate.status != TermDateStatus.HOLIDAY_NO_CLASS && effectiveDate.teachingWeek != null) {
            val activeWeek = effectiveDate.teachingWeek
            val targetWeekday = effectiveDate.weekdayUsed
            val matchingRules = rules.filter { rule ->
                isExpandableScheduleRule(rule, term.id, courseMap.keys, term.weekCount) &&
                    rule.weekday == targetWeekday &&
                    activeWeek in rule.weeks
            }
            for (rule in matchingRules) {
                val course = courseMap[rule.courseId] ?: continue
                val ruleOverride = overridesByRuleId[rule.id]
                if (ruleOverride?.canceled == true) continue
                val startPeriod = ruleOverride?.startPeriod ?: rule.startPeriod
                val endPeriod = ruleOverride?.endPeriod ?: rule.endPeriod
                val resolvedWindow = resolvePeriodWindow(
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    customStart = ruleOverride?.startAt ?: rule.customStartTime,
                    customEnd = ruleOverride?.endAt ?: rule.customEndTime,
                    periodMap = periodMap
                )
                val mergedRuleIds = (listOf(rule.id) + absorbedCompanionsByPrimaryRuleId[rule.id].orEmpty()).distinct()
                rawOccurrences += ClassOccurrence(
                    occurrenceId = date.toString() + "_" + rule.id,
                    courseId = course.id,
                    ruleId = rule.id,
                    mergedRuleIds = mergedRuleIds,
                    overrideId = ruleOverride?.id,
                    courseName = course.name,
                    teacher = course.teacher,
                    colorArgb = course.colorArgb,
                    date = date,
                    teachingWeek = effectiveDate.teachingWeek,
                    weekday = date.dayOfWeek,
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    startTime = resolvedWindow.startTime,
                    endTime = resolvedWindow.endTime,
                    rawRoom = ruleOverride?.room ?: rule.rawRoom,
                    buildingId = ruleOverride?.buildingId ?: rule.buildingId,
                    isMakeupOrOverridden = ruleOverride != null || effectiveDate.status == TermDateStatus.MAKEUP_RESCHEDULED,
                    overrideReason = ruleOverride?.reason ?: effectiveDate.calendarNotice,
                    userModified = course.userModified || rule.userModified || ruleOverride != null,
                    hasResolvedTime = resolvedWindow.hasResolvedTime,
                    timeEvidence = resolvedWindow.evidence
                )
            }
        }

        val rulesById = rules.associateBy { it.id }
        val handledOverrideIds = rawOccurrences.mapNotNull { it.overrideId }.toSet()
        for (extraOverride in dayOverrides) {
            if (extraOverride.id in handledOverrideIds) continue
            val resolvedWindow = expandableOverrideTimeWindow(
                occurrenceOverride = extraOverride,
                termId = term.id,
                rulesById = rulesById,
                courseIds = courseMap.keys,
                periodMap = periodMap
            ) ?: continue
            val baseRule = sameTermBaseRule(extraOverride, term.id, rulesById)
            val targetCourseId = extraOverride.courseId ?: baseRule?.courseId ?: continue
            val course = courseMap[targetCourseId] ?: continue
            val startPeriod = extraOverride.startPeriod ?: baseRule?.startPeriod ?: 1
            val endPeriod = extraOverride.endPeriod ?: baseRule?.endPeriod ?: (startPeriod + 1)
            val mergedRuleIds = if (baseRule != null) {
                (listOf(baseRule.id) + absorbedCompanionsByPrimaryRuleId[baseRule.id].orEmpty()).distinct()
            } else {
                emptyList()
            }
            rawOccurrences += ClassOccurrence(
                occurrenceId = date.toString() + "_override_" + extraOverride.id,
                courseId = course.id,
                ruleId = baseRule?.id,
                mergedRuleIds = mergedRuleIds,
                overrideId = extraOverride.id,
                courseName = course.name,
                teacher = course.teacher,
                colorArgb = course.colorArgb,
                date = date,
                teachingWeek = effectiveDate.teachingWeek,
                weekday = date.dayOfWeek,
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                startTime = resolvedWindow.startTime,
                endTime = resolvedWindow.endTime,
                rawRoom = extraOverride.room ?: baseRule?.rawRoom ?: "待定教室",
                buildingId = extraOverride.buildingId ?: baseRule?.buildingId,
                isMakeupOrOverridden = true,
                overrideReason = extraOverride.reason ?: "临时调课/补课",
                userModified = true,
                hasResolvedTime = resolvedWindow.hasResolvedTime,
                timeEvidence = resolvedWindow.evidence
            )
        }

        return mergeAdjacentSameCourse(
            rawOccurrences.sortedWith(
                compareBy<ClassOccurrence> { it.startTime }
                    .thenBy { it.startPeriod }
                    .thenBy { it.courseName }
            )
        )
    }

    private fun resolveCanceledOccurrencesForDate(
        date: LocalDate,
        term: Term,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride>,
        occurrenceOverrides: List<OccurrenceOverride>
    ): List<ClassOccurrence> {
        val courseMap = courses.associateBy { it.id }
        val periodMap = periodMapForDate(date, term, periodTimes)
        val effectiveDate = resolveDate(date, term, calendarOverrides)
        if (effectiveDate.status == TermDateStatus.HOLIDAY_NO_CLASS || effectiveDate.teachingWeek == null) {
            return emptyList()
        }
        val activeWeek = effectiveDate.teachingWeek
        val targetWeekday = effectiveDate.weekdayUsed
        val allDayOverrides = occurrenceOverrides.filter { it.termId == term.id && it.date == date }
        val activeNonCanceledRuleIds = allDayOverrides
            .filter { !it.canceled && it.courseRuleId != null }
            .mapNotNull { it.courseRuleId }
            .toSet()
        val dayCanceledRuleOverrides = allDayOverrides.filter { occurrenceOverride ->
            if (!occurrenceOverride.canceled || occurrenceOverride.courseRuleId == null) return@filter false
            val mergedTarget = occurrenceOverride.reason
                ?.takeIf { it.startsWith("MERGED_INTO:") }
                ?.removePrefix("MERGED_INTO:")
            if (mergedTarget != null) {
                val primaryAlsoCanceled = allDayOverrides.any { it.canceled && it.courseRuleId == mergedTarget }
                return@filter primaryAlsoCanceled && mergedTarget !in activeNonCanceledRuleIds
            }
            true
        }.associateBy { it.courseRuleId!! }
        val standaloneCanceledOverrides = allDayOverrides.filter {
            it.canceled && it.courseRuleId == null && it.courseId != null
        }
        if (dayCanceledRuleOverrides.isEmpty() && standaloneCanceledOverrides.isEmpty()) return emptyList()

        val rawRuleCanceled = rules.filter { rule ->
            rule.termId == term.id &&
                rule.weekday == targetWeekday &&
                activeWeek in rule.weeks &&
                rule.id in dayCanceledRuleOverrides
        }.mapNotNull { rule ->
            val course = courseMap[rule.courseId] ?: return@mapNotNull null
            val occurrenceOverride = dayCanceledRuleOverrides[rule.id] ?: return@mapNotNull null
            val resolvedWindow = resolvePeriodWindow(
                startPeriod = rule.startPeriod,
                endPeriod = rule.endPeriod,
                customStart = rule.customStartTime,
                customEnd = rule.customEndTime,
                periodMap = periodMap
            )
            ClassOccurrence(
                occurrenceId = "canceled_" + date + "_" + rule.id,
                courseId = course.id,
                ruleId = rule.id,
                overrideId = occurrenceOverride.id,
                courseName = course.name,
                teacher = course.teacher,
                colorArgb = course.colorArgb,
                date = date,
                teachingWeek = activeWeek,
                weekday = date.dayOfWeek,
                startPeriod = rule.startPeriod,
                endPeriod = rule.endPeriod,
                startTime = resolvedWindow.startTime,
                endTime = resolvedWindow.endTime,
                rawRoom = rule.rawRoom,
                buildingId = rule.buildingId,
                isMakeupOrOverridden = true,
                overrideReason = occurrenceOverride.reason?.takeUnless { it.startsWith("MERGED_INTO:") } ?: "单次停课",
                userModified = true,
                hasResolvedTime = resolvedWindow.hasResolvedTime,
                timeEvidence = resolvedWindow.evidence
            )
        }.sortedBy { it.startPeriod }

        val mergedRuleCanceled = mutableListOf<ClassOccurrence>()
        for (item in rawRuleCanceled) {
            val last = mergedRuleCanceled.lastOrNull()
            if (last != null &&
                last.courseId == item.courseId &&
                last.rawRoom == item.rawRoom &&
                last.endPeriod + 1 == item.startPeriod
            ) {
                mergedRuleCanceled[mergedRuleCanceled.lastIndex] = last.copy(
                    endPeriod = item.endPeriod,
                    endTime = item.endTime,
                    mergedRuleIds = (last.mergedRuleIds + item.mergedRuleIds).distinct(),
                    hasResolvedTime = last.hasResolvedTime && item.hasResolvedTime,
                    timeEvidence = TimeEvidence.worstOf(last.timeEvidence, item.timeEvidence)
                )
            } else {
                mergedRuleCanceled += item
            }
        }

        val standaloneCanceled = standaloneCanceledOverrides.mapNotNull { occurrenceOverride ->
            val course = courseMap[occurrenceOverride.courseId] ?: return@mapNotNull null
            val startPeriod = occurrenceOverride.startPeriod ?: 1
            val endPeriod = occurrenceOverride.endPeriod ?: (startPeriod + 1)
            val resolvedWindow = resolvePeriodWindow(
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                customStart = occurrenceOverride.startAt,
                customEnd = occurrenceOverride.endAt,
                periodMap = periodMap
            )
            ClassOccurrence(
                occurrenceId = "canceled_" + date + "_ov_" + occurrenceOverride.id,
                courseId = course.id,
                ruleId = null,
                overrideId = occurrenceOverride.id,
                courseName = course.name,
                teacher = course.teacher,
                colorArgb = course.colorArgb,
                date = date,
                teachingWeek = activeWeek,
                weekday = date.dayOfWeek,
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                startTime = resolvedWindow.startTime,
                endTime = resolvedWindow.endTime,
                rawRoom = occurrenceOverride.room ?: "待定教室",
                buildingId = occurrenceOverride.buildingId,
                isMakeupOrOverridden = true,
                overrideReason = occurrenceOverride.reason ?: "单次停课",
                userModified = true,
                hasResolvedTime = resolvedWindow.hasResolvedTime,
                timeEvidence = resolvedWindow.evidence
            )
        }
        return (mergedRuleCanceled + standaloneCanceled).sortedBy { it.startPeriod }
    }

    data class ResolvedPeriodWindow(
        val startTime: LocalTime,
        val endTime: LocalTime,
        val evidence: TimeEvidence
    ) {
        val hasResolvedTime: Boolean get() = evidence.hasResolvedTime
    }

    /** 与既有课表共用的时间证据语义；UNRESOLVED 不伪造 08:00，INVALID 不冒充可信时间。 */
    fun resolvePeriodWindow(
        startPeriod: Int,
        endPeriod: Int,
        customStart: LocalTime?,
        customEnd: LocalTime?,
        periodMap: Map<Int, PeriodTime>
    ): ResolvedPeriodWindow {
        val span = (endPeriod - startPeriod + 1).coerceAtLeast(1)
        val startPt = periodMap[startPeriod]
        val endPt = periodMap[endPeriod]
        val resolvedStart = customStart ?: startPt?.startLocalTime
        val resolvedEnd = customEnd ?: endPt?.endLocalTime
        if (resolvedStart != null && resolvedEnd != null) {
            val evidence = when {
                !resolvedEnd.isAfter(resolvedStart) -> TimeEvidence.INVALID
                customStart != null && customEnd != null -> TimeEvidence.EXPLICIT_VALID
                else -> TimeEvidence.TIMETABLE_VALID
            }
            return ResolvedPeriodWindow(resolvedStart, resolvedEnd, evidence)
        }
        if (customStart != null) {
            val estimatedEnd = addMinutesClampedBeforeMidnight(customStart, 45L * span + 5L * (span - 1))
            return ResolvedPeriodWindow(customStart, estimatedEnd, TimeEvidence.ESTIMATED)
        }
        val precedingKnown = periodMap.entries.filter { it.key < startPeriod }.maxByOrNull { it.key }
        val anchorStart = when {
            startPt != null -> startPt.startLocalTime
            precedingKnown != null -> {
                val gapPeriods = (startPeriod - precedingKnown.key - 1).coerceAtLeast(0)
                addMinutesClampedBeforeMidnight(precedingKnown.value.endLocalTime, 10L + gapPeriods * 50L)
            }
            else -> LocalTime.of(22, 0)
        }
        val anchorEnd = resolvedEnd?.takeIf { it.isAfter(anchorStart) }
            ?: addMinutesClampedBeforeMidnight(anchorStart, 45L * span)
        return ResolvedPeriodWindow(anchorStart, anchorEnd, TimeEvidence.UNRESOLVED)
    }

    private fun addMinutesClampedBeforeMidnight(base: LocalTime, minutesToAdd: Long): LocalTime {
        val baseMins = base.hour * 60L + base.minute
        val targetMins = (baseMins + minutesToAdd).coerceIn(0L, 23L * 60L + 59L)
        return LocalTime.of((targetMins / 60L).toInt(), (targetMins % 60L).toInt())
    }

    private fun mergeAdjacentSameCourse(sorted: List<ClassOccurrence>): List<ClassOccurrence> {
        if (sorted.size <= 1) return sorted
        val merged = mutableListOf<ClassOccurrence>()
        var current = sorted.first()
        for (index in 1 until sorted.size) {
            val next = sorted[index]
            val canMerge = current.courseId == next.courseId &&
                current.rawRoom == next.rawRoom &&
                current.endPeriod + 1 == next.startPeriod &&
                !current.isMakeupOrOverridden &&
                !next.isMakeupOrOverridden
            current = if (canMerge) {
                current.copy(
                    endPeriod = next.endPeriod,
                    endTime = next.endTime,
                    mergedRuleIds = (current.mergedRuleIds + next.mergedRuleIds).distinct(),
                    hasResolvedTime = current.hasResolvedTime && next.hasResolvedTime,
                    timeEvidence = TimeEvidence.worstOf(current.timeEvidence, next.timeEvidence)
                )
            } else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }
}
