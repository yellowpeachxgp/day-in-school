package cn.edu.haut.campus.core.inbox

import cn.edu.haut.campus.core.model.Term
import java.time.DayOfWeek
import java.time.LocalDate

/** 教学周上下文。weekStartDay 与学期排课周保持一致，默认周一。 */
data class TermContext(
    val termId: String,
    val startDate: LocalDate,
    val weekStartDay: DayOfWeek = DayOfWeek.MONDAY,
    val weekCount: Int = 20
) {
    init {
        require(termId.isNotBlank() && termId.length <= 128) { "termId 为空或过长" }
        require(termId.none { it.isISOControl() || it.isWhitespace() }) { "termId 不能包含空白或控制字符" }
        require(weekCount in 1..60) { "教学周数量超出合理范围" }
    }

    companion object {
        fun fromTerm(term: Term): TermContext = TermContext(
            termId = term.id,
            startDate = term.startDate,
            weekStartDay = term.weekStartDay,
            weekCount = term.weekCount
        )
    }
}

enum class DateResolutionStatus {
    RESOLVED,
    OUT_OF_TERM,
    UNRECOGNIZED
}

/** 日期短语自带的时间段提示；不是精确时刻，精确时刻由规则解析器另行抽取。 */
enum class DateTimeHint {
    NONE,
    EVENING
}

/** 相对日期解析结果。除 RESOLVED 外不得伪造 date。 */
data class DateResolution(
    val status: DateResolutionStatus,
    val date: LocalDate?,
    val matchedText: String,
    val span: TextSpan?,
    val timeHint: DateTimeHint = DateTimeHint.NONE,
    val confidence: Confidence = Confidence.LOW,
    val reason: String? = null
) {
    init {
        if (status == DateResolutionStatus.RESOLVED) {
            requireNotNull(date) { "RESOLVED 日期解析必须携带 date" }
        } else {
            require(date == null) { "未解析/越界日期不得伪造 date" }
        }
        require(matchedText.length <= 256) { "日期命中片段过长" }
    }

    val resolved: Boolean get() = status == DateResolutionStatus.RESOLVED && date != null
}

/** MASTER_PLAN §6.4 的相对日期解析 Port。span 可以是完整通知，也可以是小片段。 */
interface RelativeDateResolver {
    fun resolve(span: String, reference: LocalDate, term: TermContext): DateResolution
}

/**
 * 中文校园口语相对日期解析器。
 *
 * 覆盖：今天/明天/后天/大后天、今晚/今天晚上/明晚、本周/这周/下周/上周 + 星期几、
 * 未限定「周几」（取 reference 当天或之后最近一次，置信度 MEDIUM）、第 N 周星期几。
 * 解析器只返回日期与时间段提示，不猜测精确时刻；「本周几」严格按当前教学周历周解释，
 * 即使已经过去也不悄悄改成下周，避免把已发生通知误写成未来安排。
 */
object ChineseRelativeDateResolver : RelativeDateResolver {

    private data class Candidate(
        val start: Int,
        val end: Int,
        val priority: Int,
        val status: DateResolutionStatus,
        val date: LocalDate?,
        val timeHint: DateTimeHint,
        val confidence: Confidence,
        val reason: String? = null
    )

    private val teachingWeekPattern = Regex(
        """第\s*([0-9]{1,2}|[零〇一二三四五六七八九十]{1,4})\s*周\s*(?:周|星期|礼拜)?([一二三四五六日天1-7])"""
    )
    private val explicitWeekPattern = Regex(
        """(本|这|[上下]+)\s*(?:个)?\s*(?:周|星期|礼拜)\s*(?:周|星期|礼拜)?([一二三四五六日天1-7])"""
    )
    private val fixedDayPattern = Regex(
        """(今天晚上|大后天|后天|明天|明晚|今晚|今天)"""
    )
    private val bareWeekdayPattern = Regex(
        """(?<![本这上下每逢])(?:周|星期|礼拜)([一二三四五六日天1-7])"""
    )

    override fun resolve(span: String, reference: LocalDate, term: TermContext): DateResolution {
        if (span.isBlank()) return unresolved(span, "输入片段为空")
        return resolveAll(span, reference, term).firstOrNull()
            ?: unresolved(span, "未识别支持的相对日期短语")
    }

    /** 在完整通知文本中解析全部互不重叠的日期短语，供规则解析器定位 sourceSpan。 */
    fun resolveAll(text: String, reference: LocalDate, term: TermContext): List<DateResolution> {
        if (text.isBlank() || text.length > ExtractedText.MAX_TEXT_CHARS) return emptyList()
        val candidates = ArrayList<Candidate>()

        teachingWeekPattern.findAll(text).forEach { match ->
            val week = parseChineseOrArabicInt(match.groupValues[1])
            val weekday = parseDayOfWeek(match.groupValues[2])
            if (week != null && weekday != null) {
                if (week !in 1..term.weekCount) {
                    candidates += Candidate(
                        start = match.range.first,
                        end = match.range.last + 1,
                        priority = 100,
                        status = DateResolutionStatus.OUT_OF_TERM,
                        date = null,
                        timeHint = DateTimeHint.NONE,
                        confidence = Confidence.LOW,
                        reason = "教学周 " + week + " 超出本学期 1.." + term.weekCount
                    )
                } else {
                    candidates += Candidate(
                        start = match.range.first,
                        end = match.range.last + 1,
                        priority = 100,
                        status = DateResolutionStatus.RESOLVED,
                        date = teachingWeekDate(term, week, weekday),
                        timeHint = DateTimeHint.NONE,
                        confidence = Confidence.HIGH
                    )
                }
            }
        }

        explicitWeekPattern.findAll(text).forEach { match ->
            val directionToken = match.groupValues[1]
            val direction = when {
                directionToken == "本" || directionToken == "这" -> 0L
                directionToken.all { it == '下' } -> directionToken.length.toLong()
                directionToken.all { it == '上' } -> -directionToken.length.toLong()
                else -> return@forEach
            }
            val weekday = parseDayOfWeek(match.groupValues[2]) ?: return@forEach
            val weekStart = startOfWeek(reference, term.weekStartDay).plusWeeks(direction)
            val date = weekStart.plusDays(dayOffset(term.weekStartDay, weekday).toLong())
            candidates += Candidate(
                start = match.range.first,
                end = match.range.last + 1,
                priority = 90,
                status = DateResolutionStatus.RESOLVED,
                date = date,
                timeHint = DateTimeHint.NONE,
                confidence = Confidence.HIGH
            )
        }

        fixedDayPattern.findAll(text).forEach { match ->
            val word = match.groupValues[1]
            val days = when (word) {
                "明天", "明晚" -> 1L
                "后天" -> 2L
                "大后天" -> 3L
                else -> 0L
            }
            val timeHint = when (word) {
                "今晚", "今天晚上", "明晚" -> DateTimeHint.EVENING
                else -> DateTimeHint.NONE
            }
            candidates += Candidate(
                start = match.range.first,
                end = match.range.last + 1,
                priority = 80,
                status = DateResolutionStatus.RESOLVED,
                date = reference.plusDays(days),
                timeHint = timeHint,
                confidence = Confidence.HIGH
            )
        }

        bareWeekdayPattern.findAll(text).forEach { match ->
            val weekday = parseDayOfWeek(match.groupValues[1]) ?: return@forEach
            val daysAhead = (weekday.value - reference.dayOfWeek.value + 7) % 7
            candidates += Candidate(
                start = match.range.first,
                end = match.range.last + 1,
                priority = 10,
                status = DateResolutionStatus.RESOLVED,
                date = reference.plusDays(daysAhead.toLong()),
                timeHint = DateTimeHint.NONE,
                confidence = Confidence.MEDIUM
            )
        }

        val selected = ArrayList<Candidate>()
        candidates.sortedWith(
            compareBy<Candidate> { it.start }
                .thenByDescending { it.end - it.start }
                .thenByDescending { it.priority }
        ).forEach { candidate ->
            if (selected.none { overlaps(it, candidate) }) {
                selected += candidate
            }
        }

        return selected.sortedBy { it.start }.map { candidate ->
            val matchedText = text.substring(candidate.start, candidate.end)
            DateResolution(
                status = candidate.status,
                date = candidate.date,
                matchedText = matchedText,
                span = TextSpan(candidate.start, candidate.end),
                timeHint = candidate.timeHint,
                confidence = candidate.confidence,
                reason = candidate.reason
            )
        }
    }

    private fun unresolved(input: String, reason: String): DateResolution = DateResolution(
        status = DateResolutionStatus.UNRECOGNIZED,
        date = null,
        matchedText = input.take(256),
        span = null,
        timeHint = DateTimeHint.NONE,
        confidence = Confidence.LOW,
        reason = reason
    )

    private fun overlaps(left: Candidate, right: Candidate): Boolean =
        left.start < right.end && right.start < left.end

    private fun startOfWeek(date: LocalDate, weekStartDay: DayOfWeek): LocalDate =
        date.minusDays(dayOffset(weekStartDay, date.dayOfWeek).toLong())

    private fun dayOffset(weekStartDay: DayOfWeek, target: DayOfWeek): Int =
        (target.value - weekStartDay.value + 7) % 7

    private fun teachingWeekDate(term: TermContext, teachingWeek: Int, weekday: DayOfWeek): LocalDate {
        val alignedStart = startOfWeek(term.startDate, term.weekStartDay)
        val targetWeekStart = alignedStart.plusWeeks((teachingWeek - 1).toLong())
        return targetWeekStart.plusDays(dayOffset(term.weekStartDay, weekday).toLong())
    }

    private fun parseDayOfWeek(raw: String): DayOfWeek? = when (raw) {
        "一", "1" -> DayOfWeek.MONDAY
        "二", "2" -> DayOfWeek.TUESDAY
        "三", "3" -> DayOfWeek.WEDNESDAY
        "四", "4" -> DayOfWeek.THURSDAY
        "五", "5" -> DayOfWeek.FRIDAY
        "六", "6" -> DayOfWeek.SATURDAY
        "日", "天", "7" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun parseChineseOrArabicInt(raw: String): Int? {
        raw.toIntOrNull()?.let { return it }
        if (raw.isBlank() || raw.length > 4) return null
        val digits = mapOf(
            '零' to 0, '〇' to 0,
            '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        var total = 0
        var pending = 0
        var sawTen = false
        for (ch in raw) {
            if (ch == '十') {
                val tens = if (pending == 0) 1 else pending
                total += tens * 10
                pending = 0
                sawTen = true
            } else {
                pending = digits[ch] ?: return null
            }
        }
        val value = total + pending
        if (!sawTen && raw.length > 1) return null
        return value
    }
}
