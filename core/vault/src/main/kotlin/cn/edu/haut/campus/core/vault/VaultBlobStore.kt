package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.database.BlobStoreEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.security.MessageDigest
import java.time.Instant

/** blob_store 的稳定元数据引用；内容字节只在 StoredBlob.content() 中防御性复制返回。 */
data class StoredBlobRef(
    val blobId: String,
    val scope: SchoolAccountScope,
    val sha256: String,
    val mimeType: String,
    val byteCount: Long,
    val owner: EntityRef?
) {
    init {
        require(blobId.startsWith("blob-") && blobId.length <= 128) { "blobId 非法" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "blob sha256 必须是 64 位小写 hex" }
        require(mimeType.isNotBlank() && mimeType.length <= 128 && mimeType.none { it.isISOControl() || it.isWhitespace() }) {
            "blob mimeType 非法"
        }
        require(byteCount >= 0) { "blob byteCount 不能为负" }
        require(owner == null || owner.scope == scope) { "blob owner 必须同 scope" }
    }
}

class StoredBlob(
    val ref: StoredBlobRef,
    content: ByteArray,
    val createdAt: Instant
) {
    private val bytes = content.copyOf()
    fun content(): ByteArray = bytes.copyOf()
}

/** 按 scope 隔离的内容寻址 blob store；blob_store 只存字节、摘要和 owner 引用，不存凭据元数据。 */
class VaultBlobStore(
    database: HautDatabase,
    private val clock: () -> Instant = Instant::now,
    private val maxBytes: Int = 16 * 1024 * 1024
) {
    private val dao = database.vaultDao()

    suspend fun put(
        content: ByteArray,
        mimeType: String,
        scope: SchoolAccountScope,
        owner: EntityRef? = null
    ): StoredBlobRef {
        require(content.size <= maxBytes) { "blob 超过大小上限" }
        require(owner == null || owner.scope == scope) { "blob owner 必须同 scope" }
        val sha256 = sha256Hex(content)
        val blobId = blobIdFor(scope, sha256)
        val ref = StoredBlobRef(
            blobId = blobId,
            scope = scope,
            sha256 = sha256,
            mimeType = mimeType,
            byteCount = content.size.toLong(),
            owner = owner
        )
        val existing = dao.getBlob(blobId)
        if (existing == null) {
            dao.upsertBlob(
                BlobStoreEntity(
                    blobId = blobId,
                    scopeSchoolId = scope.schoolId.value,
                    scopeAccountId = scope.accountId.value,
                    scopeProfileId = scope.profileId.value,
                    scopeCampusId = scope.campusId?.value,
                    sha256 = sha256,
                    mimeType = mimeType,
                    byteCount = content.size.toLong(),
                    content = content.copyOf(),
                    ownerEntityType = owner?.type?.name,
                    ownerEntityId = owner?.id,
                    createdAtEpochMs = clock().toEpochMilli()
                )
            )
        } else {
            require(existing.sha256 == sha256) { "blobId 与 sha256 不一致" }
            require(existing.byteCount == content.size.toLong()) { "blob byteCount 与内容不一致" }
            // v1 每行只有单 mimeType/单 owner 元数据；遇到同字节不同元数据时 fail-closed，
            // 不得返回一行与持久化事实不一致的 StoredBlobRef。多 owner 分享需后续显式建模。
            require(existing.mimeType == mimeType) { "同内容 blob 的 mimeType 冲突" }
            require(existing.ownerEntityType == owner?.type?.name && existing.ownerEntityId == owner?.id) {
                "同内容 blob 的 owner 冲突"
            }
        }
        return ref
    }

    suspend fun get(blobId: String, scope: SchoolAccountScope): StoredBlob? {
        val row = dao.getBlob(blobId) ?: return null
        if (!row.inScope(scope)) return null
        val ownerType = row.ownerEntityType
        val ownerId = row.ownerEntityId
        val owner = if (ownerType != null && ownerId != null) {
            EntityRef(cn.edu.haut.campus.core.model.EntityType.valueOf(ownerType), ownerId, scope)
        } else {
            null
        }
        return StoredBlob(
            ref = StoredBlobRef(
                blobId = row.blobId,
                scope = scope,
                sha256 = row.sha256,
                mimeType = row.mimeType,
                byteCount = row.byteCount,
                owner = owner
            ),
            content = row.content,
            createdAt = Instant.ofEpochMilli(row.createdAtEpochMs)
        )
    }

    suspend fun delete(blobId: String, scope: SchoolAccountScope): Boolean {
        val row = dao.getBlob(blobId) ?: return false
        if (!row.inScope(scope)) return false
        return dao.deleteBlob(blobId) == 1
    }

    suspend fun count(): Int = dao.countBlobStore()
    suspend fun totalBytes(): Long = dao.totalBlobBytes()

    private fun BlobStoreEntity.inScope(scope: SchoolAccountScope): Boolean =
        scopeSchoolId == scope.schoolId.value &&
            scopeAccountId == scope.accountId.value &&
            scopeProfileId == scope.profileId.value &&
            scopeCampusId == scope.campusId?.value

    private fun blobIdFor(scope: SchoolAccountScope, sha256: String): String {
        val scopeDigest = sha256Hex(VaultScopeKeyCodec.encode(scope).toByteArray(Charsets.UTF_8)).take(16)
        return "blob-" + scopeDigest + "-" + sha256.take(32)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
