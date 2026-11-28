package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.JsonValue

internal fun schemaFail(path: String, message: String): Nothing =
    throw datapackInvalid("dis-datapack/v1 schema violation at " + path + ": " + message, RUNTIME_DATAPACK_SCHEMA_INVALID)

internal fun childPath(path: String, key: String): String = path + "." + key

internal fun codePointLength(value: String): Int = value.codePointCount(0, value.length)

internal fun expectObject(
    value: JsonValue?,
    path: String,
    allowedKeys: Set<String>,
    requiredKeys: Set<String>
): JsonValue.Obj {
    val obj = value as? JsonValue.Obj ?: schemaFail(path, "must be an object")
    for (key in obj.fields.keys) {
        if (key !in allowedKeys) schemaFail(childPath(path, key), "additional property is not allowed")
    }
    for (key in requiredKeys) {
        if (!obj.fields.containsKey(key)) schemaFail(childPath(path, key), "required property is missing")
    }
    return obj
}

internal fun expectArrayValue(value: JsonValue?, path: String, minItems: Int = 0): List<JsonValue> {
    val array = (value as? JsonValue.Arr)?.items ?: schemaFail(path, "must be an array")
    if (array.size < minItems) schemaFail(path, "must contain at least " + minItems + " item(s)")
    return array
}

internal fun expectStringValue(
    value: JsonValue?,
    path: String,
    min: Int = 0,
    max: Int = Int.MAX_VALUE,
    pattern: Regex? = null
): String {
    val string = (value as? JsonValue.Str)?.value ?: schemaFail(path, "must be a string")
    val length = codePointLength(string)
    if (length < min) schemaFail(path, "length must be at least " + min)
    if (length > max) schemaFail(path, "length must be at most " + max)
    if (pattern != null && !pattern.matches(string)) schemaFail(path, "does not match required pattern " + pattern.pattern)
    return string
}

internal fun expectNullableStringValue(value: JsonValue?, path: String, min: Int = 0, max: Int = Int.MAX_VALUE): String? =
    if (value is JsonValue.Null) null else expectStringValue(value, path, min, max)

internal fun expectBooleanValue(value: JsonValue?, path: String): Boolean =
    (value as? JsonValue.Bool)?.value ?: schemaFail(path, "must be a boolean")

internal fun expectNullableBooleanValue(value: JsonValue?, path: String): Boolean? =
    if (value is JsonValue.Null) null else expectBooleanValue(value, path)

internal fun expectIntegerValue(value: JsonValue?, path: String, minimum: Int? = null, maximum: Int? = null): Int {
    val literal = (value as? JsonValue.Num)?.literal ?: schemaFail(path, "must be an integer")
    val integer = literal.toIntOrNull() ?: schemaFail(path, "must be an integer in the supported 32-bit range")
    if (minimum != null && integer < minimum) schemaFail(path, "must be >= " + minimum)
    if (maximum != null && integer > maximum) schemaFail(path, "must be <= " + maximum)
    return integer
}

internal fun expectNullableIntegerValue(value: JsonValue?, path: String, minimum: Int? = null, maximum: Int? = null): Int? =
    if (value is JsonValue.Null) null else expectIntegerValue(value, path, minimum, maximum)

internal fun expectNumberValue(value: JsonValue?, path: String, minimum: Double? = null, maximum: Double? = null): Double {
    val literal = (value as? JsonValue.Num)?.literal ?: schemaFail(path, "must be a finite number")
    val number = try {
        literal.toDouble()
    } catch (error: NumberFormatException) {
        schemaFail(path, "must be a finite number")
    }
    if (number.isNaN() || number.isInfinite()) schemaFail(path, "must be a finite number")
    if (minimum != null && number < minimum) schemaFail(path, "must be >= " + minimum)
    if (maximum != null && number > maximum) schemaFail(path, "must be <= " + maximum)
    return number
}

internal fun expectNullableNumberValue(value: JsonValue?, path: String, minimum: Double? = null, maximum: Double? = null): Double? =
    if (value is JsonValue.Null) null else expectNumberValue(value, path, minimum, maximum)

internal fun expectEnumWireValue(value: JsonValue?, path: String, allowed: Set<String>): String {
    val raw = expectStringValue(value, path)
    if (raw !in allowed) schemaFail(path, "must be one of " + allowed.sorted().joinToString(", "))
    return raw
}

internal fun expectNullableEnumWireValue(value: JsonValue?, path: String, allowed: Set<String>): String? =
    if (value is JsonValue.Null) null else expectEnumWireValue(value, path, allowed)

private val DATE_PATTERN = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
private val DATE_TIME_PATTERN = Regex(
    "^([0-9]{4}-[0-9]{2}-[0-9]{2})[Tt]([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]+)?([Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])$"
)

private fun validCalendarDate(raw: String): Boolean {
    if (!DATE_PATTERN.matches(raw)) return false
    val year = raw.substring(0, 4).toInt()
    val month = raw.substring(5, 7).toInt()
    val day = raw.substring(8, 10).toInt()
    if (month !in 1..12 || day < 1) return false
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val daysInMonth = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (leap) 29 else 28
    }
    return day <= daysInMonth
}

internal fun expectDateValue(value: JsonValue?, path: String): String {
    val raw = expectStringValue(value, path)
    if (!validCalendarDate(raw)) schemaFail(path, "must be a valid RFC 3339 full-date")
    return raw
}

internal fun expectDateTimeValue(value: JsonValue?, path: String): String {
    val raw = expectStringValue(value, path)
    val match = DATE_TIME_PATTERN.matchEntire(raw)
    if (match == null || !validCalendarDate(match.groupValues[1])) {
        schemaFail(path, "must be a valid RFC 3339 date-time")
    }
    return raw
}


private val RFC3986_ALLOWED: Set<Char> = (
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
        "-._~:/?#[]@!$&'()*+,;="
    ).toSet()
private val HEX_CHARS: Set<Char> = "0123456789abcdefABCDEF".toSet()

internal fun isRfc3986UriCharactersValid(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '%') {
            if (index + 2 >= value.length || value[index + 1] !in HEX_CHARS || value[index + 2] !in HEX_CHARS) return false
            index += 3
            continue
        }
        if (ch !in RFC3986_ALLOWED) return false
        index += 1
    }
    return true
}

private fun validPortComponent(port: String): Boolean {
    if (port.isEmpty()) return true
    if (port.any { it !in '0'..'9' }) return false
    val normalized = port.trimStart('0')
    return normalized.length <= 5 && (normalized.isEmpty() || normalized.toInt() <= 65535)
}

private fun validIpv4Address(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.length in 1..3 && part.all { it in '0'..'9' } &&
            !(part.length > 1 && part.startsWith("0")) && part.toInt() <= 255
    }
}

private fun ipv6PieceGroupCount(pieces: List<String>): Int {
    var count = 0
    pieces.forEachIndexed { index, piece ->
        val isH16 = piece.length in 1..4 && piece.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        if (isH16) {
            count += 1
        } else if (index == pieces.lastIndex && validIpv4Address(piece)) {
            count += 2
        } else {
            return -1
        }
    }
    return count
}

/** RFC 3986 IPv6address grammar；HTTPS datapack 链接保守拒绝 IPvFuture。 */
private fun isValidIpv6Literal(value: String): Boolean {
    if (value.isEmpty() || value.any { ch -> ch !in '0'..'9' && ch !in 'a'..'f' && ch !in 'A'..'F' && ch != ':' && ch != '.' }) return false
    val firstCompression = value.indexOf("::")
    val hasCompression = firstCompression >= 0
    if (hasCompression && firstCompression != value.lastIndexOf("::")) return false
    fun parseSide(side: String): List<String>? {
        if (side.isEmpty()) return emptyList()
        val pieces = side.split(':')
        return if (pieces.any { it.isEmpty() }) null else pieces
    }
    if (!hasCompression) {
        val pieces = parseSide(value) ?: return false
        return ipv6PieceGroupCount(pieces) == 8
    }
    val left = parseSide(value.substring(0, firstCompression)) ?: return false
    val right = parseSide(value.substring(firstCompression + 2)) ?: return false
    // IPv4 tail 只允许占据整个 IPv6 地址的最后 32 bit；位于 "::" 左侧时，
    // 尾部实际是压缩零段，不能按混合格式接受。
    if (left.any { it.contains('.') }) return false
    val leftGroups = ipv6PieceGroupCount(left)
    val rightGroups = ipv6PieceGroupCount(right)
    if (leftGroups < 0 || rightGroups < 0) return false
    return leftGroups + rightGroups < 8
}

/**
 * RFC 3986 raw square brackets are only the delimiters of an IP-literal host.
 * They are not valid raw path/query/fragment characters; native array spellings
 * such as program[]=x must be percent-encoded before entering a datapack.
 */
private fun validateHttpsAuthorityAndBracketPlacement(raw: String, path: String) {
    val authorityStart = "https://".length
    var authorityEnd = raw.length
    for (index in authorityStart until raw.length) {
        val ch = raw[index]
        if (ch == '/' || ch == '?' || ch == '#') {
            authorityEnd = index
            break
        }
    }
    val authority = raw.substring(authorityStart, authorityEnd)
    if (authority.isEmpty()) schemaFail(path, "must include a non-empty URI authority")

    val hostStart = if (authority.indexOf('@') >= 0) authority.indexOf('@') + 1 else 0
    val hostAndPort = authority.substring(hostStart)
    if (hostAndPort.isEmpty()) schemaFail(path, "must include a non-empty URI host")
    if (hostAndPort.contains('@')) schemaFail(path, "must percent-encode additional @ characters in URI userinfo")

    val allowedOpenBracket = authorityStart + hostStart
    var allowedCloseBracket = -1
    if (hostAndPort.startsWith("[")) {
        val close = hostAndPort.indexOf(']')
        if (close <= 1) schemaFail(path, "must contain a closed non-empty IP-literal host")
        val literal = hostAndPort.substring(1, close)
        if (literal.contains('[') || literal.contains(']')) schemaFail(path, "must not nest raw brackets in an IP-literal host")
        if (!isValidIpv6Literal(literal)) schemaFail(path, "must use an RFC 3986 IPv6 literal host")
        val rest = hostAndPort.substring(close + 1)
        if (rest.isNotEmpty()) {
            if (!rest.startsWith(":")) schemaFail(path, "must not append non-port text to an IP-literal host")
            if (!validPortComponent(rest.substring(1))) schemaFail(path, "must use a decimal port in the supported URI range")
        }
        allowedCloseBracket = allowedOpenBracket + close
    } else {
        if (hostAndPort.contains('[') || hostAndPort.contains(']')) {
            schemaFail(path, "must wrap an IP-literal host in exactly one bracket pair")
        }
        if (hostAndPort.indexOf(':') != hostAndPort.lastIndexOf(':')) {
            schemaFail(path, "must bracket an IPv6 literal host")
        }
        val colon = hostAndPort.lastIndexOf(':')
        if (colon >= 0) {
            if (!validPortComponent(hostAndPort.substring(colon + 1))) {
                schemaFail(path, "must use a decimal port in the supported URI range")
            }
            if (colon == 0) schemaFail(path, "must include a non-empty URI host")
        }
    }

    raw.forEachIndexed { index, ch ->
        if (ch == '[' || ch == ']') {
            val allowedHostBracket = hostAndPort.startsWith("[") && (index == allowedOpenBracket || index == allowedCloseBracket)
            if (!allowedHostBracket) schemaFail(path, "must percent-encode square brackets outside an IP-literal host")
        }
    }
}

internal fun expectHttpsUriValue(value: JsonValue?, path: String): String {
    val raw = expectStringValue(value, path)
    val valid = raw.startsWith("https://") && isRfc3986UriCharactersValid(raw) &&
        raw.indexOf('#') == raw.lastIndexOf('#')
    if (valid) validateHttpsAuthorityAndBracketPlacement(raw, path)
    if (!valid) schemaFail(path, "must be an absolute RFC 3986 HTTPS URI")
    return raw
}

internal fun expectUniqueStrings(values: List<String>, path: String): List<String> {
    if (values.distinct().size != values.size) schemaFail(path, "must not contain duplicate strings")
    return values
}

internal inline fun <T> mapArray(values: List<JsonValue>, path: String, mapper: (JsonValue, String) -> T): List<T> =
    values.mapIndexed { index, item -> mapper(item, path + "[" + index + "]") }
