package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.time.Instant

internal sealed interface BackupCell {
    data object NullValue : BackupCell
    data class LongValue(val value: Long) : BackupCell
    data class DoubleValue(val value: Double) : BackupCell
    data class TextValue(val value: String) : BackupCell
    data class BlobValue(val sha256: String, val byteCount: Long, val zipPath: String) : BackupCell
}

internal class BackupRow(
    val cells: Map<String, BackupCell>
) {
    fun primaryKey(policy: BackupTablePolicy): String = policy.primaryKeys.joinToString("|") { column ->
        val cell = cells[column] ?: throw BackupValidationException("备份行缺少主键列: " + policy.table + "." + column)
        val identity = when (cell) {
            BackupCell.NullValue -> throw BackupValidationException("备份主键不能为 NULL: " + policy.table + "." + column)
            is BackupCell.LongValue -> "i:" + cell.value
            is BackupCell.DoubleValue -> "r:" + cell.value
            is BackupCell.TextValue -> "t:" + cell.value
            is BackupCell.BlobValue -> "b:" + cell.sha256
        }
        identity.length.toString() + ":" + identity
    }

    fun canonicalValue(): JsonValue.Obj = JsonValue.Obj(cells.mapValues { (_, cell) -> encodeCell(cell) })

    fun sha256(): String = Digests.sha256Hex(Json.canonical(canonicalValue()))
}

internal data class BackupColumnSchema(
    val name: String,
    val declaredType: String,
    val notNull: Boolean,
    val primaryKeyPosition: Int
)

internal class BackupTableData(
    val policy: BackupTablePolicy,
    val columns: List<BackupColumnSchema>,
    val rows: List<BackupRow>
) {
    init {
        require(columns.map { it.name }.distinct().size == columns.size) { "备份列重复: " + policy.table }
        val rowKeys = rows.map { it.primaryKey(policy) }
        require(rowKeys.distinct().size == rowKeys.size) { "备份行主键重复: " + policy.table }
    }

    fun canonicalValue(): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "table" to JsonValue.Str(policy.table),
            "columns" to JsonValue.Arr(columns.map { JsonValue.Str(it.name) }),
            "rows" to JsonValue.Arr(rows.map { it.canonicalValue() })
        )
    )

    fun sha256(): String = Digests.sha256Hex(Json.canonical(canonicalValue()))
}

internal class PreparedBackupArchive(
    val scope: SchoolAccountScope,
    val tables: List<BackupTableData>,
    val blobs: Map<String, ByteArray>
) {
    val rowCount: Int get() = tables.sumOf { it.rows.size }
    val entryCount: Int get() = rowCount + blobs.size
}

internal object BackupWireCodec {
    fun encodeData(archive: PreparedBackupArchive): String = Json.canonical(
        JsonValue.Obj(
            linkedMapOf(
                "format" to JsonValue.Str(BackupFormatConstants.DATA_FORMAT),
                "scope" to encodeScope(archive.scope),
                "tables" to JsonValue.Arr(archive.tables.map { it.canonicalValue() }),
                "blobEntries" to JsonValue.Arr(
                    archive.blobs.keys.sorted().map { sha ->
                        val bytes = archive.blobs.getValue(sha)
                        JsonValue.Obj(
                            linkedMapOf(
                                "sha256" to JsonValue.Str(sha),
                                "byteCount" to JsonValue.Num(bytes.size.toString()),
                                "path" to JsonValue.Str(blobPathFor(sha))
                            )
                        )
                    }
                )
            )
        )
    )

    fun parseData(dataText: String): Triple<SchoolAccountScope, List<BackupTableData>, List<Triple<String, Long, String>>> {
        val rootValue = try {
            Json.parse(dataText, BackupFormatConstants.MAX_DATA_JSON_BYTES)
        } catch (error: Throwable) {
            throw BackupCorruptedException("data.json 不是合法 CanonicalJson", error)
        }
        if (Json.canonical(rootValue) != dataText) throw BackupCorruptedException("data.json 不是 CanonicalJson 规范形")
        BackupSchemaSafety.validateJsonKeys(rootValue, "备份数据 schema")
        val root = rootValue.asObjOrNull ?: throw BackupCorruptedException("data.json 根必须是 object")
        requireExactKeys(root, setOf("format", "scope", "tables", "blobEntries"), "data.json")
        if (root.requiredStringOrCorrupt("format") != BackupFormatConstants.DATA_FORMAT) {
            throw BackupCorruptedException("data.json format 不是 dis-backup-data/v1")
        }
        val scope = try {
            decodeScope(root.requiredField("scope"))
        } catch (error: Throwable) {
            throw BackupCorruptedException("data.json scope 无法解析", error)
        }
        val blobEntries = root.requiredArrayOrCorrupt("blobEntries").map { value ->
            val obj = value.asObjOrNull ?: throw BackupCorruptedException("blobEntries 项必须是 object")
            requireExactKeys(obj, setOf("sha256", "byteCount", "path"), "blobEntries")
            val sha = obj.requiredStringOrCorrupt("sha256")
            val count = obj.requiredLongOrCorrupt("byteCount")
            val path = obj.requiredStringOrCorrupt("path")
            if (!sha.matches(Regex("[0-9a-f]{64}")) || count < 0 || path != blobPathFor(sha)) {
                throw BackupCorruptedException("blobEntries 项摘要/大小/路径非法")
            }
            Triple(sha, count, path)
        }
        if (blobEntries.map { it.first }.distinct().size != blobEntries.size) {
            throw BackupCorruptedException("blobEntries 摘要重复")
        }

        val tableValues = root.requiredArrayOrCorrupt("tables")
        if (tableValues.isEmpty()) throw BackupCorruptedException("data.json 不包含任何备份表")
        val tables = mutableListOf<BackupTableData>()
        val seenTables = mutableSetOf<String>()
        for (tableValue in tableValues) {
            val tableObj = tableValue.asObjOrNull ?: throw BackupCorruptedException("tables 项必须是 object")
            requireExactKeys(tableObj, setOf("table", "columns", "rows"), "tables")
            val tableName = tableObj.requiredStringOrCorrupt("table")
            if (!seenTables.add(tableName)) throw BackupCorruptedException("备份表重复: " + tableName)
            val policy = BackupTableCatalog.policy(tableName)
                ?: throw BackupValidationException("备份包含不允许恢复的表: " + tableName)
            BackupSchemaSafety.validateKeyName(tableName, "备份表名")
            val columns = tableObj.requiredArrayOrCorrupt("columns").map { columnValue ->
                columnValue.stringOrNull() ?: throw BackupCorruptedException("columns 必须是 string array")
            }
            if (columns.distinct().size != columns.size) throw BackupCorruptedException("备份列重复: " + tableName)
            BackupSchemaSafety.validateColumns(policy, columns)
            val rows = tableObj.requiredArrayOrCorrupt("rows").map { rowValue ->
                val rowObj = rowValue.asObjOrNull ?: throw BackupCorruptedException("备份行必须是 object")
                if (rowObj.fields.keys != columns.toSet()) {
                    throw BackupCorruptedException("备份行列集合与 columns 不一致: " + tableName)
                }
                val cells = LinkedHashMap<String, BackupCell>()
                columns.forEach { column -> cells[column] = decodeCell(rowObj.fields.getValue(column)) }
                BackupRow(cells)
            }
            // rows 的身份/主键重复由 BackupTableData init 统一 fail-closed；列 declaredType 在服务内对照当前 schema 填充。
            tables += BackupTableData(
                policy = policy,
                columns = columns.map { BackupColumnSchema(it, declaredType = "", notNull = false, primaryKeyPosition = 0) },
                rows = rows
            )
        }
        return Triple(scope, tables, blobEntries)
    }

    fun encodeManifest(
        createdAt: Instant,
        appVersion: String,
        schemaVersion: Int,
        profileFingerprint: String,
        archive: PreparedBackupArchive,
        payloadSha256: String
    ): String = Json.canonical(
        JsonValue.Obj(
            linkedMapOf(
                "format" to JsonValue.Str(BackupFormatConstants.FORMAT),
                "formatVersion" to JsonValue.Num(BackupFormatConstants.FORMAT_VERSION.toString()),
                "createdAt" to JsonValue.Str(createdAt.toString()),
                "appVersion" to JsonValue.Str(appVersion),
                "schemaVersion" to JsonValue.Num(schemaVersion.toString()),
                "profileFingerprint" to JsonValue.Str(profileFingerprint),
                "entryCount" to JsonValue.Num(archive.entryCount.toString()),
                "payloadSha256" to JsonValue.Str(payloadSha256),
                "tables" to JsonValue.Arr(
                    archive.tables.map { table ->
                        JsonValue.Obj(
                            linkedMapOf(
                                "table" to JsonValue.Str(table.policy.table),
                                "rowCount" to JsonValue.Num(table.rows.size.toString()),
                                "sha256" to JsonValue.Str(table.sha256())
                            )
                        )
                    }
                )
            )
        )
    )

    fun parseManifest(manifestText: String): BackupManifest {
        val rootValue = try {
            Json.parse(manifestText, BackupFormatConstants.MAX_DATA_JSON_BYTES)
        } catch (error: Throwable) {
            throw BackupCorruptedException("manifest.json 不是合法 CanonicalJson", error)
        }
        if (Json.canonical(rootValue) != manifestText) throw BackupCorruptedException("manifest.json 不是 CanonicalJson 规范形")
        BackupSchemaSafety.validateJsonKeys(rootValue, "备份 manifest schema")
        val root = rootValue.asObjOrNull ?: throw BackupCorruptedException("manifest.json 根必须是 object")
        requireExactKeys(
            root,
            setOf(
                "format", "formatVersion", "createdAt", "appVersion", "schemaVersion",
                "profileFingerprint", "entryCount", "payloadSha256", "tables"
            ),
            "manifest.json"
        )
        try {
            return BackupManifest(
                format = root.requiredString("format"),
                formatVersion = root.requiredInt("formatVersion"),
                createdAt = Instant.parse(root.requiredString("createdAt")),
                appVersion = root.requiredString("appVersion"),
                schemaVersion = root.requiredInt("schemaVersion"),
                profileFingerprint = root.requiredString("profileFingerprint"),
                entryCount = root.requiredInt("entryCount"),
                payloadSha256 = root.requiredString("payloadSha256"),
                tables = root.requiredArray("tables").map { value ->
                    val obj = value.asObjOrNull ?: throw IllegalArgumentException("manifest table 必须是 object")
                    requireExactKeys(obj, setOf("table", "rowCount", "sha256"), "manifest tables")
                    BackupTableManifest(
                        table = obj.requiredString("table"),
                        rowCount = obj.requiredInt("rowCount"),
                        sha256 = obj.requiredString("sha256")
                    )
                }
            )
        } catch (error: BackupException) {
            throw error
        } catch (error: Throwable) {
            throw BackupCorruptedException("manifest.json 字段缺失或类型非法", error)
        }
    }

    fun blobPathFor(sha256: String): String = BackupFormatConstants.BLOB_ENTRY_PREFIX + sha256
}

internal fun encodeCell(cell: BackupCell): JsonValue.Obj = when (cell) {
    BackupCell.NullValue -> JsonValue.Obj(linkedMapOf("type" to JsonValue.Str("null")))
    is BackupCell.LongValue -> JsonValue.Obj(
        linkedMapOf("type" to JsonValue.Str("integer"), "value" to JsonValue.Str(cell.value.toString()))
    )
    is BackupCell.DoubleValue -> JsonValue.Obj(
        linkedMapOf("type" to JsonValue.Str("real"), "value" to JsonValue.Str(cell.value.toString()))
    )
    is BackupCell.TextValue -> JsonValue.Obj(
        linkedMapOf("type" to JsonValue.Str("text"), "value" to JsonValue.Str(cell.value))
    )
    is BackupCell.BlobValue -> JsonValue.Obj(
        linkedMapOf(
            "type" to JsonValue.Str("blob"),
            "sha256" to JsonValue.Str(cell.sha256),
            "byteCount" to JsonValue.Num(cell.byteCount.toString()),
            "path" to JsonValue.Str(cell.zipPath)
        )
    )
}

private fun decodeCell(value: JsonValue): BackupCell {
    val obj = value.asObjOrNull ?: throw BackupCorruptedException("备份单元格必须是 object")
    return when (obj.requiredStringOrCorrupt("type")) {
        "null" -> {
            requireExactKeys(obj, setOf("type"), "cell")
            BackupCell.NullValue
        }
        "integer" -> {
            requireExactKeys(obj, setOf("type", "value"), "cell")
            BackupCell.LongValue(
                obj.requiredStringOrCorrupt("value").toLongOrNull() ?: throw BackupCorruptedException("integer 单元格非法")
            )
        }
        "real" -> {
            requireExactKeys(obj, setOf("type", "value"), "cell")
            BackupCell.DoubleValue(
                obj.requiredStringOrCorrupt("value").toDoubleOrNull() ?: throw BackupCorruptedException("real 单元格非法")
            )
        }
        "text" -> {
            requireExactKeys(obj, setOf("type", "value"), "cell")
            BackupCell.TextValue(obj.requiredStringOrCorrupt("value"))
        }
        "blob" -> {
            requireExactKeys(obj, setOf("type", "sha256", "byteCount", "path"), "cell")
            val sha = obj.requiredStringOrCorrupt("sha256")
            val count = obj.requiredLongOrCorrupt("byteCount")
            val path = obj.requiredStringOrCorrupt("path")
            if (!sha.matches(Regex("[0-9a-f]{64}")) || count < 0 || path != BackupWireCodec.blobPathFor(sha)) {
                throw BackupCorruptedException("blob 单元格摘要/大小/路径非法")
            }
            BackupCell.BlobValue(sha, count, path)
        }
        else -> throw BackupCorruptedException("未知备份单元格类型")
    }
}

private fun requireExactKeys(obj: JsonValue.Obj, allowed: Set<String>, context: String) {
    val extra = obj.fields.keys - allowed
    if (extra.isNotEmpty()) {
        throw BackupCorruptedException(context + " 包含 v1 未定义字段: " + extra.sorted().joinToString(","))
    }
}

internal fun JsonValue.Obj.requiredStringOrCorrupt(key: String): String = try {
    requiredString(key)
} catch (error: Throwable) {
    throw BackupCorruptedException("JSON 缺少 string 字段: " + key, error)
}

internal fun JsonValue.Obj.requiredLongOrCorrupt(key: String): Long = try {
    requiredLong(key)
} catch (error: Throwable) {
    throw BackupCorruptedException("JSON 缺少 integer 字段: " + key, error)
}

private fun JsonValue.Obj.requiredArrayOrCorrupt(key: String): List<JsonValue> = try {
    requiredArray(key)
} catch (error: Throwable) {
    throw BackupCorruptedException("JSON 缺少 array 字段: " + key, error)
}
