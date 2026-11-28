package cn.edu.haut.campus.core.ai.android

import cn.edu.haut.campus.core.ai.AiAttachmentReader
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.StoredBlobRef
import cn.edu.haut.campus.core.vault.VaultBlobStore

/** 从当前 Profile 的 blob_store 读取 vision 附件；引用字段不一致时按缺失 fail-closed。 */
class VaultAiAttachmentReader(database: HautDatabase) : AiAttachmentReader {
    private val blobs = VaultBlobStore(database)

    override suspend fun read(scope: SchoolAccountScope, ref: StoredBlobRef): ByteArray? {
        val blob = blobs.get(ref.blobId, scope) ?: return null
        val storedRef = blob.ref
        if (storedRef.sha256 != ref.sha256 ||
            storedRef.mimeType != ref.mimeType ||
            storedRef.byteCount != ref.byteCount ||
            storedRef.scope != scope
        ) {
            return null
        }
        return blob.content()
    }
}
