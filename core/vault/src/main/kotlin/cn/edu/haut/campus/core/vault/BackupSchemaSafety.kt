package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.model.json.JsonValue

/** dis-backup schema 级秘密字段防线；只要规范化键名带秘密语义，备份导出和恢复都 fail-closed。 */
internal object BackupSchemaSafety {
    private val forbiddenMarkers = listOf(
        "password", "passwd", "credential", "cookie", "authorization", "token",
        "secret", "apikey", "aikey", "privatekey", "sessionid"
    )

    fun validateKeyName(rawName: String, context: String) {
        val normalized = buildString(rawName.length) {
            rawName.lowercase().forEach { ch -> if (ch.isLetterOrDigit()) append(ch) }
        }
        if (normalized.isEmpty()) return
        if (forbiddenMarkers.any { marker -> normalized.contains(marker) }) {
            throw BackupValidationException(context + " 含禁止的凭据/Cookie/Key 字段名: " + rawName)
        }
    }

    fun validateJsonKeys(value: JsonValue, context: String) {
        when (value) {
            is JsonValue.Obj -> value.fields.forEach { (key, child) ->
                validateKeyName(key, context)
                validateJsonKeys(child, context)
            }
            is JsonValue.Arr -> value.items.forEach { validateJsonKeys(it, context) }
            else -> Unit
        }
    }

    fun validateColumns(table: BackupTablePolicy, columns: List<String>) {
        validateKeyName(table, "备份表名")
        columns.forEach { validateKeyName(it, "备份表 " + table.table + " 列") }
    }
}
