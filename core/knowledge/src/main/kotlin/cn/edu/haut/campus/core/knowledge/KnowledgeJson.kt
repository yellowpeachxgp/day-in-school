package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.textextract.ExtractionConfirmationState
import cn.edu.haut.campus.core.textextract.ExtractionSourceKind
import java.time.Instant
import java.time.ZoneId

internal const val COURSE_MATERIAL_FORMAT = "course-material/v1"
internal const val REVIEW_TASK_FORMAT = "review-task/v1"
internal const val EXTRACTION_FORMAT = "knowledge-extraction/v1"

internal fun canonicalJson(value: JsonValue): String = Json.canonical(value)
internal fun jsonString(value: String?): JsonValue = value?.let(JsonValue::Str) ?: JsonValue.Null
internal fun jsonLong(value: Long?): JsonValue = value?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null
internal fun jsonInt(value: Int?): JsonValue = value?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null
internal fun jsonBool(value: Boolean): JsonValue = JsonValue.Bool(value)
internal fun jsonObjectOf(vararg fields: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(fields.toMap())

internal fun JsonValue.Obj.requiredField(key: String): JsonValue =
    fields[key] ?: throw IllegalArgumentException("知识 JSON 缺少字段 " + key)
internal fun JsonValue.Obj.requiredString(key: String): String =
    requiredField(key).stringOrNull() ?: throw IllegalArgumentException("知识 JSON 字段不是 string: " + key)
internal fun JsonValue.Obj.nullableString(key: String): String? = fields[key]?.stringOrNull()
internal fun JsonValue.longOrNull(): Long? = (this as? JsonValue.Num)?.literal?.toLongOrNull()
internal fun JsonValue.floatOrNull(): Float? = (this as? JsonValue.Num)?.literal?.toFloatOrNull()
internal fun JsonValue.Obj.requiredLong(key: String): Long =
    requiredField(key).longOrNull() ?: throw IllegalArgumentException("知识 JSON 字段不是 integer: " + key)
internal fun JsonValue.Obj.nullableLong(key: String): Long? = fields[key]?.longOrNull()
internal fun JsonValue.Obj.requiredInt(key: String): Int =
    requiredField(key).intOrNull() ?: throw IllegalArgumentException("知识 JSON 字段不是 integer: " + key)
internal fun JsonValue.Obj.nullableInt(key: String): Int? = fields[key]?.intOrNull()
internal fun JsonValue.Obj.requiredBoolean(key: String): Boolean =
    requiredField(key).boolOrNull() ?: throw IllegalArgumentException("知识 JSON 字段不是 boolean: " + key)
internal fun JsonValue.Obj.optionalBoolean(key: String, defaultValue: Boolean): Boolean = fields[key]?.boolOrNull() ?: defaultValue
internal fun JsonValue.Obj.requiredObject(key: String): JsonValue.Obj =
    requiredField(key).asObjOrNull ?: throw IllegalArgumentException("知识 JSON 字段不是 object: " + key)
internal fun JsonValue.Obj.requiredArray(key: String): List<JsonValue> =
    requiredField(key).asArrOrNull?.items ?: throw IllegalArgumentException("知识 JSON 字段不是 array: " + key)
internal fun JsonValue.Obj.optionalArray(key: String): List<JsonValue> = fields[key]?.asArrOrNull?.items ?: emptyList()

internal fun encodeScope(scope: SchoolAccountScope): JsonValue.Obj = jsonObjectOf(
    "schoolId" to JsonValue.Str(scope.schoolId.value),
    "accountId" to JsonValue.Str(scope.accountId.value),
    "profileId" to JsonValue.Str(scope.profileId.value),
    "campusId" to jsonString(scope.campusId?.value)
)

internal fun decodeScope(value: JsonValue): SchoolAccountScope {
    val obj = value.asObjOrNull ?: throw IllegalArgumentException("scope 必须是 object")
    return SchoolAccountScope(
        schoolId = SchoolId(obj.requiredString("schoolId")),
        accountId = AccountId(obj.requiredString("accountId")),
        profileId = ProfileId(obj.requiredString("profileId")),
        campusId = obj.nullableString("campusId")?.let(::CampusId)
    )
}

internal fun encodeProvenance(provenance: DataProvenance): JsonValue.Obj = jsonObjectOf(
    "providerId" to JsonValue.Str(provenance.providerId),
    "schoolId" to JsonValue.Str(provenance.schoolId.value),
    "accountId" to JsonValue.Str(provenance.accountId.value),
    "sourceSystem" to JsonValue.Str(provenance.sourceSystem),
    "sourceRecordId" to jsonString(provenance.sourceRecordId),
    "fetchedAt" to jsonString(provenance.fetchedAt?.toString()),
    "completeness" to JsonValue.Str(provenance.completeness.name),
    "trust" to JsonValue.Str(provenance.trust.name),
    "schemaVersion" to JsonValue.Num(provenance.schemaVersion.toString()),
    "contentRevision" to jsonString(provenance.contentRevision)
)

internal fun decodeProvenance(value: JsonValue): DataProvenance {
    val obj = value.asObjOrNull ?: throw IllegalArgumentException("DataProvenance 必须是 object")
    return DataProvenance(
        providerId = obj.requiredString("providerId"),
        schoolId = SchoolId(obj.requiredString("schoolId")),
        accountId = AccountId(obj.requiredString("accountId")),
        sourceSystem = obj.requiredString("sourceSystem"),
        sourceRecordId = obj.nullableString("sourceRecordId"),
        fetchedAt = obj.nullableString("fetchedAt")?.let(Instant::parse),
        completeness = SnapshotCompleteness.valueOf(obj.requiredString("completeness")),
        trust = VerificationLevel.valueOf(obj.requiredString("trust")),
        schemaVersion = obj.requiredInt("schemaVersion"),
        contentRevision = obj.nullableString("contentRevision")
    )
}

internal fun encodeCourseAssociation(course: CourseAssociation): JsonValue.Obj = jsonObjectOf(
    "courseId" to JsonValue.Str(course.courseId),
    "courseName" to JsonValue.Str(course.courseName),
    "courseCode" to jsonString(course.courseCode),
    "termId" to jsonString(course.termId),
    "teachingWeek" to JsonValue.Num(course.teachingWeek.toString())
)

internal fun decodeCourseAssociation(value: JsonValue): CourseAssociation {
    val obj = value.asObjOrNull ?: throw IllegalArgumentException("course 必须是 object")
    return CourseAssociation(
        courseId = obj.requiredString("courseId"),
        courseName = obj.requiredString("courseName"),
        courseCode = obj.nullableString("courseCode"),
        termId = obj.nullableString("termId"),
        teachingWeek = obj.requiredInt("teachingWeek")
    )
}

internal fun encodeExtractionSummary(extraction: MaterialExtraction?): JsonValue = extraction?.let {
    jsonObjectOf(
        "blobId" to JsonValue.Str(it.blobId),
        "sourceKind" to jsonString(it.sourceKind?.name),
        "plainTextSha256" to JsonValue.Str(it.plainTextSha256),
        "lineCount" to JsonValue.Num(it.lineCount.toString()),
        "pageCount" to JsonValue.Num(it.pageCount.toString()),
        "confirmationState" to JsonValue.Str(it.confirmationState.name),
        "engineName" to jsonString(it.engineName),
        "warnings" to JsonValue.Arr(it.warnings.map(JsonValue::Str))
    )
} ?: JsonValue.Null

internal fun decodeExtractionSummary(value: JsonValue): MaterialExtraction? {
    val obj = value.asObjOrNull ?: return null
    return MaterialExtraction(
        blobId = obj.requiredString("blobId"),
        sourceKind = obj.nullableString("sourceKind")?.let(ExtractionSourceKind::valueOf),
        plainTextSha256 = obj.requiredString("plainTextSha256"),
        lineCount = obj.requiredInt("lineCount"),
        pageCount = obj.requiredInt("pageCount"),
        confirmationState = ExtractionConfirmationState.valueOf(obj.requiredString("confirmationState")),
        engineName = obj.nullableString("engineName"),
        warnings = obj.optionalArray("warnings").mapNotNull { it.stringOrNull() }
    )
}

internal fun encodeAssignment(assignment: AssignmentDeadline?): JsonValue = assignment?.let {
    jsonObjectOf(
        "dueAt" to JsonValue.Str(it.dueAt.toString()),
        "zone" to JsonValue.Str(it.zone.id),
        "timelineEventId" to jsonString(it.timelineEventId?.value)
    )
} ?: JsonValue.Null

internal fun decodeAssignment(value: JsonValue): AssignmentDeadline? {
    val obj = value.asObjOrNull ?: return null
    return AssignmentDeadline(
        dueAt = Instant.parse(obj.requiredString("dueAt")),
        zone = ZoneId.of(obj.requiredString("zone")),
        timelineEventId = obj.nullableString("timelineEventId")?.let(::cn.edu.haut.campus.core.timeline.TimelineEventId)
    )
}

internal fun encodeCourseMaterialDocument(material: CourseMaterial): JsonValue.Obj = jsonObjectOf(
    "format" to JsonValue.Str(COURSE_MATERIAL_FORMAT),
    "deleted" to jsonBool(false),
    "id" to JsonValue.Str(material.id),
    "scope" to encodeScope(material.scope),
    "course" to encodeCourseAssociation(material.course),
    "kind" to JsonValue.Str(material.kind.name),
    "title" to JsonValue.Str(material.title),
    "note" to jsonString(material.note),
    "fileName" to JsonValue.Str(material.fileName),
    "mimeType" to JsonValue.Str(material.mimeType),
    "contentBlobId" to JsonValue.Str(material.contentBlobId),
    "contentSha256" to JsonValue.Str(material.contentSha256),
    "byteCount" to JsonValue.Num(material.byteCount.toString()),
    "extraction" to encodeExtractionSummary(material.extraction),
    "assignment" to encodeAssignment(material.assignment),
    "provenance" to encodeProvenance(material.provenance),
    "revision" to JsonValue.Num(material.revision.toString()),
    "createdAt" to JsonValue.Str(material.createdAt.toString()),
    "updatedAt" to JsonValue.Str(material.updatedAt.toString())
)

internal fun isDeletedDocument(obj: JsonValue.Obj): Boolean = obj.optionalBoolean("deleted", false)

internal fun decodeCourseMaterialDocument(obj: JsonValue.Obj): CourseMaterial? {
    require(obj.requiredString("format") == COURSE_MATERIAL_FORMAT) { "课程资料快照格式不受支持" }
    if (isDeletedDocument(obj)) return null
    return CourseMaterial(
        id = obj.requiredString("id"),
        scope = decodeScope(obj.requiredField("scope")),
        course = decodeCourseAssociation(obj.requiredField("course")),
        kind = CourseMaterialKind.valueOf(obj.requiredString("kind")),
        title = obj.requiredString("title"),
        note = obj.nullableString("note"),
        fileName = obj.requiredString("fileName"),
        mimeType = obj.requiredString("mimeType"),
        contentBlobId = obj.requiredString("contentBlobId"),
        contentSha256 = obj.requiredString("contentSha256"),
        byteCount = obj.requiredLong("byteCount"),
        extraction = decodeExtractionSummary(obj.requiredField("extraction")),
        assignment = decodeAssignment(obj.requiredField("assignment")),
        provenance = decodeProvenance(obj.requiredField("provenance")),
        revision = obj.requiredLong("revision"),
        createdAt = Instant.parse(obj.requiredString("createdAt")),
        updatedAt = Instant.parse(obj.requiredString("updatedAt"))
    )
}

internal fun encodeReviewTaskDocument(task: ReviewTask): JsonValue.Obj = jsonObjectOf(
    "format" to JsonValue.Str(REVIEW_TASK_FORMAT),
    "deleted" to jsonBool(false),
    "id" to JsonValue.Str(task.id),
    "scope" to encodeScope(task.scope),
    "course" to encodeCourseAssociation(task.course),
    "materialId" to jsonString(task.materialId),
    "title" to JsonValue.Str(task.title),
    "note" to jsonString(task.note),
    "state" to JsonValue.Str(task.state.name),
    "dueAt" to jsonString(task.dueAt?.toString()),
    "estimatedMinutes" to jsonInt(task.estimatedMinutes),
    "revision" to JsonValue.Num(task.revision.toString()),
    "createdAt" to JsonValue.Str(task.createdAt.toString()),
    "updatedAt" to JsonValue.Str(task.updatedAt.toString())
)

internal fun decodeReviewTaskDocument(obj: JsonValue.Obj): ReviewTask? {
    require(obj.requiredString("format") == REVIEW_TASK_FORMAT) { "复习任务快照格式不受支持" }
    if (isDeletedDocument(obj)) return null
    return ReviewTask(
        id = obj.requiredString("id"),
        scope = decodeScope(obj.requiredField("scope")),
        course = decodeCourseAssociation(obj.requiredField("course")),
        materialId = obj.nullableString("materialId"),
        title = obj.requiredString("title"),
        note = obj.nullableString("note"),
        state = ReviewTaskState.valueOf(obj.requiredString("state")),
        dueAt = obj.nullableString("dueAt")?.let(Instant::parse),
        estimatedMinutes = obj.nullableInt("estimatedMinutes"),
        revision = obj.requiredLong("revision"),
        createdAt = Instant.parse(obj.requiredString("createdAt")),
        updatedAt = Instant.parse(obj.requiredString("updatedAt"))
    )
}

internal fun encodeBounds(bounds: KnowledgeTextBounds?): JsonValue = bounds?.let {
    jsonObjectOf(
        "left" to JsonValue.Num(it.left.toString()),
        "top" to JsonValue.Num(it.top.toString()),
        "right" to JsonValue.Num(it.right.toString()),
        "bottom" to JsonValue.Num(it.bottom.toString())
    )
} ?: JsonValue.Null

internal fun decodeBounds(value: JsonValue): KnowledgeTextBounds? {
    val obj = value.asObjOrNull ?: return null
    return KnowledgeTextBounds(
        left = obj.requiredField("left").floatOrNull() ?: 0f,
        top = obj.requiredField("top").floatOrNull() ?: 0f,
        right = obj.requiredField("right").floatOrNull() ?: 0f,
        bottom = obj.requiredField("bottom").floatOrNull() ?: 0f
    )
}

internal fun encodeKnowledgeLine(line: KnowledgeTextLine): JsonValue.Obj = jsonObjectOf(
    "lineIndex" to JsonValue.Num(line.lineIndex.toString()),
    "pageIndex" to JsonValue.Num(line.pageIndex.toString()),
    "text" to JsonValue.Str(line.text),
    "confidence" to (line.confidence?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null),
    "bounds" to encodeBounds(line.bounds),
    "coordinateSpace" to JsonValue.Str(line.coordinateSpace.name)
)

internal fun decodeKnowledgeLine(value: JsonValue): KnowledgeTextLine {
    val obj = value.asObjOrNull ?: throw IllegalArgumentException("抽取行必须是 object")
    return KnowledgeTextLine(
        lineIndex = obj.requiredInt("lineIndex"),
        pageIndex = obj.requiredInt("pageIndex"),
        text = obj.requiredString("text"),
        confidence = obj.fields["confidence"]?.floatOrNull(),
        bounds = decodeBounds(obj.requiredField("bounds")),
        coordinateSpace = KnowledgeCoordinateSpace.valueOf(obj.requiredString("coordinateSpace"))
    )
}

internal data class KnowledgeExtractionPayload(
    val plainText: String,
    val lines: List<KnowledgeTextLine>
)

internal fun encodeExtractionPayload(payload: KnowledgeExtractionPayload): JsonValue.Obj = jsonObjectOf(
    "format" to JsonValue.Str(EXTRACTION_FORMAT),
    "plainText" to JsonValue.Str(payload.plainText),
    "lines" to JsonValue.Arr(payload.lines.map(::encodeKnowledgeLine))
)

internal fun decodeExtractionPayload(text: String): KnowledgeExtractionPayload {
    val obj = Json.parse(text).asObjOrNull ?: throw IllegalArgumentException("抽取 payload 必须是 object")
    require(obj.requiredString("format") == EXTRACTION_FORMAT) { "抽取 payload 格式不受支持" }
    return KnowledgeExtractionPayload(
        plainText = obj.requiredString("plainText"),
        lines = obj.requiredArray("lines").map(::decodeKnowledgeLine)
    )
}
