package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.timeline.CourseOccurrenceTimelineMetadata
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineSnapshot
import cn.edu.haut.campus.core.timeline.CourseTimelineProjection
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineKind
import java.time.LocalDate

/**
 * 课表消费端读取 Timeline 的适配层（W1-05）。
 *
 * 切片一先将 Today 的课程读取改为经本适配器
 * 「输入快照 → Timeline 事件投影 → 元数据还原 → 展示后处理」读取课程时间流；
 * Timetable 与四款小组件属后续切片。展开语义的唯一实现因此只有
 * core:timeline 的 ScheduleOccurrenceExpander 一套。
 *
 * 信封说明：投影发生在单次进程内的闭环读取中，输入数据本身已属于当前账号；
 * 事件 scope/provenance 只是投影管道要求的信封字段，绝不进入还原后的领域模型，
 * 因此使用适配器内部常量信封，不伪造任何跨账号语义。
 *
 * 时间语义纪律：UNCERTAIN/INVALID 证据的事件没有 end，还原后 hasResolvedTime=false，
 * 只保留单调排序锚点并照常展示「时间待确认/作息待设」——绝不允许冒充精确时间。
 */
object TimelineScheduleAdapter {

    private val PROJECTION_SCOPE = SchoolAccountScope(
        schoolId = SchoolId("local-projection"),
        accountId = AccountId("local-projection"),
        profileId = ProfileId("local-projection")
    )

    private fun projectionProvenance(): DataProvenance = DataProvenance(
        providerId = "local.room",
        schoolId = PROJECTION_SCOPE.schoolId,
        accountId = PROJECTION_SCOPE.accountId,
        sourceSystem = "account-database"
    )

    private fun dayWindow(date: LocalDate): TimeWindow = TimeWindow(
        start = date.atStartOfDay(HAUT_ZONE_ID).toInstant(),
        end = date.plusDays(1).atStartOfDay(HAUT_ZONE_ID).toInstant()
    )

    private fun projectDayEvents(
        date: LocalDate,
        term: Term,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride>,
        occurrenceOverrides: List<OccurrenceOverride>
    ): List<TimelineEvent> {
        val snapshot = CourseScheduleTimelineSnapshot(
            zone = HAUT_ZONE_ID,
            provenance = projectionProvenance(),
            revision = 1L,
            terms = listOf(term),
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides
        )
        return CourseTimelineProjection.projectAll(
            snapshot = snapshot,
            scope = PROJECTION_SCOPE,
            window = dayWindow(date)
        )
    }

    /** 与既有课表相同的后处理次序：还原排序 → 楼宇名 → 连堂内部课间 → 冲突分栏。 */
    private fun List<ClassOccurrence>.presentationPostProcessed(
        date: LocalDate,
        term: Term,
        periodTimes: List<PeriodTime>,
        buildings: List<Building>
    ): List<ClassOccurrence> {
        val buildingMap = buildings.associateBy { it.id }
        val periodMap = ScheduleEngine.periodMapForDate(date, term, periodTimes)
        val sorted = sortedWith(
            compareBy<ClassOccurrence> { it.startTime }
                .thenBy { it.startPeriod }
                .thenBy { it.courseName }
        )
        val merged = sorted.map { occ ->
            occ.copy(
                buildingName = occ.buildingId?.let { buildingMap[it]?.name },
                internalBreaks = ScheduleEngine.computeInternalBreaks(
                    startPeriod = occ.startPeriod,
                    endPeriod = occ.endPeriod,
                    periodMap = periodMap
                )
            )
        }
        return ScheduleEngine.assignConflictColumns(merged)
    }

    /**
     * 读取指定日期的生效课程事件（Timeline 投影还原），返回与既有课表完全同形的
     * [ClassOccurrence] 列表：含楼宇名、连堂内部课间与冲突分栏。
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
        val events = projectDayEvents(
            date = date,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides
        )
        return resolveOccurrencesForDateFromEvents(
            date = date,
            events = events,
            term = term,
            periodTimes = periodTimes,
            courses = courses,
            buildings = buildings
        )
    }

    /** 读取指定教学周（周一至周日）每天的生效课程事件，语义同 [resolveOccurrencesForDate]。 */
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

    /** 读取指定日期被单日停课屏蔽的课程事件（含楼宇名），用于可视化恢复入口。 */
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
        val events = projectDayEvents(
            date = date,
            term = activeTerm,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides
        )
        return resolveCanceledOccurrencesForDateFromEvents(
            date = date,
            events = events,
            courses = courses,
            buildings = buildings
        )
    }

    private fun TimelineEvent.isCourseOccurrenceProjection(): Boolean =
        kind == TimelineKind.COURSE || kind == TimelineKind.PRACTICE

    /**
     * 从已投影的 reactive Timeline 事件还原指定日期生效课程；只接受 COURSE/PRACTICE，
     * 其他 kind（EXAM/PERSONAL 等）绝不进入课程后处理管道。
     */
    fun resolveOccurrencesForDateFromEvents(
        date: LocalDate,
        events: List<TimelineEvent>,
        term: Term,
        periodTimes: List<PeriodTime>,
        courses: List<Course> = emptyList(),
        buildings: List<Building> = emptyList()
    ): List<ClassOccurrence> {
        val courseNamesById = courses.associate { it.id to it.name }
        val active = events
            .filter { it.isCourseOccurrenceProjection() && it.status != EventStatus.CANCELLED_TODAY }
            .map { CourseOccurrenceTimelineMetadata.decode(it, courseNamesById) }
            .filter { it.date == date }
        return active.presentationPostProcessed(date, term, periodTimes, buildings)
    }

    /** 从已投影的 reactive Timeline 事件还原指定日期停课课程，用于恢复入口。 */
    fun resolveCanceledOccurrencesForDateFromEvents(
        date: LocalDate,
        events: List<TimelineEvent>,
        courses: List<Course> = emptyList(),
        buildings: List<Building> = emptyList()
    ): List<ClassOccurrence> {
        val buildingMap = buildings.associateBy { it.id }
        val courseNamesById = courses.associate { it.id to it.name }
        return events
            .filter { it.isCourseOccurrenceProjection() && it.status == EventStatus.CANCELLED_TODAY }
            .map { CourseOccurrenceTimelineMetadata.decode(it, courseNamesById) }
            .filter { it.date == date }
            .map { occ -> occ.copy(buildingName = occ.buildingId?.let { buildingMap[it]?.name }) }
            .sortedBy { it.startPeriod }
    }

    /** 供 Today 使用 reactive Timeline 事件注入日快照与下一课前瞻，不在组合内重新展开规则。 */
    fun occurrenceResolverFromEvents(
        events: List<TimelineEvent>,
        term: Term,
        periodTimes: List<PeriodTime>,
        courses: List<Course> = emptyList(),
        buildings: List<Building> = emptyList()
    ): (LocalDate) -> List<ClassOccurrence> = { date ->
        resolveOccurrencesForDateFromEvents(
            date = date,
            events = events,
            term = term,
            periodTimes = periodTimes,
            courses = courses,
            buildings = buildings
        )
    }

    /**
     * 供 [ScheduleEngine.buildDayTimelineSnapshot] 注入的 Timeline 展开器：
     * 日快照主日期与「下一课提示」前瞻扫描统一经由 Timeline 投影读取，不再触碰 engine 展开。
     */
    fun occurrenceResolver(
        term: Term,
        courses: List<Course>,
        rules: List<CourseScheduleRule>,
        periodTimes: List<PeriodTime>,
        calendarOverrides: List<CalendarOverride> = emptyList(),
        occurrenceOverrides: List<OccurrenceOverride> = emptyList(),
        buildings: List<Building> = emptyList()
    ): (LocalDate) -> List<ClassOccurrence> = { date ->
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
