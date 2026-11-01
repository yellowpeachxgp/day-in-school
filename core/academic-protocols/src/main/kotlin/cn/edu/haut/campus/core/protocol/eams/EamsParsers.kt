package cn.edu.haut.campus.core.protocol.eams

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * UNVERIFIED：EAMS 页面里常见的"星期/节次/周次/学分/时刻"文本解析。
 * 解析失败返回 null 或空集合，不填充默认值。
 */
internal object EamsParsers {

    private val CHINESE_WEEKDAY = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '日' to 7, '天' to 7
    )
    private val WEEK_RANGE = Regex("(\\d{1,2})\\s*(?:-|~|至|—)\\s*(\\d{1,2})")
    private val WEEK_SINGLE = Regex("\\d{1,2}")
    private val SECTION_RANGE = Regex("(\\d{1,2})\\s*-\\s*(\\d{1,2})")
    private val SECTION_SINGLE = Regex("(\\d{1,2})")

    fun weekday(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.firstOrNull { CHINESE_WEEKDAY.containsKey(it) }?.let { return CHINESE_WEEKDAY[it] }
        val digit = SECTION_SINGLE.find(value)?.value?.toIntOrNull() ?: return null
        return digit.takeIf { it in 1..7 }
    }

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
        return if (single in 1..60) single..single else null
    }

    fun weeks(raw: String?): Set<Int> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return emptySet()
        val result = linkedSetOf<Int>()
        WEEK_RANGE.findAll(value).forEach { match ->
            val start = match.groupValues[1].toIntOrNull() ?: return@forEach
            val end = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (start < 1 || end > 80 || start > end) return@forEach
            for (week in start..end) result += week
        }
        if (result.isEmpty()) {
            WEEK_SINGLE.findAll(value).forEach { match ->
                match.value.toIntOrNull()?.takeIf { it in 1..80 }?.let { result += it }
            }
        }
        return result
    }

    fun decimal(raw: String?): BigDecimal? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching { BigDecimal(value) }.getOrNull()
    }

    fun date(raw: String?): LocalDate? {
        val value = raw?.trim().orEmpty()
        val match = Regex("(\\d{4})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})").find(value) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    fun clock(raw: String?): LocalTime? {
        val value = raw?.trim().orEmpty()
        val match = Regex("(\\d{1,2}):(\\d{2})").find(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }

    fun academicYear(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        val match = Regex("(\\d{4})\\s*[-—~至]\\s*(\\d{4})").find(value) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    fun termOrdinal(raw: String?): Int? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val chinese = mapOf('一' to 1, '二' to 2, '三' to 3)
        val match = Regex("第\\s*([一二三123])\\s*学期").find(value)
        if (match != null) {
            val token = match.groupValues[1]
            return token.toIntOrNull() ?: chinese[token.firstOrNull()]
        }
        return value.trim().toIntOrNull()?.takeIf { it in 1..3 }
    }
}
