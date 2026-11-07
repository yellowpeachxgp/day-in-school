package cn.edu.haut.campus.core.protocol.chaoxing

import java.time.LocalDate
import java.time.LocalTime

/**
 * UNVERIFIED：超星教务"上课时间"自由文本的解析，例如
 * `星期一 第1-2节 1-16周`，多段用换行或分号分隔。
 *
 * 注意：只有逗号不能当作分隔符——"1-8,10-16周"里的逗号属于同一个周次表达式。
 * 任何段落解析失败都会拒绝整组时段，不做"默认第一小节"之类的填充。
 */
internal object ChaoxingTimeParser {

    data class Slot(val weekday: Int, val startSection: Int, val endSection: Int, val weeks: Set<Int>)

    private val CHINESE_WEEKDAY = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '日' to 7, '天' to 7
    )
    private val SECTION_RANGE = Regex("(\\d{1,2})\\s*-\\s*(\\d{1,2})")
    private val SECTION_SINGLE = Regex("(\\d{1,2})")

    fun slots(raw: String?): List<Slot> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return emptyList()
        val pieces = value.split('\n', '；', ';').filter { it.isNotBlank() }
        val parsed = pieces.mapNotNull { slot(it) }
        return if (parsed.size == pieces.size) parsed else emptyList()
    }

    fun slot(raw: String?): Slot? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val weekday = weekday(value) ?: return null
        val sections = sections(value) ?: return null
        return Slot(weekday, sections.first, sections.last, weeks(value))
    }

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
        if (raw == null || raw.length > 512) return emptySet()
        val clause = Regex("""[0-9\s,，、\-~～至—]+周(?:\s*[（(]?[单双][)）]?)?""").find(raw)?.value ?: return emptySet()
        return cn.edu.haut.campus.core.protocol.TeachingWeekParser.parse(clause)
    }

    /** 学分/绩点这类小数字段；非法数字返回 null，不降级成 0。 */
    fun decimalOf(raw: String?): java.math.BigDecimal? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return runCatching { java.math.BigDecimal(value) }.getOrNull()
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

/**
 * UNVERIFIED：超星教务与学习通（超星统一认证）必须使用互相隔离的会话。
 *
 * 这里只做一件事：拒绝把学习通 passport 域作为教务会话来源，
 * 避免同一个 Cookie 容器同时持有学习通账号与教务账号。
 */
internal object ChaoxingIsolation {

    fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")

    fun isStudyAccountHost(url: String): Boolean {
        val host = hostOf(url)
        return host == "chaoxing.com" || host.endsWith(".chaoxing.com")
    }

    fun requirementMessage(): String = "超星教务必须使用独立会话，不能复用学习通账号"
}
