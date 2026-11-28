package cn.edu.haut.campus.core.inbox

import java.time.LocalTime

internal data class CourseMention(
    val candidate: InboxCourseCandidate,
    val span: TextSpan,
    val confidence: Confidence
)

internal object CourseMentionMatcher {
    fun find(input: ExtractedText, courses: List<InboxCourseCandidate>): CourseMention? {
        val mentions = ArrayList<CourseMention>()
        courses.forEach { candidate ->
            val names = ArrayList<Pair<String, Confidence>>(1 + candidate.aliases.size)
            names += candidate.name to Confidence.HIGH
            candidate.aliases.forEach { names += it to Confidence.MEDIUM }
            names.forEach { (name, confidence) ->
                val index = input.plainText.indexOf(name, startIndex = 0, ignoreCase = true)
                if (index >= 0) {
                    mentions += CourseMention(
                        candidate = candidate,
                        span = input.span(index, index + name.length),
                        confidence = confidence
                    )
                }
            }
        }
        return mentions.sortedWith(
            compareByDescending<CourseMention> { it.span.endOffset - it.span.startOffset }
                .thenBy { it.confidence.ordinal }
        ).firstOrNull()
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
        """(?<!\d)(?:([01]?\d|2[0-3])\s*[:：]\s*([0-5]\d)|([01]?\d|2[0-3])\s*点\s*(?:([0-5]\d)\s*分?|半)?)"""
    )

    fun find(input: ExtractedText, startOffset: Int = 0, endOffset: Int = input.plainText.length): ParsedClockTime? {
        val start = startOffset.coerceIn(0, input.plainText.length)
        val end = endOffset.coerceIn(start, input.plainText.length)
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
            val prefixStart = maxOf(0, match.range.first - 8)
            val prefix = input.plainText.substring(prefixStart, match.range.first)
            val eveningLike = listOf("今晚", "明晚", "晚上", "晚", "傍晚", "夜里").any { prefix.contains(it) }
            val afternoonLike = prefix.contains("下午")
            val noonLike = prefix.contains("中午")
            var hour = hourRaw
            var adjusted = false
            if ((eveningLike || afternoonLike) && hour < 12) {
                hour += 12
                adjusted = true
            } else if (noonLike && hour < 12) {
                hour += 12
                adjusted = true
            }
            if (hour !in 0..23) return@forEach
            val minuteExplicit = colonMinute.isNotEmpty() || chineseMinute.isNotEmpty() || hasHalf
            return ParsedClockTime(
                time = LocalTime.of(hour, minute),
                span = input.span(match.range.first, match.range.last + 1),
                confidence = if (minuteExplicit) Confidence.HIGH else Confidence.MEDIUM,
                periodAdjusted = adjusted
            )
        }
        return null
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
    private const val ROOM_TOKEN = """(?:[A-Za-z]{1,2})?[0-9]{3,4}[A-Za-z]?|[A-Za-z一-鿿]{1,8}[0-9]{3,4}[A-Za-z]?"""
    private val explicitRoomPattern = Regex(
        """(?:教室|地点|机房|实验室|场地|在)(?:\s|为|是|：|:)*(""" + ROOM_TOKEN + """)(?![A-Za-z0-9])"""
    )
    private val directRoomPattern = Regex(
        """^[\s，。,;；:：]*(?:在|为|是)?[\s，。,;；:：]*(""" + ROOM_TOKEN + """)(?![A-Za-z0-9])"""
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
