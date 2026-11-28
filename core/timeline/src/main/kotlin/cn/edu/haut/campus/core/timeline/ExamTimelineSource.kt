package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SourceRef
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 考试 TimelineSource：考试行 ID 是唯一稳定身份，学期修复或时间修订不制造新事件 ID。 */
class ExamTimelineSource(
    private val inputProvider: ExamTimelineInputProvider,
    private val placeResolver: TimelinePlaceResolver = TimelinePlaceResolver.NONE
) : TimelineSource {
    override val id: String = SOURCE_ID
    override val kinds: Set<TimelineKind> = setOf(TimelineKind.EXAM)

    override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<TimelineEvent>> =
        inputProvider.observe(scope, window).map { snapshot ->
            validateSnapshotScope(snapshot, scope)
            snapshot.exams.mapNotNull { exam ->
                exam.toTimelineEvent(snapshot, scope, placeResolver)
            }.filter { it.overlaps(window) }
                .sortedWith(
                    compareBy<TimelineEvent> { it.start }
                        .thenBy { it.end ?: it.start }
                        .thenBy { it.id.value }
                )
        }

    private fun validateSnapshotScope(snapshot: ExamTimelineSnapshot, scope: SchoolAccountScope) {
        require(snapshot.provenance.schoolId == scope.schoolId) { "考试时间流输入证据学校与 scope 不一致" }
        require(snapshot.provenance.accountId == scope.accountId) { "考试时间流输入证据账号与 scope 不一致" }
    }

    private fun ExamScheduleItem.toTimelineEvent(
        snapshot: ExamTimelineSnapshot,
        scope: SchoolAccountScope,
        placeResolver: TimelinePlaceResolver
    ): TimelineEvent? {
        val date = examDate ?: return null
        val preciseWindow = preciseWindowOrNull(date, startTime, endTime, snapshot.zone)
        // 时间不完整时仍使用最强的已解析起点证据；只有连起点也缺失时才退到日期零点。
        val start = preciseWindow?.first
            ?: startTime?.let { localInstant(date, it, snapshot.zone) }
            ?: date.atStartOfDay(snapshot.zone).toInstant()
        val uncertainTime = preciseWindow == null
        val provenance = snapshot.provenance.copy(sourceRecordId = id)
        val examEntity = EntityRef(EntityType.EXAM, id, scope)
        return TimelineEvent(
            id = TimelineEventId(SOURCE_ID + ":" + id),
            scope = scope,
            kind = TimelineKind.EXAM,
            title = courseName,
            start = start,
            end = preciseWindow?.second,
            timeSemantics = if (uncertainTime) TimelineTimeSemantics.UNCERTAIN else TimelineTimeSemantics.TIMED,
            zone = snapshot.zone,
            place = placeResolver.resolve(scope, rawRoom, null, campusName),
            source = SourceRef(examEntity, provenance),
            provenance = provenance,
            status = if (uncertainTime) EventStatus.TENTATIVE else EventStatus.SCHEDULED,
            busy = if (uncertainTime) BusyLevel.FREE else BusyLevel.BUSY,
            revision = snapshot.revision,
            links = listOf(examEntity)
        )
    }

    /** 只有日期、缺起点或 end<=start 的考试不冒充精确时段；保留为 tentative 时间点。 */
    private fun preciseWindowOrNull(
        date: LocalDate,
        startTime: LocalTime?,
        endTime: LocalTime?,
        zone: ZoneId
    ): Pair<Instant, Instant>? {
        if (startTime == null || endTime == null || !endTime.isAfter(startTime)) return null
        val start = localInstant(date, startTime, zone)
        val end = localInstant(date, endTime, zone)
        return start to end
    }

    private fun localInstant(date: LocalDate, time: LocalTime, zone: ZoneId): Instant =
        date.atTime(time).atZone(zone).toInstant()

    companion object {
        const val SOURCE_ID = "exam"
    }
}
