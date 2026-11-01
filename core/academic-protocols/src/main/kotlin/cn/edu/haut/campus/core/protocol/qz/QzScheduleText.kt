package cn.edu.haut.campus.core.protocol.qz

/**
 * UNVERIFIED（未验证）：课表里的周次/节次/星期文本是按公开页面常见写法推导的，
 * 没有针对任何学校校对过全部写法；解析不出来时一律返回空集合，绝不猜测。
 */
internal object QzScheduleText {

    private val ODD = Regex("单")
    private val EVEN = Regex("双")
    private val RANGE = Regex("""^(\d{1,2})\s*[-~～—]\s*(\d{1,2})$""")
    private val SINGLE = Regex("""^(\d{1,2})$""")

    /** "1-16周" / "第1-15周(单)" / "1,3,5周" → 绝对教学周集合；无法解析时返回空集合。 */
    fun parseWeeks(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        val text = raw.replace('\u3000', ' ').replace(" ", "")
        val odd = ODD.containsMatchIn(text)
        val even = EVEN.containsMatchIn(text)
        if (odd && even) return emptySet()
        var head = text.substringBefore('周', text)
        head = head.removeSuffix("节")
        head = head.removePrefix("第")
        if (head.isEmpty()) return emptySet()
        val weeks = sortedSetOf<Int>()
        for (token in head.split(',', '，', '、', ';', '；')) {
            val part = token.trim()
            if (part.isEmpty()) return emptySet()
            val range = RANGE.matchEntire(part)
            if (range != null) {
                val start = range.groupValues[1].toIntOrNull() ?: return emptySet()
                val end = range.groupValues[2].toIntOrNull() ?: return emptySet()
                if (start > end) return emptySet()
                for (week in start..end) weeks += week
                continue
            }
            val single = SINGLE.matchEntire(part)?.groupValues?.get(1)?.toIntOrNull() ?: return emptySet()
            weeks += single
        }
        if (weeks.isEmpty() || weeks.any { it !in 1..80 }) return emptySet()
        return when {
            odd -> weeks.filter { it % 2 == 1 }.toSortedSet()
            even -> weeks.filter { it % 2 == 0 }.toSortedSet()
            else -> weeks
        }.also { if (it.isEmpty()) return emptySet() }
    }

    /** "1-2节" / "第3-4节" / "5" → 节次区间；越界或无法解析时返回 null。 */
    fun parseSections(raw: String?): IntRange? {
        if (raw.isNullOrBlank()) return null
        val text = raw.replace(" ", "").removePrefix("第").removeSuffix("节")
        val range = Regex("""^(\d{1,2})[-~～—](\d{1,2})$""").matchEntire(text)
        if (range != null) {
            val start = range.groupValues[1].toIntOrNull() ?: return null
            val end = range.groupValues[2].toIntOrNull() ?: return null
            if (start > end || start !in 1..60 || end !in 1..60) return null
            return start..end
        }
        val single = Regex("""^(\d{1,2})$""").matchEntire(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (single !in 1..60) return null
        return single..single
    }

    /** "星期一" / "周一" / "礼拜天" / "1" → 1..7；无法解析时返回 null。 */
    fun parseWeekday(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val text = raw.replace(" ", "").replace('\u3000', ' ')
        if (text.isEmpty()) return null
        val chinese = text.firstOrNull { it in CHINESE_DAYS.keys }
        if (chinese != null && text.any { it == '周' || it == '期' || text.startsWith("礼拜") }) {
            return CHINESE_DAYS[chinese]
        }
        val digits = text.filter { it.isDigit() }
        if (digits.length == 1) return digits.toInt().takeIf { it in 1..7 }
        return null
    }

    /** "2026-01-12" / "2026年1月12日" / "2026/1/12" → 日期；解析失败返回 null。 */
    fun parseDate(raw: String?): java.time.LocalDate? {
        if (raw.isNullOrBlank()) return null
        val match = DATE.find(raw.trim()) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        return runCatching { java.time.LocalDate.of(year, month, day) }.getOrNull()
    }

    /** "09:00" / "9:00" → 时间；解析失败返回 null。 */
    fun parseTime(raw: String?): java.time.LocalTime? {
        if (raw.isNullOrBlank()) return null
        val match = TIME.find(raw.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return java.time.LocalTime.of(hour, minute)
    }

    /** "09:00-11:00" / "09:00~11:00" → 起止时间；单值表示只有开始时间。 */
    fun parseTimeRange(raw: String?): Pair<java.time.LocalTime?, java.time.LocalTime?>? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split('-', '~', '～').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val start = parseTime(parts[0]) ?: return null
        val end = if (parts.size > 1) parseTime(parts[1]) else null
        if (parts.size > 1 && end == null) return start to null
        return start to end
    }

    private val DATE = Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})""")
    private val TIME = Regex("""(\d{1,2}):(\d{2})""")

    private val CHINESE_DAYS: Map<Char, Int> = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7
    )
}