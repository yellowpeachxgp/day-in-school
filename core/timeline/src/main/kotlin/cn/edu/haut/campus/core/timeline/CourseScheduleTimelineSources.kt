package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SourceRef
import cn.edu.haut.campus.core.model.TimeEvidence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 判断一门课程是否属于实践/集中课。默认使用 W0-04 落库时的稳定 sjk 外部标识。 */
fun interface PracticeCourseClassifier {
    fun isPractice(course: Course): Boolean

    companion object {
        val STABLE_PRACTICE_KEY: PracticeCourseClassifier = PracticeCourseClassifier { course ->
            course.externalId?.startsWith("sjk:") == true || course.id.startsWith("c_prac_")
        }
    }
}

/** 普通课程 TimelineSource；实践/集中课由 [PracticeTimelineSource] 分开投影。 */
class CourseTimelineSource(
    private val inputProvider: CourseScheduleTimelineInputProvider,
    private val placeResolver: TimelinePlaceResolver = TimelinePlaceResolver.NONE,
    private val practiceCourseClassifier: PracticeCourseClassifier = PracticeCourseClassifier.STABLE_PRACTICE_KEY
) : TimelineSource {
    override val id: String = SOURCE_ID
    override val kinds: Set<TimelineKind> = setOf(TimelineKind.COURSE)

    override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<TimelineEvent>> =
        inputProvider.observe(scope, window).map { snapshot ->
            CourseScheduleTimelineProjector.project(
                snapshot = snapshot,
                scope = scope,
                window = window,
                sourceId = id,
                kind = TimelineKind.COURSE,
                includePracticeCourses = false,
                placeResolver = placeResolver,
                practiceCourseClassifier = practiceCourseClassifier
            )
        }

    companion object {
        const val SOURCE_ID = "course"
    }
}

/** 实践/集中课 TimelineSource，按 W0-04 的多个 OccurrenceRule 落库规则逐日展开。 */
class PracticeTimelineSource(
    private val inputProvider: CourseScheduleTimelineInputProvider,
    private val placeResolver: TimelinePlaceResolver = TimelinePlaceResolver.NONE,
    private val practiceCourseClassifier: PracticeCourseClassifier = PracticeCourseClassifier.STABLE_PRACTICE_KEY
) : TimelineSource {
    override val id: String = SOURCE_ID
    override val kinds: Set<TimelineKind> = setOf(TimelineKind.PRACTICE)

    override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<TimelineEvent>> =
        inputProvider.observe(scope, window).map { snapshot ->
            CourseScheduleTimelineProjector.project(
                snapshot = snapshot,
                scope = scope,
                window = window,
                sourceId = id,
                kind = TimelineKind.PRACTICE,
                includePracticeCourses = true,
                placeResolver = placeResolver,
                practiceCourseClassifier = practiceCourseClassifier
            )
        }

    companion object {
        const val SOURCE_ID = "practice"
    }
}

internal object CourseScheduleTimelineProjector {

    fun project(
        snapshot: CourseScheduleTimelineSnapshot,
        scope: SchoolAccountScope,
        window: TimeWindow,
        sourceId: String,
        kind: TimelineKind,
        includePracticeCourses: Boolean,
        placeResolver: TimelinePlaceResolver,
        practiceCourseClassifier: PracticeCourseClassifier
    ): List<TimelineEvent> {
        validateSnapshotScope(snapshot, scope)
        val coursesById = snapshot.courses.associateBy { it.id }
        val events = mutableListOf<TimelineEvent>()
        for (term in snapshot.terms) {
            var date = firstCandidateDate(window, snapshot.zone)
            val lastDate = lastCandidateDate(window, snapshot.zone)
            while (!date.isAfter(lastDate)) {
                val expansion = ScheduleOccurrenceExpander.expandDate(
                    date = date,
                    term = term,
                    courses = snapshot.courses,
                    rules = snapshot.rules,
                    periodTimes = snapshot.periodTimes,
                    calendarOverrides = snapshot.calendarOverrides,
                    occurrenceOverrides = snapshot.occurrenceOverrides
                )
                expansion.active.forEach { occurrence ->
                    if (matchesCourseKind(occurrence, coursesById, includePracticeCourses, practiceCourseClassifier)) {
                        events += occurrence.toTimelineEvent(
                            termId = term.id,
                            snapshot = snapshot,
                            scope = scope,
                            sourceId = sourceId,
                            kind = kind,
                            cancelled = false,
                            placeResolver = placeResolver
                        )
                    }
                }
                expansion.cancelled.forEach { occurrence ->
                    if (matchesCourseKind(occurrence, coursesById, includePracticeCourses, practiceCourseClassifier)) {
                        events += occurrence.toTimelineEvent(
                            termId = term.id,
                            snapshot = snapshot,
                            scope = scope,
                            sourceId = sourceId,
                            kind = kind,
                            cancelled = true,
                            placeResolver = placeResolver
                        )
                    }
                }
                date = date.plusDays(1)
            }
        }
        return events
            .filter { it.overlaps(window) }
            .distinctBy { it.id }
            .sortedWith(
                compareBy<TimelineEvent> { it.start }
                    .thenBy { it.end ?: it.start }
                    .thenBy { it.id.value }
            )
    }

    private fun validateSnapshotScope(snapshot: CourseScheduleTimelineSnapshot, scope: SchoolAccountScope) {
        require(snapshot.provenance.schoolId == scope.schoolId) { "课程时间流输入证据学校与 scope 不一致" }
        require(snapshot.provenance.accountId == scope.accountId) { "课程时间流输入证据账号与 scope 不一致" }
    }

    /** 前后各扩一天，保证窗口边界附近的半开区间与跨时区转换不漏事件；最终仍按 Instant 精确过滤。 */
    private fun firstCandidateDate(window: TimeWindow, zone: ZoneId): LocalDate =
        window.start.atZone(zone).toLocalDate().minusDays(1)

    private fun lastCandidateDate(window: TimeWindow, zone: ZoneId): LocalDate =
        window.end.minusNanos(1).atZone(zone).toLocalDate().plusDays(1)

    private fun matchesCourseKind(
        occurrence: ClassOccurrence,
        coursesById: Map<String, Course>,
        includePracticeCourses: Boolean,
        practiceCourseClassifier: PracticeCourseClassifier
    ): Boolean {
        val course = coursesById[occurrence.courseId] ?: return false
        return practiceCourseClassifier.isPractice(course) == includePracticeCourses
    }

    private fun ClassOccurrence.toTimelineEvent(
        termId: String,
        snapshot: CourseScheduleTimelineSnapshot,
        scope: SchoolAccountScope,
        sourceId: String,
        kind: TimelineKind,
        cancelled: Boolean,
        placeResolver: TimelinePlaceResolver
    ): TimelineEvent {
        val stableKey = stableOccurrenceKey(termId, cancelled)
        val eventId = TimelineEventId(sourceId + ":" + stableKey)
        val start = localInstant(date, startTime, snapshot.zone)
        val end = when (timeEvidence) {
            TimeEvidence.EXPLICIT_VALID,
            TimeEvidence.TIMETABLE_VALID,
            TimeEvidence.ESTIMATED -> localInstant(date, endTime, snapshot.zone)
            TimeEvidence.UNRESOLVED,
            TimeEvidence.INVALID -> null
        }
        val timeSemantics = when (timeEvidence) {
            TimeEvidence.UNRESOLVED,
            TimeEvidence.INVALID -> TimelineTimeSemantics.UNCERTAIN
            TimeEvidence.EXPLICIT_VALID,
            TimeEvidence.TIMETABLE_VALID,
            TimeEvidence.ESTIMATED -> TimelineTimeSemantics.TIMED
        }
        val status = when {
            cancelled -> EventStatus.CANCELLED_TODAY
            timeEvidence != TimeEvidence.EXPLICIT_VALID && timeEvidence != TimeEvidence.TIMETABLE_VALID -> EventStatus.TENTATIVE
            isMakeupOrOverridden -> EventStatus.RESCHEDULED
            else -> EventStatus.SCHEDULED
        }
        val busy = when {
            cancelled -> BusyLevel.FREE
            timeEvidence == TimeEvidence.EXPLICIT_VALID || timeEvidence == TimeEvidence.TIMETABLE_VALID -> BusyLevel.BUSY
            timeEvidence == TimeEvidence.ESTIMATED -> BusyLevel.TENTATIVE
            else -> BusyLevel.FREE
        }
        val occurrenceEntity = EntityRef(EntityType.COURSE_OCCURRENCE, stableKey, scope)
        val provenance = snapshot.provenance.copy(sourceRecordId = stableKey)
        val links = buildList {
            add(EntityRef(EntityType.COURSE, courseId, scope))
            mergedRuleIds.forEach { ruleId ->
                add(EntityRef(EntityType.COURSE_SCHEDULE_RULE, ruleId, scope))
            }
        }.distinct()
        return TimelineEvent(
            id = eventId,
            scope = scope,
            kind = kind,
            title = courseName,
            start = start,
            end = end,
            timeSemantics = timeSemantics,
            zone = snapshot.zone,
            place = placeResolver.resolve(scope, rawRoom, buildingId, null),
            source = SourceRef(occurrenceEntity, provenance),
            provenance = provenance,
            status = status,
            busy = busy,
            revision = snapshot.revision,
            links = links
        )
    }

    /**
     * 普通 occurrence 使用 rule+date，保证「仅改本次」不换新身份；独立补课/调入行使用
     * override 行身份；停课投影使用取消覆盖行身份。重复同步同一批行时 ID 完全一致。
     */
    private fun ClassOccurrence.stableOccurrenceKey(termId: String, cancelled: Boolean): String {
        if (cancelled) {
            overrideId?.let { return termId + "/cancelled/" + it }
        }
        val standaloneOverride = overrideId != null && occurrenceId.startsWith(date.toString() + "_override_")
        if (standaloneOverride) {
            return termId + "/override/" + overrideId
        }
        val ruleIdentity = ruleId ?: mergedRuleIds.firstOrNull() ?: "course-" + courseId
        return termId + "/" + ruleIdentity + "/" + date
    }

    private fun localInstant(date: LocalDate, time: java.time.LocalTime, zone: ZoneId): Instant =
        date.atTime(time).atZone(zone).toInstant()
}
