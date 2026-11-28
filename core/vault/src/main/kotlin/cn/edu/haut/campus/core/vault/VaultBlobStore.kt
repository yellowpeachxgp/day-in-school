package cn.edu.haut.campus.core.vault

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import cn.edu.haut.campus.core.database.BlobStoreEntity
import cn.edu.haut.campus.core.database.BlobStoreMetadataRow
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.security.MessageDigest
import java.time.Instant

private const val BLOB_READ_CHUNK_BYTES = 512 * 1024

/** 兼容 W1-03 公开类型路径；实际定义上移到 core:model，供纯 JVM BYOK 接口复用。 */
typealias StoredBlobRef = cn.edu.haut.campus.core.model.StoredBlobRef

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
    private val database: HautDatabase,
    private val clock: () -> Instant = Instant::now,
    private val maxBytes: Int = 16 * 1024 * 1024
) {
    private val dao = database.vaultDao()

    /** 只在 core:vault 并发回归中使用的写入前探针；生产路径永远不得依赖它改变行为。 */
    internal var beforeBlobWriteHook: suspend () -> Unit = {}

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
        val entity = BlobStoreEntity(
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
        // 测试探针只保证两个 put 调用在存储前重叠；existing check 与原子 insert 仍全部在同一事务内。
        beforeBlobWriteHook()
        return try {
            database.withTransaction {
                val existingBeforeInsert = dao.getBlobMetadata(blobId)
                if (existingBeforeInsert != null) {
                    requireCompatibleBlob(existingBeforeInsert, sha256, mimeType, content.size.toLong(), owner)
                    return@withTransaction ref
                }

                dao.insertBlobOrAbort(entity)
                ref
            }
        } catch (constraint: SQLiteConstraintException) {
            // INSERT OR ABORT 是原子冲突检测：并发胜者提交后，负者绝不能 REPLACE 覆盖其
            // mimeType/owner。约束异常先让本事务整体回滚，再在事务外按已提交可见性复检胜者行；
            // 完全相同则保持幂等，不同元数据则 fail-closed。
            val winner = dao.getBlobMetadata(blobId) ?: throw constraint
            requireCompatibleBlob(winner, sha256, mimeType, content.size.toLong(), owner)
            ref
        }
    }

    suspend fun get(blobId: String, scope: SchoolAccountScope): StoredBlob? {
        val row = dao.getBlobMetadata(blobId) ?: return null
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
            content = readBlobContent(row.blobId, row.byteCount),
            createdAt = Instant.ofEpochMilli(row.createdAtEpochMs)
        )
    }

    suspend fun delete(blobId: String, scope: SchoolAccountScope): Boolean {
        val row = dao.getBlobMetadata(blobId) ?: return false
        if (!row.inScope(scope)) return false
        return dao.deleteBlob(blobId) == 1
    }

    suspend fun count(): Int = dao.countBlobStore()
    suspend fun totalBytes(): Long = dao.totalBlobBytes()

    private suspend fun readBlobContent(blobId: String, byteCount: Long): ByteArray {
        require(byteCount <= Int.MAX_VALUE) { "blob 过大，超出当前内存读取上限" }
        val total = byteCount.toInt()
        if (total == 0) return ByteArray(0)
        val result = ByteArray(total)
        var offset = 0
        while (offset < total) {
            val length = minOf(BLOB_READ_CHUNK_BYTES, total - offset)
            val chunk = dao.readBlobContentChunk(blobId, offset.toLong() + 1L, length)
                ?: throw IllegalStateException("blob 内容读取失败: " + blobId)
            require(chunk.isNotEmpty()) { "blob 内容提前结束: " + blobId }
            require(chunk.size == length) { "blob 分块长度不一致: " + blobId }
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }

    private fun requireCompatibleBlob(
        existing: BlobStoreMetadataRow,
        sha256: String,
        mimeType: String,
        byteCount: Long,
        owner: EntityRef?
    ) {
        require(existing.sha256 == sha256) { "blobId 与 sha256 不一致" }
        require(existing.byteCount == byteCount) { "blob byteCount 与内容不一致" }
        // v1 每行只有单 mimeType/单 owner 元数据；遇到同字节不同元数据时 fail-closed，
        // 不得返回一行与持久化事实不一致的 StoredBlobRef。多 owner 分享需后续显式建模。
        require(existing.mimeType == mimeType) { "同内容 blob 的 mimeType 冲突" }
        require(existing.ownerEntityType == owner?.type?.name && existing.ownerEntityId == owner?.id) {
            "同内容 blob 的 owner 冲突"
        }
    }

    private fun BlobStoreEntity.inScope(scope: SchoolAccountScope): Boolean =
        scopeSchoolId == scope.schoolId.value &&
            scopeAccountId == scope.accountId.value &&
            scopeProfileId == scope.profileId.value &&
            scopeCampusId == scope.campusId?.value

    private fun BlobStoreMetadataRow.inScope(scope: SchoolAccountScope): Boolean =
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
