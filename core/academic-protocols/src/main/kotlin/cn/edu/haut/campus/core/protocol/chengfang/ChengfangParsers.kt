package cn.edu.haut.campus.core.protocol.chengfang

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * UNVERIFIED：乘方课表/成绩页面上常见的文本字段解析。
 *
 * 解析失败一律返回 null 或空集合，由调用方决定"缺字段"还是"整页不可信"；
 * 这里不做任何默认值填充，避免把猜测写进课表。
 */
internal object ChengfangParsers {

    private val CHINESE_WEEKDAY = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '日' to 7, '天' to 7
    )

    private val SECTION_RANGE = Regex("(\\d{1,2})\\s*-\\s*(\\d{1,2})")
    private val SECTION_SINGLE = Regex("(\\d{1,2})")
    private val CLOCK = Regex("(\\d{1,2}):(\\d{2})")

    /** "星期一" / "周一" / "1" 都解析为 1..7；无法判定返回 null。 */
    fun weekday(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.firstOrNull { CHINESE_WEEKDAY.containsKey(it) }?.let { return CHINESE_WEEKDAY[it] }
        val digit = SECTION_SINGLE.find(value)?.value?.toIntOrNull() ?: return null
        return digit.takeIf { it in 1..7 }
    }

    /** "1-2" / "第3-4节" / "5" 解析为闭区间；越界或倒序返回 null。 */
    fun sections(raw: String?): IntRange? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val range = SECTION_RANGE.find(value)
        if (range != null) {
            val start = range.groupValues[1].toIntOrNull() ?: return null
            val end = range.groupValues[2].toIntOrNull() ?: return null
            if (start !in 1..60 || end !in 1..60 || start > end) return null
            return start..end
        }
        val single = SECTION_SINGLE.find(value)?.value?.toIntOrNull() ?: return null
        if (single !in 1..60) return null
        return single..single
    }

    /** "1-16周" / "1-8,10-16周" / "第3周"；解析不出时返回空集而不是 1..16 之类的猜测。 */
    fun weeks(raw: String?): Set<Int> = cn.edu.haut.campus.core.protocol.TeachingWeekParser.parse(raw)

    fun decimal(raw: String?): BigDecimal? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching { BigDecimal(value) }.getOrNull()
    }

    fun date(raw: String?): LocalDate? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val match = Regex("(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})").find(value) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /** "09:00-11:00" 之类的考试时间区间；只有一个时刻时只填开始时间。 */
    fun timeRange(raw: String?): Pair<LocalTime?, LocalTime?> {
        val value = raw?.trim().orEmpty()
        val times = CLOCK.findAll(value).mapNotNull { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val minute = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            runCatching { LocalTime.of(hour, minute) }.getOrNull()
        }.toList()
        return when {
            times.isEmpty() -> null to null
            times.size == 1 -> times[0] to null
            else -> times[0] to times[1]
        }
    }

    /** "2026-2027学年第一学期" → 2026；认不出返回 null。 */
    fun academicYear(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        val match = Regex("(\\d{4})\\s*[-—~至]\\s*(\\d{4})").find(value) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    /** "第一学期" / "第2学期" / "秋季学期" → 1..3；认不出返回 null。 */
    fun termOrdinal(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val chinese = mapOf('一' to 1, '二' to 2, '三' to 3)
        val match = Regex("第\\s*([一二三123])\\s*学期").find(value)
        if (match != null) {
            val token = match.groupValues[1]
            return token.toIntOrNull() ?: chinese[token.firstOrNull()]
        }
        return when {
            value.contains("秋季") -> 1
            value.contains("春季") -> 2
            value.contains("夏季") || value.contains("小学期") -> 3
            else -> null
        }
    }
}
