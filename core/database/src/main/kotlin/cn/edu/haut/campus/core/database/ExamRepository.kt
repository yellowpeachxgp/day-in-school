package cn.edu.haut.campus.core.database

import androidx.room.withTransaction
import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ExamScheduleItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 考试安排、考试学期修复审计与考试快照。
 * 实现从 CampusRepository 兼容门面逐字迁入；事务、证据规则、审计与通知语义保持不变。
 */
class ExamRepository internal constructor(
    private val environment: CampusRepositoryEnvironment
) {
    private val database = environment.database
    private val dao = environment.dao
    private val preferences = environment.preferences

    companion object {
        /** 考试学期修复规则版本：审计行按 (ruleVersion, examRowId) 幂等。 */
        internal const val EXAM_TERM_REPAIR_RULE_V2 = "exam_term_repair_v2"
    }

    /** 学期无法自动确认、需用户重新选择学期同步核对的考试记录审计（V2 逐条修复审计）。 */
    val examTermReviewAuditsFlow: Flow<List<ExamTermRepairAuditEntity>> =
        dao.observeExamTermRepairAuditsByDisposition(ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED)

    val examSchedulesFlow: Flow<List<ExamScheduleItem>> =
        dao.observeExamSchedules().map { list -> list.map { it.toDomain() } }

    private suspend fun notifyScheduleOrPolicyChanged(reason: String) {
        environment.notifyScheduleOrPolicyChanged(reason)
    }

    /**
     * 考试学期修复 V2（逐条证据驱动，替代 V1 的纯日期窗口猜测）：
     * - 有权威来源证明（originTermId 由可信同步管线写入）且 termId 被篡改的行 → 修复回原始学期；
     * - 目标学期已存在相同指纹考试 → 删除错标副本并记 MERGED_DUPLICATE，不产生重复考试；
     * - 旧库无来源行（originTermId == null）：学期有同步/导入证据 → 不动（仅用于抑制首次标记，不算解决证据）；
     *   学期为已知污染签名（"2026-2027-1"）或非法格式且无证据 → 保留并记 REVIEW_REQUIRED 待用户重同步核对；
     *   解除 REVIEW_REQUIRED 必须凭该行自身证据（originTermId 落库或行被权威重同步移除），
     *   学期级「该学期同步过/有导入审计」不再充当解决依据——证据不足一律维持待核对；
     * - 绝不凭显示日期猜学期（一月考试不当然属于春季学期），绝不盲目反改 V1 已修过的行；
     * - 修复与审计在同一事务内提交；无任何 DataStore 布尔门控——审计表即事实来源，
     *   进程在任何时刻崩溃重跑都安全（同一 examRowId+ruleVersion 的审计幂等覆盖）。
     */
    internal suspend fun ensureExamTermRepairV2IfNeeded() {
        val prefs = preferences.data.first()
        val savedXnm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XNM]
        val savedXqm = prefs[CampusRepositoryPrefKeys.SAVED_SYNC_XQM]
        val savedSyncTermId = if (!savedXnm.isNullOrBlank() && !savedXqm.isNullOrBlank()) {
            runCatching { HautAcademicEndpoints.toTermId(savedXnm, savedXqm) }.getOrNull()
        } else null
        var changed = false
        environment.academicEntityWritePort.writeMany(
            refsProvider = {
                dao.getExamSchedulesSnapshot().map { environment.academicRef(EntityType.EXAM, it.id) }
            },
            actor = Actor.SYNC,
            actorDetail = "exam-term-repair",
            writeGroupId = "exam-term-repair:" + UUID.randomUUID().toString()
        ) {
            database.withTransaction {
            val exams = dao.getExamSchedulesSnapshot()
            val audits = dao.getExamTermRepairAuditsSnapshot()
            val auditsByRow = audits.filter { it.ruleVersion == EXAM_TERM_REPAIR_RULE_V2 }
                .associateBy { it.examRowId }
            val importAuditTermIds = dao.observeImportAudits().first()
                .map { it.termId }.filter { it.isNotBlank() }.toSet()
            val evidencedTermIds = buildSet {
                savedSyncTermId?.let(::add)
                addAll(importAuditTermIds)
            }
            val termIdPattern = Regex("\\d{4}-\\d{4}-\\d+")
            val nowMs = System.currentTimeMillis()
            val toWrite = mutableListOf<ExamTermRepairAuditEntity>()
            val toRepair = mutableListOf<ExamScheduleEntity>()
            val examsById = exams.associateBy { it.id }
            val fingerprintIndex = exams.groupBy { examFingerprint(it) }

            for (row in exams) {
                val priorAudit = auditsByRow[row.id]
                val provenance = row.originTermId?.takeIf { it.isNotBlank() }
                when {
                    provenance != null && provenance != row.termId -> {
                        // 确定错标且有原始来源证明 → 修复（同事务写审计）
                        val duplicates = fingerprintIndex[examFingerprint(row)].orEmpty()
                            .filter { it.id != row.id && it.termId == provenance }
                        if (duplicates.isNotEmpty()) {
                            if (priorAudit?.disposition != ExamTermRepairAuditEntity.DISPOSITION_MERGED_DUPLICATE ||
                                priorAudit.newTermId != provenance
                            ) {
                                toWrite += ExamTermRepairAuditEntity(
                                    id = "v2:${row.id}",
                                    examRowId = row.id,
                                    previousTermId = row.termId,
                                    newTermId = provenance,
                                    evidenceRef = "originTermId+duplicate:${duplicates.first().id}",
                                    ruleVersion = EXAM_TERM_REPAIR_RULE_V2,
                                    disposition = ExamTermRepairAuditEntity.DISPOSITION_MERGED_DUPLICATE,
                                    repairedAtEpochMilli = nowMs
                                )
                            }
                            dao.deleteExamScheduleById(row.id)
                            changed = true
                        } else {
                            if (priorAudit?.disposition != ExamTermRepairAuditEntity.DISPOSITION_REPAIRED ||
                                priorAudit.previousTermId != row.termId ||
                                priorAudit.newTermId != provenance
                            ) {
                                toWrite += ExamTermRepairAuditEntity(
                                    id = "v2:${row.id}",
                                    examRowId = row.id,
                                    previousTermId = row.termId,
                                    newTermId = provenance,
                                    evidenceRef = "originTermId",
                                    ruleVersion = EXAM_TERM_REPAIR_RULE_V2,
                                    disposition = ExamTermRepairAuditEntity.DISPOSITION_REPAIRED,
                                    repairedAtEpochMilli = nowMs
                                )
                            }
                            toRepair += row.copy(termId = provenance)
                            changed = true
                        }
                    }
                    provenance != null -> {
                        // 一致：可信来源与存储学期相符；若曾被标记待核对则标记为已由重同步解决
                        if (priorAudit?.disposition == ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED) {
                            toWrite += priorAudit.copy(
                                newTermId = row.termId,
                                evidenceRef = "${priorAudit.evidenceRef}|resync_verified",
                                disposition = ExamTermRepairAuditEntity.DISPOSITION_RESOLVED_BY_RESYNC,
                                repairedAtEpochMilli = nowMs
                            )
                            changed = true
                        }
                    }
                    else -> {
                        // 旧库无来源行：绝不按日期猜学期。
                        val suspicious = row.termId.isBlank() ||
                            !termIdPattern.matches(row.termId) ||
                            (row.termId == "2026-2027-1" && row.termId !in evidencedTermIds)
                        if (suspicious) {
                            if (priorAudit?.disposition != ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED ||
                                priorAudit.previousTermId != row.termId
                            ) {
                                toWrite += ExamTermRepairAuditEntity(
                                    id = "v2:${row.id}",
                                    examRowId = row.id,
                                    previousTermId = row.termId,
                                    newTermId = null,
                                    evidenceRef = "missing_provenance",
                                    ruleVersion = EXAM_TERM_REPAIR_RULE_V2,
                                    disposition = ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED,
                                    repairedAtEpochMilli = nowMs
                                )
                                changed = true
                            }
                        }
                        // 注意：无来源行的学期级证据（学期曾同步/有导入审计、termId 形态合法）
                        // 只能抑制「首次标记」，绝不能把既有 REVIEW_REQUIRED 翻成 RESOLVED_BY_RESYNC；
                        // 该行何时解决只取决于自身证据（originTermId 一致 / 行被移除）。
                    }
                }
            }
            // 审计引用已不存在的数据行 → 视为已由重同步/清理解决
            for (audit in auditsByRow.values) {
                if (audit.disposition == ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED &&
                    audit.examRowId !in examsById
                ) {
                    toWrite += audit.copy(
                        evidenceRef = "${audit.evidenceRef}|row_removed",
                        disposition = ExamTermRepairAuditEntity.DISPOSITION_RESOLVED_BY_RESYNC,
                        repairedAtEpochMilli = nowMs
                    )
                    changed = true
                }
            }
            if (toRepair.isNotEmpty()) dao.upsertExamSchedules(toRepair)
            if (toWrite.isNotEmpty()) dao.upsertExamTermRepairAudits(toWrite)
            }
        }
        if (changed) notifyScheduleOrPolicyChanged("EXAM_TERM_REPAIR_V2")
    }

    /** 可信同步管线考试行 id 形态：`exam_` + SHA-256（64 位小写 hex）。 */
    private val TRUSTED_EXAM_ROW_ID = Regex("exam_[0-9a-f]{64}")

    /**
     * 自动去重所需的「同一考试会话」身份强度：
     * 明确考试日期+开考时间（「时间待定」的考试无法凭内容指纹区分跨学期同名考试）
     * 且行 id 为可信同步管线生成的稳定来源 id。两者缺一都不得自动删除旧行。
     */
    private fun hasTrustedExamSessionIdentity(row: ExamScheduleEntity): Boolean =
        !row.examDateIso.isNullOrBlank() &&
            !row.startTimeIso.isNullOrBlank() &&
            TRUSTED_EXAM_ROW_ID.matches(row.id)

    /** 考试内容指纹：同一场考试的逻辑身份（不含学期列与同步时间戳）。 */
    private fun examFingerprint(row: ExamScheduleEntity): String = listOf(
        row.courseCode,
        row.courseName,
        row.examBatchName,
        row.examStageLabel,
        row.examFormat,
        row.examDateIso.orEmpty(),
        row.startTimeIso.orEmpty(),
        row.endTimeIso.orEmpty(),
        row.rawRoom,
        row.isRetake.toString()
    ).joinToString("|")


    suspend fun saveImportedExams(
        exams: List<ExamScheduleItem>,
        replaceExisting: Boolean = true,
        targetTermId: String? = null,
        targetTermComplete: Boolean = true,
        completedTermIds: Set<String> = emptySet()
    ) {
        val incomingIds = exams.map { it.id }
        environment.academicEntityWritePort.writeMany(
            refsProvider = {
                (dao.getExamSchedulesSnapshot().map { it.id } + incomingIds)
                    .distinct()
                    .map { environment.academicRef(EntityType.EXAM, it) }
            },
            actor = Actor.SYNC,
            actorDetail = "exams-import",
            writeGroupId = "exams-import:" + UUID.randomUUID().toString()
        ) {
            database.withTransaction {
            if (replaceExisting) {
                if (!targetTermId.isNullOrBlank()) {
                    if (targetTermComplete) {
                        dao.clearExamSchedulesForTerm(targetTermId)
                    }
                    val termsToClear = if (completedTermIds.isNotEmpty()) {
                        completedTermIds.filter { it.isNotBlank() && it != targetTermId }
                    } else {
                        exams.map { it.termId }.distinct().filter { it.isNotBlank() && it != targetTermId }
                    }
                    for (tId in termsToClear) {
                        dao.clearExamSchedulesForTerm(tId)
                    }
                } else {
                    dao.clearExamSchedules()
                }
            }
            val filteredToUpsert = if (!targetTermId.isNullOrBlank() && !targetTermComplete) {
                exams.filter { it.termId != targetTermId }
            } else {
                exams
            }
            // 可信同步管线：写入时记录每行的原始学期为权威证据（originTermId）。
            // 后续任何把 termId 写坏的缺陷都可凭此列确定修复方向；旧库行的该列为 NULL。
            val stamped = filteredToUpsert.map { item ->
                item.toEntity().copy(
                    originTermId = item.originTermId ?: item.termId
                )
            }
            dao.upsertExamSchedules(stamped)
            // 权威重同步去重：同一场考试（内容指纹一致）若在其他学期遗留无来源旧行，
            // 视为历史错标残留并在同事务内清除+审计，保证修复后不存在重复考试。
            // 但自动删除以「会话身份充足」为门槛：明确考试日期时间 + 可信管线稳定 id；
            // 「时间待定」考试可在两学期字段全等撞指纹——证据不足时两行都保留并记 REVIEW_REQUIRED，
            // 绝不因字段同为空而误删合法的跨学期待定考试。
            val staleFingerprints = mutableMapOf<String, ExamScheduleEntity>()
            dao.getExamSchedulesSnapshot()
                .filter { it.originTermId.isNullOrBlank() }
                .forEach { staleFingerprints[examFingerprint(it)] = it }
            val auditsByRow = dao.getExamTermRepairAuditsSnapshot()
                .filter { it.ruleVersion == EXAM_TERM_REPAIR_RULE_V2 }
                .associateBy { it.examRowId }
            val nowMs = System.currentTimeMillis()
            val mergedAudits = mutableListOf<ExamTermRepairAuditEntity>()
            for (incoming in stamped) {
                val stale = staleFingerprints[examFingerprint(incoming)] ?: continue
                if (stale.id == incoming.id || stale.termId == incoming.termId) continue
                if (!hasTrustedExamSessionIdentity(incoming)) {
                    val prior = auditsByRow[stale.id]
                    if (prior?.disposition != ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED ||
                        prior.previousTermId != stale.termId
                    ) {
                        mergedAudits += ExamTermRepairAuditEntity(
                            id = "v2:${stale.id}",
                            examRowId = stale.id,
                            previousTermId = stale.termId,
                            newTermId = null,
                            evidenceRef = "fingerprint_match_insufficient_session_identity:${incoming.id}",
                            ruleVersion = EXAM_TERM_REPAIR_RULE_V2,
                            disposition = ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED,
                            repairedAtEpochMilli = nowMs
                        )
                    }
                    continue
                }
                dao.deleteExamScheduleById(stale.id)
                mergedAudits += ExamTermRepairAuditEntity(
                    id = "v2:${stale.id}",
                    examRowId = stale.id,
                    previousTermId = stale.termId,
                    newTermId = incoming.termId,
                    evidenceRef = "authoritative_resync_fingerprint:${incoming.id}",
                    ruleVersion = EXAM_TERM_REPAIR_RULE_V2,
                    disposition = ExamTermRepairAuditEntity.DISPOSITION_MERGED_DUPLICATE,
                    repairedAtEpochMilli = nowMs
                )
            }
            if (mergedAudits.isNotEmpty()) {
                dao.upsertExamTermRepairAudits(mergedAudits)
            }
            }
        }
        notifyScheduleOrPolicyChanged("EXAMS_IMPORTED")
    }


    suspend fun getExamSchedulesSnapshot(): List<ExamScheduleItem> {
        return dao.getExamSchedulesSnapshot().map { it.toDomain() }
    }
}
