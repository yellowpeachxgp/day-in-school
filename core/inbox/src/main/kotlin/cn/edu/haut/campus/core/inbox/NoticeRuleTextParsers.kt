package cn.edu.haut.campus.core.inbox

import java.time.LocalTime

internal data class CourseMention(
    val candidate: InboxCourseCandidate,
    val span: TextSpan,
    val confidence: Confidence
)

internal data class CourseMentionSelection(
    val mention: CourseMention?,
    val ambiguous: Boolean = false
)

/** 中文通知的保守语境窗口。硬句边界用于跨句隔离；逗号边界用于动作状态与时刻修饰。 */
internal object NoticeTextContext {
    val sentenceBoundaryChars: Set<Char> = setOf('\n', '\r', '。', '！', '？', '；', ';', '!', '?')
    val clauseBoundaryChars: Set<Char> = setOf('\n', '\r', '。', '！', '？', '；', ';', '!', '?', '，', ',', '、')
    val timeBoundaryChars: Set<Char> = setOf('\n', '\r', '。', '！', '？', '；', ';', '!', '?', '，', ',', '、', '：', ':')

    fun boundsAround(text: String, index: Int, boundaryChars: Set<Char>): Pair<Int, Int> {
        val anchor = index.coerceIn(0, text.length)
        var start = 0
        for (i in anchor - 1 downTo 0) {
            if (text[i] in boundaryChars) {
                start = i + 1
                break
            }
        }
        var end = text.length
        for (i in anchor until text.length) {
            if (text[i] in boundaryChars) {
                end = i
                break
            }
        }
        return start to end
    }

    fun windowAround(
        text: String,
        start: Int,
        end: Int,
        before: Int,
        after: Int,
        boundaryChars: Set<Char> = clauseBoundaryChars
    ): Pair<Int, Int> {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        val lowerBound = maxOf(0, safeStart - before)
        var windowStart = lowerBound
        for (i in safeStart - 1 downTo lowerBound) {
            if (text[i] in boundaryChars) {
                windowStart = i + 1
                break
            }
        }
        val upperBound = minOf(text.length, safeEnd + after)
        var windowEnd = upperBound
        for (i in safeEnd until upperBound) {
            if (text[i] in boundaryChars) {
                windowEnd = i
                break
            }
        }
        return windowStart to windowEnd
    }
}

/** 否定/辟谣守卫：只检查同一标点片段内的短窗口，不把跨句否定扩散到正向动作。 */
internal object NoticeNegationGuard {
    private val preNegatorPattern = Regex("""(并非|辟谣|不实|没有|没|未(?!来)|无(?!论)|不)""")

    fun isNegated(text: String, start: Int, end: Int): Boolean {
        val bounds = NoticeTextContext.windowAround(text, start, end, before = 10, after = 10)
        val beforeTail = text.substring(bounds.first, start)
            .filterNot { it.isWhitespace() }
            .takeLast(10)
        val afterHead = text.substring(end, bounds.second)
            .filterNot { it.isWhitespace() }
            .take(10)
        if (
            afterHead.contains("不实") ||
            afterHead.contains("不属实") ||
            afterHead.contains("辟谣") ||
            afterHead.contains("谣言") ||
            afterHead.contains("假的")
        ) {
            return true
        }
        // 「不得不停课」是双重否定后的正向停课，先移除该固定复合形式再查单否定词。
        val effectiveBefore = beforeTail.replace("不得不", "")
        return preNegatorPattern.containsMatchIn(effectiveBefore)
    }
}

/** 动作状态守卫：讲座已取消、作业已提交等明确终态不得再生成创建型提案。 */
internal object NoticeActionStateGuard {
    private val eventCancellationPattern = Regex("""(不再举办|不再举行|取消|停办|不办了|不举办|不举行|不开)""")
    private val completionPattern = Regex("""(已(?:经)?(?:提交|上交|完成|交)|(?:提交|上交|完成)(?:了|完毕))""")

    fun hasEventCancellation(text: String, triggerStart: Int, triggerEnd: Int): Boolean {
        val bounds = NoticeTextContext.boundsAround(text, triggerStart, NoticeTextContext.clauseBoundaryChars)
        val clause = text.substring(bounds.first, bounds.second)
        return eventCancellationPattern.findAll(clause).any { match ->
            val absoluteStart = bounds.first + match.range.first
            val absoluteEnd = bounds.first + match.range.last + 1
            !NoticeNegationGuard.isNegated(text, absoluteStart, absoluteEnd)
        }
    }

    fun hasCompletionState(text: String, triggerStart: Int, triggerEnd: Int): Boolean {
        val bounds = NoticeTextContext.boundsAround(text, triggerStart, NoticeTextContext.clauseBoundaryChars)
        val clause = text.substring(bounds.first, bounds.second)
        return completionPattern.findAll(clause).any { match ->
            val absoluteStart = bounds.first + match.range.first
            val absoluteEnd = bounds.first + match.range.last + 1
            !NoticeNegationGuard.isNegated(text, absoluteStart, absoluteEnd)
        }
    }
}

internal object CourseMentionMatcher {
    fun find(input: ExtractedText, courses: List<InboxCourseCandidate>): CourseMention? {
        return findAll(input, courses).sortedWith(
            compareByDescending<CourseMention> { it.span.endOffset - it.span.startOffset }
                .thenBy { it.confidence.ordinal }
        ).firstOrNull()
    }

    fun findAll(input: ExtractedText, courses: List<InboxCourseCandidate>): List<CourseMention> {
        val mentions = ArrayList<CourseMention>()
        courses.forEach { candidate ->
            val names = ArrayList<Pair<String, Confidence>>(1 + candidate.aliases.size)
            names += candidate.name to Confidence.HIGH
            candidate.aliases.forEach { names += it to Confidence.MEDIUM }
            names.forEach { (name, confidence) ->
                var index = input.plainText.indexOf(name, startIndex = 0, ignoreCase = true)
                while (index >= 0) {
                    mentions += CourseMention(
                        candidate = candidate,
                        span = input.span(index, index + name.length),
                        confidence = confidence
                    )
                    index = input.plainText.indexOf(name, startIndex = index + name.length, ignoreCase = true)
                }
            }
        }
        return mentions.distinct().sortedBy { it.span.startOffset }
    }

    /** 在动作所在句子内选择最近课程；同距离命中不同课程时 fail-closed 为歧义。 */
    fun selectNear(
        input: ExtractedText,
        courses: List<InboxCourseCandidate>,
        anchor: Int,
        startOffset: Int,
        endOffset: Int
    ): CourseMentionSelection {
        val allMentions = findAll(input, courses)
        val mentions = allMentions.filter {
            it.span.startOffset >= startOffset && it.span.endOffset <= endOffset
        }
        if (mentions.isEmpty()) {
            // 常见通知会把课程名放在标题/上一句、动作放在下一句。只有全文课程指代唯一时才跨句继承；
            // 多课程通知不得沿用文档级最长名，否则会把停课/调课挂到另一门课。
            val refs = allMentions.map { it.candidate.ref }.distinct()
            if (refs.size != 1) {
                return CourseMentionSelection(mention = null, ambiguous = refs.size > 1)
            }
            val best = allMentions.minWithOrNull(
                compareBy<CourseMention> { distanceToAnchor(it.span, anchor) }
                    .thenByDescending { it.span.endOffset - it.span.startOffset }
                    .thenBy { it.confidence.ordinal }
            )
            return CourseMentionSelection(mention = best)
        }
        val nearestDistance = mentions.minOf { distanceToAnchor(it.span, anchor) }
        val nearest = mentions.filter { distanceToAnchor(it.span, anchor) == nearestDistance }
        val refs = nearest.map { it.candidate.ref }.distinct()
        if (refs.size > 1) return CourseMentionSelection(mention = null, ambiguous = true)
        val best = nearest.sortedWith(
            compareByDescending<CourseMention> { it.span.endOffset - it.span.startOffset }
                .thenBy { it.confidence.ordinal }
        ).first()
        return CourseMentionSelection(mention = best)
    }

    private fun distanceToAnchor(span: TextSpan, anchor: Int): Int = when {
        anchor < span.startOffset -> span.startOffset - anchor
        anchor >= span.endOffset -> anchor - span.endOffset
        else -> 0
    }
}

internal data class ParsedClockTime(
    val time: LocalTime,
    val span: TextSpan,
    val confidence: Confidence,
    val periodAdjusted: Boolean
)

internal object NoticeTimeParser {
    private val timePattern = Regex(
        """(?<!\d)(?:([01]?\d|2[0-3])\s*[:：]\s*([0-5]\d)|([01]?\d|2[0-3])\s*点\s*(?:([0-9]{1,2})\s*分?|半)?)"""
    )

    private enum class Daypart {
        NONE,
        MORNING,
        NOON,
        AFTERNOON,
        EVENING
    }

    private data class DaypartCue(
        val start: Int,
        val end: Int,
        val kind: Daypart
    )

    private val daypartTokens: List<Pair<String, Daypart>> = listOf(
        "今天晚上" to Daypart.EVENING,
        "今晚" to Daypart.EVENING,
        "明晚" to Daypart.EVENING,
        "晚上" to Daypart.EVENING,
        "晚间" to Daypart.EVENING,
        "傍晚" to Daypart.EVENING,
        "夜里" to Daypart.EVENING,
        "下午" to Daypart.AFTERNOON,
        "中午" to Daypart.NOON,
        "上午" to Daypart.MORNING,
        "早上" to Daypart.MORNING,
        "早晨" to Daypart.MORNING,
        "清晨" to Daypart.MORNING,
        "凌晨" to Daypart.MORNING,
        "晚" to Daypart.EVENING
    )

    fun find(input: ExtractedText, startOffset: Int = 0, endOffset: Int = input.plainText.length): ParsedClockTime? =
        findAll(input, startOffset, endOffset).firstOrNull()

    fun findNearest(
        input: ExtractedText,
        anchor: Int,
        startOffset: Int = 0,
        endOffset: Int = input.plainText.length
    ): ParsedClockTime? {
        return findAll(input, startOffset, endOffset).minWithOrNull(
            compareBy<ParsedClockTime> { distanceToAnchor(it.span, anchor) }
                .thenBy { it.span.startOffset }
        )
    }

    fun findAll(input: ExtractedText, startOffset: Int = 0, endOffset: Int = input.plainText.length): List<ParsedClockTime> {
        val start = startOffset.coerceIn(0, input.plainText.length)
        val end = endOffset.coerceIn(start, input.plainText.length)
        val results = ArrayList<ParsedClockTime>()
        timePattern.findAll(input.plainText).forEach { match ->
            if (match.range.first < start || match.range.last + 1 > end) return@forEach
            val colonHour = match.groupValues.getOrNull(1).orEmpty()
            val colonMinute = match.groupValues.getOrNull(2).orEmpty()
            val chineseHour = match.groupValues.getOrNull(3).orEmpty()
            val chineseMinute = match.groupValues.getOrNull(4).orEmpty()
            val hourRaw = (colonHour.ifEmpty { chineseHour }).toIntOrNull() ?: return@forEach
            val hasHalf = match.value.contains('半')
            val minute = when {
                colonMinute.isNotEmpty() -> colonMinute.toInt()
                chineseMinute.isNotEmpty() -> chineseMinute.toInt()
                hasHalf -> 30
                else -> 0
            }
            if (minute !in 0..59) return@forEach
            val daypart = nearestDaypart(input.plainText, match.range.first)
            var hour = hourRaw
            var adjusted = false
            if ((daypart == Daypart.EVENING || daypart == Daypart.AFTERNOON || daypart == Daypart.NOON) && hour < 12) {
                hour += 12
                adjusted = true
            }
            if (hour !in 0..23) return@forEach
            val minuteExplicit = colonMinute.isNotEmpty() || chineseMinute.isNotEmpty() || hasHalf
            results += ParsedClockTime(
                time = LocalTime.of(hour, minute),
                span = input.span(match.range.first, match.range.last + 1),
                confidence = if (minuteExplicit) Confidence.HIGH else Confidence.MEDIUM,
                periodAdjusted = adjusted
            )
        }
        return results
    }

    /** 只在同一标点片段内取离时钟最近的日段；上午/早上等就近反证优先于远处的「晚」。 */
    private fun nearestDaypart(text: String, timeStart: Int): Daypart {
        var segmentStart = 0
        for (i in timeStart - 1 downTo 0) {
            if (text[i] in NoticeTextContext.timeBoundaryChars) {
                segmentStart = i + 1
                break
            }
        }
        val prefixStart = maxOf(segmentStart, timeStart - 8)
        val prefix = text.substring(prefixStart, timeStart)
        val cues = ArrayList<DaypartCue>()
        daypartTokens.forEach { (token, kind) ->
            var index = prefix.indexOf(token, startIndex = 0, ignoreCase = true)
            while (index >= 0) {
                val isDeadlineSuperlative = token == "晚" && index > 0 && prefix[index - 1] == '最'
                if (!isDeadlineSuperlative) {
                    cues += DaypartCue(start = index, end = index + token.length, kind = kind)
                }
                index = prefix.indexOf(token, startIndex = index + token.length, ignoreCase = true)
            }
        }
        val nearest = cues.minWithOrNull(
            compareBy<DaypartCue> { prefix.length - it.end }
                .thenByDescending { it.end - it.start }
        ) ?: return Daypart.NONE
        return nearest.kind
    }

    private fun distanceToAnchor(span: TextSpan, anchor: Int): Int = when {
        anchor < span.startOffset -> span.startOffset - anchor
        anchor >= span.endOffset -> anchor - span.endOffset
        else -> 0
    }
}

internal data class ParsedPeriodRange(
    val startPeriod: Int,
    val endPeriod: Int,
    val span: TextSpan
)

internal object NoticePeriodParser {
    private val periodPattern = Regex(
        """第\s*([0-9]{1,2})\s*(?:[-—~～至到]\s*([0-9]{1,2}))?\s*节"""
    )

    fun find(input: ExtractedText, startOffset: Int = 0, endOffset: Int = input.plainText.length): ParsedPeriodRange? {
        val start = startOffset.coerceIn(0, input.plainText.length)
        val end = endOffset.coerceIn(start, input.plainText.length)
        periodPattern.findAll(input.plainText).forEach { match ->
            if (match.range.first < start || match.range.last + 1 > end) return@forEach
            val startPeriod = match.groupValues[1].toIntOrNull() ?: return@forEach
            val endPeriod = match.groupValues[2].toIntOrNull() ?: startPeriod
            if (startPeriod !in 1..30 || endPeriod !in 1..30 || endPeriod < startPeriod) return@forEach
            return ParsedPeriodRange(
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                span = input.span(match.range.first, match.range.last + 1)
            )
        }
        return null
    }
}

internal data class ParsedRoom(
    val room: String,
    val span: TextSpan,
    val confidence: Confidence
)

internal object NoticeRoomParser {
    // 教室标识优先要求楼宇字母或明确中文建筑/功能房间前缀；显式“教室/地点/在”后才接受三位纯数字房号。
    // 四位纯数字（如年份 2024、人数 1500）始终不当教室，四位房号必须带楼宇/建筑前缀。
    private const val NAMED_ROOM_TOKEN = """(?:[A-Za-z]{1,3}[0-9]{2,4}[A-Za-z]?|[一-鿿]{0,6}(?:楼|栋|馆|区|中心|机房|实验室)[0-9]{2,4}[A-Za-z]?)"""
    private val explicitRoomPattern = Regex(
        """(?:教室|地点|机房|实验室|场地|在)(?:\s|为|是|：|:)*((?:""" + NAMED_ROOM_TOKEN + """)|(?:[0-9]{3}))(?![A-Za-z0-9])"""
    )
    private val directRoomPattern = Regex(
        """^[\s，。,;；:：]*(?:在|为|是)?[\s，。,;；:：]*(""" + NAMED_ROOM_TOKEN + """)(?![A-Za-z0-9])"""
    )

    fun find(input: ExtractedText, startOffset: Int = 0, endOffset: Int = input.plainText.length): ParsedRoom? {
        val start = startOffset.coerceIn(0, input.plainText.length)
        val end = endOffset.coerceIn(start, input.plainText.length)
        if (start >= end) return null
        val segmentStart = start
        val segment = input.plainText.substring(segmentStart, end)

        explicitRoomPattern.find(segment)?.let { match ->
            val group = match.groups[1] ?: return@let
            return ParsedRoom(
                room = group.value,
                span = input.span(segmentStart + group.range.first, segmentStart + group.range.last + 1),
                confidence = Confidence.HIGH
            )
        }
        directRoomPattern.find(segment)?.let { match ->
            val group = match.groups[1] ?: return@let
            return ParsedRoom(
                room = group.value,
                span = input.span(segmentStart + group.range.first, segmentStart + group.range.last + 1),
                confidence = Confidence.MEDIUM
            )
        }
        return null
    }
}
