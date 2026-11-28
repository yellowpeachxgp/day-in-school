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

internal fun requireOptionalText(value: String?, field: String, maxLength: Int? = null, allowBlank: Boolean = false): String? {
    if (value == null) return null
    if (!allowBlank) return requireText(value, field, maxLength)
    if (maxLength != null) require(value.length <= maxLength) { field + " 长度不能超过 " + maxLength }
    return value
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

internal fun requireAlignedInstantRange(
    startAt: String,
    endAt: String,
    startField: String,
    endField: String,
    granularityMinutes: Int = 30,
) {
    require(granularityMinutes >= 30 && granularityMinutes % 30 == 0) {
        "granularity_minutes 必须是 30 的正整数倍"
    }
    val start = OffsetDateTime.parse(requireIsoDateTime(startAt, startField))
    val end = OffsetDateTime.parse(requireIsoDateTime(endAt, endField))
    require(end.isAfter(start)) { endField + " 必须晚于 " + startField }
    val granularityMillis = granularityMinutes.toLong() * 60_000L
    val startMillis = start.toInstant().toEpochMilli()
    val endMillis = end.toInstant().toEpochMilli()
    require(startMillis % granularityMillis == 0L) {
        startField + " 必须按 UTC 绝对时间对齐 " + granularityMinutes + " 分钟边界"
    }
    require(endMillis % granularityMillis == 0L) {
        endField + " 必须按 UTC 绝对时间对齐 " + granularityMinutes + " 分钟边界"
    }
}

internal fun epochMillisIsoDateTime(value: String): Long = OffsetDateTime.parse(value).toInstant().toEpochMilli()

internal fun requireUuid(value: String, field: String): String {
    requireText(value, field, 64)
    require(value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) {
        field + " 必须是 UUID"
    }
    return value
}

internal fun requireLimit(limit: Int?, field: String = "limit"): Int? {
    if (limit != null) require(limit in 1..100) { field + " 必须在 1..100" }
    return limit
}

internal fun requireGroupInviteCode(value: String, field: String = "code"): String {
    requireText(value, field, 64)
    require(value.length >= 8 && value.matches(Regex("^[A-Za-z0-9_-]+$"))) {
        field + " 必须是 8..64 位邀请码字符"
    }
    return value
}

internal fun requireBboxQuery(value: String, field: String = "bbox"): String {
    requireText(value, field, 120)
    val parts = value.split(',')
    require(parts.size == 4) { field + " 必须是 minLat,minLng,maxLat,maxLng 四个十进制数" }
    val numbers = parts.mapIndexed { index, part ->
        val parsed = part.toDoubleOrNull() ?: throw IllegalArgumentException(field + " 第 " + (index + 1) + " 项必须是数字")
        require(java.lang.Double.isFinite(parsed)) { field + " 不允许 NaN 或 Infinity" }
        parsed
    }
    require(numbers[0] in -90.0..90.0 && numbers[2] in -90.0..90.0) { field + " 纬度必须在 -90..90" }
    require(numbers[1] in -180.0..180.0 && numbers[3] in -180.0..180.0) { field + " 经度必须在 -180..180" }
    require(numbers[0] < numbers[2] && numbers[1] < numbers[3]) { field + " 下限必须小于上限" }
    return value
}

internal fun requirePlaceRef(value: String, field: String = "place_ref"): String {
    requireText(value, field, 256)
    require(
        value.matches(
            Regex("^[A-Za-z0-9_-]{1,128}/[A-Za-z0-9_-]{1,128}/(campus|building|floor|room|facility|entrance|indoor_node)/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"),
        ),
    ) { field + " 必须是 <schoolId>/<campusId>/<type>/<slug> 形式的 Campus Graph place_ref" }
    return value
}

internal fun requireWireEnum(value: String, allowed: Set<String>, field: String): String {
    require(value in allowed) { field + " 必须是契约枚举值之一" }
    return value
}
