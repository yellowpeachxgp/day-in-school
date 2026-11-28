package cn.edu.haut.campus.core.degree

import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.vault.EntitySnapshotStore
import java.time.Instant

/**
 * training_plan_imports 的过渡 Vault snapshot store（AUTO-042）；物理版本在 blob_store。
 * 删除语义与 W3-04 一致：写 deleted 标记文档而不是抹除历史，journal 撤销按快照回放。
 */
internal class TrainingPlanImportSnapshotStore(
    private val documents: VersionedDegreeDocumentStore,
    private val scope: SchoolAccountScope,
    private val clock: () -> Instant = Instant::now
) : EntitySnapshotStore {
    override val type: EntityType = EntityType.TRAINING_PLAN_IMPORT

    override suspend fun read(ref: EntityRef): CanonicalJson? {
        requireValidRef(ref)
        val document = documents.readDocument(ref.id) ?: return null
        if (isDeletedDocument(document)) return null
        return CanonicalJson.of(canonicalJson(document))
    }

    override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) {
        requireValidRef(ref)
        if (snapshot == null) {
            val current = documents.readDocument(ref.id)
            val revision = current?.nullableLong("revision") ?: 0L
            documents.writeDocument(
                ownerId = ref.id,
                document = jsonObjectOf(
                    "format" to JsonValue.Str(PLAN_IMPORT_FORMAT),
                    "deleted" to jsonBool(true),
                    "id" to JsonValue.Str(ref.id),
                    "scope" to encodeScope(scope),
                    "revision" to JsonValue.Num((revision + 1L).toString()),
                    "updatedAt" to JsonValue.Str(clock().toString())
                )
            )
            return
        }
        val obj = Json.parse(snapshot.text).asObjOrNull
            ?: throw IllegalArgumentException("培养方案导入快照必须是 object")
        require(obj.requiredString("id") == ref.id) { "培养方案导入快照 id 与 EntityRef 不一致" }
        require(decodeScope(obj.requiredField("scope")) == ref.scope) { "培养方案导入快照 scope 与 EntityRef 不一致" }
        documents.writeDocument(ref.id, obj)
    }

    private fun requireValidRef(ref: EntityRef) {
        require(ref.type == type) { "TrainingPlanImportSnapshotStore 只能处理 TRAINING_PLAN_IMPORT" }
        require(ref.scope == scope) { "TrainingPlanImportSnapshotStore 只能处理本 profile scope" }
    }
}

/** audit_overrides 的过渡 Vault snapshot store；人工认定的写入与撤销全部经 journal 回放。 */
internal class AuditOverrideSnapshotStore(
    private val documents: VersionedDegreeDocumentStore,
    private val scope: SchoolAccountScope,
    private val clock: () -> Instant = Instant::now
) : EntitySnapshotStore {
    override val type: EntityType = EntityType.AUDIT_OVERRIDE

    override suspend fun read(ref: EntityRef): CanonicalJson? {
        requireValidRef(ref)
        val document = documents.readDocument(ref.id) ?: return null
        if (isDeletedDocument(document)) return null
        return CanonicalJson.of(canonicalJson(document))
    }

    override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) {
        requireValidRef(ref)
        if (snapshot == null) {
            val current = documents.readDocument(ref.id)
            val revision = current?.nullableLong("revision") ?: 0L
            documents.writeDocument(
                ownerId = ref.id,
                document = jsonObjectOf(
                    "format" to JsonValue.Str(AUDIT_OVERRIDE_FORMAT),
                    "deleted" to jsonBool(true),
                    "id" to JsonValue.Str(ref.id),
                    "scope" to encodeScope(scope),
                    "revision" to JsonValue.Num((revision + 1L).toString()),
                    "updatedAt" to JsonValue.Str(clock().toString())
                )
            )
            return
        }
        val obj = Json.parse(snapshot.text).asObjOrNull
            ?: throw IllegalArgumentException("人工认定快照必须是 object")
        require(obj.requiredString("id") == ref.id) { "人工认定快照 id 与 EntityRef 不一致" }
        require(decodeScope(obj.requiredField("scope")) == ref.scope) { "人工认定快照 scope 与 EntityRef 不一致" }
        documents.writeDocument(ref.id, obj)
    }

    private fun requireValidRef(ref: EntityRef) {
        require(ref.type == type) { "AuditOverrideSnapshotStore 只能处理 AUDIT_OVERRIDE" }
        require(ref.scope == scope) { "AuditOverrideSnapshotStore 只能处理本 profile scope" }
    }
}

internal const val PLAN_IMPORT_FORMAT = "training-plan-import/v1"
internal const val AUDIT_OVERRIDE_FORMAT = "audit-override/v1"

internal fun isDeletedDocument(document: JsonValue.Obj): Boolean =
    (document.obj("deleted") as? JsonValue.Bool)?.value == true

internal fun encodeScope(scope: SchoolAccountScope): JsonValue.Obj {
    val fields = linkedMapOf<String, JsonValue>()
    fields["schoolId"] = JsonValue.Str(scope.schoolId.value)
    fields["accountId"] = JsonValue.Str(scope.accountId.value)
    fields["profileId"] = JsonValue.Str(scope.profileId.value)
    scope.campusId?.let { fields["campusId"] = JsonValue.Str(it.value) }
    return JsonValue.Obj(fields)
}

internal fun decodeScope(value: JsonValue): SchoolAccountScope {
    val obj = value.asObjOrNull ?: throw IllegalArgumentException("scope 必须是 object")
    return SchoolAccountScope(
        schoolId = cn.edu.haut.campus.core.model.SchoolId(obj.requiredString("schoolId")),
        accountId = cn.edu.haut.campus.core.model.AccountId(obj.requiredString("accountId")),
        profileId = cn.edu.haut.campus.core.model.ProfileId(obj.requiredString("profileId")),
        campusId = obj.str("campusId")?.let { cn.edu.haut.campus.core.model.CampusId(it) }
    )
}
