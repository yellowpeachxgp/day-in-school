package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.json.JsonValue

class SensitiveAutomationDataException(fieldName: String) :
    IllegalArgumentException("自动化配置/证据不得携带凭据、Cookie、Token 或秘密字段: " + fieldName)

/**
 * 自动化规则与运行记录都可能是长生命周期本地数据；秘密必须进入专门 Keystore/授权链路，而不是 config 或 evidence。
 *
 * 字段名口径与 core/events validateFieldDiffSafety 的五类保持一致：口令、凭据/会话、令牌、秘密值、密钥材料。
 * 自动化数据生命周期更长，因此规范化后恰好为 key/session/auth/passcode 的“直接秘密字段名”也拒绝；
 * 复合词只匹配明确秘密形态，避免把 publicKey、author 这类普通字段误报为秘密。
 */
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
        if (normalized in DIRECT_SECRET_FIELD_NAMES || FORBIDDEN_COMPOUND_MARKERS.any { marker -> normalized.contains(marker) }) {
            throw SensitiveAutomationDataException(rawName)
        }
    }

    /** 规范化后的直接秘密字段名；即使没有任何前缀/后缀也不得进入自动化持久化数据。 */
    private val DIRECT_SECRET_FIELD_NAMES = setOf(
        "key",
        "session",
        "auth",
        "passcode",
        "password",
        "passwd",
        "passphrase",
        "credential",
        "credentials",
        "cookie",
        "sessionid",
        "authorization",
        "token",
        "secret",
        "apikey",
        "aikey",
        "privatekey",
        "accesskey",
        "secretkey",
        "clientsecret"
    )

    /** 复合字段名中的明确秘密形态；publicKey/author 等非秘密词不包含这些标记。 */
    private val FORBIDDEN_COMPOUND_MARKERS = listOf(
        // 1. 口令
        "password",
        "passwd",
        "passcode",
        "passphrase",
        // 2. 凭据 / 会话 / Cookie / Auth
        "credential",
        "cookie",
        "sessionid",
        "sessionkey",
        "sessioncookie",
        "sessiontoken",
        "authorization",
        "authheader",
        // 3. 令牌
        "token",
        // 4. 秘密值
        "secret",
        // 5. 密钥材料（不包含 publicKey）
        "apikey",
        "aikey",
        "privatekey",
        "accesskey",
        "secretkey",
        "signingkey",
        "encryptionkey",
        "clientkey",
        "masterkey"
    )
}
