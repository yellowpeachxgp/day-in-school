package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TermDateStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class EffectiveScheduleDate(
    val actualDate: LocalDate,
    val scheduleDateUsed: LocalDate,
    val teachingWeek: Int?,
    val weekdayUsed: DayOfWeek,
    val status: TermDateStatus,
    val calendarNotice: String? = null
)

object TeachingWeekCalculator {

    /**
     * 将实际日期映射为教学周与排课基准日期（支持节假日停课、调休补课按某天课表执行、特殊周次覆盖）。
     */
    fun resolveDate(
        date: LocalDate,
        term: Term,
        overrides: List<CalendarOverride> = emptyList()
    ): EffectiveScheduleDate {
        val override = overrides.firstOrNull { it.termId == term.id && it.date == date }
        if (override != null) {
            when (override.overrideType) {
                CalendarOverrideType.HOLIDAY_NO_CLASS -> {
                    val rawWeek = calculateRawTeachingWeek(date, term)
                    return EffectiveScheduleDate(
                        actualDate = date,
                        scheduleDateUsed = date,
                        teachingWeek = rawWeek,
                        weekdayUsed = date.dayOfWeek,
                        status = TermDateStatus.HOLIDAY_NO_CLASS,
                        calendarNotice = override.title
                    )
                }
                CalendarOverrideType.MAKEUP_FOLLOW_DATE -> {
                    val follow = override.followDate ?: date
                    val followWeek = calculateRawTeachingWeek(follow, term)
                    return EffectiveScheduleDate(
                        actualDate = date,
                        scheduleDateUsed = follow,
                        teachingWeek = followWeek,
                        weekdayUsed = follow.dayOfWeek,
                        status = TermDateStatus.MAKEUP_RESCHEDULED,
                        calendarNotice = override.title
                    )
                }
                CalendarOverrideType.CUSTOM_TEACHING_WEEK -> {
                    val week = override.customTeachingWeek ?: calculateRawTeachingWeek(date, term)
                    return EffectiveScheduleDate(
                        actualDate = date,
                        scheduleDateUsed = date,
                        teachingWeek = week,
                        weekdayUsed = date.dayOfWeek,
                        status = TermDateStatus.IN_TERM,
                        calendarNotice = override.title
                    )
                }
            }
        }

        val alignedStart = alignToWeekStart(term.startDate, term.weekStartDay)
        val daysBetween = ChronoUnit.DAYS.between(alignedStart, date)
        if (daysBetween < 0) {
            return EffectiveScheduleDate(
                actualDate = date,
                scheduleDateUsed = date,
                teachingWeek = null,
                weekdayUsed = date.dayOfWeek,
                status = TermDateStatus.BEFORE_TERM,
                calendarNotice = "距开学还有 ${ChronoUnit.DAYS.between(date, term.startDate).coerceAtLeast(1)} 天"
            )
        }

        val weekNo = (daysBetween / 7).toInt() + 1
        if (weekNo > term.weekCount) {
            return EffectiveScheduleDate(
                actualDate = date,
                scheduleDateUsed = date,
                teachingWeek = null,
                weekdayUsed = date.dayOfWeek,
                status = TermDateStatus.AFTER_TERM,
                calendarNotice = "本学期课程已结束"
            )
        }

        return EffectiveScheduleDate(
            actualDate = date,
            scheduleDateUsed = date,
            teachingWeek = weekNo,
            weekdayUsed = date.dayOfWeek,
            status = TermDateStatus.IN_TERM
        )
    }

    fun calculateRawTeachingWeek(date: LocalDate, term: Term): Int? {
        val alignedStart = alignToWeekStart(term.startDate, term.weekStartDay)
        val daysBetween = ChronoUnit.DAYS.between(alignedStart, date)
        if (daysBetween < 0) return null
        val weekNo = (daysBetween / 7).toInt() + 1
        return if (weekNo in 1..term.weekCount) weekNo else null
    }

    fun datesOfTeachingWeek(term: Term, teachingWeek: Int): List<LocalDate> {
        require(teachingWeek >= 1) { "teachingWeek must be >= 1" }
        val alignedStart = alignToWeekStart(term.startDate, term.weekStartDay)
        val weekMonday = alignedStart.plusWeeks((teachingWeek - 1).toLong())
        return (0L..6L).map { weekMonday.plusDays(it) }
    }

    private fun alignToWeekStart(startDate: LocalDate, weekStartDay: DayOfWeek): LocalDate {
        val diff = (startDate.dayOfWeek.value - weekStartDay.value + 7) % 7
        return startDate.minusDays(diff.toLong())
    }
}

object WeeksSetParser {

    /**
     * 解析正方教务常见周次字符串（如 "1-16周", "1-15周(单)", "2-16周(双)", "1-4周,7-14周(双),16周"）为精确周集合。
     */
    fun parse(raw: String): Set<Int> {
        if (raw.isBlank()) return emptySet()
        val normalized = raw
            .replace("，", ",")
            .replace("；", ",")
            .replace(";", ",")
            .replace(" ", "")

        val result = sortedSetOf<Int>()
        for (segment in normalized.split(",").filter { it.isNotBlank() }) {
            val isOddOnly = segment.contains("单")
            val isEvenOnly = segment.contains("双")
            val cleaned = segment
                .replace(Regex("\\(.*?\\)|（.*?）"), "")
                .replace("单周", "")
                .replace("双周", "")
                .replace("单", "")
                .replace("双", "")
                .replace("第", "")
                .replace("周", "")
                .trim()

            if (cleaned.isEmpty()) continue
            val rangeParts = cleaned.split("-")
            val candidates = when (rangeParts.size) {
                1 -> listOfNotNull(rangeParts[0].toIntOrNull())
                2 -> {
                    val start = rangeParts[0].toIntOrNull()
                    val end = rangeParts[1].toIntOrNull()
                    if (start != null && end != null && start <= end) {
                        (start..end).toList()
                    } else {
                        emptyList()
                    }
                }
                else -> emptyList()
            }

            for (week in candidates) {
                when {
                    isOddOnly && week % 2 != 0 -> result.add(week)
                    isEvenOnly && week % 2 == 0 -> result.add(week)
                    !isOddOnly && !isEvenOnly -> result.add(week)
                }
            }
        }
        return result
    }

    /**
     * 将周集合压缩为紧凑可读文本，如 "1-16周"、"1-15单周"、"2-16双周"。
     */
    fun format(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return "未设置周次"
        val sorted = weeks.sorted()
        if (sorted.size == 1) return "第${sorted.first()}周"

        val min = sorted.first()
        val max = sorted.last()
        val fullRange = (min..max).toSet()
        if (weeks == fullRange) {
            return "${min}-${max}周"
        }
        val oddRange = (min..max).filter { it % 2 == 1 }.toSet()
        if (sorted.size >= 3 && weeks == oddRange) {
            return "${min}-${max}单周"
        }
        val evenRange = (min..max).filter { it % 2 == 0 }.toSet()
        if (sorted.size >= 3 && weeks == evenRange) {
            return "${min}-${max}双周"
        }

        val ranges = mutableListOf<String>()
        var rangeStart = sorted.first()
        var prev = rangeStart
        for (i in 1 until sorted.size) {
            val current = sorted[i]
            if (current == prev + 1) {
                prev = current
            } else {
                ranges.add(if (rangeStart == prev) "$rangeStart" else "$rangeStart-$prev")
                rangeStart = current
                prev = current
            }
        }
        ranges.add(if (rangeStart == prev) "$rangeStart" else "$rangeStart-$prev")
        return ranges.joinToString(",") + "周"
    }
}
