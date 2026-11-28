package cn.edu.haut.campus.core.packages.datapack

import cn.edu.haut.campus.core.model.json.JsonValue
import java.net.URI
import java.time.LocalDate
import java.time.OffsetDateTime

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
    "^([0-9]{4}-[0-9]{2}-[0-9]{2})T([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](\\.[0-9]+)?(Z|[+-]([01][0-9]|2[0-3]):[0-5][0-9])$"
)

internal fun expectDateValue(value: JsonValue?, path: String): String {
    val raw = expectStringValue(value, path)
    val valid = DATE_PATTERN.matches(raw) && try {
        LocalDate.parse(raw)
        true
    } catch (error: Exception) {
        false
    }
    if (!valid) schemaFail(path, "must be a valid RFC 3339 full-date")
    return raw
}

internal fun expectDateTimeValue(value: JsonValue?, path: String): String {
    val raw = expectStringValue(value, path)
    val match = DATE_TIME_PATTERN.matchEntire(raw)
    val valid = match != null && try {
        LocalDate.parse(match.groupValues[1])
        OffsetDateTime.parse(raw)
        true
    } catch (error: Exception) {
        false
    }
    if (!valid) schemaFail(path, "must be a valid RFC 3339 date-time")
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

internal fun expectHttpsUriValue(value: JsonValue?, path: String): String {
    val raw = expectStringValue(value, path)
    val valid = raw.startsWith("https://") && isRfc3986UriCharactersValid(raw) && try {
        val uri = URI(raw)
        uri.isAbsolute && uri.scheme == "https" && !uri.host.isNullOrBlank()
    } catch (error: Exception) {
        false
    }
    if (!valid) schemaFail(path, "must be an absolute HTTPS URI")
    return raw
}

internal fun expectUniqueStrings(values: List<String>, path: String): List<String> {
    if (values.distinct().size != values.size) schemaFail(path, "must not contain duplicate strings")
    return values
}

internal inline fun <T> mapArray(values: List<JsonValue>, path: String, mapper: (JsonValue, String) -> T): List<T> =
    values.mapIndexed { index, item -> mapper(item, path + "[" + index + "]") }
