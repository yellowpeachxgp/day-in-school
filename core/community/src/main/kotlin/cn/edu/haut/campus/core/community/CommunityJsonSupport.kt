package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.json.JsonValue
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

internal fun jsonObject(fields: Map<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(fields)

internal fun jsonFields(): LinkedHashMap<String, JsonValue> = LinkedHashMap()

internal fun LinkedHashMap<String, JsonValue>.putJson(key: String, value: JsonValue?) {
    if (value != null) this[key] = value
}

internal fun LinkedHashMap<String, JsonValue>.putString(key: String, value: String?) {
    if (value != null) this[key] = JsonValue.Str(value)
}

internal fun LinkedHashMap<String, JsonValue>.putNumber(key: String, value: Long?) {
    if (value != null) this[key] = JsonValue.Num(value.toString())
}

internal fun LinkedHashMap<String, JsonValue>.putNumber(key: String, value: Int?) {
    if (value != null) this[key] = JsonValue.Num(value.toString())
}

internal fun LinkedHashMap<String, JsonValue>.putBoolean(key: String, value: Boolean?) {
    if (value != null) this[key] = JsonValue.Bool(value)
}

internal fun jsonStringArray(values: List<String>): JsonValue.Arr =
    JsonValue.Arr(values.map { JsonValue.Str(it) })

internal fun jsonObjectArray(values: List<JsonValue.Obj>): JsonValue.Arr = JsonValue.Arr(values)

internal fun requireText(value: String, field: String, maxLength: Int? = null): String {
    require(value.isNotBlank()) { field + " 不能为空" }
    if (maxLength != null) require(value.length <= maxLength) { field + " 长度不能超过 " + maxLength }
    return value
}

internal fun requireOptionalText(value: String?, field: String, maxLength: Int? = null): String? {
    if (value == null) return null
    return requireText(value, field, maxLength)
}

internal fun requireIsoDateTime(value: String, field: String): String {
    requireText(value, field)
    try {
        OffsetDateTime.parse(value)
    } catch (error: DateTimeParseException) {
        throw IllegalArgumentException(field + " 必须是 RFC 3339 date-time", error)
    }
    return value
}

internal fun requireIsoDate(value: String, field: String): String {
    requireText(value, field)
    try {
        LocalDate.parse(value)
    } catch (error: DateTimeParseException) {
        throw IllegalArgumentException(field + " 必须是 ISO yyyy-MM-dd 日期", error)
    }
    return value
}

internal fun requireAlignedInstantRange(startAt: String, endAt: String, startField: String, endField: String) {
    val start = OffsetDateTime.parse(requireIsoDateTime(startAt, startField))
    val end = OffsetDateTime.parse(requireIsoDateTime(endAt, endField))
    require(end.isAfter(start)) { endField + " 必须晚于 " + startField }
    require(start.minute % 30 == 0 && start.second == 0 && start.nano == 0) {
        startField + " 必须对齐 30 分钟边界"
    }
    require(end.minute % 30 == 0 && end.second == 0 && end.nano == 0) {
        endField + " 必须对齐 30 分钟边界"
    }
}

internal fun requireWireEnum(value: String, allowed: Set<String>, field: String): String {
    require(value in allowed) { field + " 必须是契约枚举值之一" }
    return value
}
