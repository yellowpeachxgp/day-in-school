package cn.edu.haut.campus.core.inbox

import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.PersonalEventDraft
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.time.LocalTime

/** 单条确定性规则输出。规则必须保守：证据不足时返回诊断或空结果，不伪造提案。 */
interface InboxRule {
    val id: String
    fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome
}

data class RuleOutcome(
    val actions: List<ProposedAction> = emptyList(),
    val issues: List<ParseIssue> = emptyList()
)

/**
 * 规则解析器框架。默认规则按「调课 → 换教室 → 停课 → 普通活动 → 截止任务」执行；
 * 调入提案覆盖同一课程同一源日期的独立停课提案，避免把一条调课通知拆成两个冲突动作。
 */
class RuleNoticeParser(
    private val dateResolver: RelativeDateResolver = ChineseRelativeDateResolver,
    private val rules: List<InboxRule> = listOf(
        CourseRescheduleRule,
        CourseRoomChangeRule,
        CourseCancellationRule,
        PersonalEventRule,
        TaskDeadlineRule
    )
) : NoticeParser {
    override val id: String = "rules-v1"

    init {
        require(rules.isNotEmpty()) { "规则解析器至少需要一条规则" }
        require(rules.map { it.id }.distinct().size == rules.size) { "规则 id 不能重复" }
        require(rules.all { it.id.isNotBlank() && it.id.length <= 64 }) { "规则 id 为空或过长" }
    }

    override suspend fun parse(text: ExtractedText, ctx: ParseContext): List<ProposedAction> =
        parseReport(text, ctx).actions

    fun parseReport(input: ExtractedText, ctx: ParseContext): RuleParseReport {
        val dates = resolveDates(input, ctx)
        val outcomes = rules.map { it.parse(input, ctx, dates) }
        val mergedActions = suppressCancellationCoveredByReschedule(outcomes.flatMap { it.actions })
        val mergedIssues = outcomes.flatMap { it.issues }.distinct()
        return RuleParseReport(actions = mergedActions, issues = mergedIssues)
    }

    private fun resolveDates(input: ExtractedText, ctx: ParseContext): List<DateResolution> {
        return if (dateResolver is ChineseRelativeDateResolver) {
            dateResolver.resolveAll(input.plainText, ctx.referenceDate, ctx.term)
        } else {
            val single = dateResolver.resolve(input.plainText, ctx.referenceDate, ctx.term)
            if (single.span == null) emptyList() else listOf(single)
        }
    }

    private fun suppressCancellationCoveredByReschedule(actions: List<ProposedAction>): List<ProposedAction> {
        val rescheduledKeys = actions.mapNotNull { action ->
            if (action is ProposedAction.ModifyCourseOccurrence && action.change is OccurrenceChange.Reschedule) {
                action.courseRef to action.date
            } else {
                null
            }
        }.toSet()
        if (rescheduledKeys.isEmpty()) return actions
        return actions.filterNot { action ->
            action is ProposedAction.ModifyCourseOccurrence &&
                action.change is OccurrenceChange.Cancel &&
                (action.courseRef to action.date) in rescheduledKeys
        }
    }
}

/** 「原日期课程 调到/改到 目标日期」规则。目标时间、节次和教室只在原文明确出现时填入。 */
object CourseRescheduleRule : InboxRule {
    override val id: String = "course-reschedule-v1"
    private val markerPattern = Regex("""(调整到|调整至|改期至|延期至|改到|调到|移至)""")

    override fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome {
        val mention = CourseMentionMatcher.find(input, ctx.courses)
        val issues = ArrayList<ParseIssue>()
        val actions = ArrayList<ProposedAction>()
        val markers = markerPattern.findAll(input.plainText).toList()
        if (markers.isEmpty()) return RuleOutcome()

        if (mention == null) {
            issues += ParseIssue(
                kind = ParseIssueKind.COURSE_NOT_MATCHED,
                message = "通知疑似调课，但未能在当前课表中匹配课程"
            )
        }

        markers.forEach { marker ->
            val beforeDates = dates.filter { it.resolved && it.span != null && it.span.endOffset <= marker.range.first }
            val afterDates = dates.filter { it.resolved && it.span != null && it.span.startOffset > marker.range.last }
            val source = beforeDates.lastOrNull()
            val target = afterDates.firstOrNull()
            val targetEnd = minOf(input.plainText.length, marker.range.last + 1 + 120)
            val targetStart = marker.range.last + 1
            val roomInLookahead = NoticeRoomParser.find(input, targetStart, targetEnd)
            // 「改到A203」且后面没有目标日期是换教室，不应由调课规则误报缺日期。
            if (target == null && roomInLookahead != null) return@forEach
            if (source == null || target == null) {
                issues += ParseIssue(
                    kind = ParseIssueKind.MISSING_DATE,
                    message = "调课通知缺少可确认的源日期或目标日期"
                )
                return@forEach
            }
            if (mention == null) return@forEach
            val sourceDate = source.date ?: return@forEach
            val targetDate = target.date ?: return@forEach
            val sourceEvidence = source.span ?: return@forEach
            val targetEvidence = target.span ?: return@forEach
            val time = NoticeTimeParser.find(input, targetStart, targetEnd)
            val period = NoticePeriodParser.find(input, targetStart, targetEnd)
            val room = roomInLookahead
            val sourceSpan = input.span(sourceEvidence.startOffset, sourceEvidence.endOffset)
            val targetSpan = input.span(targetEvidence.startOffset, targetEvidence.endOffset)
            val spans = listOfNotNull(
                mention.span,
                sourceSpan,
                targetSpan,
                time?.span,
                period?.span,
                room?.span
            ).distinct()
            actions += ProposedAction.ModifyCourseOccurrence(
                courseRef = mention.candidate.ref,
                date = sourceDate,
                change = OccurrenceChange.Reschedule(
                    targetDate = targetDate,
                    startTime = time?.time,
                    startPeriod = period?.startPeriod,
                    endPeriod = period?.endPeriod,
                    room = room?.room
                ),
                confidence = linkedMapOf(
                    "course" to mention.confidence,
                    "date" to source.confidence,
                    "targetDate" to target.confidence,
                    "targetTime" to (time?.confidence ?: Confidence.LOW),
                    "targetPeriod" to (if (period != null) Confidence.HIGH else Confidence.LOW),
                    "room" to (room?.confidence ?: Confidence.LOW),
                    "action" to Confidence.HIGH
                ),
                sourceSpans = spans
            )
        }
        return RuleOutcome(actions = actions, issues = issues.distinct())
    }
}

/** 「某天课程改到/换到某教室」规则；若 marker 后还有目标日期，则交由调课规则处理。 */
object CourseRoomChangeRule : InboxRule {
    override val id: String = "course-room-change-v1"
    private val markerPattern = Regex("""(换到|改到|调整到|调整至)""")

    override fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome {
        val markers = markerPattern.findAll(input.plainText).toList()
        if (markers.isEmpty()) return RuleOutcome()
        val mention = CourseMentionMatcher.find(input, ctx.courses)
        val actions = ArrayList<ProposedAction>()
        val issues = ArrayList<ParseIssue>()

        markers.forEach { marker ->
            val hasTargetDateAfter = dates.any {
                it.resolved && it.span != null && it.span.startOffset > marker.range.last
            }
            if (hasTargetDateAfter) return@forEach
            val room = NoticeRoomParser.find(input, marker.range.last + 1, minOf(input.plainText.length, marker.range.last + 1 + 64))
                ?: return@forEach
            val date = dates.filter { it.resolved }.lastOrNull { it.span != null && it.span.endOffset <= marker.range.first }
                ?: dates.firstOrNull { it.resolved }
            if (mention == null) {
                issues += ParseIssue(
                    kind = ParseIssueKind.COURSE_NOT_MATCHED,
                    message = "通知疑似换教室，但未能在当前课表中匹配课程"
                )
                return@forEach
            }
            val dateResolution = date
            val dateValue = dateResolution?.date
            if (dateResolution == null || dateValue == null) {
                issues += ParseIssue(
                    kind = ParseIssueKind.MISSING_DATE,
                    message = "换教室通知缺少可确认日期"
                )
                return@forEach
            }
            val dateSpan = dateResolution.span?.let { input.span(it.startOffset, it.endOffset) }
            actions += ProposedAction.ModifyCourseOccurrence(
                courseRef = mention.candidate.ref,
                date = dateValue,
                change = OccurrenceChange.ChangeRoom(room = room.room),
                confidence = linkedMapOf(
                    "course" to mention.confidence,
                    "date" to dateResolution.confidence,
                    "room" to room.confidence,
                    "action" to Confidence.HIGH
                ),
                sourceSpans = listOfNotNull(mention.span, dateSpan, room.span).distinct()
            )
        }
        return RuleOutcome(actions = actions, issues = issues.distinct())
    }
}

/** 独立停课规则；调课提案覆盖的同源停课会在 parser 汇总阶段被抑制。 */
object CourseCancellationRule : InboxRule {
    override val id: String = "course-cancel-v1"
    private val cancellationPattern = Regex("""(取消上课|暂停上课|课程取消|停课|不上)""")

    override fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome {
        val matches = cancellationPattern.findAll(input.plainText).toList()
        if (matches.isEmpty()) return RuleOutcome()
        val mention = CourseMentionMatcher.find(input, ctx.courses)
        if (mention == null) {
            return RuleOutcome(
                issues = listOf(
                    ParseIssue(
                        kind = ParseIssueKind.COURSE_NOT_MATCHED,
                        message = "通知疑似停课，但未能在当前课表中匹配课程"
                    )
                )
            )
        }
        val resolvedDates = dates.filter { it.resolved && it.date != null && it.span != null }
        if (resolvedDates.isEmpty()) {
            return RuleOutcome(
                issues = listOf(
                    ParseIssue(
                        kind = ParseIssueKind.MISSING_DATE,
                        message = "停课通知缺少可确认日期",
                        spans = listOf(mention.span)
                    )
                )
            )
        }
        val actions = ArrayList<ProposedAction>()
        val emittedKeys = HashSet<String>()
        matches.forEach { match ->
            val distance = kotlin.math.abs(match.range.first - mention.span.startOffset)
            if (distance > 80) return@forEach
            val date = resolvedDates.minByOrNull {
                kotlin.math.abs((it.span?.startOffset ?: 0) - match.range.first)
            } ?: return@forEach
            val dateValue = date.date ?: return@forEach
            val key = mention.candidate.ref.id + "|" + dateValue
            if (!emittedKeys.add(key)) return@forEach
            val dateSpan = date.span?.let { input.span(it.startOffset, it.endOffset) }
            val keywordSpan = input.span(match.range.first, match.range.last + 1)
            actions += ProposedAction.ModifyCourseOccurrence(
                courseRef = mention.candidate.ref,
                date = dateValue,
                change = OccurrenceChange.Cancel(reason = null),
                confidence = linkedMapOf(
                    "course" to mention.confidence,
                    "date" to date.confidence,
                    "action" to Confidence.HIGH
                ),
                sourceSpans = listOfNotNull(mention.span, dateSpan, keywordSpan).distinct()
            )
        }
        return RuleOutcome(actions = actions)
    }
}

/** 普通会议/讲座/活动规则。缺少精确时刻时不创建占用窗口，避免伪造日程。 */
object PersonalEventRule : InboxRule {
    override val id: String = "personal-event-v1"
    private val triggerPattern = Regex("""(报告会|讲座|开会|会议|活动)""")

    override fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome {
        val trigger = triggerPattern.find(input.plainText) ?: return RuleOutcome()
        val dateResolution = dates.firstOrNull { it.resolved && it.date != null }
        val dateValue = dateResolution?.date
        if (dateResolution == null || dateValue == null) {
            return RuleOutcome(
                issues = listOf(ParseIssue(ParseIssueKind.MISSING_DATE, "活动通知缺少可确认日期"))
            )
        }
        val time = NoticeTimeParser.find(input)
        if (time == null) {
            return RuleOutcome(
                issues = listOf(ParseIssue(ParseIssueKind.TIME_NOT_FOUND, "活动通知缺少可确认时刻"))
            )
        }
        val room = NoticeRoomParser.find(input)
        val start = dateValue.atTime(time.time).atZone(ctx.zone).toInstant()
        val title = titleNear(input, trigger.range.first, "活动安排")
        val dateSpan = dateResolution.span?.let { input.span(it.startOffset, it.endOffset) }
        val triggerSpan = input.span(trigger.range.first, trigger.range.last + 1)
        val draft = PersonalEventDraft(
            scope = ctx.scope,
            kind = TimelineKind.PERSONAL,
            title = title,
            start = start,
            end = null,
            timeSemantics = TimelineTimeSemantics.UNCERTAIN,
            zone = ctx.zone,
            placeText = room?.room,
            busy = BusyLevel.FREE,
            termId = ctx.term.termId,
            provenance = ctx.provenance,
            links = emptyList()
        )
        return RuleOutcome(
            actions = listOf(
                ProposedAction.CreateEvent(
                    draft = draft,
                    confidence = linkedMapOf(
                        "title" to Confidence.MEDIUM,
                        "date" to dateResolution.confidence,
                        "time" to time.confidence,
                        "room" to (room?.confidence ?: Confidence.LOW),
                        "action" to Confidence.HIGH
                    ),
                    sourceSpans = listOfNotNull(dateSpan, time.span, triggerSpan, room?.span).distinct()
                )
            )
        )
    }
}

/** 截止/提交任务规则。无精确时刻时按 23:59 低置信度提案，确认页必须可改。 */
object TaskDeadlineRule : InboxRule {
    override val id: String = "task-deadline-v1"
    private val triggerPattern = Regex("""(?i)(提交|上交|截止|ddl|deadline)""")

    override fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome {
        val trigger = triggerPattern.find(input.plainText) ?: return RuleOutcome()
        val dateResolution = dates.firstOrNull { it.resolved && it.date != null }
        val dateValue = dateResolution?.date
        if (dateResolution == null || dateValue == null) {
            return RuleOutcome(
                issues = listOf(ParseIssue(ParseIssueKind.MISSING_DATE, "任务通知缺少可确认截止日期"))
            )
        }
        val time = NoticeTimeParser.find(input)
        val clock = time?.time ?: LocalTime.of(23, 59)
        val due = dateValue.atTime(clock).atZone(ctx.zone).toInstant()
        val title = titleNear(input, trigger.range.first, "待提交事项")
        val triggerSpan = input.span(trigger.range.first, trigger.range.last + 1)
        val dateSpan = dateResolution.span?.let { input.span(it.startOffset, it.endOffset) }
        return RuleOutcome(
            actions = listOf(
                ProposedAction.CreateTask(
                    title = title,
                    due = due,
                    checklist = emptyList(),
                    confidence = linkedMapOf(
                        "title" to Confidence.MEDIUM,
                        "date" to dateResolution.confidence,
                        "dueTime" to (time?.confidence ?: Confidence.LOW),
                        "action" to Confidence.HIGH
                    ),
                    sourceSpans = listOfNotNull(dateSpan, time?.span, triggerSpan).distinct()
                )
            )
        )
    }
}

private fun titleNear(input: ExtractedText, offset: Int, fallback: String): String {
    val row = input.lines.firstOrNull { offset >= it.startOffset && offset <= it.endOffset }
    val raw = row?.text ?: input.plainText
    var title = raw
        .replace(Regex("""^\s*(?:【[^】]{0,24}】)?\s*(?:紧急)?通知\s*[：:]?\s*"""), "")
        .replace(Regex("""^关于"""), "")
        .filterNot { it.isISOControl() }
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('，', '。', '；', ',', ';', '：', ':')
    if (title.isBlank()) title = fallback
    return title.take(80)
}
