package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.domain.WeeksSetParser

/**
 * 教学周集合。
 *
 * UNVERIFIED：本模块不内置任何真实学校的周次文本格式；[parse] 只复用项目已有的正方风格周次解析器，
 * 其他教务系统的写法必须由调用方先转换成明确的周集合再构造，不得凭猜测推断。
 *
 * 空集合表示“未解析出周次”，此时 [intersects] 按“可能冲突”处理：
 * 把未知当安全会直接导致用户撞课，因此在缺证据时一律保守。
 */
data class TeachingWeeks(val weeks: Set<Int>) {

    init {
        require(weeks.all { it in MIN_WEEK..MAX_WEEK }) { "教学周必须在 $MIN_WEEK..$MAX_WEEK 之间" }
    }

    /** 是否真的解析出了周次；空集合代表未知，而不是“没有课”。 */
    val isKnown: Boolean get() = weeks.isNotEmpty()

    /**
     * 周次是否有交集。任一方未知时返回 true（保守判定），
     * 调用方必须结合 [sharedWeeks] 为空且 [isKnown] 为 false 来判断“这是不确定性而非确定冲突”。
     */
    fun intersects(other: TeachingWeeks): Boolean {
        if (!isKnown || !other.isKnown) return true
        return weeks.any { it in other.weeks }
    }

    /** 共同周次；任一方未知时返回空集合（不伪造共同周次）。 */
    fun sharedWeeks(other: TeachingWeeks): Set<Int> {
        if (!isKnown || !other.isKnown) return emptySet()
        return weeks.filterTo(sortedSetOf()) { it in other.weeks }
    }

    /** 给用户看的周次文本；未知时明确显示未知，不显示成空周次。 */
    val displayText: String
        get() = if (isKnown) WeeksSetParser.format(weeks) else "周次未知"

    companion object {
        const val MIN_WEEK: Int = 1
        const val MAX_WEEK: Int = 80

        val UNKNOWN: TeachingWeeks = TeachingWeeks(emptySet())

        fun of(weeks: Iterable<Int>): TeachingWeeks = TeachingWeeks(weeks.toSortedSet())

        /** 连续周次，如 1..16。 */
        fun range(firstWeek: Int, lastWeek: Int): TeachingWeeks {
            require(firstWeek <= lastWeek) { "起始周不能晚于结束周" }
            return of(firstWeek..lastWeek)
        }

        /** 单周（奇数周），如 1,3,...,15。 */
        fun odd(firstWeek: Int, lastWeek: Int): TeachingWeeks {
            require(firstWeek <= lastWeek) { "起始周不能晚于结束周" }
            return of((firstWeek..lastWeek).filter { it % 2 != 0 })
        }

        /** 双周（偶数周），如 2,4,...,16。 */
        fun even(firstWeek: Int, lastWeek: Int): TeachingWeeks {
            require(firstWeek <= lastWeek) { "起始周不能晚于结束周" }
            return of((firstWeek..lastWeek).filter { it % 2 == 0 })
        }

        /** 解析周次文本；空白或无法解析时返回 [UNKNOWN]，绝不猜测。 */
        fun parse(raw: String?): TeachingWeeks {
            if (raw.isNullOrBlank()) return UNKNOWN
            val parsed = WeeksSetParser.parse(raw)
            return if (parsed.isEmpty()) UNKNOWN else of(parsed)
        }
    }
}

/** 一次上课时段：星期 + 节次半开区间 [startSection, endSection + 1) + 周次。 */
data class ClassSlot(
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: TeachingWeeks = TeachingWeeks.UNKNOWN,
    val location: String? = null
) {

    init {
        require(weekday in 1..7) { "星期必须在 1..7 之间" }
        require(startSection in 1..MAX_SECTION && endSection in 1..MAX_SECTION && startSection <= endSection) {
            "节次范围无效：$startSection-$endSection"
        }
    }

    /**
     * 节次是否重叠。按半开区间 [startSection, endSection + 1) 比较，
     * 因此相邻节次（1-2 与 3-4）不算冲突，而共享一节（1-2 与 2-3）算冲突。
     */
    fun sharesSectionWith(other: ClassSlot): Boolean =
        weekday == other.weekday &&
            startSection < other.endSection + 1 &&
            other.startSection < endSection + 1

    val weekdayLabel: String get() = weekdayLabelOf(weekday)

    val sectionText: String
        get() = sectionTextOf(startSection, endSection)

    companion object {
        const val MAX_SECTION: Int = 60
        private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        fun weekdayLabelOf(weekday: Int): String {
            require(weekday in 1..7) { "星期必须在 1..7 之间" }
            return WEEKDAY_LABELS[weekday - 1]
        }

        fun sectionTextOf(startSection: Int, endSection: Int): String =
            if (startSection == endSection) "第 $startSection 节" else "第 $startSection-$endSection 节"
    }
}
