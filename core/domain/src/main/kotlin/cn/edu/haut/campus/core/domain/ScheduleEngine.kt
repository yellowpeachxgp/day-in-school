package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.BreakType
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.DayTimelineSnapshot
import cn.edu.haut.campus.core.model.InternalPeriodBreak
import cn.edu.haut.campus.core.model.NextClassDayHint
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ScheduleBreakInterval
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TermDateStatus
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

object ScheduleEngine {

    /**
     * 解析指定日期的实际课程列表（包含学期日历调休、单日停课/改教室/改时间、临时补课、连续同课合并与冲突分栏计算）。
     */
    fun resolveOccurrencesForDate(
        date: LocalDate,
        term: Term,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride> = emptyList(),
        occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
        buildings: List<Building> = emptyList()
    ): List<ClassOccurrence> {
        val courseMap = courses.associateBy { it.id }
        val periodMap = periodTimes
            .filter { it.timeTableId == term.timeTableId }
            .associateBy { it.periodNo }
        val buildingMap = buildings.associateBy { it.id }

        val effectiveDate = TeachingWeekCalculator.resolveDate(date, term, calendarOverrides)
        val dayOverrides = occurrenceOverrides.filter { it.termId == term.id && it.date == date }
        val overridesByRuleId = dayOverrides.filter { it.courseRuleId != null }.associateBy { it.courseRuleId!! }

        val rawOccurrences = mutableListOf<ClassOccurrence>()

        // 1. 常规排课规则匹配（若非放假停课）
        if (effectiveDate.status != TermDateStatus.HOLIDAY_NO_CLASS &&
            effectiveDate.teachingWeek != null
        ) {
            val activeWeek = effectiveDate.teachingWeek
            val targetWeekday = effectiveDate.weekdayUsed
            val matchingRules = rules.filter { rule ->
                rule.termId == term.id &&
                    rule.weekday == targetWeekday &&
                    activeWeek in rule.weeks
            }

            for (rule in matchingRules) {
                val course = courseMap[rule.courseId] ?: continue
                val ruleOverride = overridesByRuleId[rule.id]
                if (ruleOverride?.canceled == true) {
                    continue
                }

                val startPeriod = ruleOverride?.startPeriod ?: rule.startPeriod
                val endPeriod = ruleOverride?.endPeriod ?: rule.endPeriod
                val resolvedWindow = resolvePeriodWindow(
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    customStart = ruleOverride?.startAt ?: rule.customStartTime,
                    customEnd = ruleOverride?.endAt ?: rule.customEndTime,
                    periodMap = periodMap
                )

                val finalRoom = ruleOverride?.room ?: rule.rawRoom
                val finalBuildingId = ruleOverride?.buildingId ?: rule.buildingId
                val isOverridden = ruleOverride != null || effectiveDate.status == TermDateStatus.MAKEUP_RESCHEDULED
                val reason = ruleOverride?.reason ?: effectiveDate.calendarNotice

                rawOccurrences.add(
                    ClassOccurrence(
                        occurrenceId = "${date}_${rule.id}",
                        courseId = course.id,
                        ruleId = rule.id,
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
                        rawRoom = finalRoom,
                        buildingId = finalBuildingId,
                        buildingName = finalBuildingId?.let { buildingMap[it]?.name },
                        isMakeupOrOverridden = isOverridden,
                        overrideReason = reason,
                        userModified = course.userModified || rule.userModified || ruleOverride != null,
                        hasResolvedTime = resolvedWindow.hasResolvedTime
                    )
                )
            }
        }

        // 2. 单日临时补课或跨天调入课程（courseRuleId 不在今日常规触发集，或纯手工补课）
        val handledOverrideIds = rawOccurrences.mapNotNull { it.overrideId }.toSet()
        for (extraOverride in dayOverrides) {
            if (extraOverride.id in handledOverrideIds || extraOverride.canceled) continue
            val baseRule = extraOverride.courseRuleId?.let { ruleId -> rules.firstOrNull { it.id == ruleId } }
            val targetCourseId = extraOverride.courseId ?: baseRule?.courseId ?: continue
            val course = courseMap[targetCourseId] ?: continue

            val startPeriod = extraOverride.startPeriod ?: baseRule?.startPeriod ?: 1
            val endPeriod = extraOverride.endPeriod ?: baseRule?.endPeriod ?: (startPeriod + 1)
            val resolvedWindow = resolvePeriodWindow(
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                customStart = extraOverride.startAt ?: baseRule?.customStartTime,
                customEnd = extraOverride.endAt ?: baseRule?.customEndTime,
                periodMap = periodMap
            )
            val finalRoom = extraOverride.room ?: baseRule?.rawRoom ?: "待定教室"
            val finalBuildingId = extraOverride.buildingId ?: baseRule?.buildingId

            rawOccurrences.add(
                ClassOccurrence(
                    occurrenceId = "${date}_override_${extraOverride.id}",
                    courseId = course.id,
                    ruleId = baseRule?.id,
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
                    rawRoom = finalRoom,
                    buildingId = finalBuildingId,
                    buildingName = finalBuildingId?.let { buildingMap[it]?.name },
                    isMakeupOrOverridden = true,
                    overrideReason = extraOverride.reason ?: "临时调课/补课",
                    userModified = true,
                    hasResolvedTime = resolvedWindow.hasResolvedTime
                )
            )
        }

        // 3. 同一天同一门课同一教室相邻节次纵向合并，并自动识别连堂课内部的小课间/大课间
        val merged = mergeAdjacentSameCourse(
            rawOccurrences.sortedWith(
                compareBy<ClassOccurrence> { it.startTime }
                    .thenBy { it.startPeriod }
                    .thenBy { it.courseName }
            )
        ).map { occ ->
            occ.copy(
                internalBreaks = computeInternalBreaks(
                    startPeriod = occ.startPeriod,
                    endPeriod = occ.endPeriod,
                    periodMap = periodMap
                )
            )
        }

        // 4. 冲突检测与并列分栏标记
        return assignConflictColumns(merged)
    }

    /**
     * 解析指定日期被「单日停课」(`OccurrenceOverride(canceled = true)`) 屏蔽的课程列表，
     * 用于在今日页与课表页提供可视化恢复入口。
     */
    fun resolveCanceledOccurrencesForDate(
        date: LocalDate,
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride> = emptyList(),
        occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
        buildings: List<Building> = emptyList()
    ): List<ClassOccurrence> {
        val activeTerm = term ?: return emptyList()
        val courseMap = courses.associateBy { it.id }
        val periodMap = periodTimes
            .filter { it.timeTableId == activeTerm.timeTableId }
            .associateBy { it.periodNo }
        val buildingMap = buildings.associateBy { it.id }

        val effectiveDate = TeachingWeekCalculator.resolveDate(date, activeTerm, calendarOverrides)
        if (effectiveDate.status == TermDateStatus.HOLIDAY_NO_CLASS || effectiveDate.teachingWeek == null) {
            return emptyList()
        }
        val activeWeek = effectiveDate.teachingWeek
        val targetWeekday = effectiveDate.weekdayUsed
        val allDayOverrides = occurrenceOverrides.filter { it.termId == activeTerm.id && it.date == date }
        val activeNonCanceledRuleIds = allDayOverrides
            .filter { !it.canceled && it.courseRuleId != null }
            .mapNotNull { it.courseRuleId }
            .toSet()

        val dayCanceledRuleOverrides = allDayOverrides.filter { ov ->
            if (!ov.canceled || ov.courseRuleId == null) return@filter false
            // 忽略连堂课单次调课时为副规则自动生成的内部吸收标记（MERGED_INTO:<primaryRuleId>），除非主规则自身也已停课
            val mergedTarget = ov.reason?.removePrefix("MERGED_INTO:")?.takeIf { ov.reason?.startsWith("MERGED_INTO:") == true }
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

        val matchingRules = rules.filter { rule ->
            rule.termId == activeTerm.id &&
                rule.weekday == targetWeekday &&
                activeWeek in rule.weeks &&
                rule.id in dayCanceledRuleOverrides
        }
        val rawRuleCanceled = matchingRules.mapNotNull { rule ->
            val course = courseMap[rule.courseId] ?: return@mapNotNull null
            val ov = dayCanceledRuleOverrides[rule.id] ?: return@mapNotNull null
            val resolvedWindow = resolvePeriodWindow(
                startPeriod = rule.startPeriod,
                endPeriod = rule.endPeriod,
                customStart = rule.customStartTime,
                customEnd = rule.customEndTime,
                periodMap = periodMap
            )
            val cleanReason = ov.reason?.takeUnless { it.startsWith("MERGED_INTO:") } ?: "单次停课"
            ClassOccurrence(
                occurrenceId = "canceled_${date}_${rule.id}",
                courseId = course.id,
                ruleId = rule.id,
                overrideId = ov.id,
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
                buildingName = rule.buildingId?.let { buildingMap[it]?.name },
                isMakeupOrOverridden = true,
                overrideReason = cleanReason,
                userModified = true,
                hasResolvedTime = resolvedWindow.hasResolvedTime
            )
        }.sortedBy { it.startPeriod }

        // 将相邻同课程同教室的已停课规则合并为同一条可一键完整恢复的连堂记录（保留全部 mergedRuleIds）
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
                    hasResolvedTime = last.hasResolvedTime && item.hasResolvedTime
                )
            } else {
                mergedRuleCanceled.add(item)
            }
        }

        val standaloneCanceled = standaloneCanceledOverrides.mapNotNull { ov ->
            val course = courseMap[ov.courseId] ?: return@mapNotNull null
            val startPeriod = ov.startPeriod ?: 1
            val endPeriod = ov.endPeriod ?: (startPeriod + 1)
            val resolvedWindow = resolvePeriodWindow(
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                customStart = ov.startAt,
                customEnd = ov.endAt,
                periodMap = periodMap
            )
            ClassOccurrence(
                occurrenceId = "canceled_${date}_ov_${ov.id}",
                courseId = course.id,
                ruleId = null,
                overrideId = ov.id,
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
                rawRoom = ov.room ?: "待定教室",
                buildingId = ov.buildingId,
                buildingName = ov.buildingId?.let { buildingMap[it]?.name },
                isMakeupOrOverridden = true,
                overrideReason = ov.reason ?: "单次停课",
                userModified = true,
                hasResolvedTime = resolvedWindow.hasResolvedTime
            )
        }
        return (mergedRuleCanceled + standaloneCanceled).sortedBy { it.startPeriod }
    }

    private data class ResolvedPeriodWindow(
        val startTime: LocalTime,
        val endTime: LocalTime,
        val hasResolvedTime: Boolean
    )

    /**
     * 解析课程节次对应的起止时刻：
     * - 当显式提供 `customStart` 或 `periodMap` 同时包含起始/结束节次时，`hasResolvedTime = true`；
     * - 当作息表缺少该节次（例如旧版升级尚未补齐 11-12 节或自定义超出作息表）时，严禁回退伪造为晨间 `08:00`！
     *   此时标记 `hasResolvedTime = false`，并仅基于已知最晚节次向后生成不与早课重叠的顺序排序锚点，供列表排序使用。
     */
    private fun resolvePeriodWindow(
        startPeriod: Int,
        endPeriod: Int,
        customStart: LocalTime?,
        customEnd: LocalTime?,
        periodMap: Map<Int, PeriodTime>
    ): ResolvedPeriodWindow {
        val span = (endPeriod - startPeriod + 1).coerceAtLeast(1)
        if (customStart != null) {
            val resolvedEnd = customEnd
                ?: periodMap[endPeriod]?.endLocalTime
                ?: addMinutesClampedBeforeMidnight(customStart, 45L * span + 5L * (span - 1))
            return ResolvedPeriodWindow(customStart, resolvedEnd, hasResolvedTime = true)
        }
        val startPt = periodMap[startPeriod]
        val endPt = periodMap[endPeriod]
        if (startPt != null && endPt != null) {
            val endSafe = if (endPt.endLocalTime.isAfter(startPt.startLocalTime)) {
                endPt.endLocalTime
            } else {
                addMinutesClampedBeforeMidnight(startPt.startLocalTime, 45L * span)
            }
            return ResolvedPeriodWindow(startPt.startLocalTime, endSafe, hasResolvedTime = true)
        }

        // 缺少作息定义：绝不伪造晨间 08:00！按已知相邻节次计算单调排序锚点并标记 hasResolvedTime = false
        val precedingKnown = periodMap.entries
            .filter { it.key < startPeriod }
            .maxByOrNull { it.key }
        val anchorStart = when {
            startPt != null -> startPt.startLocalTime
            precedingKnown != null -> {
                val gapPeriods = (startPeriod - precedingKnown.key - 1).coerceAtLeast(0)
                addMinutesClampedBeforeMidnight(
                    precedingKnown.value.endLocalTime,
                    10L + gapPeriods * 50L
                )
            }
            else -> LocalTime.of(22, 0)
        }
        val anchorEnd = endPt?.endLocalTime?.takeIf { it.isAfter(anchorStart) }
            ?: addMinutesClampedBeforeMidnight(anchorStart, 45L * span)
        return ResolvedPeriodWindow(anchorStart, anchorEnd, hasResolvedTime = false)
    }

    private fun addMinutesClampedBeforeMidnight(base: LocalTime, minutesToAdd: Long): LocalTime {
        val baseMins = base.hour * 60L + base.minute
        val targetMins = (baseMins + minutesToAdd).coerceIn(0L, 23L * 60L + 59L)
        return LocalTime.of((targetMins / 60L).toInt(), (targetMins % 60L).toInt())
    }
    /**
     * 自动识别连堂课（如 1-2 节、3-4 节、1-4 节实验课）内部各小节之间的课间时间。
     * 例如 1-2 节（08:30–10:05）包含第 1 节结束（09:15）至第 2 节开始（09:20）的 5 分钟小课间。
     */
    fun computeInternalBreaks(
        startPeriod: Int,
        endPeriod: Int,
        periodMap: Map<Int, PeriodTime>
    ): List<InternalPeriodBreak> {
        if (endPeriod <= startPeriod) return emptyList()
        val breaks = mutableListOf<InternalPeriodBreak>()
        for (p in startPeriod until endPeriod) {
            val currentPeriod = periodMap[p] ?: continue
            val nextPeriod = periodMap[p + 1] ?: continue
            if (currentPeriod.endLocalTime.isBefore(nextPeriod.startLocalTime)) {
                val mins = Duration.between(currentPeriod.endLocalTime, nextPeriod.startLocalTime).toMinutes()
                if (mins > 0) {
                    breaks.add(
                        InternalPeriodBreak(
                            afterPeriod = p,
                            beforePeriod = p + 1,
                            startTime = currentPeriod.endLocalTime,
                            endTime = nextPeriod.startLocalTime,
                            durationMinutes = mins
                        )
                    )
                }
            }
        }
        return breaks
    }

    /**
     * 自动识别同一天相邻两门不同课程之间的跨课间歇：
     * - 课间小休息（<= 10 分钟）
     * - 跨大节大课间（11..45 分钟，如 2-3 节之间 10:05–10:25 的 20 分钟大课间）
     * - 午间休息（如 4 节 12:00 下课至 5 节 14:30 上课的 150 分钟）
     * - 傍晚/晚餐间歇（如 8 节 18:00 下课至 9 节 19:30 上课的 90 分钟）
     * - 跨大节空堂间歇（如 1-2 节下课至 7-8 节上课）
     * 同时自动检测两节课是否需要更换教室或跨教学楼转移。
     */
    fun computeBreakIntervals(
        occurrences: List<ClassOccurrence>,
        date: LocalDate,
        now: ZonedDateTime,
        timelineStart: LocalTime,
        pxPerMinute: Float
    ): List<ScheduleBreakInterval> {
        val resolvedOccurrences = occurrences.filter { it.hasResolvedTime }
        if (resolvedOccurrences.size <= 1) return emptyList()
        val sorted = resolvedOccurrences.sortedWith(
            compareBy<ClassOccurrence> { it.startTime }.thenBy { it.endTime }
        )
        val nowLocalDate = now.toLocalDate()
        val nowLocalTime = now.toLocalTime()
        val intervals = mutableListOf<ScheduleBreakInterval>()

        for (i in 0 until sorted.size - 1) {
            val prev = sorted[i]
            val next = sorted[i + 1]
            if (!prev.endTime.isBefore(next.startTime)) continue

            val durationMinutes = Duration.between(prev.endTime, next.startTime).toMinutes()
            if (durationMinutes <= 0) continue

            val breakType = when {
                prev.endPeriod <= 4 && next.startPeriod in 5..8 -> BreakType.NOON_BREAK
                prev.endPeriod <= 8 && next.startPeriod >= 9 -> BreakType.EVENING_BREAK
                durationMinutes <= 10L -> BreakType.SMALL_BREAK
                durationMinutes <= 45L -> BreakType.BIG_BREAK
                else -> BreakType.FREE_PERIOD_GAP
            }

            val formattedDuration = formatDurationChinese(durationMinutes)
            val label = when (breakType) {
                BreakType.SMALL_BREAK -> "课间小休 $formattedDuration"
                BreakType.BIG_BREAK -> "大课间 $formattedDuration"
                BreakType.NOON_BREAK -> "午间休息 $formattedDuration"
                BreakType.EVENING_BREAK -> "傍晚间歇 $formattedDuration"
                BreakType.FREE_PERIOD_GAP -> "空堂间歇 $formattedDuration"
            }

            val isActiveNow = date == nowLocalDate &&
                !nowLocalTime.isBefore(prev.endTime) &&
                nowLocalTime.isBefore(next.startTime)

            val minutesRemainingInBreak = if (isActiveNow) {
                Duration.between(nowLocalTime, next.startTime).toMinutes().coerceAtLeast(0)
            } else {
                null
            }

            intervals.add(
                ScheduleBreakInterval(
                    previousCourseName = prev.courseName,
                    nextCourseName = next.courseName,
                    afterPeriod = prev.endPeriod,
                    beforePeriod = next.startPeriod,
                    startTime = prev.endTime,
                    endTime = next.startTime,
                    durationMinutes = durationMinutes,
                    breakType = breakType,
                    label = label,
                    fromRoom = prev.rawRoom,
                    toRoom = next.rawRoom,
                    fromBuildingId = prev.buildingId,
                    toBuildingId = next.buildingId,
                    topPx = mapTimeToY(prev.endTime, timelineStart, pxPerMinute),
                    heightPx = durationMinutes * pxPerMinute,
                    isActiveNow = isActiveNow,
                    minutesRemainingInBreak = minutesRemainingInBreak
                )
            )
        }
        return intervals
    }

    private fun formatDurationChinese(minutes: Long): String {
        val hours = minutes / 60
        val remMinutes = minutes % 60
        return when {
            hours > 0 && remMinutes > 0 -> "${hours}小时${remMinutes}分钟"
            hours > 0 -> "${hours}小时"
            else -> "${minutes}分钟"
        }
    }

    /**
     * 生成指定整周（周一至周日）的全部实际排课。
     */
    fun resolveOccurrencesForTeachingWeek(
        teachingWeek: Int,
        term: Term,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride> = emptyList(),
        occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
        buildings: List<Building> = emptyList()
    ): Map<LocalDate, List<ClassOccurrence>> {
        val dates = TeachingWeekCalculator.datesOfTeachingWeek(term, teachingWeek)
        return dates.associateWith { date ->
            resolveOccurrencesForDate(
                date = date,
                term = term,
                courses = courses,
                rules = rules,
                periodTimes = periodTimes,
                calendarOverrides = calendarOverrides,
                occurrenceOverrides = occurrenceOverrides,
                buildings = buildings
            )
        }
    }

    /**
     * 构建日时间轴快照（真实时间比例映射：y = (t - timelineStart) * pxPerMinute）。
     */
    fun buildDayTimelineSnapshot(
        date: LocalDate,
        now: ZonedDateTime,
        term: Term?,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride> = emptyList(),
        occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
        buildings: List<Building> = emptyList(),
        pxPerMinute: Float = 2.0f,
        defaultStart: LocalTime = LocalTime.of(7, 45),
        defaultEnd: LocalTime = LocalTime.of(21, 45)
    ): DayTimelineSnapshot {
        if (term == null) {
            val totalMinutes = Duration.between(defaultStart, defaultEnd).toMinutes().coerceAtLeast(60)
            return DayTimelineSnapshot(
                date = date,
                now = now,
                term = null,
                teachingWeek = null,
                termStatus = TermDateStatus.BEFORE_TERM,
                calendarNotice = "尚未配置当前学期",
                timelineStart = defaultStart,
                timelineEnd = defaultEnd,
                pxPerMinute = pxPerMinute,
                totalHeightPx = totalMinutes * pxPerMinute,
                currentTimeLinePx = null,
                activeOrNextClass = null,
                items = emptyList(),
                nextClassDayHint = null
            )
        }

        val effectiveDate = TeachingWeekCalculator.resolveDate(date, term, calendarOverrides)
        val occurrences = resolveOccurrencesForDate(
            date = date,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            buildings = buildings
        )

        val resolvedForBounds = occurrences.filter { it.hasResolvedTime }
        val earliestClass = resolvedForBounds.minOfOrNull { it.startTime }
        val latestClass = resolvedForBounds.maxOfOrNull { it.endTime }

        val timelineStart = if (earliestClass != null && earliestClass.isBefore(defaultStart)) {
            earliestClass.minusMinutes(15)
        } else {
            defaultStart
        }
        val timelineEnd = if (latestClass != null && latestClass.isAfter(defaultEnd)) {
            latestClass.plusMinutes(15)
        } else {
            defaultEnd
        }

        val totalMinutes = Duration.between(timelineStart, timelineEnd).toMinutes().coerceAtLeast(60)
        val totalHeightPx = totalMinutes * pxPerMinute

        val nowLocalDate = now.toLocalDate()
        val nowLocalTime = now.toLocalTime()

        val currentTimeLinePx: Float? = if (date == nowLocalDate &&
            !nowLocalTime.isBefore(timelineStart) &&
            !nowLocalTime.isAfter(timelineEnd)
        ) {
            mapTimeToY(nowLocalTime, timelineStart, pxPerMinute)
        } else {
            null
        }

        val periodMap = periodTimes
            .filter { it.timeTableId == term.timeTableId }
            .associateBy { it.periodNo }

        val timelineItems = occurrences.map { occ ->
            val topPx = mapTimeToY(occ.startTime, timelineStart, pxPerMinute)
            val durationMinutes = Duration.between(occ.startTime, occ.endTime).toMinutes().coerceAtLeast(15)
            val heightPx = durationMinutes * pxPerMinute

            val status = if (!occ.hasResolvedTime) {
                if (date.isBefore(nowLocalDate)) ClassStatus.FINISHED else ClassStatus.UPCOMING
            } else {
                resolveClassStatus(date, occ.startTime, occ.endTime, nowLocalDate, nowLocalTime)
            }
            val minutesUntilStart = if (occ.hasResolvedTime && status == ClassStatus.UPCOMING && date == nowLocalDate) {
                Duration.between(nowLocalTime, occ.startTime).toMinutes().coerceAtLeast(0)
            } else null

            val minutesElapsed = if (occ.hasResolvedTime && status == ClassStatus.IN_PROGRESS && date == nowLocalDate) {
                Duration.between(occ.startTime, nowLocalTime).toMinutes().coerceAtLeast(0)
            } else null

            val minutesRemaining = if (occ.hasResolvedTime && status == ClassStatus.IN_PROGRESS && date == nowLocalDate) {
                Duration.between(nowLocalTime, occ.endTime).toMinutes().coerceAtLeast(0)
            } else null

            val activeInternalBreak = if (occ.hasResolvedTime && status == ClassStatus.IN_PROGRESS && date == nowLocalDate) {
                occ.internalBreaks.firstOrNull { brk ->
                    !nowLocalTime.isBefore(brk.startTime) && nowLocalTime.isBefore(brk.endTime)
                }
            } else null

            val currentSubPeriodNo = if (occ.hasResolvedTime && status == ClassStatus.IN_PROGRESS && date == nowLocalDate && activeInternalBreak == null) {
                (occ.startPeriod..occ.endPeriod).firstOrNull { p ->
                    val pt = periodMap[p] ?: return@firstOrNull false
                    !nowLocalTime.isBefore(pt.startLocalTime) && !nowLocalTime.isAfter(pt.endLocalTime)
                }
            } else null

            val statusText = if (!occ.hasResolvedTime) {
                "节次时间待设置 (${occ.periodRangeText})"
            } else when (status) {
                ClassStatus.UPCOMING -> {
                    if (minutesUntilStart != null) {
                        if (minutesUntilStart >= 60) {
                            val h = minutesUntilStart / 60
                            val m = minutesUntilStart % 60
                            if (m == 0L) "距开始 ${h} 小时" else "距开始 ${h}小时${m}分"
                        } else if (minutesUntilStart == 0L) {
                            "即将开始"
                        } else {
                            "距开始 $minutesUntilStart 分钟"
                        }
                    } else {
                        "未开始"
                    }
                }
                ClassStatus.IN_PROGRESS -> {
                    if (activeInternalBreak != null) {
                        val breakLeft = Duration.between(nowLocalTime, activeInternalBreak.endTime).toMinutes().coerceAtLeast(1)
                        "课内${activeInternalBreak.label}休息中 (${activeInternalBreak.startTime}–${activeInternalBreak.endTime} · 剩 ${breakLeft}分) · 即将第${activeInternalBreak.beforePeriod}节"
                    } else if (currentSubPeriodNo != null && occ.startPeriod != occ.endPeriod) {
                        "第${currentSubPeriodNo}节上课中 · 已上 ${minutesElapsed ?: 0} 分钟 / 剩 ${minutesRemaining ?: 0} 分钟"
                    } else {
                        "正在上课 · 已上 ${minutesElapsed ?: 0} 分钟 / 剩 ${minutesRemaining ?: 0} 分钟"
                    }
                }
                ClassStatus.FINISHED -> "已结束"
            }

            TimelineOccurrenceState(
                occurrence = occ,
                status = status,
                topPx = topPx,
                heightPx = heightPx,
                minutesUntilStart = minutesUntilStart,
                minutesElapsed = minutesElapsed,
                minutesRemaining = minutesRemaining,
                activeInternalBreak = activeInternalBreak,
                currentSubPeriodNo = currentSubPeriodNo,
                statusText = statusText
            )
        }

        val breakIntervals = computeBreakIntervals(
            occurrences = occurrences,
            date = date,
            now = now,
            timelineStart = timelineStart,
            pxPerMinute = pxPerMinute
        )
        val activeBreakInterval = breakIntervals.firstOrNull { it.isActiveNow }

        val activeOrNextClass = timelineItems.firstOrNull { it.occurrence.hasResolvedTime && it.status == ClassStatus.IN_PROGRESS }
            ?: timelineItems.firstOrNull { it.occurrence.hasResolvedTime && it.status == ClassStatus.UPCOMING }
            ?: timelineItems.firstOrNull { it.status == ClassStatus.UPCOMING }

        val nextClassDayHint = if (activeOrNextClass == null) {
            findNextClassDayHint(
                fromDate = date,
                term = term,
                courses = courses,
                rules = rules,
                periodTimes = periodTimes,
                calendarOverrides = calendarOverrides,
                occurrenceOverrides = occurrenceOverrides,
                buildings = buildings
            )
        } else {
            null
        }

        return DayTimelineSnapshot(
            date = date,
            now = now,
            term = term,
            teachingWeek = effectiveDate.teachingWeek,
            termStatus = effectiveDate.status,
            calendarNotice = effectiveDate.calendarNotice,
            timelineStart = timelineStart,
            timelineEnd = timelineEnd,
            pxPerMinute = pxPerMinute,
            totalHeightPx = totalHeightPx,
            currentTimeLinePx = currentTimeLinePx,
            activeOrNextClass = activeOrNextClass,
            items = timelineItems,
            breakIntervals = breakIntervals,
            activeBreakInterval = activeBreakInterval,
            nextClassDayHint = nextClassDayHint
        )
    }

    /**
     * 真实时刻映射到纵轴坐标：y = (t - timelineStart) * pxPerMinute
     */
    fun mapTimeToY(time: LocalTime, timelineStart: LocalTime, pxPerMinute: Float): Float {
        val minutesFromStart = Duration.between(timelineStart, time).toMinutes()
        return minutesFromStart * pxPerMinute
    }

    fun resolveClassStatus(
        classDate: LocalDate,
        startTime: LocalTime,
        endTime: LocalTime,
        nowDate: LocalDate,
        nowTime: LocalTime
    ): ClassStatus {
        return when {
            classDate.isBefore(nowDate) -> ClassStatus.FINISHED
            classDate.isAfter(nowDate) -> ClassStatus.UPCOMING
            nowTime.isBefore(startTime) -> ClassStatus.UPCOMING
            !nowTime.isAfter(endTime) -> ClassStatus.IN_PROGRESS
            else -> ClassStatus.FINISHED
        }
    }

    private fun findNextClassDayHint(
        fromDate: LocalDate,
        term: Term,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride>,
        occurrenceOverrides: List<OccurrenceOverride>,
        buildings: List<Building>
    ): NextClassDayHint? {
        for (offset in 1L..21L) {
            val candidateDate = fromDate.plusDays(offset)
            val occurrences = resolveOccurrencesForDate(
                date = candidateDate,
                term = term,
                courses = courses,
                rules = rules,
                periodTimes = periodTimes,
                calendarOverrides = calendarOverrides,
                occurrenceOverrides = occurrenceOverrides,
                buildings = buildings
            )
            if (occurrences.isNotEmpty()) {
                val first = occurrences.firstOrNull { it.hasResolvedTime } ?: occurrences.first()
                return NextClassDayHint(
                    nextDate = candidateDate,
                    daysUntil = offset,
                    firstCourseName = first.courseName,
                    firstCourseStartTime = first.startTime,
                    firstCourseRoom = first.rawRoom,
                    hasResolvedStartTime = first.hasResolvedTime,
                    firstCoursePeriodRangeText = first.periodRangeText
                )
            }
        }
        return null
    }

    private fun mergeAdjacentSameCourse(sorted: List<ClassOccurrence>): List<ClassOccurrence> {
        if (sorted.size <= 1) return sorted
        val merged = mutableListOf<ClassOccurrence>()
        var current = sorted.first()

        for (i in 1 until sorted.size) {
            val next = sorted[i]
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
                    hasResolvedTime = current.hasResolvedTime && next.hasResolvedTime
                )
            } else {
                merged.add(current)
                next
            }
        }
        merged.add(current)
        return merged
    }

    private fun assignConflictColumns(occurrences: List<ClassOccurrence>): List<ClassOccurrence> {
        if (occurrences.size <= 1) return occurrences
        val result = occurrences.toMutableList()
        var clusterStart = 0

        while (clusterStart < result.size) {
            var clusterEnd = clusterStart
            var maxEndTime = result[clusterStart].endTime

            while (clusterEnd + 1 < result.size &&
                result[clusterEnd + 1].startTime.isBefore(maxEndTime)
            ) {
                clusterEnd++
                if (result[clusterEnd].endTime.isAfter(maxEndTime)) {
                    maxEndTime = result[clusterEnd].endTime
                }
            }

            val clusterSize = clusterEnd - clusterStart + 1
            if (clusterSize > 1) {
                for (idx in clusterStart..clusterEnd) {
                    result[idx] = result[idx].copy(
                        conflictColumnIndex = idx - clusterStart,
                        conflictColumnCount = clusterSize
                    )
                }
            }
            clusterStart = clusterEnd + 1
        }

        return result
    }
}
