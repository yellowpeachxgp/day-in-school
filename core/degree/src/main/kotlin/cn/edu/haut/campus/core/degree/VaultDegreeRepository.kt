package cn.edu.haut.campus.core.degree

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.plan.AuditContext
import cn.edu.haut.campus.core.plan.AuditOverride
import cn.edu.haut.campus.core.plan.AuditReport
import cn.edu.haut.campus.core.plan.DegreeAuditor
import cn.edu.haut.campus.core.plan.ImportInput
import cn.edu.haut.campus.core.plan.OverrideAction
import cn.edu.haut.campus.core.plan.PlanSource
import cn.edu.haut.campus.core.plan.TrainingPlan
import cn.edu.haut.campus.core.plan.TrainingPlanCodec
import cn.edu.haut.campus.core.plan.TrainingPlanImporter
import cn.edu.haut.campus.core.vault.EntityWriter
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * W3-01 毕业审计的 Vault 实现（AUTO-042 过渡）。
 *
 * 方案导入与人工认定都是版本化 canonical JSON 文档，物理保存在既有 blob_store，
 * 全部写操作经 EntityWriter 进 journal：人工认定的依据、操作者与时间可在变更历史展开，
 * 也可按 journal 撤销。激活指针是小型非实体状态，不进 journal（语义类似 UI 偏好），
 * 同样随 profile 擦除。
 */
class VaultDegreeRepository internal constructor(
    private val scope: SchoolAccountScope,
    private val writer: EntityWriter,
    private val planDocuments: VersionedDegreeDocumentStore,
    private val overrideDocuments: VersionedDegreeDocumentStore,
    private val clock: () -> Instant = Instant::now,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val writeGroupIdGenerator: () -> String = { UUID.randomUUID().toString() },
    private val ownedCloseables: List<AutoCloseable> = emptyList()
) : DegreeRepository {
    private val overrideClockFloor = AtomicLong(0L)

    override fun close() {
        ownedCloseables.forEach { it.close() }
    }

    override suspend fun importPlan(input: ImportInput, actor: Actor): TrainingPlanImportRecord {
        val parsed = TrainingPlanImporter.import(input)
        val importId = "tpl-" + parsed.contentDigest.take(16)
        val now = clock()
        // 幂等：同一内容重复导入不重写文档，避免 revision 递增制造 journal 审计噪声。
        val existing = planDocuments.readDocument(importId)
        if (existing != null && !isDeletedDocument(existing) && existing.str("contentDigest") == parsed.contentDigest) {
            val record = listImports().firstOrNull { it.importId == importId }
            if (record != null) return record
        }
        val ref = EntityRef(EntityType.TRAINING_PLAN_IMPORT, importId, scope)
        writer.write(
            ref = ref,
            actor = actor,
            actorDetail = "training-plan-import",
            writeGroupId = "degree-import-" + writeGroupIdGenerator()
        ) {
            val current = planDocuments.readDocument(importId)
            val revision = current?.nullableLong("revision") ?: 0L
            val confirmed = (current?.obj("confirmedForStudent") as? JsonValue.Bool)?.value == true
            planDocuments.writeDocument(
                ownerId = importId,
                document = encodeImportDocument(
                    importId = importId,
                    parsed = parsed,
                    confirmedForStudent = confirmed,
                    importedAt = now,
                    actor = actor,
                    revision = revision + 1L
                )
            )
        }
        // 首次导入自动激活，后续重复导入不改变激活选择
        val pointer = readActivePointer()
        if (pointer == null) {
            setActiveImport(importId, actor)
        }
        return checkNotNull(listImports().firstOrNull { it.importId == importId }) {
            "导入写入后列表必须能读到该记录"
        }
    }

    override suspend fun listImports(): List<TrainingPlanImportRecord> {
        val documents = planDocuments.listDocuments()
        val activeId = readActivePointer()
        return documents.entries
            .filter { (ownerId, _) -> ownerId != VersionedDegreeDocumentStore.ACTIVE_POINTER_OWNER }
            .mapNotNull { (_, document) -> decodeImportDocument(document) }
            .map { it.copy(active = it.importId == activeId) }
            .sortedBy { it.importedAt }
    }

    override suspend fun loadPlan(importId: String): TrainingPlan? {
        val document = planDocuments.readDocument(importId) ?: return null
        if (isDeletedDocument(document)) return null
        val record = decodeImportDocument(document) ?: return null
        val planObj = document.obj("planDocument")?.asObjOrNull ?: return null
        val plan = TrainingPlanCodec.fromJson(Json.canonical(planObj))
        return plan.copy(source = record.source, confirmedForStudent = record.confirmedForStudent)
    }

    override suspend fun setConfirmedForStudent(importId: String, confirmed: Boolean, actor: Actor) {
        val ref = EntityRef(EntityType.TRAINING_PLAN_IMPORT, importId, scope)
        writer.write(
            ref = ref,
            actor = actor,
            actorDetail = if (confirmed) "training-plan-confirm" else "training-plan-unconfirm",
            writeGroupId = "degree-confirm-" + writeGroupIdGenerator()
        ) {
            val current = planDocuments.readDocument(importId)
                ?: throw IllegalArgumentException("培养方案导入不存在: " + importId)
            require(!isDeletedDocument(current)) { "培养方案导入已删除: " + importId }
            val revision = current.nullableLong("revision") ?: 0L
            // 确认适用时把 UNKNOWN 升级为 USER_CONFIRMED：文件导入本身没有官方来源背书，
            // 用户的确认行为就是来源升级的唯一通道；取消确认不回退来源，信任级别由 confirmedForStudent 控制。
            val upgradedSource = if (confirmed && current.str("source") == PlanSource.UNKNOWN.name) {
                PlanSource.USER_CONFIRMED.name
            } else {
                current.str("source") ?: PlanSource.UNKNOWN.name
            }
            val updated = current.copy(
                fields = current.fields + ("confirmedForStudent" to jsonBool(confirmed)) +
                    ("source" to JsonValue.Str(upgradedSource)) +
                    ("revision" to JsonValue.Num((revision + 1L).toString())) +
                    ("updatedAt" to JsonValue.Str(clock().toString()))
            )
            planDocuments.writeDocument(importId, updated)
        }
    }

    override suspend fun setActiveImport(importId: String?, actor: Actor) {
        if (importId != null) {
            require(planDocuments.readDocument(importId)?.let { !isDeletedDocument(it) } == true) {
                "不能激活不存在的培养方案导入: " + importId
            }
        }
        planDocuments.writeDocument(
            ownerId = VersionedDegreeDocumentStore.ACTIVE_POINTER_OWNER,
            document = jsonObjectOf(
                "format" to JsonValue.Str("active-plan-pointer/v1"),
                "importId" to (importId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
                "updatedAt" to JsonValue.Str(clock().toString()),
                "actor" to JsonValue.Str(actor.name)
            )
        )
    }

    override suspend fun activeImport(): TrainingPlanImportRecord? {
        val activeId = readActivePointer() ?: return null
        return listImports().firstOrNull { it.importId == activeId }
    }

    override suspend fun recordOverride(input: OverrideInput, actor: Actor): AuditOverride {
        val active = activeImport() ?: throw IllegalArgumentException("没有激活的培养方案，不能记录人工认定")
        require(input.importId == null || input.importId == active.importId) {
            "人工认定 importId 与当前激活方案不一致"
        }
        val activePlan = loadPlan(active.importId)
            ?: throw IllegalArgumentException("激活的培养方案文档不存在: " + active.importId)
        if (input.action == OverrideAction.ASSIGN) {
            val target = input.mapToGroupId?.let(activePlan::groupById)
            require(target != null) { "人工认定目标组不在当前培养方案中: " + input.mapToGroupId }
        }
        val overrideId = "ovr-" + idGenerator().replace("-", "").take(16)
        val now = nextOverrideInstant()
        val attemptRef = EntityRef(EntityType.GRADE_ATTEMPT, input.attemptId, scope)
        val override = AuditOverride(
            id = overrideId,
            attemptRef = attemptRef,
            action = input.action,
            mapToGroupId = input.mapToGroupId,
            reason = input.reason,
            actor = actor,
            at = now,
            planImportId = active.importId,
            planId = activePlan.id,
            planVersion = activePlan.version
        )
        val ref = EntityRef(EntityType.AUDIT_OVERRIDE, overrideId, scope)
        writer.write(
            ref = ref,
            actor = actor,
            actorDetail = "audit-override-" + input.action.name.lowercase(),
            writeGroupId = "degree-override-" + writeGroupIdGenerator()
        ) {
            overrideDocuments.writeDocument(
                ownerId = overrideId,
                document = encodeOverrideDocument(override, revision = 1L)
            )
        }
        return override
    }

    override suspend fun listOverrides(): List<AuditOverride> {
        return overrideDocuments.listDocuments().values
            .filter { !isDeletedDocument(it) }
            .mapNotNull { decodeOverrideDocument(it) }
            .sortedWith(compareBy({ it.at }, { it.id }))
    }

    override suspend fun auditActivePlan(
        attempts: List<GradeAttempt>,
        now: LocalDate,
        context: AuditContext
    ): AuditReport? {
        val active = activeImport() ?: return null
        val plan = loadPlan(active.importId) ?: return null
        val overrides = listOverrides()
        return DegreeAuditor.audit(plan, attempts, overrides, now, context, planImportId = active.importId)
    }

    /**
     * 认定动作是独立实体，不能依赖同一毫秒的 wall clock 或随机 ID 决定“最新”。
     * 在仓库生命周期内结合既有认定时间生成严格递增时间戳；恢复/重启后也不会早于历史认定。
     */
    private suspend fun nextOverrideInstant(): Instant {
        val candidate = clock().toEpochMilli()
        val existingMax = listOverrides().maxOfOrNull { it.at.toEpochMilli() } ?: 0L
        val next = overrideClockFloor.updateAndGet { previous ->
            maxOf(previous + 1L, candidate, existingMax + 1L)
        }
        return Instant.ofEpochMilli(next)
    }

    private suspend fun readActivePointer(): String? {
        val document = planDocuments.readDocument(VersionedDegreeDocumentStore.ACTIVE_POINTER_OWNER) ?: return null
        return when (val raw = document.obj("importId")) {
            is JsonValue.Str -> raw.value
            else -> null
        }
    }

    private fun encodeImportDocument(
        importId: String,
        parsed: cn.edu.haut.campus.core.plan.TrainingPlanImport,
        confirmedForStudent: Boolean,
        importedAt: Instant,
        actor: Actor,
        revision: Long
    ): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>()
        fields["format"] = JsonValue.Str(PLAN_IMPORT_FORMAT)
        fields["id"] = JsonValue.Str(importId)
        fields["scope"] = encodeScope(scope)
        fields["revision"] = JsonValue.Num(revision.toString())
        fields["updatedAt"] = JsonValue.Str(clock().toString())
        fields["planId"] = JsonValue.Str(parsed.plan.id)
        fields["title"] = JsonValue.Str(parsed.plan.title)
        fields["planVersion"] = JsonValue.Str(parsed.plan.version)
        fields["importFormat"] = JsonValue.Str(parsed.format)
        fields["contentDigest"] = JsonValue.Str(parsed.contentDigest)
        fields["warnings"] = JsonValue.Arr(parsed.warnings.map { JsonValue.Str(it) })
        fields["skippedRowCount"] = JsonValue.Num(parsed.skippedRowCount.toString())
        fields["source"] = JsonValue.Str(parsed.plan.source.name)
        fields["confirmedForStudent"] = jsonBool(confirmedForStudent)
        fields["importedAt"] = JsonValue.Str(importedAt.toString())
        fields["actor"] = JsonValue.Str(actor.name)
        fields["planDocument"] = TrainingPlanCodec.toJson(parsed.plan)
        return JsonValue.Obj(fields)
    }

    private fun decodeImportDocument(document: JsonValue.Obj): TrainingPlanImportRecord? {
        if (document.str("format") != PLAN_IMPORT_FORMAT) return null
        if (isDeletedDocument(document)) return null
        return TrainingPlanImportRecord(
            importId = document.requiredString("id"),
            planId = document.requiredString("planId"),
            title = document.requiredString("title"),
            planVersion = document.requiredString("planVersion"),
            format = document.requiredString("importFormat"),
            contentDigest = document.requiredString("contentDigest"),
            warnings = (document.arr("warnings") ?: emptyList()).mapNotNull { it.stringOrNull() },
            skippedRowCount = ((document.obj("skippedRowCount") as? JsonValue.Num)?.literal?.toIntOrNull()) ?: 0,
            source = PlanSource.valueOf(document.requiredString("source")),
            confirmedForStudent = (document.obj("confirmedForStudent") as? JsonValue.Bool)?.value == true,
            importedAt = Instant.parse(document.requiredString("importedAt")),
            importedBy = Actor.valueOf(document.requiredString("actor")),
            active = false
        )
    }

    private fun encodeOverrideDocument(override: AuditOverride, revision: Long): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>()
        fields["format"] = JsonValue.Str(AUDIT_OVERRIDE_FORMAT)
        fields["id"] = JsonValue.Str(override.id)
        fields["scope"] = encodeScope(scope)
        fields["revision"] = JsonValue.Num(revision.toString())
        fields["updatedAt"] = JsonValue.Str(clock().toString())
        fields["attemptEntityId"] = JsonValue.Str(override.attemptRef.id)
        fields["action"] = JsonValue.Str(override.action.name)
        override.mapToGroupId?.let { fields["mapToGroupId"] = JsonValue.Str(it) }
        fields["reason"] = JsonValue.Str(override.reason)
        fields["actor"] = JsonValue.Str(override.actor.name)
        fields["at"] = JsonValue.Str(override.at.toString())
        override.planImportId?.let { fields["planImportId"] = JsonValue.Str(it) }
        override.planId?.let { fields["planId"] = JsonValue.Str(it) }
        override.planVersion?.let { fields["planVersion"] = JsonValue.Str(it) }
        return JsonValue.Obj(fields)
    }

    private fun decodeOverrideDocument(document: JsonValue.Obj): AuditOverride? {
        if (document.str("format") != AUDIT_OVERRIDE_FORMAT) return null
        val action = OverrideAction.valueOf(document.requiredString("action"))
        return AuditOverride(
            id = document.requiredString("id"),
            attemptRef = EntityRef(EntityType.GRADE_ATTEMPT, document.requiredString("attemptEntityId"), scope),
            action = action,
            mapToGroupId = document.str("mapToGroupId"),
            reason = document.requiredString("reason"),
            actor = Actor.valueOf(document.requiredString("actor")),
            at = Instant.parse(document.requiredString("at")),
            planImportId = document.str("planImportId"),
            planId = document.str("planId"),
            planVersion = document.str("planVersion")
        )
    }
}
