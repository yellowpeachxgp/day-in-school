package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.database.CourseEntity
import cn.edu.haut.campus.core.database.CourseScheduleRuleEntity
import cn.edu.haut.campus.core.database.ExamScheduleEntity
import cn.edu.haut.campus.core.database.GradeAttemptEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.OccurrenceOverrideEntity
import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.json.JsonValue

/** 课程聚合快照：课程行只是入口，排课规则与单次覆盖同属该课程可撤销聚合。 */
class CourseEntitySnapshotStore(
    database: HautDatabase
) : EntitySnapshotStore {
    private val dao = database.campusDao()
    override val type: EntityType = EntityType.COURSE

    override suspend fun read(ref: EntityRef): CanonicalJson? {
        require(ref.type == type) { "CourseSnapshotStore 只能读取 COURSE" }
        val course = dao.getCourseById(ref.id)
        val rules = dao.getRulesSnapshot().filter { it.courseId == ref.id }.sortedBy { it.id }
        val ruleIds = rules.mapTo(mutableSetOf()) { it.id }
        val overrides = dao.getOccurrenceOverridesSnapshot()
            .filter { it.courseId == ref.id || it.courseRuleId in ruleIds }
            .sortedBy { it.id }
        if (course == null && rules.isEmpty() && overrides.isEmpty()) return null
        return canonicalJsonOf(
            jsonObjectOf(
                "format" to JsonValue.Str(COURSE_AGGREGATE_FORMAT),
                "course" to (course?.let(::encodeCourse) ?: JsonValue.Null),
                "rules" to JsonValue.Arr(rules.map(::encodeRule)),
                "occurrenceOverrides" to JsonValue.Arr(overrides.map(::encodeOccurrenceOverride))
            )
        )
    }

    override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) {
        require(ref.type == type) { "CourseSnapshotStore 只能恢复 COURSE" }
        val existingRules = dao.getRulesSnapshot().filter { it.courseId == ref.id }
        val existingRuleIds = existingRules.mapTo(mutableSetOf()) { it.id }
        val existingOverrides = dao.getOccurrenceOverridesSnapshot()
            .filter { it.courseId == ref.id || it.courseRuleId in existingRuleIds }
        if (snapshot == null) {
            existingOverrides.forEach { dao.deleteOccurrenceOverrideById(it.id) }
            existingRules.forEach { dao.deleteRuleById(it.id) }
            dao.deleteCourseById(ref.id)
            return
        }
        val root = parseCanonicalObject(snapshot)
        require(root.requiredString("format") == COURSE_AGGREGATE_FORMAT) { "课程聚合快照格式不受支持" }
        val course = root.fields["course"]?.asObjOrNull?.let(::decodeCourse)
        val rules = root.requiredArray("rules").map { decodeRule(it.asObjOrNull ?: throw IllegalArgumentException("rule 必须是 object")) }
        val overrides = root.requiredArray("occurrenceOverrides").map {
            decodeOccurrenceOverride(it.asObjOrNull ?: throw IllegalArgumentException("occurrence override 必须是 object"))
        }
        require(rules.all { it.courseId == ref.id }) { "课程快照包含跨课程 rule" }
        val snapshotRuleIds = rules.mapTo(mutableSetOf()) { it.id }
        require(overrides.all { it.courseId == ref.id || it.courseRuleId in snapshotRuleIds }) {
            "课程快照包含跨课程 occurrence override"
        }

        val desiredRuleIds = rules.mapTo(mutableSetOf()) { it.id }
        val desiredOverrideIds = overrides.mapTo(mutableSetOf()) { it.id }
        existingOverrides.filter { it.id !in desiredOverrideIds }.forEach { dao.deleteOccurrenceOverrideById(it.id) }
        existingRules.filter { it.id !in desiredRuleIds }.forEach { dao.deleteRuleById(it.id) }
        if (course == null) {
            dao.deleteCourseById(ref.id)
        } else {
            require(course.id == ref.id) { "课程快照 id 与 EntityRef 不一致" }
            dao.upsertCourse(course)
        }
        if (rules.isNotEmpty()) dao.upsertRules(rules)
        if (overrides.isNotEmpty()) dao.upsertOccurrenceOverrides(overrides)
    }

    private companion object {
        const val COURSE_AGGREGATE_FORMAT = "course-aggregate/v1"
    }
}

/** 成绩 attempt 行级快照。 */
class GradeAttemptSnapshotStore(
    database: HautDatabase
) : EntitySnapshotStore {
    private val dao = database.campusDao()
    override val type: EntityType = EntityType.GRADE_ATTEMPT

    override suspend fun read(ref: EntityRef): CanonicalJson? {
        require(ref.type == type) { "GradeSnapshotStore 只能读取 GRADE_ATTEMPT" }
        val row = dao.getGradeAttemptById(ref.id) ?: return null
        return canonicalJsonOf(
            jsonObjectOf(
                "format" to JsonValue.Str(FORMAT),
                "row" to encodeGrade(row)
            )
        )
    }

    override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) {
        require(ref.type == type) { "GradeSnapshotStore 只能恢复 GRADE_ATTEMPT" }
        if (snapshot == null) {
            dao.deleteGradeAttemptById(ref.id)
            return
        }
        val root = parseCanonicalObject(snapshot)
        require(root.requiredString("format") == FORMAT) { "成绩快照格式不受支持" }
        val row = decodeGrade(root.requiredObject("row"))
        require(row.id == ref.id) { "成绩快照 id 与 EntityRef 不一致" }
        dao.upsertGradeAttempts(listOf(row))
    }

    private companion object {
        const val FORMAT = "grade-attempt/v1"
    }
}

/** 考试安排行级快照。 */
class ExamScheduleSnapshotStore(
    database: HautDatabase
) : EntitySnapshotStore {
    private val dao = database.campusDao()
    override val type: EntityType = EntityType.EXAM

    override suspend fun read(ref: EntityRef): CanonicalJson? {
        require(ref.type == type) { "ExamSnapshotStore 只能读取 EXAM" }
        val row = dao.getExamScheduleById(ref.id) ?: return null
        return canonicalJsonOf(
            jsonObjectOf(
                "format" to JsonValue.Str(FORMAT),
                "row" to encodeExam(row)
            )
        )
    }

    override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) {
        require(ref.type == type) { "ExamSnapshotStore 只能恢复 EXAM" }
        if (snapshot == null) {
            dao.deleteExamScheduleById(ref.id)
            return
        }
        val root = parseCanonicalObject(snapshot)
        require(root.requiredString("format") == FORMAT) { "考试快照格式不受支持" }
        val row = decodeExam(root.requiredObject("row"))
        require(row.id == ref.id) { "考试快照 id 与 EntityRef 不一致" }
        dao.upsertExamSchedules(listOf(row))
    }

    private companion object {
        const val FORMAT = "exam-schedule/v1"
    }
}

private fun encodeCourse(row: CourseEntity): JsonValue.Obj = jsonObjectOf(
    "id" to JsonValue.Str(row.id),
    "externalId" to jsonString(row.externalId),
    "name" to JsonValue.Str(row.name),
    "teacher" to jsonString(row.teacher),
    "colorArgb" to JsonValue.Num(row.colorArgb.toString()),
    "creditPlain" to jsonString(row.creditPlain),
    "source" to JsonValue.Str(row.source),
    "userModified" to JsonValue.Bool(row.userModified),
    "lastSyncEpochMilli" to jsonLong(row.lastSyncEpochMilli)
)

private fun decodeCourse(obj: JsonValue.Obj): CourseEntity = CourseEntity(
    id = obj.requiredString("id"),
    externalId = obj.nullableString("externalId"),
    name = obj.requiredString("name"),
    teacher = obj.nullableString("teacher"),
    colorArgb = obj.requiredLong("colorArgb"),
    creditPlain = obj.nullableString("creditPlain"),
    source = obj.requiredString("source"),
    userModified = obj.requiredBoolean("userModified"),
    lastSyncEpochMilli = obj.nullableLong("lastSyncEpochMilli")
)

private fun encodeRule(row: CourseScheduleRuleEntity): JsonValue.Obj = jsonObjectOf(
    "id" to JsonValue.Str(row.id),
    "courseId" to JsonValue.Str(row.courseId),
    "termId" to JsonValue.Str(row.termId),
    "weekdayIso" to JsonValue.Num(row.weekdayIso.toString()),
    "startPeriod" to JsonValue.Num(row.startPeriod.toString()),
    "endPeriod" to JsonValue.Num(row.endPeriod.toString()),
    "weeksCsv" to JsonValue.Str(row.weeksCsv),
    "rawRoom" to JsonValue.Str(row.rawRoom),
    "buildingId" to jsonString(row.buildingId),
    "customStartTimeIso" to jsonString(row.customStartTimeIso),
    "customEndTimeIso" to jsonString(row.customEndTimeIso),
    "userModified" to JsonValue.Bool(row.userModified)
)

private fun decodeRule(obj: JsonValue.Obj): CourseScheduleRuleEntity = CourseScheduleRuleEntity(
    id = obj.requiredString("id"),
    courseId = obj.requiredString("courseId"),
    termId = obj.requiredString("termId"),
    weekdayIso = obj.requiredInt("weekdayIso"),
    startPeriod = obj.requiredInt("startPeriod"),
    endPeriod = obj.requiredInt("endPeriod"),
    weeksCsv = obj.requiredString("weeksCsv"),
    rawRoom = obj.requiredString("rawRoom"),
    buildingId = obj.nullableString("buildingId"),
    customStartTimeIso = obj.nullableString("customStartTimeIso"),
    customEndTimeIso = obj.nullableString("customEndTimeIso"),
    userModified = obj.requiredBoolean("userModified")
)

private fun encodeOccurrenceOverride(row: OccurrenceOverrideEntity): JsonValue.Obj = jsonObjectOf(
    "id" to JsonValue.Str(row.id),
    "termId" to JsonValue.Str(row.termId),
    "courseRuleId" to jsonString(row.courseRuleId),
    "courseId" to jsonString(row.courseId),
    "dateIso" to JsonValue.Str(row.dateIso),
    "startPeriod" to jsonInt(row.startPeriod),
    "endPeriod" to jsonInt(row.endPeriod),
    "startAtIso" to jsonString(row.startAtIso),
    "endAtIso" to jsonString(row.endAtIso),
    "room" to jsonString(row.room),
    "buildingId" to jsonString(row.buildingId),
    "canceled" to JsonValue.Bool(row.canceled),
    "reason" to jsonString(row.reason),
    "moveId" to jsonString(row.moveId),
    "moveRole" to jsonString(row.moveRole),
    "mutationToken" to JsonValue.Str(row.mutationToken)
)

private fun decodeOccurrenceOverride(obj: JsonValue.Obj): OccurrenceOverrideEntity = OccurrenceOverrideEntity(
    id = obj.requiredString("id"),
    termId = obj.requiredString("termId"),
    courseRuleId = obj.nullableString("courseRuleId"),
    courseId = obj.nullableString("courseId"),
    dateIso = obj.requiredString("dateIso"),
    startPeriod = obj.nullableInt("startPeriod"),
    endPeriod = obj.nullableInt("endPeriod"),
    startAtIso = obj.nullableString("startAtIso"),
    endAtIso = obj.nullableString("endAtIso"),
    room = obj.nullableString("room"),
    buildingId = obj.nullableString("buildingId"),
    canceled = obj.requiredBoolean("canceled"),
    reason = obj.nullableString("reason"),
    moveId = obj.nullableString("moveId"),
    moveRole = obj.nullableString("moveRole"),
    mutationToken = obj.nullableString("mutationToken") ?: ""
)

private fun encodeGrade(row: GradeAttemptEntity): JsonValue.Obj = jsonObjectOf(
    "id" to JsonValue.Str(row.id),
    "termId" to JsonValue.Str(row.termId),
    "academicYear" to JsonValue.Str(row.academicYear),
    "termNumber" to JsonValue.Num(row.termNumber.toString()),
    "courseKey" to JsonValue.Str(row.courseKey),
    "name" to JsonValue.Str(row.name),
    "category" to JsonValue.Str(row.category),
    "creditsPlain" to JsonValue.Str(row.creditsPlain),
    "rawGrade" to JsonValue.Str(row.rawGrade),
    "numericScorePlain" to jsonString(row.numericScorePlain),
    "gradePointPlain" to jsonString(row.gradePointPlain),
    "attemptType" to JsonValue.Str(row.attemptType),
    "passed" to JsonValue.Bool(row.passed),
    "isCourseReplacement" to JsonValue.Bool(row.isCourseReplacement),
    "source" to JsonValue.Str(row.source),
    "syncedAtEpochMilli" to JsonValue.Num(row.syncedAtEpochMilli.toString())
)

private fun decodeGrade(obj: JsonValue.Obj): GradeAttemptEntity = GradeAttemptEntity(
    id = obj.requiredString("id"),
    termId = obj.requiredString("termId"),
    academicYear = obj.requiredString("academicYear"),
    termNumber = obj.requiredInt("termNumber"),
    courseKey = obj.requiredString("courseKey"),
    name = obj.requiredString("name"),
    category = obj.requiredString("category"),
    creditsPlain = obj.requiredString("creditsPlain"),
    rawGrade = obj.requiredString("rawGrade"),
    numericScorePlain = obj.nullableString("numericScorePlain"),
    gradePointPlain = obj.nullableString("gradePointPlain"),
    attemptType = obj.requiredString("attemptType"),
    passed = obj.requiredBoolean("passed"),
    isCourseReplacement = obj.requiredBoolean("isCourseReplacement"),
    source = obj.requiredString("source"),
    syncedAtEpochMilli = obj.requiredLong("syncedAtEpochMilli")
)

private fun encodeExam(row: ExamScheduleEntity): JsonValue.Obj = jsonObjectOf(
    "id" to JsonValue.Str(row.id),
    "termId" to JsonValue.Str(row.termId),
    "courseCode" to JsonValue.Str(row.courseCode),
    "courseName" to JsonValue.Str(row.courseName),
    "examBatchName" to JsonValue.Str(row.examBatchName),
    "examStageLabel" to JsonValue.Str(row.examStageLabel),
    "examFormat" to JsonValue.Str(row.examFormat),
    "rawTimeText" to JsonValue.Str(row.rawTimeText),
    "examDateIso" to jsonString(row.examDateIso),
    "startTimeIso" to jsonString(row.startTimeIso),
    "endTimeIso" to jsonString(row.endTimeIso),
    "rawRoom" to JsonValue.Str(row.rawRoom),
    "seatNumber" to jsonString(row.seatNumber),
    "campusName" to JsonValue.Str(row.campusName),
    "isRetake" to JsonValue.Bool(row.isRetake),
    "syncedAtEpochMilli" to JsonValue.Num(row.syncedAtEpochMilli.toString()),
    "originTermId" to jsonString(row.originTermId)
)

private fun decodeExam(obj: JsonValue.Obj): ExamScheduleEntity = ExamScheduleEntity(
    id = obj.requiredString("id"),
    termId = obj.requiredString("termId"),
    courseCode = obj.requiredString("courseCode"),
    courseName = obj.requiredString("courseName"),
    examBatchName = obj.requiredString("examBatchName"),
    examStageLabel = obj.requiredString("examStageLabel"),
    examFormat = obj.requiredString("examFormat"),
    rawTimeText = obj.requiredString("rawTimeText"),
    examDateIso = obj.nullableString("examDateIso"),
    startTimeIso = obj.nullableString("startTimeIso"),
    endTimeIso = obj.nullableString("endTimeIso"),
    rawRoom = obj.requiredString("rawRoom"),
    seatNumber = obj.nullableString("seatNumber"),
    campusName = obj.requiredString("campusName"),
    isRetake = obj.requiredBoolean("isRetake"),
    syncedAtEpochMilli = obj.requiredLong("syncedAtEpochMilli"),
    originTermId = obj.nullableString("originTermId")
)
