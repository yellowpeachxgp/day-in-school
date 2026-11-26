package cn.edu.haut.campus.core.timeline

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

interface IcsExporter {
    fun export(events: List<TimelineEvent>, calendarName: String): String
}

/** ICS 只能安全往返的日历面相字段；scope、账号、source/provenance 不写入导出文件。 */
data class IcsEventSnapshot(
    val uid: String,
    val title: String,
    val start: Instant,
    val end: Instant?,
    val zone: ZoneId,
    val location: String?,
    val kind: TimelineKind?,
    val status: EventStatus?,
    val busy: BusyLevel?,
    val revision: Long?
) {
    companion object {
        fun from(event: TimelineEvent): IcsEventSnapshot = IcsEventSnapshot(
            uid = event.id.value,
            title = event.title,
            start = event.start,
            end = event.end,
            zone = event.zone,
            location = event.place?.id,
            kind = event.kind,
            status = event.status,
            busy = event.busy,
            revision = event.revision
        )
    }
}

class IcsParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * RFC 5545 VEVENT 导出器。
 *
 * UID 逐字使用 TimelineEventId.value；DTSTAMP 使用固定 epoch，因为 TimelineEvent 没有创建时间，
 * 同时保证同一输入导出结果幂等。scope、账号、source/provenance 不进入 ICS；忙闲、真实状态、
 * 截止点语义只放在 dis 私有扩展属性中，避免把学校账号标识泄漏到用户导出的日历文件。
 */
object StandardIcsExporter : IcsExporter {
    private const val CRLF = "\r\n"
    private const val MAX_LINE_OCTETS = 75
    private const val MAX_ICS_SEQUENCE = 2147483647L
    private val localDateTimeFormat: DateTimeFormatter = DateTimeFormatter
        .ofPattern("uuuuMMdd'T'HHmmss")
        .withResolverStyle(ResolverStyle.STRICT)

    override fun export(events: List<TimelineEvent>, calendarName: String): String {
        require(calendarName.isNotBlank() && calendarName.length <= 200) { "日历名称为空或过长" }
        require(calendarName.none { it.isISOControl() }) { "日历名称不能包含控制字符" }
        val deduped = dedupeTimelineEvents(events).sortedWith(
            compareBy<TimelineEvent> { it.start }
                .thenBy { it.end ?: it.start }
                .thenBy { it.id.value }
        )

        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//day-in-school//core-timeline//EN",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:" + escapeText(calendarName)
        )
        deduped.forEach { event ->
            validateExportable(event)
            lines += "BEGIN:VEVENT"
            lines += "UID:" + escapeText(event.id.value)
            lines += "DTSTAMP:19700101T000000Z"
            lines += "DTSTART;TZID=" + quoteParam(event.zone.id) + ":" + formatLocal(event.start, event.zone)
            event.end?.let { end ->
                lines += "DTEND;TZID=" + quoteParam(event.zone.id) + ":" + formatLocal(end, event.zone)
            }
            lines += "SUMMARY:" + escapeText(event.title)
            event.place?.let { place -> lines += "LOCATION:" + escapeText(place.id) }
            lines += "STATUS:" + when (event.status) {
                EventStatus.TENTATIVE -> "TENTATIVE"
                EventStatus.CANCELLED_TODAY -> "CANCELLED"
                EventStatus.SCHEDULED,
                EventStatus.RESCHEDULED,
                EventStatus.DONE -> "CONFIRMED"
            }
            lines += "TRANSP:" + if (event.busy == BusyLevel.FREE) "TRANSPARENT" else "OPAQUE"
            if (event.revision <= MAX_ICS_SEQUENCE) {
                lines += "SEQUENCE:" + event.revision.toString()
            }
            lines += "X-DIS-KIND:" + event.kind.name
            lines += "X-DIS-EVENT-STATUS:" + event.status.name
            lines += "X-DIS-BUSY-LEVEL:" + event.busy.name
            lines += "X-DIS-REVISION:" + event.revision.toString()
            if (event.end == null) lines += "X-DIS-DEADLINE:TRUE"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString(CRLF) { foldLine(it) } + CRLF
    }

    private fun validateExportable(event: TimelineEvent) {
        require(event.start.nano == 0 && (event.end?.nano ?: 0) == 0) {
            "ICS 仅支持秒级精度，事件 ${event.id.value} 含亚秒时间"
        }
    }

    private fun dedupeTimelineEvents(events: List<TimelineEvent>): List<TimelineEvent> {
        val byId = LinkedHashMap<TimelineEventId, TimelineEvent>()
        events.forEach { event ->
            val existing = byId[event.id]
            when {
                existing == null -> byId[event.id] = event
                existing == event -> Unit
                event.revision > existing.revision -> byId[event.id] = event
                event.revision < existing.revision -> Unit
                else -> throw IllegalArgumentException("ICS 导出事件 ${event.id.value} 同 revision 内容冲突")
            }
        }
        return byId.values.toList()
    }

    private fun formatLocal(instant: Instant, zone: ZoneId): String =
        LocalDateTime.ofInstant(instant, zone).format(localDateTimeFormat)

    private fun quoteParam(value: String): String {
        require(value.none { it == '"' || it.isISOControl() }) { "ICS 参数值含引号或控制字符" }
        return "\"" + value + "\""
    }

    internal fun escapeText(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    /** 按 RFC 5545 的 75 octet 上限折叠；续行以一个空格开头，且不拆 UTF-8 字符。 */
    internal fun foldLine(line: String): String {
        val codePoints = line.codePoints().toArray()
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        var currentBytes = 0
        for (codePoint in codePoints) {
            val length = utf8Length(codePoint)
            if (currentBytes + length > MAX_LINE_OCTETS) {
                chunks += current.toString()
                current = StringBuilder(" ")
                currentBytes = 1
            }
            current.appendCodePoint(codePoint)
            currentBytes += length
        }
        chunks += current.toString()
        return chunks.joinToString(CRLF)
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint <= 0x7f -> 1
        codePoint <= 0x7ff -> 2
        codePoint <= 0xffff -> 3
        else -> 4
    }
}

/** 解析 StandardIcsExporter 输出的最小 VEVENT 读取器，用于往返验证和未来导入去重。 */
object IcsParser {
    private val localDateTimeFormat: DateTimeFormatter = DateTimeFormatter
        .ofPattern("uuuuMMdd'T'HHmmss")
        .withResolverStyle(ResolverStyle.STRICT)

    fun parseEvents(text: String): List<IcsEventSnapshot> {
        val lines = unfoldLines(text)
        if (lines.isEmpty() || lines.first() != "BEGIN:VCALENDAR" || lines.last() != "END:VCALENDAR") {
            throw IcsParseException("ICS 文档缺少 VCALENDAR 边界")
        }
        val snapshots = mutableListOf<IcsEventSnapshot>()
        var inEvent = false
        val properties = mutableListOf<IcsProperty>()
        lines.forEach { line ->
            when (line.uppercase()) {
                "BEGIN:VEVENT" -> {
                    if (inEvent) throw IcsParseException("VEVENT 嵌套非法")
                    inEvent = true
                    properties.clear()
                }
                "END:VEVENT" -> {
                    if (!inEvent) throw IcsParseException("END:VEVENT 没有对应 BEGIN")
                    snapshots += parseEvent(properties)
                    properties.clear()
                    inEvent = false
                }
                else -> if (inEvent) properties += parseProperty(line)
            }
        }
        if (inEvent) throw IcsParseException("VEVENT 未闭合")
        return dedupeSnapshots(snapshots)
    }

    private fun parseEvent(properties: List<IcsProperty>): IcsEventSnapshot {
        var uid: String? = null
        var title: String? = null
        var start: Pair<Instant, ZoneId>? = null
        var end: Pair<Instant, ZoneId>? = null
        var location: String? = null
        var disKind: TimelineKind? = null
        var disStatus: EventStatus? = null
        var standardStatus: EventStatus? = null
        var disBusy: BusyLevel? = null
        var standardBusy: BusyLevel? = null
        var disRevision: Long? = null
        var sequence: Long? = null
        var deadline = false

        properties.forEach { property ->
            when (property.name) {
                "UID" -> uid = unescapeText(property.value)
                "SUMMARY" -> title = unescapeText(property.value)
                "DTSTART" -> start = parseDateTime(property)
                "DTEND" -> end = parseDateTime(property)
                "LOCATION" -> location = unescapeText(property.value)
                "STATUS" -> standardStatus = parseStandardStatus(property.value)
                "TRANSP" -> standardBusy = parseStandardTransparency(property.value)
                "SEQUENCE" -> sequence = parseNonNegativeLong(property.value, property.name)
                "X-DIS-KIND" -> disKind = parseEnum<TimelineKind>(property.value, property.name)
                "X-DIS-EVENT-STATUS" -> disStatus = parseEnum<EventStatus>(property.value, property.name)
                "X-DIS-BUSY-LEVEL" -> disBusy = parseEnum<BusyLevel>(property.value, property.name)
                "X-DIS-REVISION" -> disRevision = parseNonNegativeLong(property.value, property.name)
                "X-DIS-DEADLINE" -> deadline = when (property.value.uppercase()) {
                    "TRUE" -> true
                    "FALSE" -> false
                    else -> throw IcsParseException("X-DIS-DEADLINE 必须是 TRUE 或 FALSE")
                }
            }
        }

        val parsedStart = start ?: throw IcsParseException("VEVENT 缺少 DTSTART")
        val parsedEnd = end
        if (parsedEnd != null && parsedEnd.second != parsedStart.second) {
            throw IcsParseException("DTSTART 与 DTEND 时区不一致")
        }
        if (deadline && parsedEnd != null) {
            throw IcsParseException("截止点 VEVENT 不能同时携带 DTEND")
        }
        val endInstant = if (deadline) null else parsedEnd?.first
        if (endInstant != null && !endInstant.isAfter(parsedStart.first)) {
            throw IcsParseException("非截止点 VEVENT 的 DTEND 必须晚于 DTSTART")
        }
        return IcsEventSnapshot(
            uid = uid?.takeIf { it.isNotBlank() } ?: throw IcsParseException("VEVENT 缺少 UID"),
            title = title?.takeIf { it.isNotBlank() } ?: throw IcsParseException("VEVENT 缺少 SUMMARY"),
            start = parsedStart.first,
            end = endInstant,
            zone = parsedStart.second,
            location = location,
            kind = disKind,
            status = disStatus ?: standardStatus,
            busy = disBusy ?: standardBusy,
            revision = disRevision ?: sequence
        )
    }

    private fun parseDateTime(property: IcsProperty): Pair<Instant, ZoneId> {
        val zoneText = property.params["TZID"]
        if (zoneText != null) {
            val zone = parseZone(zoneText)
            return parseLocalDateTime(property.value).atZone(zone).toInstant() to zone
        }
        if (property.value.endsWith('Z')) {
            val zone = ZoneId.of("UTC")
            return parseLocalDateTime(property.value.dropLast(1)).atZone(zone).toInstant() to zone
        }
        throw IcsParseException("日期时间缺少 TZID 或 UTC Z 后缀")
    }

    private fun parseLocalDateTime(value: String): LocalDateTime {
        if (value.length != 15) throw IcsParseException("日期时间格式非法: $value")
        return try {
            LocalDateTime.parse(value, localDateTimeFormat)
        } catch (error: DateTimeException) {
            throw IcsParseException("日期时间格式非法: $value", error)
        }
    }

    private fun parseZone(value: String): ZoneId = try {
        ZoneId.of(value)
    } catch (error: DateTimeException) {
        throw IcsParseException("TZID 非法: $value", error)
    }

    private fun parseStandardStatus(value: String): EventStatus = when (value.uppercase()) {
        "CONFIRMED" -> EventStatus.SCHEDULED
        "TENTATIVE" -> EventStatus.TENTATIVE
        "CANCELLED" -> EventStatus.CANCELLED_TODAY
        else -> throw IcsParseException("STATUS 含未知事件状态 $value")
    }

    private fun parseStandardTransparency(value: String): BusyLevel = when (value.uppercase()) {
        "OPAQUE" -> BusyLevel.BUSY
        "TRANSPARENT" -> BusyLevel.FREE
        else -> throw IcsParseException("TRANSP 含未知透明度 $value")
    }

    private fun parseNonNegativeLong(value: String, property: String): Long {
        val parsed = value.toLongOrNull() ?: throw IcsParseException(property + " 不是整数")
        if (parsed < 0) throw IcsParseException(property + " 不能为负")
        return parsed
    }

    private fun dedupeSnapshots(snapshots: List<IcsEventSnapshot>): List<IcsEventSnapshot> {
        val byUid = LinkedHashMap<String, IcsEventSnapshot>()
        snapshots.forEach { snapshot ->
            val existing = byUid[snapshot.uid]
            when {
                existing == null -> byUid[snapshot.uid] = snapshot
                existing == snapshot -> Unit
                (snapshot.revision ?: Long.MIN_VALUE) > (existing.revision ?: Long.MIN_VALUE) -> byUid[snapshot.uid] = snapshot
                (snapshot.revision ?: Long.MIN_VALUE) < (existing.revision ?: Long.MIN_VALUE) -> Unit
                else -> throw IcsParseException("ICS UID ${snapshot.uid} 重复且内容冲突")
            }
        }
        return byUid.values.toList()
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String, property: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: throw IcsParseException("$property 含未知枚举值 $value")

    private data class IcsProperty(
        val name: String,
        val params: Map<String, String>,
        val value: String
    )

    private fun parseProperty(line: String): IcsProperty {
        val split = splitFirstOutsideQuotes(line, ':') ?: throw IcsParseException("ICS 属性缺少冒号: $line")
        val head = split.first
        val value = split.second
        val parts = splitOutsideQuotes(head, ';')
        val name = parts.first().uppercase()
        if (name.isBlank()) throw IcsParseException("ICS 属性名为空")
        val params = LinkedHashMap<String, String>()
        parts.drop(1).forEach { rawParam ->
            val equals = rawParam.indexOf('=')
            if (equals <= 0) throw IcsParseException("ICS 参数缺少等号: $rawParam")
            val key = rawParam.substring(0, equals).uppercase()
            val rawValue = rawParam.substring(equals + 1)
            if (params.put(key, unquoteParam(rawValue)) != null) {
                throw IcsParseException("ICS 参数重复: $key")
            }
        }
        return IcsProperty(name, params, value)
    }

    private fun splitFirstOutsideQuotes(text: String, delimiter: Char): Pair<String, String>? {
        var quoted = false
        text.forEachIndexed { index, ch ->
            when (ch) {
                '"' -> quoted = !quoted
                delimiter -> if (!quoted) return text.substring(0, index) to text.substring(index + 1)
            }
        }
        return null
    }

    private fun splitOutsideQuotes(text: String, delimiter: Char): List<String> {
        val result = mutableListOf<String>()
        var quoted = false
        var start = 0
        text.forEachIndexed { index, ch ->
            when (ch) {
                '"' -> quoted = !quoted
                delimiter -> if (!quoted) {
                    result += text.substring(start, index)
                    start = index + 1
                }
            }
        }
        if (quoted) throw IcsParseException("ICS 参数引号未闭合")
        result += text.substring(start)
        return result
    }

    private fun unquoteParam(value: String): String {
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.length - 1)
        }
        if (value.firstOrNull() == '"' || value.lastOrNull() == '"') {
            throw IcsParseException("ICS 参数引号不完整")
        }
        return value
    }

    private fun unfoldLines(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val rawLines = normalized.split('\n').let { lines ->
            if (lines.lastOrNull()?.isEmpty() == true) lines.dropLast(1) else lines
        }
        val result = mutableListOf<String>()
        rawLines.forEach { raw ->
            if (raw.isEmpty()) throw IcsParseException("ICS 包含空行")
            if (raw.first() == ' ' || raw.first() == '\t') {
                if (result.isEmpty()) throw IcsParseException("ICS 续行没有前行")
                result[result.lastIndex] = result.last() + raw.substring(1)
            } else {
                result += raw
            }
        }
        return result
    }

    internal fun unescapeText(value: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch != '\\') {
                out.append(ch)
                index++
                continue
            }
            if (index + 1 >= value.length) throw IcsParseException("ICS 文本转义未完成")
            when (val escaped = value[index + 1]) {
                'n', 'N' -> out.append('\n')
                '\\', ';', ',' -> out.append(escaped)
                else -> throw IcsParseException("ICS 文本含未知转义: " + escaped)
            }
            index += 2
        }
        return out.toString()
    }
}
