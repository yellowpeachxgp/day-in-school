package cn.edu.haut.campus.core.automation.android

import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineInputProvider
import cn.edu.haut.campus.core.timeline.CourseScheduleTimelineSnapshot
import cn.edu.haut.campus.core.timeline.ExamTimelineInputProvider
import cn.edu.haut.campus.core.timeline.ExamTimelineSnapshot
import cn.edu.haut.campus.core.timeline.TimeWindow
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Room 快照到 Timeline 输入的窄适配；每次调度/求值重新取快照，不在后台常驻监听。 */
class RepositoryCourseScheduleTimelineInputProvider(
    private val repository: CampusRepository,
    private val scope: SchoolAccountScope,
    private val zone: ZoneId
) : CourseScheduleTimelineInputProvider {
    override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<CourseScheduleTimelineSnapshot> = flow {
        require(scope == this@RepositoryCourseScheduleTimelineInputProvider.scope) { "课程 Timeline 输入 scope 不一致" }
        val data = repository.getDirectScheduleDataSnapshot()
        emit(
            CourseScheduleTimelineSnapshot(
                zone = zone,
                provenance = localProvenance(scope, "course-schedule"),
                revision = 1L,
                terms = listOfNotNull(data.term),
                courses = data.courses,
                rules = data.rules,
                periodTimes = data.periodTimes,
                calendarOverrides = data.calendarOverrides,
                occurrenceOverrides = data.occurrenceOverrides
            )
        )
    }
}

class RepositoryExamTimelineInputProvider(
    private val repository: CampusRepository,
    private val scope: SchoolAccountScope,
    private val zone: ZoneId
) : ExamTimelineInputProvider {
    override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<ExamTimelineSnapshot> = flow {
        require(scope == this@RepositoryExamTimelineInputProvider.scope) { "考试 Timeline 输入 scope 不一致" }
        emit(
            ExamTimelineSnapshot(
                zone = zone,
                provenance = localProvenance(scope, "exam-schedule"),
                revision = 1L,
                exams = repository.getExamSchedulesSnapshot()
            )
        )
    }
}

private fun localProvenance(scope: SchoolAccountScope, record: String): DataProvenance = DataProvenance(
    providerId = "local.room",
    schoolId = scope.schoolId,
    accountId = scope.accountId,
    sourceSystem = "account-database",
    sourceRecordId = record,
    completeness = SnapshotCompleteness.UNKNOWN,
    trust = VerificationLevel.SOURCE_CONFIRMED,
    schemaVersion = 1
)
