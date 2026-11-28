package cn.edu.haut.campus.feature.today

import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.currentAccountDatabase
import cn.edu.haut.campus.core.database.currentAccountScope
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineInputProvider
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineSnapshot
import cn.edu.haut.campus.core.timeline.CourseTimelineSource
import cn.edu.haut.campus.core.timeline.DefaultTimelineService
import cn.edu.haut.campus.core.timeline.ExamTimelineInputProvider
import cn.edu.haut.campus.core.timeline.ExamTimelineSnapshot
import cn.edu.haut.campus.core.timeline.ExamTimelineSource
import cn.edu.haut.campus.core.timeline.PersonalEventTimelineInputProvider
import cn.edu.haut.campus.core.timeline.PersonalTimelineSource
import cn.edu.haut.campus.core.timeline.PracticeTimelineSource
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import cn.edu.haut.campus.core.timeline.TimelineFilter
import cn.edu.haut.campus.core.vault.RoomPersonalEventTimelineInputProvider
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

/** Today 的统一 Timeline 读取路径；UI 只消费投影结果，不再自行展开课程规则。 */
internal fun interface TodayTimelineProjection {
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

private fun examTimelineSnapshots(
    repository: CampusRepository,
    scope: SchoolAccountScope,
    zone: ZoneId
): Flow<ExamTimelineSnapshot> = repository.examRepository.examSchedulesFlow
    .distinctUntilChanged()
    .contentRevisioned()
    .map { revisioned ->
        ExamTimelineSnapshot(
            zone = zone,
            provenance = localTimelineProvenance(scope, "exam-schedule"),
            revision = revisioned.revision,
            exams = revisioned.value
        )
    }

/**
 * 以当前账号 Room 数据为输入的 reactive TimelineService 组合。
 *
 * 课程与实践课共用一个共享输入快照（两个 source 分别投 kind，不重复展开仓库 Flow）；
 * 考试与 personal_events 也注册为正式 source。窗口覆盖当前教学周前后一年，并并集
 * 当前学期完整跨度+下一课 21 天前瞻，因此不会让当前课程、学期考试或近期个人日程掉出读取路径。
 */
internal class RepositoryTodayTimelineProjection(
    private val accountScope: SchoolAccountScope,
    private val zone: ZoneId,
    currentTermFlow: Flow<Term?>,
    courseSnapshots: Flow<CourseScheduleTimelineSnapshot>,
    examSnapshots: Flow<ExamTimelineSnapshot>,
    personalInputProvider: PersonalEventTimelineInputProvider,
    private val nowDate: () -> LocalDate = { LocalDate.now(zone) }
) : TodayTimelineProjection {
    private val currentTermFlow = currentTermFlow.distinctUntilChanged()

    private val courseInputProvider = CourseScheduleTimelineInputProvider { requestScope, _ ->
        require(requestScope == accountScope) { "课程 Timeline 输入 scope 不一致" }
        courseSnapshots
    }
    private val examInputProvider = ExamTimelineInputProvider { requestScope, _ ->
        require(requestScope == accountScope) { "考试 Timeline 输入 scope 不一致" }
        examSnapshots
    }
    private val personalSource = PersonalTimelineSource(personalInputProvider)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeEvents(): Flow<List<TimelineEvent>> = currentTermFlow.flatMapLatest { term ->
        val timelineService = DefaultTimelineService(
            CourseTimelineSource(courseInputProvider),
            PracticeTimelineSource(courseInputProvider),
            ExamTimelineSource(examInputProvider),
            personalSource
        )
        timelineService.observe(
            scope = accountScope,
            window = windowFor(term),
            filter = TimelineFilter.ALL
        )
    }

    private fun windowFor(term: Term?): TimeWindow {
        val today = nowDate()
        var start = today.minusDays(370)
        var endInclusive = today.plusDays(370)
        if (term != null) {
            val termStart = term.startDate.minusDays(7)
            val termEndWithLookahead = term.startDate.plusWeeks(term.weekCount.toLong()).plusDays(21)
            if (termStart.isBefore(start)) start = termStart
            if (termEndWithLookahead.isAfter(endInclusive)) endInclusive = termEndWithLookahead
        }
        return TimeWindow(
            start = start.atStartOfDay(zone).toInstant(),
            end = endInclusive.plusDays(1).atStartOfDay(zone).toInstant()
        )
    }
}

/** 组装 Today VM 的账号级 reactive TimelineService；只读投影，不写 personal_events。 */
internal fun repositoryTodayTimelineProjection(
    repository: CampusRepository,
    sharingScope: CoroutineScope
): TodayTimelineProjection {
    val accountScope = repository.currentAccountScope
    val zone = repository.timeZone
    val courseSnapshots = courseTimelineSnapshots(repository, accountScope, zone)
        .shareIn(sharingScope, SharingStarted.Lazily, replay = 1)
    val examSnapshots = examTimelineSnapshots(repository, accountScope, zone)
        .shareIn(sharingScope, SharingStarted.Lazily, replay = 1)
    return RepositoryTodayTimelineProjection(
        accountScope = accountScope,
        zone = zone,
        currentTermFlow = repository.termRepository.currentTermFlow,
        courseSnapshots = courseSnapshots,
        examSnapshots = examSnapshots,
        personalInputProvider = RoomPersonalEventTimelineInputProvider(
            database = repository.currentAccountDatabase,
            scope = accountScope
        )
    )
}
