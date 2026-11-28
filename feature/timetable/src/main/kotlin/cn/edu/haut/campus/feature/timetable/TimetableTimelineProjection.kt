package cn.edu.haut.campus.feature.timetable

import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.currentAccountScope
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineInputProvider
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineSnapshot
import cn.edu.haut.campus.core.timeline.CourseTimelineSource
import cn.edu.haut.campus.core.timeline.DefaultTimelineService
import cn.edu.haut.campus.core.timeline.PracticeTimelineSource
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineFilter
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** Timetable 的统一 Timeline 读取路径；UI 只消费投影结果，不再自行展开课程规则。 */
internal fun interface TimetableTimelineProjection {
    fun observeEvents(): Flow<List<TimelineEvent>>
}

private data class CourseTimelineInputs(
    val term: Term?,
    val courses: List<Course>,
    val rules: List<CourseScheduleRule>,
    val periodTimes: List<PeriodTime>,
    val calendarOverrides: List<CalendarOverride>,
    val occurrenceOverrides: List<OccurrenceOverride>
)

private data class RevisionedValue<T>(
    val value: T,
    val revision: Long
)

/** 同内容重放不升 revision；内容变化后严格单调递增，满足 Timeline 输入快照契约。 */
private fun <T> Flow<T>.contentRevisioned(): Flow<RevisionedValue<T>> = flow {
    var initialized = false
    var previous: T? = null
    var revision = 0L
    collect { value ->
        if (!initialized || previous != value) {
            initialized = true
            previous = value
            revision += 1L
        }
        emit(RevisionedValue(value, revision))
    }
}

private fun localTimelineProvenance(scope: SchoolAccountScope, sourceRecordId: String): DataProvenance = DataProvenance(
    providerId = "local.room",
    schoolId = scope.schoolId,
    accountId = scope.accountId,
    sourceSystem = "account-database",
    sourceRecordId = sourceRecordId
)

@Suppress("UNCHECKED_CAST")
private fun courseTimelineSnapshots(
    repository: CampusRepository,
    scope: SchoolAccountScope,
    zone: ZoneId
): Flow<CourseScheduleTimelineSnapshot> = combine(
    repository.termRepository.currentTermFlow,
    repository.timetableRepository.coursesFlow,
    repository.timetableRepository.rulesFlow,
    repository.termRepository.periodTimesFlow,
    repository.termRepository.calendarOverridesFlow,
    repository.timetableRepository.occurrenceOverridesFlow
) { values ->
    CourseTimelineInputs(
        term = values[0] as Term?,
        courses = values[1] as List<Course>,
        rules = values[2] as List<CourseScheduleRule>,
        periodTimes = values[3] as List<PeriodTime>,
        calendarOverrides = values[4] as List<CalendarOverride>,
        occurrenceOverrides = values[5] as List<OccurrenceOverride>
    )
}.distinctUntilChanged().contentRevisioned().map { revisioned ->
    val inputs = revisioned.value
    CourseScheduleTimelineSnapshot(
        zone = zone,
        provenance = localTimelineProvenance(scope, "course-schedule"),
        revision = revisioned.revision,
        terms = listOfNotNull(inputs.term),
        courses = inputs.courses,
        rules = inputs.rules,
        periodTimes = inputs.periodTimes,
        calendarOverrides = inputs.calendarOverrides,
        occurrenceOverrides = inputs.occurrenceOverrides
    )
}

/** 覆盖当前学期全部可导航教学周；前后余量与切片一 Today 对齐，避免周界/调休漏投影。 */
internal fun timetableTimelineWindow(term: Term, zone: ZoneId): TimeWindow = TimeWindow(
    start = term.startDate.minusDays(7).atStartOfDay(zone).toInstant(),
    end = term.startDate.plusWeeks(term.weekCount.toLong()).plusDays(22).atStartOfDay(zone).toInstant()
)

/**
 * Timetable 的账号级 reactive TimelineService 组合。
 *
 * 课表页历史渲染对象只有课程/实践课，因此本投影故意不注册考试与 personal_events；
 * 它们已经在 Today 的统一时间流中消费，不能借本切片改变课表截图与页面语义。
 */
internal class RepositoryTimetableTimelineProjection(
    private val accountScope: SchoolAccountScope,
    private val zone: ZoneId,
    currentTermFlow: Flow<Term?>,
    courseSnapshots: Flow<CourseScheduleTimelineSnapshot>,
    private val nowDate: () -> LocalDate = { LocalDate.now(zone) }
) : TimetableTimelineProjection {
    private val currentTermFlow = currentTermFlow.distinctUntilChanged()

    private val courseInputProvider = CourseScheduleTimelineInputProvider { requestScope, _ ->
        require(requestScope == accountScope) { "课程 Timeline 输入 scope 不一致" }
        courseSnapshots
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeEvents(): Flow<List<TimelineEvent>> = currentTermFlow.flatMapLatest { term ->
        val timelineService = DefaultTimelineService(
            CourseTimelineSource(courseInputProvider),
            PracticeTimelineSource(courseInputProvider)
        )
        timelineService.observe(
            scope = accountScope,
            window = windowFor(term),
            filter = TimelineFilter.ALL
        )
    }

    private fun windowFor(term: Term?): TimeWindow {
        if (term != null) return timetableTimelineWindow(term, zone)
        val today = nowDate()
        return TimeWindow(
            start = today.minusDays(7).atStartOfDay(zone).toInstant(),
            end = today.plusDays(8).atStartOfDay(zone).toInstant()
        )
    }
}

/** 组装 Timetable VM 的账号级 reactive TimelineService；只读投影，不写任何领域表。 */
internal fun repositoryTimetableTimelineProjection(
    repository: CampusRepository,
    sharingScope: CoroutineScope
): TimetableTimelineProjection {
    val accountScope = repository.currentAccountScope
    val zone = repository.timeZone
    val courseSnapshots = courseTimelineSnapshots(repository, accountScope, zone)
        .shareIn(sharingScope, SharingStarted.Lazily, replay = 1)
    return RepositoryTimetableTimelineProjection(
        accountScope = accountScope,
        zone = zone,
        currentTermFlow = repository.termRepository.currentTermFlow,
        courseSnapshots = courseSnapshots
    )
}
