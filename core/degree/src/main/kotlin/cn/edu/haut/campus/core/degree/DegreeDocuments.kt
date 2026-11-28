package cn.edu.haut.campus.core.degree

import cn.edu.haut.campus.core.database.BlobStoreEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.vault.VaultBlobStore
import java.time.Instant
import java.util.UUID

/**
 * 不新增 Room 表的版本化文档存储。
 *
 * 与 W3-04 core:knowledge 的 VersionedBlobDocumentStore 同构：在 v14/v15 未按序合入前
 * 各自模块暂存一份过渡实现（AUTO-042），后续 Room v15 显式迁移窗口统一收敛并保证
 * 不重写这些 blob 的内容身份。逻辑快照只取 envelope.document，写信封不进 entity_journal。
 */
internal class VersionedDegreeDocumentStore(
    private val database: HautDatabase,
    private val blobStore: VaultBlobStore,
    private val scope: SchoolAccountScope,
    private val ownerType: EntityType,
    private val mimeType: String,
    private val clock: () -> Instant = Instant::now,
    private val nonceGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    private val dao = database.vaultDao()

    suspend fun writeDocument(ownerId: String, document: JsonValue.Obj) {
        val now = clock()
        val envelope = jsonObjectOf(
            "format" to JsonValue.Str(VERSIONED_DOCUMENT_FORMAT),
            "ownerId" to JsonValue.Str(ownerId),
            "writeAtEpochMs" to JsonValue.Num(now.toEpochMilli().toString()),
            "writeNonce" to JsonValue.Str(nonceGenerator()),
            "document" to document
        )
        blobStore.put(
            content = canonicalJson(envelope).toByteArray(Charsets.UTF_8),
            mimeType = mimeType,
            scope = scope,
            owner = EntityRef(ownerType, ownerId, scope)
        )
    }

    suspend fun readDocument(ownerId: String): JsonValue.Obj? {
        val rows = dao.getBlobsForOwner(
            schoolId = scope.schoolId.value,
            accountId = scope.accountId.value,
            profileId = scope.profileId.value,
            ownerEntityType = ownerType.name,
            ownerEntityId = ownerId
        )
        return latestEnvelope(rows)?.document
    }

    suspend fun listDocuments(): Map<String, JsonValue.Obj> {
        val rows = dao.getBlobsByOwnerType(
            schoolId = scope.schoolId.value,
            accountId = scope.accountId.value,
            profileId = scope.profileId.value,
            ownerEntityType = ownerType.name
        )
        val grouped = rows.filter { it.ownerEntityId != null }.groupBy { it.ownerEntityId.orEmpty() }
        val result = LinkedHashMap<String, JsonValue.Obj>()
        grouped.forEach { (ownerId, versions) ->
            latestEnvelope(versions)?.let { result[ownerId] = it.document }
        }
        return result
    }

    private fun latestEnvelope(rows: List<BlobStoreEntity>): VersionedDocument? {
        return rows.asSequence()
            .filter { row -> row.inScope() && row.mimeType == mimeType }
            .map(::decodeEnvelope)
            .lastOrNull()
    }

    private fun decodeEnvelope(row: BlobStoreEntity): VersionedDocument {
        val obj = Json.parse(row.content.toString(Charsets.UTF_8)).asObjOrNull
            ?: throw IllegalArgumentException("版本化毕业审计文档必须是 object")
        require(obj.requiredString("format") == VERSIONED_DOCUMENT_FORMAT) { "版本化毕业审计文档格式不受支持" }
        return VersionedDocument(
            ownerId = obj.requiredString("ownerId"),
            writeAtEpochMs = obj.requiredLong("writeAtEpochMs"),
            writeNonce = obj.requiredString("writeNonce"),
            document = obj.requiredObject("document")
        )
    }

    private fun BlobStoreEntity.inScope(): Boolean =
        scopeSchoolId == scope.schoolId.value &&
            scopeAccountId == scope.accountId.value &&
            scopeProfileId == scope.profileId.value &&
            scopeCampusId == scope.campusId?.value

    private data class VersionedDocument(
        val ownerId: String,
        val writeAtEpochMs: Long,
        val writeNonce: String,
        val document: JsonValue.Obj
    )

    companion object {
        const val VERSIONED_DOCUMENT_FORMAT = "versioned-degree-document/v1"
        const val PLAN_IMPORT_MIME = "application/vnd.dis.training-plan-import+json"
        const val AUDIT_OVERRIDE_MIME = "application/vnd.dis.audit-override+json"
        const val ACTIVE_POINTER_OWNER = "active-plan-pointer"
    }
}

internal fun jsonObjectOf(vararg entries: Pair<String, JsonValue>): JsonValue.Obj {
    val fields = linkedMapOf<String, JsonValue>()
    entries.forEach { (key, value) -> fields[key] = value }
    return JsonValue.Obj(fields)
}

internal fun canonicalJson(value: JsonValue.Obj): String = Json.canonical(value)

internal fun jsonBool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)

internal fun JsonValue.Obj.requiredString(key: String): String =
    str(key) ?: throw IllegalArgumentException("文档缺少字符串字段 " + key)

internal fun JsonValue.Obj.requiredLong(key: String): Long {
    val raw = obj(key) ?: throw IllegalArgumentException("文档缺少数字字段 " + key)
    return when (raw) {
        is JsonValue.Num -> raw.literal.toLongOrNull() ?: throw IllegalArgumentException("字段 " + key + " 不是整数")
        else -> throw IllegalArgumentException("字段 " + key + " 不是数字")
    }
}

internal fun JsonValue.Obj.requiredObject(key: String): JsonValue.Obj =
    obj(key)?.asObjOrNull ?: throw IllegalArgumentException("文档缺少 object 字段 " + key)

internal fun JsonValue.Obj.requiredField(key: String): JsonValue =
    obj(key) ?: throw IllegalArgumentException("文档缺少字段 " + key)

internal fun JsonValue.Obj.nullableLong(key: String): Long? {
    val raw = obj(key) as? JsonValue.Num ?: return null
    return raw.literal.toLongOrNull()
}
