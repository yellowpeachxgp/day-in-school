package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.textextract.ExtractionConfirmationState
import cn.edu.haut.campus.core.textextract.ExtractionSourceKind
import cn.edu.haut.campus.core.timeline.TimelineEventId
import java.time.Instant
import java.time.ZoneId

/** W3-04 课程资料类型。首版严格限定在用户明确选择的五类，不用 OTHER 模糊归档口径。 */
enum class CourseMaterialKind {
    SLIDES,
    PDF,
    PHOTO,
    NOTE,
    ASSIGNMENT
}

enum class KnowledgeCoordinateSpace {
    IMAGE_PIXEL_TOP_LEFT,
    PDF_POINT_TOP_LEFT,
    TEXT_LINE
}

data class KnowledgeTextBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    init {
        require(right >= left && bottom >= top) { "文本边界坐标非法" }
    }
}

/** 搜索可回溯到的一行文本；pageIndex 保持抽取层零基语义，UI 展示时再 +1。 */
data class KnowledgeTextLine(
    val lineIndex: Int,
    val pageIndex: Int,
    val text: String,
    val confidence: Float?,
    val bounds: KnowledgeTextBounds?,
    val coordinateSpace: KnowledgeCoordinateSpace
) {
    init {
        require(lineIndex >= 0 && pageIndex >= 0) { "文本行/页索引不能为负" }
        require(text.length <= 16 * 1024) { "单行抽取文本过长" }
        require(confidence == null || confidence in 0f..1f) { "OCR 置信度必须在 0..1" }
    }
}

/** 课程与教学周关联。courseId 是稳定实体 ID，courseName 只是显示名，二者不得混用。 */
data class CourseAssociation(
    val courseId: String,
    val courseName: String,
    val courseCode: String? = null,
    val termId: String? = null,
    val teachingWeek: Int
) {
    init {
        require(courseId.length in 1..256 && courseId.none { it.isISOControl() || it.isWhitespace() }) {
            "courseId 为空、过长或含空白/控制字符"
        }
        require(courseId.none { it == '"' || it == '\\' }) {
            "courseId 含引号或反斜线，无法与 SearchQuery facet 等值过滤安全对齐"
        }
        require(courseName.isNotBlank() && courseName.length <= 256 && courseName.none { it.isISOControl() }) {
            "课程名称为空、过长或含控制字符"
        }
        require(courseCode == null || courseCode.length <= 128) { "课程代码过长" }
        require(termId == null || termId.isNotBlank()) { "termId 不能为空白" }
        require(teachingWeek in 1..60) { "教学周必须在 1..60" }
    }

    fun courseRef(scope: SchoolAccountScope): EntityRef = EntityRef(
        type = cn.edu.haut.campus.core.model.EntityType.COURSE,
        id = courseId,
        scope = scope
    )
}

/** 大正文不进 entity_journal；metadata 只保存 extraction blob 的引用、哈希与统计。 */
data class MaterialExtraction(
    val blobId: String,
    val sourceKind: ExtractionSourceKind?,
    val plainTextSha256: String,
    val lineCount: Int,
    val pageCount: Int,
    val confirmationState: ExtractionConfirmationState,
    val engineName: String?,
    val warnings: List<String> = emptyList()
) {
    init {
        require(blobId.startsWith("blob-")) { "extraction blobId 非法" }
        require(plainTextSha256.matches(Regex("[0-9a-f]{64}"))) { "抽取文本哈希必须是 SHA-256 hex" }
        require(lineCount >= 0 && pageCount >= 0) { "抽取行/页数不能为负" }
        require(warnings.all { it.length <= 512 && it.none { ch -> ch.isISOControl() } }) { "抽取警告非法" }
    }
}

data class AssignmentDeadline(
    val dueAt: Instant,
    val zone: ZoneId,
    val timelineEventId: TimelineEventId? = null
)

data class CourseMaterial(
    val id: String,
    val scope: SchoolAccountScope,
    val course: CourseAssociation,
    val kind: CourseMaterialKind,
    val title: String,
    val note: String?,
    val fileName: String,
    val mimeType: String,
    val contentBlobId: String,
    val contentSha256: String,
    val byteCount: Long,
    val extraction: MaterialExtraction?,
    val assignment: AssignmentDeadline?,
    val provenance: DataProvenance,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    init {
        requireStableKnowledgeId(id, "material id")
        require(title.isNotBlank() && title.length <= 256 && title.none { it.isISOControl() }) { "资料标题非法" }
        require(note == null || note.length <= 8 * 1024) { "资料备注过长" }
        require(fileName.isNotBlank() && fileName.length <= 256 && fileName.none { it.isISOControl() }) { "文件名非法" }
        require(mimeType.isNotBlank() && mimeType.length <= 128 && mimeType.none { it.isISOControl() || it.isWhitespace() }) { "mimeType 非法" }
        require(contentBlobId.startsWith("blob-")) { "content blobId 非法" }
        require(contentSha256.matches(Regex("[0-9a-f]{64}"))) { "contentSha256 必须是 SHA-256 hex" }
        require(byteCount >= 0) { "byteCount 不能为负" }
        require(revision > 0) { "revision 必须大于 0" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt 不能早于 createdAt" }
    }

    val ref: EntityRef
        get() = EntityRef(cn.edu.haut.campus.core.model.EntityType.COURSE_MATERIAL, id, scope)
}

class ArchiveMaterialRequest(
    val scope: SchoolAccountScope,
    val course: CourseAssociation,
    val kind: CourseMaterialKind,
    val title: String,
    val note: String?,
    val fileName: String,
    val mimeType: String,
    contentBytes: ByteArray,
    val assignmentDueAt: Instant? = null,
    val deadlineZone: ZoneId = ZoneId.systemDefault()
) {
    val contentBytes: ByteArray = contentBytes.copyOf()

    init {
        require(this.contentBytes.isNotEmpty()) { "资料内容不能为空" }
        require(this.contentBytes.size <= 16 * 1024 * 1024) { "资料超过 16 MiB blob 上限" }
        if (assignmentDueAt != null) {
            require(kind == CourseMaterialKind.ASSIGNMENT) { "只有作业资料能携带截止时间" }
        }
    }
}

enum class ReviewTaskState {
    TODO,
    DONE,
    ARCHIVED
}

enum class ReviewBlockState {
    /** 未来的排程块；会作为 REVIEW_BLOCK 占用 Timeline。 */
    PLANNED,

    /** 用户已完成；仍保留在历史时间流中。 */
    DONE,

    /** 排程块已过期但仍未完成；重排时必须保留该历史并生成替代块，不得静默丢任务。 */
    MISSED
}

/** 一次具体的复习块。targetStart 是间隔重复算法给出的理想时间，start/end 是求解器实际选择。 */
data class ReviewBlock(
    val id: String,
    val repetitionIndex: Int,
    val targetStart: Instant,
    val start: Instant,
    val end: Instant,
    val state: ReviewBlockState,
    val timelineEventId: TimelineEventId
) {
    init {
        requireStableKnowledgeId(id, "review block id")
        require(repetitionIndex in 1..ReviewSchedule.MAX_REVIEW_BLOCKS) { "复习块序号超出范围" }
        require(end.isAfter(start)) { "复习块 end 必须晚于 start" }
    }
}

/** 一个复习任务的完整本地排程快照；历史 DONE/MISSED 块会随重排继续保留。 */
data class ReviewSchedule(
    val algorithm: String,
    val planRevision: Long,
    val generatedAt: Instant,
    val zone: ZoneId,
    val deadlineAt: Instant?,
    val blocks: List<ReviewBlock>
) {
    init {
        require(algorithm.isNotBlank() && algorithm.length <= 128 && algorithm.none { it.isISOControl() || it.isWhitespace() }) {
            "复习排程算法标识非法"
        }
        require(planRevision > 0) { "复习排程 planRevision 必须大于 0" }
        require(blocks.size <= MAX_REVIEW_BLOCKS) { "单个复习任务的排程块数量超出上限" }
        require(blocks.map { it.id }.distinct().size == blocks.size) { "复习块 ID 不能重复" }
        require(blocks.map { it.repetitionIndex }.distinct().size == blocks.size) { "复习块 repetitionIndex 不能重复" }
        require(blocks.map { it.timelineEventId }.distinct().size == blocks.size) { "复习块 TimelineEventId 不能重复" }
        val ordered = blocks.sortedWith(compareBy<ReviewBlock> { it.start }.thenBy { it.repetitionIndex })
        for (index in 1 until ordered.size) {
            require(ordered[index].start >= ordered[index - 1].end) { "同一复习任务的排程块不能重叠" }
        }
    }

    companion object {
        const val MAX_REVIEW_BLOCKS: Int = 32
    }
}

/**
 * W3-05 复习排程复用的任务模型。
 *
 * masteryPercent 是用户或完成记录给出的当前掌握度（0..100）；successfulReviewCount、
 * lapseCount 与 lastReviewedAt 是间隔重复算法的本地记忆状态。新增字段都有默认值，
 * W3-04 已写入 blob_store 的 review-task/v1 文档可以继续解码，不需要 Room 迁移。
 */
data class ReviewTask(
    val id: String,
    val scope: SchoolAccountScope,
    val course: CourseAssociation,
    val materialId: String?,
    val title: String,
    val note: String?,
    val state: ReviewTaskState,
    val dueAt: Instant?,
    val estimatedMinutes: Int?,
    val revision: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val masteryPercent: Int = 0,
    val successfulReviewCount: Int = 0,
    val lapseCount: Int = 0,
    val lastReviewedAt: Instant? = null,
    val schedule: ReviewSchedule? = null
) {
    init {
        requireStableKnowledgeId(id, "review task id")
        require(title.isNotBlank() && title.length <= 256 && title.none { it.isISOControl() }) { "复习任务标题非法" }
        require(note == null || note.length <= 8 * 1024) { "复习任务备注过长" }
        require(materialId == null || materialId.isNotBlank()) { "materialId 不能为空白" }
        require(estimatedMinutes == null || estimatedMinutes in 1..24 * 60) { "估时必须在 1 分钟到 24 小时" }
        require(revision > 0) { "revision 必须大于 0" }
        require(masteryPercent in 0..100) { "掌握度必须在 0..100" }
        require(successfulReviewCount >= 0) { "成功复习次数不能为负" }
        require(lapseCount >= 0) { "漏复习次数不能为负" }
    }

    val ref: EntityRef
        get() = EntityRef(cn.edu.haut.campus.core.model.EntityType.REVIEW_ITEM, id, scope)
}

data class KnowledgeSearchHit(
    val materialId: String,
    val title: String,
    val course: CourseAssociation,
    val kind: CourseMaterialKind,
    val pageIndex: Int?,
    val lineIndex: Int?,
    val snippet: String,
    val bounds: KnowledgeTextBounds?,
    val coordinateSpace: KnowledgeCoordinateSpace?,
    val confirmationState: ExtractionConfirmationState?,
    val updatedAt: Instant
)

data class KnowledgeExportSummary(
    val format: String,
    val materialCount: Int,
    val fileCount: Int,
    val courseId: String
)

internal fun requireStableKnowledgeId(value: String, label: String) {
    require(value.length in 1..256) { "$label 为空或过长" }
    require(value.none { it.isISOControl() || it.isWhitespace() }) { "$label 不能包含空白或控制字符" }
    require(value.all { it.isLetterOrDigit() || it in "_-./:" }) { "$label 包含非法字符" }
}
