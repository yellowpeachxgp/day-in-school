package cn.edu.haut.campus.core.protocol.jinzhi

/**
 * UNVERIFIED（未验证）：金智课表把"星期 + 节次 + 周次"写在同一列文本里
 * （例如 "星期一 第1-2节 1-16周"），这里按公开写法推导；
 * 任何一项解析不出来就返回 null，由调用方跳过该行，绝不默认成"第 1 周第 1 节"。
 */
internal object JinzhiSchedule {

    private val CHINESE_WEEKDAY = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7
    )
    private val WEEKDAY_TEXT = Regex("""(?:星期|周|礼拜)([一二三四五六日天])""")
    private val WEEKDAY_DIGIT = Regex("""(?:星期|周)\s*([1-7])""")
    private val SECTION_RANGE = Regex("""(\d{1,2})\s*[-~～—]\s*(\d{1,2})\s*节""")
    private val SECTION_SINGLE = Regex("""(\d{1,2})\s*节""")

    class Meeting(val weekday: Int, val startSection: Int, val endSection: Int, val weeks: Set<Int>)

    fun parse(raw: String?): Meeting? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val weekday = parseWeekday(text) ?: return null
        val sections = parseSections(text) ?: return null
        return Meeting(weekday, sections.first, sections.last, parseWeeks(text))
    }

    fun parseWeekday(text: String): Int? {
        WEEKDAY_TEXT.find(text)?.groupValues?.get(1)?.firstOrNull()?.let { ch ->
            CHINESE_WEEKDAY[ch]?.let { return it }
        }
        WEEKDAY_DIGIT.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 1..7) return it }
        return null
    }

    fun parseSections(text: String): IntRange? {
        val range = SECTION_RANGE.find(text)
        if (range != null) {
            val start = range.groupValues[1].toIntOrNull() ?: return null
            val end = range.groupValues[2].toIntOrNull() ?: return null
            if (start > end || start !in 1..60 || end !in 1..60) return null
            return start..end
        }
        val single = SECTION_SINGLE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (single !in 1..60) return null
        return single..single
    }

    fun parseWeeks(text: String): Set<Int> {
        if (text.length > 512) return emptySet()
        val clause = Regex("""[0-9\s,，、\-~～至—]+周(?:\s*[（(]?[单双][)）]?)?""").find(text)?.value ?: return emptySet()
        return cn.edu.haut.campus.core.protocol.TeachingWeekParser.parse(clause)
    }

    /** "2026-01-12" / "2026年1月12日" → 日期。 */
    fun parseDate(raw: String?): java.time.LocalDate? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""(\d{4})\\s*[-/.年]\s*(\d{1,2})\\s*[-/.月]\s*(\d{1,2})""").find(raw) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        return runCatching { java.time.LocalDate.of(year, month, day) }.getOrNull()
    }

    /** "09:00-11:00" → 起止时间。 */
    fun parseTimeRange(raw: String?): Pair<java.time.LocalTime?, java.time.LocalTime?>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('-', '~', '～').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val start = parseTime(parts[0]) ?: return null
        val end = if (parts.size > 1) parseTime(parts[1]) else null
        return start to end
    }

    fun parseTime(raw: String?): java.time.LocalTime? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""(\d{1,2}):(\d{2})""").find(raw) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return java.time.LocalTime.of(hour, minute)
    }
}
