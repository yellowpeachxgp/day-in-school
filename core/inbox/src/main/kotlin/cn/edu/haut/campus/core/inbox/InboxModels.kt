package cn.edu.haut.campus.core.inbox

import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.StoredBlobRef
import cn.edu.haut.campus.core.timeline.PersonalEventDraft
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class InboxOrigin {
    SHARE_SHEET,
    CLIPBOARD,
    URL,
    PLUGIN
}

/** MASTER_PLAN §6.4 的收件箱条目。originals 只存 blob 引用，原件字节仍由 Vault/blob_store 管理。 */
data class InboxItem(
    val id: String,
    val scope: SchoolAccountScope,
    val receivedAt: Instant,
    val referenceDate: LocalDate?,
    val originals: List<StoredBlobRef>,
    val origin: InboxOrigin
) {
    init {
        requireStableToken(id, "InboxItem id")
        require(originals.size <= 16) { "单条收件箱原件数量超限" }
        require(originals.map { it.blobId }.distinct().size == originals.size) { "收件箱原件 blob 不能重复" }
        require(originals.all { it.scope == scope }) { "收件箱原件必须与条目共 scope" }
    }
}

/** 文本抽取 Port。Android OCR/PDF 适配器在后续切片实现；解析内核只依赖纯 JVM 输入模型。 */
interface TextExtractor {
    suspend fun extract(blob: StoredBlobRef): ExtractedText
}

/** 可参与课程通知匹配的用户课表候选。ref 必须指向同 scope 的课程实体。 */
data class InboxCourseCandidate(
    val ref: EntityRef,
    val name: String,
    val aliases: List<String> = emptyList(),
    val teacher: String? = null
) {
    init {
        require(ref.type == EntityType.COURSE) { "课程候选 ref 必须是 COURSE" }
        require(name.isNotBlank() && name.length <= 128) { "课程名为空或过长" }
        require(name.none { it.isISOControl() }) { "课程名不能包含控制字符" }
        require(aliases.size <= 16) { "课程别名数量超限" }
        require(aliases.all { it.isNotBlank() && it.length <= 128 && it.none { ch -> ch.isISOControl() } }) {
            "课程别名为空、过长或含控制字符"
        }
        require((listOf(name) + aliases).distinct().size == aliases.size + 1) { "课程别名不能重复" }
        require(teacher == null || (teacher.isNotBlank() && teacher.length <= 128 && teacher.none { it.isISOControl() })) {
            "教师名为空、过长或含控制字符"
        }
    }
}

/** 规则/AI 解析器共享上下文。provenance 描述用户提供的通知原件，不代表解析结果已被校方确认。 */
data class ParseContext(
    val scope: SchoolAccountScope,
    val referenceDate: LocalDate,
    val term: TermContext,
    val zone: ZoneId,
    val provenance: DataProvenance,
    val courses: List<InboxCourseCandidate> = emptyList()
) {
    init {
        require(provenance.schoolId == scope.schoolId) { "ParseContext provenance 学校与 scope 不一致" }
        require(provenance.accountId == scope.accountId) { "ParseContext provenance 账号与 scope 不一致" }
        require(courses.size <= 500) { "课程候选数量超限" }
        require(courses.map { it.ref }.distinct().size == courses.size) { "课程候选 ref 不能重复" }
        require(courses.all { it.ref.scope == scope }) { "课程候选必须与 ParseContext 同 scope" }
    }
}

sealed interface OccurrenceChange {
    /** 单次停课；reason 只保存用户可见通知原因，不推断未写明的理由。 */
    data class Cancel(val reason: String? = null) : OccurrenceChange {
        init {
            require(reason == null || (reason.isNotBlank() && reason.length <= 512 && reason.none { it.isISOControl() })) {
                "停课原因为空、过长或含控制字符"
            }
        }
    }

    /** 单次调入目标日期；节次、精确时间和教室都允许缺失，由确认页逐字段补齐。 */
    data class Reschedule(
        val targetDate: LocalDate,
        val startTime: LocalTime? = null,
        val endTime: LocalTime? = null,
        val startPeriod: Int? = null,
        val endPeriod: Int? = null,
        val room: String? = null
    ) : OccurrenceChange {
        init {
            if (startTime != null && endTime != null) {
                require(endTime.isAfter(startTime)) { "调入结束时间必须晚于开始时间" }
            }
            if (startPeriod != null) require(startPeriod in 1..30) { "调入开始节次超出范围" }
            if (endPeriod != null) require(endPeriod in 1..30) { "调入结束节次超出范围" }
            if (startPeriod != null && endPeriod != null) {
                require(endPeriod >= startPeriod) { "调入结束节次不能早于开始节次" }
            }
            require(room == null || (room.isNotBlank() && room.length <= 128 && room.none { it.isISOControl() })) {
                "调入教室为空、过长或含控制字符"
            }
        }
    }

    /** 单次更换教室/场地，不改变日期或时间。 */
    data class ChangeRoom(
        val room: String,
        val buildingId: String? = null
    ) : OccurrenceChange {
        init {
            require(room.isNotBlank() && room.length <= 128 && room.none { it.isISOControl() }) {
                "教室为空、过长或含控制字符"
            }
            require(buildingId == null || (buildingId.isNotBlank() && buildingId.length <= 128 && buildingId.none { it.isISOControl() || it.isWhitespace() })) {
                "buildingId 为空、过长或含空白/控制字符"
            }
        }
    }
}

/** MASTER_PLAN §6.4 的解析提案。所有字段均需确认后才可应用；confidence 以字段名为键。 */
sealed interface ProposedAction {
    val confidence: Map<String, Confidence>
    val sourceSpans: List<TextSpan>

    data class CreateEvent(
        val draft: PersonalEventDraft,
        override val confidence: Map<String, Confidence>,
        override val sourceSpans: List<TextSpan>
    ) : ProposedAction {
        init { requireProposalMetadata(confidence, sourceSpans) }
    }

    data class CreateTask(
        val title: String,
        val due: Instant?,
        val checklist: List<String>,
        override val confidence: Map<String, Confidence>,
        override val sourceSpans: List<TextSpan>
    ) : ProposedAction {
        init {
            requireProposalMetadata(confidence, sourceSpans)
            require(title.isNotBlank() && title.length <= 256) { "任务标题为空或过长" }
            require(title.none { it.isISOControl() }) { "任务标题不能包含控制字符" }
            require(checklist.size <= 100) { "任务清单项数量超限" }
            require(checklist.all { it.isNotBlank() && it.length <= 512 && it.none { ch -> ch.isISOControl() } }) {
                "任务清单项为空、过长或含控制字符"
            }
        }
    }

    data class ModifyCourseOccurrence(
        val courseRef: EntityRef,
        val date: LocalDate,
        val change: OccurrenceChange,
        override val confidence: Map<String, Confidence>,
        override val sourceSpans: List<TextSpan>
    ) : ProposedAction {
        init {
            requireProposalMetadata(confidence, sourceSpans)
            require(courseRef.type == EntityType.COURSE) { "ModifyCourseOccurrence 目标必须是 COURSE" }
        }
    }

    /** 指纹去重识别为同一通知更新时使用；diff 仍需用户逐字段确认。 */
    data class UpdateExisting(
        val target: EntityRef,
        val diff: FieldDiff,
        override val confidence: Map<String, Confidence>,
        override val sourceSpans: List<TextSpan>
    ) : ProposedAction {
        init { requireProposalMetadata(confidence, sourceSpans) }
    }
}

/** MASTER_PLAN §6.4 的通知解析 Port；规则解析器优先，AI 实现必须另受预览与单次同意约束。 */
interface NoticeParser {
    val id: String
    suspend fun parse(text: ExtractedText, ctx: ParseContext): List<ProposedAction>
}

/** MASTER_PLAN §6.4 的指纹 Port。实现必须把 scope 纳入指纹，禁止跨账号复用去重结果。 */
interface NoticeFingerprinter {
    fun fingerprint(a: ProposedAction): String
    suspend fun findExisting(fp: String): EntityRef?
}

enum class ParseIssueKind {
    MISSING_DATE,
    DATE_UNRESOLVED,
    COURSE_NOT_MATCHED,
    TIME_NOT_FOUND,
    NO_ACTION
}

/** 解析诊断只描述问题类别，不复制原文内容，避免把用户通知带入日志。 */
data class ParseIssue(
    val kind: ParseIssueKind,
    val message: String,
    val spans: List<TextSpan> = emptyList()
) {
    init {
        require(message.isNotBlank() && message.length <= 256) { "解析诊断消息为空或过长" }
        require(message.none { it.isISOControl() }) { "解析诊断消息不能包含控制字符" }
    }
}

data class RuleParseReport(
    val actions: List<ProposedAction>,
    val issues: List<ParseIssue>
)

private fun requireProposalMetadata(confidence: Map<String, Confidence>, sourceSpans: List<TextSpan>) {
    require(confidence.isNotEmpty()) { "ProposedAction 必须携带逐字段 confidence" }
    require(confidence.keys.all { it.isNotBlank() && it.length <= 64 && it.none { ch -> ch.isISOControl() || ch.isWhitespace() } }) {
        "confidence 字段名为空、过长或含非法字符"
    }
    require(sourceSpans.isNotEmpty()) { "ProposedAction 必须至少有一个原文 sourceSpan" }
}

private fun requireStableToken(value: String, label: String) {
    require(value.length in 1..256) { label + " 为空或过长" }
    require(value.none { it.isISOControl() || it.isWhitespace() }) { label + " 不能包含空白或控制字符" }
    require(value.all { it.isLetterOrDigit() || it in "_-./:" }) { label + " 包含非法字符" }
}
