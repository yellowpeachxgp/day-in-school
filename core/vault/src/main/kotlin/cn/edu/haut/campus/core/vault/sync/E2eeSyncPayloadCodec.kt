package cn.edu.haut.campus.core.vault.sync

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.vault.BackupCell
import cn.edu.haut.campus.core.vault.BackupRow
import cn.edu.haut.campus.core.vault.BackupTablePolicy
import cn.edu.haut.campus.core.vault.BackupValidationException
import cn.edu.haut.campus.core.vault.encodeCell
import java.util.Base64

internal object SyncBackupObjectKeys {
    fun logicalKey(table: String, rowKey: String): String =
        table + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(rowKey.toByteArray(Charsets.UTF_8))

    fun rowKeyFromLogical(table: String, logicalKey: String): String {
        val prefix = table + ":"
        require(logicalKey.startsWith(prefix)) { "logicalKey 表名不匹配" }
        return String(Base64.getUrlDecoder().decode(logicalKey.removePrefix(prefix)), Charsets.UTF_8)
    }
}

/** 上传快照的模块边界：所有行都必须已经处于 sync-normalized scope 形态。 */
internal data class SyncBackupRowSnapshot(
    val policy: BackupTablePolicy,
    val row: BackupRow,
    val blobs: Map<String, ByteArray>
) {
    val table: String get() = policy.table
    val rowKey: String get() = row.primaryKey(policy)
    val logicalKey: String get() = SyncBackupObjectKeys.logicalKey(table, rowKey)
    val rowSha256: String get() = row.sha256()
}

internal interface SyncBackupStore {
    val schemaVersion: Int
    suspend fun captureRows(): List<SyncBackupRowSnapshot>
    suspend fun readRow(table: String, rowKey: String): SyncBackupRowSnapshot?
    suspend fun applyUpserts(rows: List<SyncBackupRowSnapshot>)
    suspend fun applyDeletes(deletes: List<Pair<String, String>>)
}

internal object SyncBackupScopeNormalizer {
    const val SYNC_SCOPE_VALUE = "sync"

    fun normalize(policy: BackupTablePolicy, row: BackupRow): BackupRow {
        val columns = policy.scopeColumns ?: return row
        val cells = LinkedHashMap(row.cells)
        cells[columns.school] = BackupCell.TextValue(SYNC_SCOPE_VALUE)
        cells[columns.account] = BackupCell.TextValue(SYNC_SCOPE_VALUE)
        cells[columns.profile] = BackupCell.TextValue(SYNC_SCOPE_VALUE)
        cells[columns.campus] = BackupCell.NullValue
        return BackupRow(cells)
    }

    fun localize(policy: BackupTablePolicy, row: BackupRow, scope: SchoolAccountScope): BackupRow {
        val columns = policy.scopeColumns ?: return row
        val cells = LinkedHashMap(row.cells)
        cells[columns.school] = BackupCell.TextValue(scope.schoolId.value)
        cells[columns.account] = BackupCell.TextValue(scope.accountId.value)
        cells[columns.profile] = BackupCell.TextValue(scope.profileId.value)
        cells[columns.campus] = scope.campusId?.value?.let { BackupCell.TextValue(it) } ?: BackupCell.NullValue
        return BackupRow(cells)
    }

    fun validateNormalized(policy: BackupTablePolicy, row: BackupRow) {
        val columns = policy.scopeColumns ?: return
        fun expect(column: String, expected: String) {
            if ((row.cells[column] as? BackupCell.TextValue)?.value != expected) {
                throw E2eeSyncCryptoException("同步 payload scope 未规范化: " + policy.table)
            }
        }
        expect(columns.school, SYNC_SCOPE_VALUE)
        expect(columns.account, SYNC_SCOPE_VALUE)
        expect(columns.profile, SYNC_SCOPE_VALUE)
        if (row.cells[columns.campus] != BackupCell.NullValue) {
            throw E2eeSyncCryptoException("同步 payload campus scope 未规范化: " + policy.table)
        }
    }
}

internal data class E2eeSyncRowPayload(
    val policy: BackupTablePolicy,
    val row: BackupRow,
    val blobs: Map<String, ByteArray>,
    val schemaVersion: Int,
    val syncSpaceId: String,
    val logicalClock: Long,
    val writerDeviceId: String,
    val rowSha256: String
) {
    val table: String get() = policy.table
    val rowKey: String get() = row.primaryKey(policy)
    val logicalKey: String get() = SyncBackupObjectKeys.logicalKey(table, rowKey)
}

/** dis-backup typed-cell 行在同步 plaintext 内的 CanonicalJson 编解码。 */
internal object E2eeSyncPayloadCodec {
    private const val MAX_PAYLOAD_JSON_CHARS = 2 * 1024 * 1024

    fun encode(input: E2eeSyncRowPayload): String {
        SyncBackupScopeNormalizer.validateNormalized(input.policy, input.row)
        require(input.schemaVersion > 0) { "schemaVersion 非法" }
        require(input.logicalClock >= 1) { "logicalClock 必须从 1 开始" }
        E2eeSyncProtocol.requireValidUuid(input.writerDeviceId, "writerDeviceId")
        require(input.rowSha256 == input.row.sha256()) { "rowSha256 与行内容不一致" }
        val blobEntries = input.blobs.keys.sorted().map { sha ->
            val bytes = input.blobs.getValue(sha)
            JsonValue.Obj(
                linkedMapOf(
                    "sha256" to JsonValue.Str(sha),
                    "byteCount" to JsonValue.Num(bytes.size.toString()),
                    "base64" to JsonValue.Str(Base64.getEncoder().encodeToString(bytes))
                )
            )
        }
        return Json.canonical(
            JsonValue.Obj(
                linkedMapOf(
                    "format" to JsonValue.Str(E2eeSyncProtocol.PAYLOAD_FORMAT),
                    "table" to JsonValue.Str(input.table),
                    "rowKey" to JsonValue.Str(input.rowKey),
                    "schemaVersion" to JsonValue.Num(input.schemaVersion.toString()),
                    "syncSpaceId" to JsonValue.Str(input.syncSpaceId),
                    "logicalClock" to JsonValue.Num(input.logicalClock.toString()),
                    "writerDeviceId" to JsonValue.Str(input.writerDeviceId),
                    "rowSha256" to JsonValue.Str(input.rowSha256),
                    "row" to input.row.canonicalValue(),
                    "blobs" to JsonValue.Arr(blobEntries)
                )
            )
        )
    }

    fun decode(text: String, expectedSyncSpaceId: String, currentSchemaVersion: Int): E2eeSyncRowPayload {
        val value = try {
            Json.parse(text, MAX_PAYLOAD_JSON_CHARS)
        } catch (error: Throwable) {
            throw E2eeSyncCryptoException("同步 payload 不是合法 JSON", error)
        }
        if (Json.canonical(value) != text) throw E2eeSyncCryptoException("同步 payload 不是 CanonicalJson")
        val root = value.asObjOrNull ?: throw E2eeSyncCryptoException("同步 payload 根必须是 object")
        val allowed = setOf("format", "table", "rowKey", "schemaVersion", "syncSpaceId", "logicalClock", "writerDeviceId", "rowSha256", "row", "blobs")
        if ((root.fields.keys - allowed).isNotEmpty()) throw E2eeSyncCryptoException("同步 payload 含未定义字段")
        if (root.str("format") != E2eeSyncProtocol.PAYLOAD_FORMAT) throw E2eeSyncCryptoException("同步 payload format 不受支持")
        val table = root.str("table") ?: throw E2eeSyncCryptoException("同步 payload 缺少 table")
        val policy = cn.edu.haut.campus.core.vault.BackupTableCatalog.policy(table)
            ?: throw E2eeSyncCryptoException("同步 payload 表不在 dis-backup 白名单")
        val rowKey = root.str("rowKey").orEmpty()
        if (rowKey.isBlank()) throw E2eeSyncCryptoException("同步 payload rowKey 为空")
        val schemaVersion = requiredLong(root, "schemaVersion").toInt()
        if (schemaVersion <= 0) throw E2eeSyncCryptoException("同步 payload schemaVersion 非法")
        if (schemaVersion > currentSchemaVersion) {
            throw E2eeSyncCryptoException("同步 payload schemaVersion 高于当前设备")
        }
        val syncSpaceId = root.str("syncSpaceId").orEmpty()
        if (syncSpaceId != expectedSyncSpaceId) throw E2eeSyncCryptoException("同步 payload 不属于当前同步空间")
        val logicalClock = requiredLong(root, "logicalClock")
        if (logicalClock < 1) throw E2eeSyncCryptoException("同步 payload logicalClock 非法")
        val writerDeviceId = root.str("writerDeviceId").orEmpty()
        E2eeSyncProtocol.requireValidUuid(writerDeviceId, "writerDeviceId")
        val rowSha = root.str("rowSha256").orEmpty()
        if (!rowSha.matches(Regex("[0-9a-f]{64}"))) throw E2eeSyncCryptoException("同步 payload rowSha256 非法")
        val rowObj = root.obj("row")?.asObjOrNull ?: throw E2eeSyncCryptoException("同步 payload row 非法")
        val cells = LinkedHashMap<String, BackupCell>()
        rowObj.fields.forEach { (column, cellValue) -> cells[column] = decodeCell(cellValue) }
        val row = try {
            BackupRow(cells)
        } catch (error: Throwable) {
            throw E2eeSyncCryptoException("同步 payload 行结构非法", error)
        }
        if (cells.keys != policy.exportedColumns) {
            throw E2eeSyncCryptoException("同步 payload 列集合与 dis-backup 白名单不一致")
        }
        try {
            cn.edu.haut.campus.core.vault.BackupSchemaSafety.validateCellValues(cells, "E2EE 同步 payload " + policy.table)
        } catch (error: Throwable) {
            throw E2eeSyncCryptoException("同步 payload 值级秘密防线拒绝", error)
        }
        if (row.primaryKey(policy) != rowKey) throw E2eeSyncCryptoException("同步 payload rowKey 与主键列不一致")
        if (row.sha256() != rowSha) throw E2eeSyncCryptoException("同步 payload rowSha256 与行内容不一致")
        val blobs = LinkedHashMap<String, ByteArray>()
        root.arr("blobs")?.forEach { item ->
            val obj = item.asObjOrNull ?: throw E2eeSyncCryptoException("同步 payload blob 项非法")
            val sha = obj.str("sha256").orEmpty()
            val byteCount = obj.obj("byteCount")?.let { (it as? JsonValue.Num)?.literal?.toLongOrNull() }
                ?: throw E2eeSyncCryptoException("同步 payload blob byteCount 非法")
            val bytes = try {
                Base64.getDecoder().decode(obj.str("base64").orEmpty())
            } catch (error: IllegalArgumentException) {
                throw E2eeSyncCryptoException("同步 payload blob base64 非法", error)
            }
            if (!sha.matches(Regex("[0-9a-f]{64}")) || bytes.size.toLong() != byteCount ||
                cn.edu.haut.campus.core.model.json.Digests.sha256Hex(bytes) != sha
            ) {
                throw E2eeSyncCryptoException("同步 payload blob 摘要或大小不一致")
            }
            try {
                cn.edu.haut.campus.core.vault.BackupSchemaSafety.validateBlobBytes(bytes, "E2EE 同步 payload blob")
            } catch (error: Throwable) {
                throw E2eeSyncCryptoException("同步 payload blob 值级秘密防线拒绝", error)
            }
            blobs[sha] = bytes
        }
        val referenced = row.cells.values.filterIsInstance<BackupCell.BlobValue>().map { it.sha256 }.toSet()
        if (referenced != blobs.keys) throw E2eeSyncCryptoException("同步 payload blob 引用集合不一致")
        val payload = E2eeSyncRowPayload(
            policy = policy,
            row = row,
            blobs = blobs,
            schemaVersion = schemaVersion,
            syncSpaceId = syncSpaceId,
            logicalClock = logicalClock,
            writerDeviceId = writerDeviceId,
            rowSha256 = rowSha
        )
        SyncBackupScopeNormalizer.validateNormalized(policy, row)
        return payload
    }

    fun payloadFromSnapshot(
        snapshot: SyncBackupRowSnapshot,
        schemaVersion: Int,
        syncSpaceId: String,
        logicalClock: Long,
        writerDeviceId: String
    ): E2eeSyncRowPayload = E2eeSyncRowPayload(
        policy = snapshot.policy,
        row = snapshot.row,
        blobs = snapshot.blobs,
        schemaVersion = schemaVersion,
        syncSpaceId = syncSpaceId,
        logicalClock = logicalClock,
        writerDeviceId = writerDeviceId,
        rowSha256 = snapshot.rowSha256
    )

    private fun requiredLong(root: JsonValue.Obj, key: String): Long {
        val literal = (root.fields[key] as? JsonValue.Num)?.literal
            ?: throw E2eeSyncCryptoException("同步 payload 缺少 integer 字段 " + key)
        if (!literal.matches(Regex("(0|[1-9][0-9]*)"))) throw E2eeSyncCryptoException("同步 payload integer 字段非法 " + key)
        return literal.toLongOrNull() ?: throw E2eeSyncCryptoException("同步 payload integer 超出范围 " + key)
    }

    private fun decodeCell(value: JsonValue): BackupCell {
        val obj = value.asObjOrNull ?: throw E2eeSyncCryptoException("typed-cell 必须是 object")
        return when (obj.str("type")) {
            "null" -> BackupCell.NullValue
            "integer" -> BackupCell.LongValue(obj.str("value")?.toLongOrNull() ?: throw E2eeSyncCryptoException("integer typed-cell 非法"))
            "real" -> BackupCell.DoubleValue(obj.str("value")?.toDoubleOrNull() ?: throw E2eeSyncCryptoException("real typed-cell 非法"))
            "text" -> BackupCell.TextValue(obj.str("value") ?: throw E2eeSyncCryptoException("text typed-cell 非法"))
            "blob" -> {
                val sha = obj.str("sha256").orEmpty()
                val count = obj.obj("byteCount")?.let { (it as? JsonValue.Num)?.literal?.toLongOrNull() }
                    ?: throw E2eeSyncCryptoException("blob typed-cell byteCount 非法")
                if (!sha.matches(Regex("[0-9a-f]{64}"))) throw E2eeSyncCryptoException("blob typed-cell sha256 非法")
                BackupCell.BlobValue(sha, count, "blobs/" + sha)
            }
            else -> throw E2eeSyncCryptoException("未知 typed-cell 类型")
        }
    }
}

/** 把 dis-backup length-prefixed primaryKey 还原为按主键列排列的 typed-cell，供同步删除 WHERE 使用。 */
internal object SyncBackupPrimaryKeyCodec {
    fun decode(policy: BackupTablePolicy, rowKey: String): Map<String, BackupCell> {
        val identities = mutableListOf<String>()
        var index = 0
        while (index < rowKey.length) {
            val colon = rowKey.indexOf(':', index)
            if (colon <= index) throw BackupValidationException("备份行键长度前缀损坏")
            val length = rowKey.substring(index, colon).toIntOrNull()
                ?: throw BackupValidationException("备份行键长度前缀不是整数")
            val start = colon + 1
            val end = start + length
            if (length < 2 || end > rowKey.length) throw BackupValidationException("备份行键长度越界")
            identities += rowKey.substring(start, end)
            if (end < rowKey.length) {
                if (rowKey[end] != '|') throw BackupValidationException("备份行键缺少分隔符")
                index = end + 1
            } else {
                index = end
            }
        }
        if (identities.size != policy.primaryKeys.size) throw BackupValidationException("备份行键主键数量不一致")
        return policy.primaryKeys.mapIndexed { position, column ->
            val identity = identities[position]
            val valueText = identity.drop(2)
            val cell = when (identity.take(2)) {
                "t:" -> BackupCell.TextValue(valueText)
                "i:" -> BackupCell.LongValue(valueText.toLongOrNull() ?: throw BackupValidationException("备份整数主键非法"))
                "r:" -> BackupCell.DoubleValue(valueText.toDoubleOrNull() ?: throw BackupValidationException("备份实数主键非法"))
                "b:" -> throw BackupValidationException("blob 不能作为同步删除主键")
                else -> throw BackupValidationException("备份主键类型未知")
            }
            column to cell
        }.toMap()
    }
}
