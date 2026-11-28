package cn.edu.haut.campus.core.degree

import androidx.room.withTransaction
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
 * 逻辑文档本体（可能接近 JSON 8 MiB 上限）作为不可变原件单独写入 blob_store；
 * 版本信封只保存小型引用、writeSequence 与写入元数据。Vault journal 快照再进一步只存
 * 文档 blob 引用/摘要，因此大方案不会触碰 64 KiB 快照硬限。writeSequence 随信封持久化，
 * 备份恢复后不再依赖 rowid/blobId 的物理顺序判断最新版本。
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
    private val documentMimeType = mimeType.removeSuffix("+json") + "-document+json"

    suspend fun writeDocument(ownerId: String, document: JsonValue.Obj) {
        database.withTransaction {
            val canonicalDocument = canonicalJson(document).toByteArray(Charsets.UTF_8)
            val documentRef = blobStore.put(
                content = canonicalDocument,
                mimeType = documentMimeType,
                scope = scope,
                owner = EntityRef(ownerType, ownerId, scope)
            )
            val rows = dao.getBlobsForOwner(
                schoolId = scope.schoolId.value,
                accountId = scope.accountId.value,
                profileId = scope.profileId.value,
                ownerEntityType = ownerType.name,
                ownerEntityId = ownerId
            )
            val nextSequence = rows.asSequence()
                .filter { it.inScope() && it.mimeType == mimeType }
                .mapNotNull { row -> runCatching { decodeEnvelope(row).writeSequence }.getOrNull() }
                .maxOrNull()
                ?.plus(1L) ?: 1L
            val now = clock()
            val envelope = jsonObjectOf(
                "format" to JsonValue.Str(VERSIONED_DOCUMENT_FORMAT_V2),
                "ownerId" to JsonValue.Str(ownerId),
                "writeSequence" to JsonValue.Num(nextSequence.toString()),
                "writeAtEpochMs" to JsonValue.Num(now.toEpochMilli().toString()),
                "writeNonce" to JsonValue.Str(nonceGenerator()),
                "documentRef" to jsonObjectOf(
                    "blobId" to JsonValue.Str(documentRef.blobId),
                    "sha256" to JsonValue.Str(documentRef.sha256),
                    "byteCount" to JsonValue.Num(documentRef.byteCount.toString()),
                    "mimeType" to JsonValue.Str(documentRef.mimeType)
                )
            )
            blobStore.put(
                content = canonicalJson(envelope).toByteArray(Charsets.UTF_8),
                mimeType = mimeType,
                scope = scope,
                owner = EntityRef(ownerType, ownerId, scope)
            )
        }
    }

    suspend fun readDocument(ownerId: String): JsonValue.Obj? {
        val rows = dao.getBlobsForOwner(
            schoolId = scope.schoolId.value,
            accountId = scope.accountId.value,
            profileId = scope.profileId.value,
            ownerEntityType = ownerType.name,
            ownerEntityId = ownerId
        )
        val envelope = latestEnvelope(rows) ?: return null
        return loadDocument(envelope)
    }

    suspend fun listDocuments(): Map<String, JsonValue.Obj> {
        val rows = dao.getBlobsByOwnerType(
            schoolId = scope.schoolId.value,
            accountId = scope.accountId.value,
            profileId = scope.profileId.value,
            ownerEntityType = ownerType.name
        )
        val grouped = rows.filter { it.ownerEntityId != null && it.mimeType == mimeType }
            .groupBy { it.ownerEntityId.orEmpty() }
        val result = LinkedHashMap<String, JsonValue.Obj>()
        grouped.forEach { (ownerId, versions) ->
            latestEnvelope(versions)?.let { envelope -> result[ownerId] = loadDocument(envelope) }
        }
        return result
    }

    /**
     * 读取当前逻辑状态的小型 journal 快照。快照只含文档 blob 引用、摘要与少量元数据；
     * 同一逻辑文档重写为新 writeSequence 后快照文本仍完全一致， revert invariant 可精确校验。
     */
    suspend fun readSnapshot(ownerId: String): JsonValue.Obj? {
        val rows = dao.getBlobsForOwner(
            schoolId = scope.schoolId.value,
            accountId = scope.accountId.value,
            profileId = scope.profileId.value,
            ownerEntityType = ownerType.name,
            ownerEntityId = ownerId
        )
        val envelope = latestEnvelope(rows) ?: return null
        val ref = envelope.documentRef
            ?: throw IllegalStateException("遗留 v1 内联文档不能直接作为大对象安全快照")
        val document = loadDocument(envelope)
        val fields = linkedMapOf<String, JsonValue>()
        fields["format"] = JsonValue.Str(DEGREE_SNAPSHOT_FORMAT)
        fields["ownerId"] = JsonValue.Str(ownerId)
        fields["documentBlobId"] = JsonValue.Str(ref.blobId)
        fields["documentSha256"] = JsonValue.Str(ref.sha256)
        fields["documentByteCount"] = JsonValue.Num(ref.byteCount.toString())
        fields["documentFormat"] = JsonValue.Str(document.str("format") ?: "unknown")
        fields["revision"] = JsonValue.Num((document.nullableLong("revision") ?: 0L).toString())
        fields["deleted"] = jsonBool(isDeletedDocument(document))
        val summary = linkedMapOf<String, JsonValue>()
        document.str("title")?.let { summary["title"] = JsonValue.Str(it.take(128)) }
        document.str("action")?.let { summary["action"] = JsonValue.Str(it) }
        document.str("planId")?.let { summary["planId"] = JsonValue.Str(it) }
        document.str("planVersion")?.let { summary["planVersion"] = JsonValue.Str(it) }
        if (summary.isNotEmpty()) fields["summary"] = JsonValue.Obj(summary)
        return JsonValue.Obj(fields)
    }

    /** 按 journal 快照解析原始逻辑文档；引用缺失或摘要不符时 fail-closed。 */
    suspend fun resolveSnapshotDocument(snapshot: CanonicalSnapshotText, ownerId: String): JsonValue.Obj {
        val obj = Json.parse(snapshot.text).asObjOrNull
            ?: throw IllegalArgumentException("毕业审计 journal 快照必须是 object")
        require(obj.requiredString("format") == DEGREE_SNAPSHOT_FORMAT) { "毕业审计 journal 快照格式不受支持" }
        require(obj.requiredString("ownerId") == ownerId) { "毕业审计 journal 快照 owner 与 EntityRef 不一致" }
        val blobId = obj.requiredString("documentBlobId")
        val expectedSha = obj.requiredString("documentSha256")
        val expectedBytes = obj.requiredLong("documentByteCount")
        val blob = blobStore.get(blobId, scope)
            ?: throw IllegalStateException("journal 快照引用的文档 blob 不存在: " + blobId)
        require(blob.ref.sha256 == expectedSha) { "journal 快照引用的文档摘要不一致" }
        require(blob.ref.byteCount == expectedBytes) { "journal 快照引用的文档大小不一致" }
        return Json.parse(blob.content().toString(Charsets.UTF_8)).asObjOrNull
            ?: throw IllegalArgumentException("journal 快照引用的逻辑文档必须是 object")
    }

    private suspend fun loadDocument(envelope: VersionedDocument): JsonValue.Obj {
        envelope.inlineDocument?.let { return it }
        val ref = envelope.documentRef ?: throw IllegalArgumentException("版本化毕业审计文档缺少 documentRef")
        val blob = blobStore.get(ref.blobId, scope)
            ?: throw IllegalStateException("版本化文档引用的 blob 不存在: " + ref.blobId)
        require(blob.ref.sha256 == ref.sha256) { "版本化文档引用摘要不一致" }
        require(blob.ref.byteCount == ref.byteCount) { "版本化文档引用大小不一致" }
        require(blob.ref.mimeType == ref.mimeType) { "版本化文档引用 MIME 不一致" }
        return Json.parse(blob.content().toString(Charsets.UTF_8)).asObjOrNull
            ?: throw IllegalArgumentException("版本化毕业审计文档必须是 object")
    }

    private fun latestEnvelope(rows: List<BlobStoreEntity>): VersionedDocument? {
        return rows.asSequence()
            .filter { row -> row.inScope() && row.mimeType == mimeType }
            .map(::decodeEnvelope)
            .maxWithOrNull(
                compareBy<VersionedDocument> { it.writeSequence }
                    .thenBy { it.writeAtEpochMs }
                    .thenBy { it.writeNonce }
            )
    }

    private fun decodeEnvelope(row: BlobStoreEntity): VersionedDocument {
        val obj = Json.parse(row.content.toString(Charsets.UTF_8)).asObjOrNull
            ?: throw IllegalArgumentException("版本化毕业审计文档必须是 object")
        val format = obj.requiredString("format")
        require(format == VERSIONED_DOCUMENT_FORMAT_V2 || format == VERSIONED_DOCUMENT_FORMAT_V1) {
            "版本化毕业审计文档格式不受支持"
        }
        val refObj = obj.obj("documentRef")?.asObjOrNull
        val ref = refObj?.let {
            DocumentRef(
                blobId = it.requiredString("blobId"),
                sha256 = it.requiredString("sha256"),
                byteCount = it.requiredLong("byteCount"),
                mimeType = it.requiredString("mimeType")
            )
        }
        return VersionedDocument(
            ownerId = obj.requiredString("ownerId"),
            writeSequence = obj.nullableLong("writeSequence") ?: 0L,
            writeAtEpochMs = obj.requiredLong("writeAtEpochMs"),
            writeNonce = obj.requiredString("writeNonce"),
            inlineDocument = obj.obj("document")?.asObjOrNull,
            documentRef = ref
        )
    }

    private fun BlobStoreEntity.inScope(): Boolean =
        scopeSchoolId == scope.schoolId.value &&
            scopeAccountId == scope.accountId.value &&
            scopeProfileId == scope.profileId.value &&
            scopeCampusId == scope.campusId?.value

    private data class VersionedDocument(
        val ownerId: String,
        val writeSequence: Long,
        val writeAtEpochMs: Long,
        val writeNonce: String,
        val inlineDocument: JsonValue.Obj?,
        val documentRef: DocumentRef?
    )

    private data class DocumentRef(
        val blobId: String,
        val sha256: String,
        val byteCount: Long,
        val mimeType: String
    )

    companion object {
        const val VERSIONED_DOCUMENT_FORMAT_V1 = "versioned-degree-document/v1"
        const val VERSIONED_DOCUMENT_FORMAT_V2 = "versioned-degree-document/v2"
        const val DEGREE_SNAPSHOT_FORMAT = "degree-document-snapshot/v1"
        const val PLAN_IMPORT_MIME = "application/vnd.dis.training-plan-import+json"
        const val AUDIT_OVERRIDE_MIME = "application/vnd.dis.audit-override+json"
        const val ACTIVE_POINTER_OWNER = "active-plan-pointer"
    }
}

/** resolveSnapshotDocument 只依赖 canonical 文本，避免把 core:events 类型暴露给文档存储调用方。 */
internal data class CanonicalSnapshotText(val text: String)

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
