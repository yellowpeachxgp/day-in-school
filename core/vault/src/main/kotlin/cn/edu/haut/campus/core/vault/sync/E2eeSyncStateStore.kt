package cn.edu.haut.campus.core.vault.sync

import android.content.Context
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class E2eeSyncObjectState(
    val logicalKey: String,
    val table: String,
    val rowKey: String,
    val blobId: String,
    val logicalClock: Long,
    val syncedRowSha256: String?,
    val versionVector: SyncVersionVector,
    val deleted: Boolean = false,
    val lastChangeSequence: Long = 0L
)

data class E2eeSyncPendingWrite(
    val logicalKey: String,
    val kind: String,
    val blobId: String,
    val payloadJson: String?,
    val encryption: SyncEncryptionEnvelope?,
    val ciphertextBase64: String?,
    val ciphertextSha256: String?,
    val baseVector: SyncVersionVector,
    val writerCounter: Int,
    val idempotencyKey: String,
    val logicalClock: Long,
    val rowSha256: String?
) {
    init {
        require(kind == "UPSERT" || kind == "DELETE") { "pending write kind 非法" }
    }
}

data class E2eeSyncConflict(
    val id: String,
    val logicalKey: String,
    val table: String,
    val rowKey: String,
    val kind: String,
    val reason: String,
    val blobId: String?,
    val localPayloadJson: String?,
    val remotePayloadJson: String?,
    val localVector: SyncVersionVector,
    val remoteVector: SyncVersionVector,
    val remoteDeletion: Boolean,
    val localDeletion: Boolean = false,
    val createdAtEpochMs: Long
) {
    init {
        require(kind in setOf("CONCURRENT_UPDATE", "ROLLBACK_DETECTED", "DIVERGENT_CLOCK", "DELETE_VS_LOCAL", "DUPLICATE_IDENTITY", "CAS_CONFLICT")) {
            "未知同步冲突类型"
        }
    }
}

data class E2eeSyncState(
    val schemaVersion: Int = 1,
    var enabled: Boolean = false,
    val deviceId: String? = null,
    val consentId: String? = null,
    val syncSpaceId: String? = null,
    val keyId: String? = null,
    val deviceLabel: String? = null,
    var cursor: String? = null,
    val objects: MutableMap<String, E2eeSyncObjectState> = mutableMapOf(),
    val pendingWrites: MutableMap<String, E2eeSyncPendingWrite> = mutableMapOf(),
    val conflicts: MutableMap<String, E2eeSyncConflict> = mutableMapOf(),
    var lastRunAtEpochMs: Long = 0L,
    var lastRunSummary: String? = null
)

interface E2eeSyncStateStore {
    suspend fun load(): E2eeSyncState
    suspend fun save(state: E2eeSyncState)
    suspend fun wipe()
}

object E2eeSyncStateCodec {
    fun encode(state: E2eeSyncState): String {
        val root = JSONObject()
            .put("schemaVersion", state.schemaVersion)
            .put("enabled", state.enabled)
            .put("deviceId", state.deviceId)
            .put("consentId", state.consentId)
            .put("syncSpaceId", state.syncSpaceId)
            .put("keyId", state.keyId)
            .put("deviceLabel", state.deviceLabel)
            .put("cursor", state.cursor)
            .put("lastRunAtEpochMs", state.lastRunAtEpochMs)
            .put("lastRunSummary", state.lastRunSummary)
        val objects = JSONArray()
        state.objects.values.sortedBy { it.logicalKey }.forEach { objects.put(encodeObject(it)) }
        root.put("objects", objects)
        val pending = JSONArray()
        state.pendingWrites.values.sortedBy { it.logicalKey }.forEach { pending.put(encodePending(it)) }
        root.put("pendingWrites", pending)
        val conflicts = JSONArray()
        state.conflicts.values.sortedBy { it.id }.forEach { conflicts.put(encodeConflict(it)) }
        root.put("conflicts", conflicts)
        return root.toString()
    }

    fun decode(text: String): E2eeSyncState {
        val root = JSONObject(text)
        if (root.optInt("schemaVersion", 0) != 1) throw E2eeSyncException("同步状态 schemaVersion 不受支持")
        val state = E2eeSyncState(
            enabled = root.optBoolean("enabled", false),
            deviceId = root.optNullableString("deviceId"),
            consentId = root.optNullableString("consentId"),
            syncSpaceId = root.optNullableString("syncSpaceId"),
            keyId = root.optNullableString("keyId"),
            deviceLabel = root.optNullableString("deviceLabel"),
            cursor = root.optNullableString("cursor"),
            lastRunAtEpochMs = root.optLong("lastRunAtEpochMs", 0L),
            lastRunSummary = root.optNullableString("lastRunSummary")
        )
        root.optJSONArray("objects")?.let { array ->
            for (i in 0 until array.length()) {
                val item = decodeObject(array.getJSONObject(i))
                state.objects[item.logicalKey] = item
            }
        }
        root.optJSONArray("pendingWrites")?.let { array ->
            for (i in 0 until array.length()) {
                val item = decodePending(array.getJSONObject(i))
                state.pendingWrites[item.logicalKey] = item
            }
        }
        root.optJSONArray("conflicts")?.let { array ->
            for (i in 0 until array.length()) {
                val item = decodeConflict(array.getJSONObject(i))
                state.conflicts[item.id] = item
            }
        }
        return state
    }

    fun copy(state: E2eeSyncState): E2eeSyncState = decode(encode(state))

    private fun encodeObject(item: E2eeSyncObjectState): JSONObject = JSONObject()
        .put("logicalKey", item.logicalKey)
        .put("table", item.table)
        .put("rowKey", item.rowKey)
        .put("blobId", item.blobId)
        .put("logicalClock", item.logicalClock)
        .put("syncedRowSha256", item.syncedRowSha256)
        .put("versionVector", encodeVector(item.versionVector))
        .put("deleted", item.deleted)
        .put("lastChangeSequence", item.lastChangeSequence)

    private fun decodeObject(obj: JSONObject): E2eeSyncObjectState = E2eeSyncObjectState(
        logicalKey = obj.getString("logicalKey"),
        table = obj.getString("table"),
        rowKey = obj.getString("rowKey"),
        blobId = obj.getString("blobId"),
        logicalClock = obj.getLong("logicalClock"),
        syncedRowSha256 = obj.optNullableString("syncedRowSha256"),
        versionVector = decodeVector(obj.getJSONArray("versionVector")),
        deleted = obj.optBoolean("deleted", false),
        lastChangeSequence = obj.optLong("lastChangeSequence", 0L)
    )

    private fun encodePending(item: E2eeSyncPendingWrite): JSONObject = JSONObject()
        .put("logicalKey", item.logicalKey)
        .put("kind", item.kind)
        .put("blobId", item.blobId)
        .put("payloadJson", item.payloadJson)
        .put("encryption", item.encryption?.let(::encodeEncryption))
        .put("ciphertextBase64", item.ciphertextBase64)
        .put("ciphertextSha256", item.ciphertextSha256)
        .put("baseVector", encodeVector(item.baseVector))
        .put("writerCounter", item.writerCounter)
        .put("idempotencyKey", item.idempotencyKey)
        .put("logicalClock", item.logicalClock)
        .put("rowSha256", item.rowSha256)

    private fun decodePending(obj: JSONObject): E2eeSyncPendingWrite = E2eeSyncPendingWrite(
        logicalKey = obj.getString("logicalKey"),
        kind = obj.getString("kind"),
        blobId = obj.getString("blobId"),
        payloadJson = obj.optNullableString("payloadJson"),
        encryption = obj.optJSONObject("encryption")?.let(::decodeEncryption),
        ciphertextBase64 = obj.optNullableString("ciphertextBase64"),
        ciphertextSha256 = obj.optNullableString("ciphertextSha256"),
        baseVector = decodeVector(obj.getJSONArray("baseVector")),
        writerCounter = obj.getInt("writerCounter"),
        idempotencyKey = obj.getString("idempotencyKey"),
        logicalClock = obj.getLong("logicalClock"),
        rowSha256 = obj.optNullableString("rowSha256")
    )

    private fun encodeConflict(item: E2eeSyncConflict): JSONObject = JSONObject()
        .put("id", item.id)
        .put("logicalKey", item.logicalKey)
        .put("table", item.table)
        .put("rowKey", item.rowKey)
        .put("kind", item.kind)
        .put("reason", item.reason)
        .put("blobId", item.blobId)
        .put("localPayloadJson", item.localPayloadJson)
        .put("remotePayloadJson", item.remotePayloadJson)
        .put("localVector", encodeVector(item.localVector))
        .put("remoteVector", encodeVector(item.remoteVector))
        .put("remoteDeletion", item.remoteDeletion)
        .put("localDeletion", item.localDeletion)
        .put("createdAtEpochMs", item.createdAtEpochMs)

    private fun decodeConflict(obj: JSONObject): E2eeSyncConflict = E2eeSyncConflict(
        id = obj.getString("id"),
        logicalKey = obj.getString("logicalKey"),
        table = obj.getString("table"),
        rowKey = obj.getString("rowKey"),
        kind = obj.getString("kind"),
        reason = obj.getString("reason"),
        blobId = obj.optNullableString("blobId"),
        localPayloadJson = obj.optNullableString("localPayloadJson"),
        remotePayloadJson = obj.optNullableString("remotePayloadJson"),
        localVector = decodeVector(obj.getJSONArray("localVector")),
        remoteVector = decodeVector(obj.getJSONArray("remoteVector")),
        remoteDeletion = obj.optBoolean("remoteDeletion", false),
        localDeletion = obj.optBoolean("localDeletion", false),
        createdAtEpochMs = obj.getLong("createdAtEpochMs")
    )

    private fun encodeEncryption(value: SyncEncryptionEnvelope): JSONObject = JSONObject()
        .put("format", value.format)
        .put("cipherSuite", value.cipherSuite)
        .put("paddingScheme", value.paddingScheme)
        .put("keyId", value.keyId)
        .put("nonceBase64", value.nonceBase64)

    private fun decodeEncryption(obj: JSONObject): SyncEncryptionEnvelope = SyncEncryptionEnvelope(
        format = obj.getString("format"),
        cipherSuite = obj.getString("cipherSuite"),
        paddingScheme = obj.getString("paddingScheme"),
        keyId = obj.getString("keyId"),
        nonceBase64 = obj.getString("nonceBase64")
    )

    private fun encodeVector(vector: SyncVersionVector): JSONArray {
        val array = JSONArray()
        SyncVectors.canonical(vector).forEach {
            array.put(JSONObject().put("deviceId", it.deviceId).put("counter", it.counter))
        }
        return array
    }

    private fun decodeVector(array: JSONArray): SyncVersionVector {
        val entries = mutableListOf<SyncVersionVectorEntry>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            entries += SyncVersionVectorEntry(item.getString("deviceId"), item.getInt("counter"))
        }
        return SyncVectors.canonical(entries)
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

/** 应用私有目录中的按 Profile 隔离状态文件；不是云端密钥/明文备份，擦除时必须删除。 */
class FileE2eeSyncStateStore(
    context: Context,
    profileId: String
) : E2eeSyncStateStore {
    private val mutex = Mutex()
    private val rootDir: File
    private val stateFile: File

    init {
        require(profileId.isNotBlank()) { "profileId 不能为空" }
        val digest = cn.edu.haut.campus.core.model.json.Digests.sha256Hex(profileId)
        rootDir = File(context.applicationContext.filesDir, "e2ee-sync/" + digest)
        stateFile = File(rootDir, "state.json")
    }

    override suspend fun load(): E2eeSyncState = mutex.withLock {
        if (!stateFile.isFile) return@withLock E2eeSyncState()
        runCatching { E2eeSyncStateCodec.decode(stateFile.readText(Charsets.UTF_8)) }.getOrElse {
            throw E2eeSyncException("E2EE 同步状态文件损坏，已停止避免误写云端", it)
        }
    }

    override suspend fun save(state: E2eeSyncState): Unit = mutex.withLock {
        if (!rootDir.exists() && !rootDir.mkdirs()) throw E2eeSyncException("无法创建 E2EE 同步状态目录")
        val tmp = File(rootDir, "state.json.tmp")
        tmp.writeText(E2eeSyncStateCodec.encode(state), Charsets.UTF_8)
        if (!tmp.renameTo(stateFile)) {
            tmp.delete()
            throw E2eeSyncException("无法原子保存 E2EE 同步状态")
        }
    }

    override suspend fun wipe(): Unit = mutex.withLock {
        runCatching { stateFile.delete() }
        runCatching { File(rootDir, "state.json.tmp").delete() }
    }

    fun rootDirectory(): File = rootDir
}

class InMemoryE2eeSyncStateStore(
    private var state: E2eeSyncState = E2eeSyncState()
) : E2eeSyncStateStore {
    private val mutex = Mutex()

    override suspend fun load(): E2eeSyncState = mutex.withLock { E2eeSyncStateCodec.copy(state) }

    override suspend fun save(state: E2eeSyncState): Unit = mutex.withLock {
        this.state = E2eeSyncStateCodec.copy(state)
    }

    override suspend fun wipe(): Unit = mutex.withLock {
        state = E2eeSyncState()
    }
}
