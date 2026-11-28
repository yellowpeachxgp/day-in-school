package cn.edu.haut.campus.core.knowledge

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
 * blob_store 行是不可变内容；每次写入都包一层 versioned-document 信封（写入时间与 nonce），
 * 查询按 SQLite rowid 的插入顺序取最后有效版本，即使测试或快速连续写入共享同一毫秒也不会
 * 随机选错 head。逻辑快照只取 envelope.document，不把 volatile 写信封泄进 entity_journal。
 * 该结构是 W3-04 在 v13–v15
 * 未按序合入前的过渡落地，后续 Room v16 结构化表迁移不得重写这些 blob 的内容身份。
 */
internal class VersionedBlobDocumentStore(
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
            .filter { row -> row.inKnowledgeScope() && row.mimeType == mimeType }
            .map(::decodeEnvelope)
            .lastOrNull()
    }

    private fun decodeEnvelope(row: BlobStoreEntity): VersionedDocument {
        val obj = Json.parse(row.content.toString(Charsets.UTF_8)).asObjOrNull
            ?: throw IllegalArgumentException("版本化知识文档必须是 object")
        require(obj.requiredString("format") == VERSIONED_DOCUMENT_FORMAT) { "版本化知识文档格式不受支持" }
        val document = obj.requiredObject("document")
        return VersionedDocument(
            ownerId = obj.requiredString("ownerId"),
            writeAtEpochMs = obj.requiredLong("writeAtEpochMs"),
            writeNonce = obj.requiredString("writeNonce"),
            document = document
        )
    }

    private fun BlobStoreEntity.inKnowledgeScope(): Boolean =
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
        const val VERSIONED_DOCUMENT_FORMAT = "versioned-knowledge-document/v1"
        const val MATERIAL_MIME = "application/vnd.dis.course-material+json"
        const val REVIEW_TASK_MIME = "application/vnd.dis.review-task+json"
    }
}
