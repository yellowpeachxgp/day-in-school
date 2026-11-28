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
import cn.edu.haut.campus.core.model.TimeEvidence
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import cn.edu.haut.campus.core.timeline.ScheduleOccurrenceExpander
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

object ScheduleEngine {

    /**
     * 按真实日期选择生效节次表：默认参考作息（schemeId=null）始终可用；
     * 若当天落在某个同学期用户作息方案的闭区间内，则整套切换到该方案，
     * 不与默认表混排（这样删除节次在方案内也有明确语义）。重叠方案属非法输入；
     * 对历史/手工损坏数据按生效开始日期、schemeId 确定性选择，绝不随机。
     */
    fun periodMapForDate(
        date: LocalDate,
        term: Term,
        periodTimes: List<PeriodTime>
    ): Map<Int, PeriodTime> = ScheduleOccurrenceExpander.periodMapForDate(date, term, periodTimes)

    /**
     * 无单一日期上下文（如「本学期课表依据完整性」）使用的合并视图：
     * 默认表与全部方案行按节次合并，方案行优先；仅用于存在性/完整性判断，绝不用于某日展开。
     */
    fun periodMapAcrossSchemes(
        term: Term,
        periodTimes: List<PeriodTime>
    ): Map<Int, PeriodTime> {
        val rowsForTable = periodTimes.filter { it.timeTableId == term.timeTableId }
        val merged = linkedMapOf<Int, PeriodTime>()
        rowsForTable.filter { it.schemeId == null }.sortedBy { it.periodNo }.forEach { merged[it.periodNo] = it }
        rowsForTable.filter { it.schemeId != null && it.termId == term.id }.sortedBy { it.periodNo }.forEach { merged[it.periodNo] = it }
        return merged
    }

    /**
     * 排课输入「可展开」统一校验（课表展开器与「课表依据」判定共用同一函数，绝不两套口径）：
     * 规则须属于该学期、有课周次与学期有效周范围(1..[termWeekCount])相交、节次区间有效且
     * 课程可解析——跨学期残留、零周次空壳、周次全部越出学期范围、倒序节次或缺课孤儿规则
     * 均不可展开，不得充当任何"课表依据"。
     * [termWeekCount] 默认不限周范围（仅要求周次非空）；展开器与依据判定均传学期 weekCount。
     */
    fun isExpandableScheduleRule(
        rule: CourseScheduleRule,
        termId: String,
        courseIds: Set<String>,
        termWeekCount: Int = Int.MAX_VALUE
    ): Boolean = ScheduleOccurrenceExpander.isExpandableScheduleRule(rule, termId, courseIds, termWeekCount)

    /**
     * 覆盖行关联规则的本学期解析：courseRuleId 必须指向**同一学期**的现存规则——
     * 跨学期关联无效，一律按无关联处理（绝不允许借用别学期规则的课程/节次/时间字段）。
     * 课程解析与时段回退共享此入口，与「课表依据」判定同一口径。
     */
    fun sameTermBaseRule(
        override: OccurrenceOverride,
        termId: String,
        rulesById: Map<String, CourseScheduleRule>
    ): CourseScheduleRule? = ScheduleOccurrenceExpander.sameTermBaseRule(override, termId, rulesById)

    /**
     * 单日覆盖「可展开」统一校验：仅非停课覆盖（补课/改时间/改教室）才可能展开为课程事件，
     * 且必须可解析出真实课程（自带 courseId，或经 courseRuleId 关联到**本学期**现存规则
     * 再解析课程；跨学期关联无效）。停课覆盖只会屏蔽既有事件、自身绝不产生事件；
     * 指向已删除规则的孤儿停课行不构成任何依据。
     */
    fun isExpandableOccurrenceOverride(
        override: OccurrenceOverride,
        termId: String,
        rulesById: Map<String, CourseScheduleRule>,
        courseIds: Set<String>
    ): Boolean = ScheduleOccurrenceExpander.isExpandableOccurrenceOverride(override, termId, rulesById, courseIds)

    /**
     * 单日覆盖的「可展开时间窗」共享校验：与展开器完全同一取值顺序与同一解析路径——
     * 不可展开返回 null；返回窗口的 [TimeEvidence] 独立证据等级区分
     * 可信（EXPLICIT_VALID/TIMETABLE_VALID）、推算锚点（ESTIMATED）、
     * 无法解析（UNRESOLVED）与非法时间（INVALID）——展开器据此标注
     * "节次时间待设置"/"时间待确认"展示，「课表依据」判定则只采信
     * [TimeEvidence.isBasisTrustworthy] 的窗口作为"个人无冲突"统计依据。
     */
    fun expandableOverrideTimeWindow(
        override: OccurrenceOverride,
        termId: String,
        rulesById: Map<String, CourseScheduleRule>,
        courseIds: Set<String>,
        periodMap: Map<Int, PeriodTime>
    ): ResolvedPeriodWindow? = ScheduleOccurrenceExpander.expandableOverrideTimeWindow(
        occurrenceOverride = override,
        termId = termId,
        rulesById = rulesById,
        courseIds = courseIds,
        periodMap = periodMap
    )?.toDomainWindow()

    /**
     * 排课规则自身时间输入的可解析窗口（与展开器同一解析路径，绝不另起口径）：
     * 自带合法 customStart/customEnd（EXPLICIT_VALID）或经学期作息表/混合解析出
     * 两端明确合法时间（TIMETABLE_VALID）的窗口才构成「课表依据」的可信时间——
     * 推算补全（ESTIMATED）、无法解析（UNRESOLVED）与非法反向时间（INVALID）
     * 一律不采信；缺作息表但自带完整合法自定义时间的课程同样计为有效依据。
     */
    fun ruleSchedulePeriodWindow(
        rule: CourseScheduleRule,
        periodMap: Map<Int, PeriodTime>
    ): ResolvedPeriodWindow = ScheduleOccurrenceExpander.resolvePeriodWindow(
        startPeriod = rule.startPeriod,
        endPeriod = rule.endPeriod,
        customStart = rule.customStartTime,
        customEnd = rule.customEndTime,
        periodMap = periodMap
    ).toDomainWindow()

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
        // W1-05 收敛：课表展开（教学周对齐、调休、单日覆盖、连堂合并）的唯一实现
        // 是 core:timeline 的 ScheduleOccurrenceExpander；engine 不再保留第二套语义，
        // 此处只补楼宇名、连堂课内部课间与冲突分栏等展示后处理。
        val expansion = ScheduleOccurrenceExpander.expandDate(
            date = date,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides
        )
        val buildingMap = buildings.associateBy { it.id }
        val periodMap = periodMapForDate(date, term, periodTimes)
        val merged = expansion.active.map { occ ->
            occ.copy(
                buildingName = occ.buildingId?.let { buildingMap[it]?.name },
                internalBreaks = computeInternalBreaks(
                    startPeriod = occ.startPeriod,
                    endPeriod = occ.endPeriod,
                    periodMap = periodMap
                )
            )
        }
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
        // W1-05 收敛：停课投影同样由 ScheduleOccurrenceExpander 统一展开，此处只补楼宇名。
        val expansion = ScheduleOccurrenceExpander.expandDate(
            date = date,
            term = activeTerm,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides
        )
        val buildingMap = buildings.associateBy { it.id }
        return expansion.cancelled.map { occ ->
            occ.copy(buildingName = occ.buildingId?.let { buildingMap[it]?.name })
        }
    }

    /**
     * 节次时间窗解析结果（展开器与「课表依据」判定共用同一返回类型）：
     * [evidence] 为独立时间证据等级（[TimeEvidence]）——[hasResolvedTime] 布尔
     * 仅是 [evidence] 的展示/提醒兼容投影；「个人无冲突」统计等依据判定
     * 必须使用 [TimeEvidence.isBasisTrustworthy] 严格口径。
     * [TimeEvidence.UNRESOLVED]/[TimeEvidence.INVALID] 时起止时刻仅是
     * 按已知节次推导的单调排序锚点或如实保留的非法输入，绝非可信时刻。
     */
    data class ResolvedPeriodWindow(
        val startTime: LocalTime,
        val endTime: LocalTime,
        val evidence: TimeEvidence
    ) {
        /** 展示/提醒兼容布尔：等价于 [TimeEvidence.hasResolvedTime]。 */
        val hasResolvedTime: Boolean get() = evidence.hasResolvedTime
    }

    /** Timeline 展开器时间窗到 engine 公开类型的映射；字段语义完全一致，仅类型归属不同。 */
    private fun ScheduleOccurrenceExpander.ResolvedPeriodWindow.toDomainWindow(): ResolvedPeriodWindow =
        ResolvedPeriodWindow(startTime = startTime, endTime = endTime, evidence = evidence)

    /**
     * 解析课程节次对应的起止时刻并给出独立「时间证据等级」（[TimeEvidence]）：
     * - 起止均显式提供且 end>start → [TimeEvidence.EXPLICIT_VALID]（缺作息表但自带
     *   完整合法 customStart/customEnd 同样成立，绝不回退"必须有 PeriodTime"旧误判）；
     * - 作息表解析或显式端×作息端混合且两端明确、顺序合法 → [TimeEvidence.TIMETABLE_VALID]；
     * - 仅自带 customStart、终点按节次跨度推算补全 → [TimeEvidence.ESTIMATED]
     *   （保留为排序锚点供展示/提醒兼容，但绝不足以充当时间依据）；
     * - 两端均明确但 end<=start（反向/零时长非法时间）→ [TimeEvidence.INVALID]，
     *   如实保留非法起止，事件不删除、展示"时间待确认"；
     * - 起止均无法解析（作息表缺节次且未自带起点）→ [TimeEvidence.UNRESOLVED]，
     *   严禁回退伪造为晨间 `08:00`！仅按已知相邻节次向后生成单调排序锚点。
     */

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
        defaultEnd: LocalTime = LocalTime.of(21, 45),
        occurrenceResolver: ((LocalDate) -> List<ClassOccurrence>)? = null
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
        // W1-05：消费端注入 Timeline 投影展开器时一律走注入路径（含下一课前瞻），
        // 缺省回退 engine 委托同一展开内核的兼容路径，两种路径语义同一。
        val resolveOccurrences = occurrenceResolver ?: { candidate: LocalDate ->
            resolveOccurrencesForDate(
                date = candidate,
                term = term,
                courses = courses,
                rules = rules,
                periodTimes = periodTimes,
                calendarOverrides = calendarOverrides,
                occurrenceOverrides = occurrenceOverrides,
                buildings = buildings
            )
        }
        val occurrences = resolveOccurrences(date)

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

        val periodMap = periodMapForDate(date, term, periodTimes)

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
                // 非法/反向时间的事件保留在课表上提示"时间待确认"，绝不删除用户课程
                if (occ.timeEvidence == TimeEvidence.INVALID) {
                    "时间待确认 (${occ.periodRangeText})"
                } else {
                    "节次时间待设置 (${occ.periodRangeText})"
                }
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
                resolveOccurrences = resolveOccurrences
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
        resolveOccurrences: (LocalDate) -> List<ClassOccurrence>
    ): NextClassDayHint? {
        for (offset in 1L..21L) {
            val candidateDate = fromDate.plusDays(offset)
            val occurrences = resolveOccurrences(candidateDate)
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


    internal fun assignConflictColumns(occurrences: List<ClassOccurrence>): List<ClassOccurrence> {
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
