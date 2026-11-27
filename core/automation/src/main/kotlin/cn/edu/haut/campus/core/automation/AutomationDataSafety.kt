package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.json.JsonValue

class SensitiveAutomationDataException(fieldName: String) :
    IllegalArgumentException("自动化配置/证据不得携带凭据、Cookie、Token 或秘密字段: " + fieldName)

/** 自动化规则与运行记录都可能是长生命周期本地数据；秘密必须进入专门 Keystore/授权链路，而不是 config 或 evidence。 */
internal object AutomationDataSafety {
    fun validateJsonValue(value: JsonValue) {
        when (value) {
            is JsonValue.Obj -> value.fields.forEach { (key, child) ->
                validateNotSensitiveFieldName(key)
                validateJsonValue(child)
            }
            is JsonValue.Arr -> value.items.forEach(::validateJsonValue)
            else -> Unit
        }
    }

    private fun validateNotSensitiveFieldName(rawName: String) {
        val normalized = buildString(rawName.length) {
            rawName.lowercase().forEach { ch ->
                if (ch.isLetterOrDigit()) append(ch)
            }
        }
        if (normalized.isEmpty()) return
        val forbiddenMarkers = listOf(
            "password",
            "passwd",
            "credential",
            "cookie",
            "authorization",
            "token",
            "secret",
            "apikey",
            "aikey",
            "privatekey",
            "sessionid"
        )
        if (forbiddenMarkers.any { marker -> normalized.contains(marker) }) {
            throw SensitiveAutomationDataException(rawName)
        }
    }
}
