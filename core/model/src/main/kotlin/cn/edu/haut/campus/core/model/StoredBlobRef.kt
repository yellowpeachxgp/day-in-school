package cn.edu.haut.campus.core.model

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
