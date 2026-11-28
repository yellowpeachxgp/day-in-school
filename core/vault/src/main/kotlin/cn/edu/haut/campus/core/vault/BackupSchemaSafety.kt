package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * dis-backup schema/值级秘密防线。
 *
 * 键名检查覆盖 auth/session/key/cookie/token 常见变体；文本值检查不依赖键名，
 * 对嵌套 JSON 递归检查，并识别 Bearer/JWT/Cookie/私有Key/口令赋值等高风险形态。
 * 发现疑似秘密时 fail-closed；错误消息只包含表/列上下文，绝不回显秘密值。
 */
internal object BackupSchemaSafety {
    private const val MAX_NESTED_JSON_BYTES = 1024 * 1024
    private const val MAX_NESTED_DEPTH = 12

    private val forbiddenNormalizedExactOrSuffix = setOf(
        "password", "passwd", "pwd", "passphrase", "passcode", "credential", "credentials",
        "cookie", "setcookie", "authorization", "proxyauthorization", "secret", "clientsecret",
        "apikey", "aikey", "privatekey", "secretkey", "accesskey", "sessionkey", "cookiekey",
        "encryptionkey", "signingkey", "masterkey", "keysecret", "keystore", "keychain",
        "jwt", "passkey", "sessid", "authcode", "authsecret", "hmackey", "authkey", "authsession"
    )

    private val forbiddenSegments = setOf(
        "password", "passwd", "pwd", "passphrase", "passcode", "credential", "credentials",
        "cookie", "authorization", "auth", "secret", "token", "bearer", "session", "sessionid",
        "jwt", "oauth", "sso", "sessid", "csrf", "xsrf", "passkey", "otp", "totp"
    )

    private val sensitiveKeyContexts = setOf(
        "api", "ai", "private", "secret", "access", "session", "auth", "encryption",
        "signing", "master", "client", "credential", "cookie", "hmac", "x"
    )

    // token 只作为键名后缀匹配，且后方必须紧接可选引号/空白与 : 或 =；
    // 因而 tokenize/tokenization 等普通词不会命中，token/passToken/serviceToken/*Token 会命中。
    private val sensitiveAssignmentPattern = Regex(
        """(?i)(?<![A-Za-z0-9_])["']?""" +
            """(?:password|passwd|pwd|passphrase|passcode|credential|client[_-]?secret|secret|""" +
            """api[_-]?key|ai[_-]?key|private[_-]?key|access[_-]?key|secret[_-]?key|""" +
            """[A-Za-z0-9_-]*token|auth(?:orization)?(?:[_-]?token)?|bearer|cookie|set-cookie|""" +
            """session(?:[_-]?id)?|sid|csrf(?:[_-]?token)?|xsrf(?:[_-]?token)?)""" +
            """["']?\s*[:=]\s*["']?[^\s,;\]\}"']{3,}"""
    )
    private val cookieNameAssignmentPattern = Regex(
        """(?i)(?<![A-Za-z0-9_])(?:jsessionid|sessionid|session|ssid|sid|csrftoken|csrf[_-]token|xsrf[_-]token)[A-Za-z0-9_-]*\s*=\s*[^\s;,]{3,}"""
    )
    private val bearerValuePattern = Regex(
        """(?i)(?<![A-Za-z0-9_])bearer\s+[A-Za-z0-9][A-Za-z0-9._~+/-]{7,}={0,2}"""
    )
    private val jwtValuePattern = Regex(
        """(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}(?![A-Za-z0-9_-])"""
    )

    private val privateKeyBlockPattern = Regex("-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----")
    private val commonProviderSecretPatterns = listOf(
        Regex("(?<![A-Za-z0-9])sk-(?:ant-)?[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])"),
        Regex("(?<![A-Za-z0-9])AIza[0-9A-Za-z_-]{35}(?![0-9A-Za-z_-])"),
        Regex("(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9-]{20,}(?![A-Za-z0-9-])")
    )

    fun validateKeyName(rawName: String, context: String) {
        val normalized = normalizeKey(rawName)
        if (normalized.isEmpty()) return
        val segments = keySegments(rawName)
        val exactOrCompoundHit = forbiddenNormalizedExactOrSuffix.any { marker ->
            normalized == marker || normalized.endsWith(marker) || normalized.contains("cookie") && marker == "cookie"
        }
        val tokenHit = normalized == "token" || normalized.endsWith("token")
        val sessionHit = normalized == "session" || normalized.endsWith("sessionid") || normalized == "sid"
        val segmentHit = segments.any { it in forbiddenSegments }
        val keyHit = run {
            if (!segments.contains("key") && normalized != "key") return@run false
            if (normalized == "key" || segments.size == 1) return@run true
            segments.any { it in sensitiveKeyContexts } || forbiddenNormalizedExactOrSuffix.any { normalized.contains(it) }
        }
        if (exactOrCompoundHit || tokenHit || sessionHit || segmentHit || keyHit) {
            throw BackupValidationException(context + " 含禁止的凭据/Cookie/Key 字段名: " + rawName)
        }
    }

    /** 递归检查 JSON 键和字符串值；嵌套文本若本身是 JSON，也继续递归检查。 */
    fun validateJsonKeys(value: JsonValue, context: String) = validateJsonKeysAndValues(value, context, depth = 0)

    fun validateJsonKeysAndValues(value: JsonValue, context: String, depth: Int) {
        if (depth > MAX_NESTED_DEPTH) throw BackupValidationException(context + " 嵌套层级过深")
        when (value) {
            is JsonValue.Obj -> value.fields.forEach { (key, child) ->
                validateKeyName(key, context)
                validateJsonKeysAndValues(child, context, depth + 1)
            }
            is JsonValue.Arr -> value.items.forEach { validateJsonKeysAndValues(it, context, depth + 1) }
            is JsonValue.Str -> validateTextValue(value.value, context, depth + 1)
            else -> Unit
        }
    }

    fun validateCellValues(cells: Map<String, BackupCell>, context: String) {
        cells.forEach { (column, cell) ->
            validateKeyName(column, context + " 列")
            if (cell is BackupCell.TextValue) {
                validateTextValue(cell.value, context + " 列 " + column, depth = 0)
            }
        }
    }

    fun validateTextValue(text: String, context: String, depth: Int = 0) {
        if (containsSecretLikeValue(text)) {
            throw BackupValidationException(context + " 含疑似凭据/Cookie/Token/Key 值")
        }
        if (depth >= MAX_NESTED_DEPTH) return
        val trimmed = text.trim()
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            val nested = runCatching { Json.parse(trimmed, MAX_NESTED_JSON_BYTES) }.getOrNull()
            if (nested != null) validateJsonKeysAndValues(nested, context + " 嵌套JSON", depth + 1)
        }
    }

    /** 对文本型 blob 做 best-effort 值扫描；含 NUL 的二进制 large object 不按文本强制解释。 */
    fun validateBlobBytes(bytes: ByteArray, context: String) {
        val sampleLength = minOf(bytes.size, 8192)
        var binary = false
        for (index in 0 until sampleLength) {
            if (bytes[index].toInt() == 0) {
                binary = true
                break
            }
        }
        if (!binary) validateTextValue(String(bytes, Charsets.UTF_8), context, depth = 0)
    }

    fun validateColumns(table: BackupTablePolicy, columns: List<String>) {
        validateKeyName(table.table, "备份表名")
        columns.forEach { validateKeyName(it, "备份表 " + table.table + " 列") }
    }

    private fun containsSecretLikeValue(text: String): Boolean =
        sensitiveAssignmentPattern.containsMatchIn(text) ||
            cookieNameAssignmentPattern.containsMatchIn(text) ||
            bearerValuePattern.containsMatchIn(text) ||
            jwtValuePattern.containsMatchIn(text) ||
            privateKeyBlockPattern.containsMatchIn(text) ||
            commonProviderSecretPatterns.any { it.containsMatchIn(text) }

    private fun normalizeKey(rawName: String): String = buildString(rawName.length) {
        rawName.lowercase().forEach { ch -> if (ch.isLetterOrDigit()) append(ch) }
    }

    private fun keySegments(rawName: String): List<String> = rawName
        .split(Regex("[^A-Za-z0-9]+"))
        .filter { it.isNotEmpty() }
        .flatMap { part ->
            part
                .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .split(' ')
        }
        .map { it.lowercase() }
        .filter { it.isNotEmpty() }
}