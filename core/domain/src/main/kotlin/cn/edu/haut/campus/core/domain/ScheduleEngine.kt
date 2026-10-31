package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.DayTimelineSnapshot
import cn.edu.haut.campus.core.model.NextClassDayHint
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
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
                val startTime = ruleOverride?.startAt
                    ?: rule.customStartTime
                    ?: periodMap[startPeriod]?.startLocalTime
                    ?: LocalTime.of(8, 0)
                val endTime = ruleOverride?.endAt
                    ?: rule.customEndTime
                    ?: periodMap[endPeriod]?.endLocalTime
                    ?: startTime.plusMinutes(95)

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
                        startTime = startTime,
                        endTime = endTime,
                        rawRoom = finalRoom,
                        buildingId = finalBuildingId,
                        buildingName = finalBuildingId?.let { buildingMap[it]?.name },
                        isMakeupOrOverridden = isOverridden,
                        overrideReason = reason,
                        userModified = course.userModified || rule.userModified || ruleOverride != null
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
            val startTime = extraOverride.startAt
                ?: baseRule?.customStartTime
                ?: periodMap[startPeriod]?.startLocalTime
                ?: LocalTime.of(8, 0)
            val endTime = extraOverride.endAt
                ?: baseRule?.customEndTime
                ?: periodMap[endPeriod]?.endLocalTime
                ?: startTime.plusMinutes(95)
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
                    startTime = startTime,
                    endTime = endTime,
                    rawRoom = finalRoom,
                    buildingId = finalBuildingId,
                    buildingName = finalBuildingId?.let { buildingMap[it]?.name },
                    isMakeupOrOverridden = true,
                    overrideReason = extraOverride.reason ?: "临时调课/补课",
                    userModified = true
                )
            )
        }

        // 3. 同一天同一门课同一教室相邻节次纵向合并
        val merged = mergeAdjacentSameCourse(
            rawOccurrences.sortedWith(
                compareBy<ClassOccurrence> { it.startTime }
                    .thenBy { it.startPeriod }
                    .thenBy { it.courseName }
            )
        )

        // 4. 冲突检测与并列分栏标记
        return assignConflictColumns(merged)
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

        val earliestClass = occurrences.minOfOrNull { it.startTime }
        val latestClass = occurrences.maxOfOrNull { it.endTime }

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

        val timelineItems = occurrences.map { occ ->
            val topPx = mapTimeToY(occ.startTime, timelineStart, pxPerMinute)
            val durationMinutes = Duration.between(occ.startTime, occ.endTime).toMinutes().coerceAtLeast(15)
            val heightPx = durationMinutes * pxPerMinute

            val status = resolveClassStatus(date, occ.startTime, occ.endTime, nowLocalDate, nowLocalTime)
            val minutesUntilStart = if (status == ClassStatus.UPCOMING && date == nowLocalDate) {
                Duration.between(nowLocalTime, occ.startTime).toMinutes().coerceAtLeast(0)
            } else null

            val minutesElapsed = if (status == ClassStatus.IN_PROGRESS && date == nowLocalDate) {
                Duration.between(occ.startTime, nowLocalTime).toMinutes().coerceAtLeast(0)
            } else null

            val minutesRemaining = if (status == ClassStatus.IN_PROGRESS && date == nowLocalDate) {
                Duration.between(nowLocalTime, occ.endTime).toMinutes().coerceAtLeast(0)
            } else null

            val statusText = when (status) {
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
                    "正在上课 · 已上 ${minutesElapsed ?: 0} 分钟 / 剩 ${minutesRemaining ?: 0} 分钟"
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
                statusText = statusText
            )
        }

        val activeOrNextClass = timelineItems.firstOrNull { it.status == ClassStatus.IN_PROGRESS }
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
                val first = occurrences.first()
                return NextClassDayHint(
                    nextDate = candidateDate,
                    daysUntil = offset,
                    firstCourseName = first.courseName,
                    firstCourseStartTime = first.startTime,
                    firstCourseRoom = first.rawRoom
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
                    endTime = next.endTime
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
