package cn.edu.haut.campus.core.protocol.zf

import cn.edu.haut.campus.core.model.json.JsonValue
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 一场考试的日期与时间；任一段解析不出来就保持 null，不猜。 */
internal data class ZfExamSlot(val date: LocalDate?, val start: LocalTime?, val end: LocalTime?)

/**
 * 两套正方形态共用的取值解析（UNVERIFIED）。
 *
 * 输入既可能是 JSON 字段，也可能是 HTML 单元格文本；全部按公开样例形态推导，
 * 未与真实学校返回核对。
 */
internal object ZfParsing {

    private val SECTION_NUMBER = Regex("""\d{1,2}""")
    private val EXAM_SLOT = Regex(
        """(\d{4})-(\d{1,2})-(\d{1,2})""" +
            """(?:[\sT]+(\d{1,2}):(\d{2}))?""" +
            """(?:\s*[-~至到]+\s*(\d{1,2}):(\d{2}))?"""
    )
    private val TIME_ONLY = Regex("""^(\d{1,2}):(\d{2})$""")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-M-d")

    /** JSON 数字字段既可能是 3，也可能是 "3"。 */
    fun int(value: JsonValue?): Int? = value?.let { it.intOrNull() ?: it.stringOrNull()?.trim()?.toIntOrNull() }

    /** JSON 文本字段既可能是 "3"，也可能是 3。 */
    fun text(value: JsonValue?): String? =
        value?.let { it.stringOrNull() ?: it.intOrNull()?.toString() }?.trim()?.takeIf { it.isNotEmpty() }

    /** 学期 sourceId 约定为“学年-学期代码”，两套形态都靠它拼查询参数。 */
    fun splitTermCode(sourceId: String?): Pair<String, String>? {
        val parts = sourceId?.trim()?.split('-') ?: return null
        if (parts.size != 2) return null
        val year = parts[0]
        val term = parts[1]
        if (year.isEmpty() || term.isEmpty()) return null
        if (!year.all { it.isDigit() } || !term.all { it.isDigit() }) return null
        return year to term
    }

    /** 节次文本："1-2"、"第1-2节"、"3" 都能解析；越界返回 null。 */
    fun sections(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val numbers = SECTION_NUMBER.findAll(raw).map { it.value.toInt() }.toList()
        return when (numbers.size) {
            0 -> null
            1 -> validSections(numbers[0], numbers[0])
            else -> validSections(numbers[0], numbers[1])
        }
    }

    fun validSections(start: Int, end: Int): Pair<Int, Int>? =
        if (start in 1..60 && end in 1..60 && start <= end) start to end else null

    /** 考试时间："2026-01-12 09:00-11:00"、"2026-01-12 09:00"、"2026-01-12"。 */
    fun examSlot(raw: String?): ZfExamSlot? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val match = EXAM_SLOT.find(text) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val date = runCatching { LocalDate.parse(year.toString() + "-" + month + "-" + day, DATE) }.getOrNull()
        val start = timeOf(match.groupValues[4], match.groupValues[5])
        val end = timeOf(match.groupValues[6], match.groupValues[7])
        if (date == null && start == null && end == null) return null
        return ZfExamSlot(date, start, end)
    }

    /** 单独的时间字段："09:00" 或 "9:00"。 */
    fun time(raw: String?): LocalTime? {
        val match = TIME_ONLY.matchEntire(raw?.trim().orEmpty()) ?: return null
        return timeOf(match.groupValues[1], match.groupValues[2])
    }

    private fun timeOf(hour: String, minute: String): LocalTime? {
        if (hour.isEmpty() || minute.isEmpty()) return null
        val h = hour.toIntOrNull() ?: return null
        val m = minute.toIntOrNull() ?: return null
        return runCatching { LocalTime.of(h, m) }.getOrNull()
    }
}
