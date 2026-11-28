package cn.edu.haut.campus.core.vault.sync

import cn.edu.haut.campus.core.model.json.Digests

/** CT-07 `dis-sync-e2ee/v1` 客户端常量。服务端只见密文；这里的常量不含任何用户秘密。 */
object E2eeSyncProtocol {
    const val FORMAT = "dis-sync-e2ee/v1"
    const val CIPHER_SUITE = "AES-256-GCM"
    const val PADDING_SCHEME = "DIS_SYNC_SIZE_BUCKETS_POW4_V1"
    const val PAYLOAD_FORMAT = "dis-sync-backup-row/v1"
    const val CONSENT_PURPOSE = "E2EE_CLOUD_SYNC"
    const val CONSENT_DOCUMENT_VERSION = "e2ee-sync-consent-v1"
    const val SYNC_PROTOCOL_VERSION = 1
    const val GCM_TAG_BYTES = 16
    const val NONCE_BYTES = 12
    const val MASTER_KEY_BYTES = 32
    const val SYNC_SPACE_BYTES = 16
    const val RECOVERY_KEY_PREFIX = "dssk1."
    const val MAX_VECTOR_ENTRIES = 512
    const val MAX_COUNTER = 2_147_483_647
    const val MAX_CHANGE_PAGE_LIMIT = 100

    val BUCKET_SIZES: List<Int> = listOf(1_024, 4_096, 16_384, 65_536, 262_144, 1_048_576)
    val MAX_PLAINTEXT_BYTES: Int = BUCKET_SIZES.last() - GCM_TAG_BYTES - 4

    /** 与 UI 逐字展示的同意文本；sha256 只覆盖 UTF-8 文本，不包含密钥或用户信息。 */
    const val CONSENT_DOCUMENT_TEXT = "E2EE_CLOUD_SYNC v1\n" +
        "1. 云端只保存端到端加密后的密文块、密文摘要、版本向量、设备登记和同步游标；不保存明文、明文摘要、同步密钥、密钥加密密钥、口令或密钥派生秘密。\n" +
        "2. 固定大小 POW4 填充桶会隐藏精确明文长度，但云端仍可见 bucket size、修改次数、修改时间和设备在线模式。\n" +
        "3. v1 配额：32 台 ACTIVE 设备、256 条设备登记历史、4096 个存活密文块、268435456 字节存活填充密文，以及每 24 小时 536870912 字节上行和下行同步传输。\n" +
        "4. 撤回同意后上传、下载、删除和增量拉取立即停止；既有密文和元数据最多保留 30 天用于导出或重新授权，到期删除密文、向量、tombstone 传播元数据、游标和确认水位，并自动撤销仍为 ACTIVE 的同步设备。\n" +
        "5. 跨设备恢复密钥只由用户手动保管和粘贴，不经过云端；遗失密钥将无法解密其他设备上的同步数据。"

    val CONSENT_DOCUMENT_SHA256: String = Digests.sha256Hex(CONSENT_DOCUMENT_TEXT)

    fun requireValidUuid(value: String, field: String) {
        require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {
            field + " 必须是 UUID"
        }
    }

    fun bucketForPlaintext(plaintextBytes: Int): Int {
        require(plaintextBytes >= 0) { "plaintext 长度不能为负" }
        val requiredEnvelopeBytes = plaintextBytes + 4
        return BUCKET_SIZES.firstOrNull { it - GCM_TAG_BYTES >= requiredEnvelopeBytes }
            ?: throw E2eeSyncOversizeException("单个同步行 plaintext 超过 v1 块上限")
    }

    fun associatedData(blobId: String, keyId: String): ByteArray =
        (FORMAT + "\n" + blobId.lowercase() + "\n" + keyId + "\n").toByteArray(Charsets.UTF_8)
}

class E2eeSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
class E2eeSyncCryptoException(message: String, cause: Throwable? = null) : E2eeSyncException(message, cause)
class E2eeSyncOversizeException(message: String) : E2eeSyncException(message)
class E2eeSyncConsentException(message: String) : E2eeSyncException(message)
class E2eeSyncConflictException(message: String) : E2eeSyncException(message)

/** 服务端或 fake server 返回的可分类同步错误。message 不得携带 plaintext/key。 */
class E2eeSyncApiException(
    val httpStatus: Int,
    val errorCode: String,
    message: String,
    val detail: String? = null
) : E2eeSyncException(errorCode + ": " + message) {
    val uncertainOutcome: Boolean
        get() = httpStatus >= 500 || errorCode == "STORAGE_COMMIT_UNKNOWN"
}

data class SyncVersionVectorEntry(
    val deviceId: String,
    val counter: Int
) {
    init {
        E2eeSyncProtocol.requireValidUuid(deviceId, "version vector device_id")
        require(counter in 1..E2eeSyncProtocol.MAX_COUNTER) { "version vector counter 超出范围" }
    }
}

typealias SyncVersionVector = List<SyncVersionVectorEntry>

enum class VectorOrdering { EQUAL, DOMINATES, DOMINATED_BY, CONCURRENT }

object SyncVectors {
    fun canonical(vector: SyncVersionVector): SyncVersionVector {
        require(vector.size <= E2eeSyncProtocol.MAX_VECTOR_ENTRIES) { "version vector 超过 512 项" }
        val ids = vector.map { it.deviceId }
        require(ids.distinct().size == ids.size) { "version vector device_id 重复" }
        return vector.sortedBy { it.deviceId.lowercase() }
    }

    fun toMap(vector: SyncVersionVector): Map<String, Int> =
        canonical(vector).associate { it.deviceId.lowercase() to it.counter }

    fun fromMap(map: Map<String, Int>): SyncVersionVector =
        canonical(map.map { (device, counter) -> SyncVersionVectorEntry(device, counter) })

    fun compare(left: SyncVersionVector, right: SyncVersionVector): VectorOrdering {
        val l = toMap(left)
        val r = toMap(right)
        if (l == r) return VectorOrdering.EQUAL
        val keys = l.keys + r.keys
        var leftGreater = false
        var rightGreater = false
        keys.forEach { key ->
            val diff = (l[key] ?: 0).compareTo(r[key] ?: 0)
            if (diff > 0) leftGreater = true
            if (diff < 0) rightGreater = true
        }
        return when {
            leftGreater && !rightGreater -> VectorOrdering.DOMINATES
            rightGreater && !leftGreater -> VectorOrdering.DOMINATED_BY
            else -> VectorOrdering.CONCURRENT
        }
    }

    fun nextWriterCounter(base: SyncVersionVector, deviceId: String): Int {
        val baseCounter = toMap(base)[deviceId.lowercase()] ?: 0
        require(baseCounter < E2eeSyncProtocol.MAX_COUNTER) { "writer counter 已到达 v1 上限" }
        if (baseCounter == 0) {
            require(base.size < E2eeSyncProtocol.MAX_VECTOR_ENTRIES) { "写入将引入第 513 个 vector entry" }
        }
        return baseCounter + 1
    }
}

data class SyncEncryptionEnvelope(
    val format: String = E2eeSyncProtocol.FORMAT,
    val cipherSuite: String = E2eeSyncProtocol.CIPHER_SUITE,
    val paddingScheme: String = E2eeSyncProtocol.PADDING_SCHEME,
    val keyId: String,
    val nonceBase64: String
)

data class SyncBlobMetadata(
    val blobId: String,
    val changeKind: String,
    val versionVector: SyncVersionVector,
    val authorDeviceId: String,
    val changeSequence: Long,
    val encryption: SyncEncryptionEnvelope?,
    val ciphertextSha256: String?,
    val ciphertextSizeBytes: Int?,
    val committedAt: String
) {
    init {
        E2eeSyncProtocol.requireValidUuid(blobId, "blob_id")
        require(changeKind == "UPSERT" || changeKind == "TOMBSTONE") { "未知同步变更类型" }
        E2eeSyncProtocol.requireValidUuid(authorDeviceId, "author_device_id")
        require(changeSequence >= 1) { "change_sequence 必须从 1 开始" }
        if (changeKind == "UPSERT") {
            requireNotNull(encryption) { "UPSERT 缺少 encryption envelope" }
            require(!ciphertextSha256.isNullOrBlank()) { "UPSERT 缺少 ciphertext_sha256" }
            require(ciphertextSizeBytes in E2eeSyncProtocol.BUCKET_SIZES) { "UPSERT bucket size 非法" }
        }
    }
}

data class SyncChangePage(
    val rows: List<SyncBlobMetadata>,
    val hasMore: Boolean,
    val nextCursor: String,
    val snapshotEpoch: Long,
    val compactionEpoch: Long
) {
    init {
        require(nextCursor.length >= 8) { "sync cursor 过短" }
        require(rows.size <= E2eeSyncProtocol.MAX_CHANGE_PAGE_LIMIT) { "change page 超过契约上限" }
    }
}

data class SyncDevice(
    val deviceId: String,
    val status: String,
    val deviceLabel: String?,
    val platform: String,
    val syncProtocolVersion: Int,
    val registeredAt: String,
    val revokedAt: String?,
    val revocationReason: String?,
    val lastSeenAt: String?
)

data class SyncBlobPutInput(
    val encryption: SyncEncryptionEnvelope,
    val ciphertextBase64: String,
    val ciphertextSha256: String,
    val baseVector: SyncVersionVector,
    val writerCounter: Int,
    val idempotencyKey: String
)

data class SyncBlobDeleteInput(
    val baseVector: SyncVersionVector,
    val writerCounter: Int,
    val idempotencyKey: String
)

data class SyncConsentRecord(
    val id: String,
    val purpose: String,
    val documentVersion: String,
    val documentSha256: String
)

/** 云 API 抽象；生产实现走 HTTPS，两设备测试用严格 fake server。 */
interface E2eeSyncApi {
    suspend fun grantConsent(): SyncConsentRecord
    suspend fun withdrawConsent(consentId: String)
    suspend fun listDevices(): List<SyncDevice>
    suspend fun registerDevice(deviceId: String, consentId: String, deviceLabel: String?, idempotencyKey: String): SyncDevice
    suspend fun revokeDevice(deviceId: String, reason: String): SyncDevice
    suspend fun putBlob(blobId: String, request: SyncBlobPutInput): SyncBlobMetadata
    suspend fun downloadBlob(blobId: String): Pair<SyncBlobMetadata, String>
    suspend fun deleteBlob(blobId: String, request: SyncBlobDeleteInput): SyncBlobMetadata
    suspend fun listChanges(cursor: String?, limit: Int = E2eeSyncProtocol.MAX_CHANGE_PAGE_LIMIT): SyncChangePage
}
