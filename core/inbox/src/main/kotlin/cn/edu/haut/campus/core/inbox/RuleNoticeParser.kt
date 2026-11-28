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

/** 「原日期课程 调到/改到 目标日期」规则。目标时间、节次和教室只在同一目标句内明确出现时填入。 */
object CourseRescheduleRule : InboxRule {
    override val id: String = "course-reschedule-v1"
    private val markerPattern = Regex("""(调整到|调整至|改期至|延期至|改到|调到|移至)""")

    override fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome {
        val issues = ArrayList<ParseIssue>()
        val actions = ArrayList<ProposedAction>()
        val markers = markerPattern.findAll(input.plainText).toList()
        if (markers.isEmpty()) return RuleOutcome()

        markers.forEach { marker ->
            if (NoticeNegationGuard.isNegated(input.plainText, marker.range.first, marker.range.last + 1)) return@forEach
            val sentenceBounds = NoticeTextContext.boundsAround(
                input.plainText,
                marker.range.first,
                NoticeTextContext.sentenceBoundaryChars
            )
            val clauseBounds = NoticeTextContext.boundsAround(
                input.plainText,
                marker.range.first,
                NoticeTextContext.clauseBoundaryChars
            )
            val selection = CourseMentionMatcher.selectNear(
                input,
                ctx.courses,
                marker.range.first,
                clauseBounds.first,
                clauseBounds.second
            )
            if (selection.ambiguous) {
                issues += ParseIssue(
                    kind = ParseIssueKind.COURSE_NOT_MATCHED,
                    message = "调课通知中的课程指代不唯一"
                )
                return@forEach
            }
            val mention = selection.mention
            if (mention == null) {
                issues += ParseIssue(
                    kind = ParseIssueKind.COURSE_NOT_MATCHED,
                    message = "通知疑似调课，但未能在当前课表中匹配课程"
                )
                return@forEach
            }

            val beforeMentions = dates.filter {
                it.span != null && it.span.endOffset <= marker.range.first && it.span.startOffset >= sentenceBounds.first
            }.sortedBy { it.span?.endOffset ?: 0 }
            val nearestBefore = beforeMentions.lastOrNull()
            if (nearestBefore != null && !nearestBefore.resolved) {
                issues += ParseIssue(
                    kind = ParseIssueKind.DATE_UNRESOLVED,
                    message = "调课源日期无法确认",
                    spans = listOfNotNull(nearestBefore.span)
                )
                return@forEach
            }
            val source = nearestBefore?.takeIf { it.resolved }
                ?: selectUniqueResolvedDateBefore(dates, marker.range.first)

            val targetStart = marker.range.last + 1
            val targetEnd = sentenceBounds.second
            val afterMentions = dates.filter {
                it.span != null && it.span.startOffset > marker.range.last && it.span.startOffset < targetEnd
            }.sortedBy { it.span?.startOffset ?: 0 }
            val targetMention = afterMentions.firstOrNull()
            val roomInLookahead = NoticeRoomParser.find(input, targetStart, targetEnd)
            if (targetMention != null && !targetMention.resolved) {
                // 明确写了目标日期短语但解析失败（例如越界教学周）时，不允许降级成换教室。
                issues += ParseIssue(
                    kind = ParseIssueKind.DATE_UNRESOLVED,
                    message = "调课目标日期无法确认",
                    spans = listOfNotNull(targetMention.span)
                )
                return@forEach
            }
            val target = targetMention?.takeIf { it.resolved }
            // 「改到A203」且同一目标句没有目标日期是换教室，不应由调课规则误报缺日期。
            if (target == null && roomInLookahead != null) return@forEach
            if (source == null || target == null) {
                issues += ParseIssue(
                    kind = ParseIssueKind.MISSING_DATE,
                    message = "调课通知缺少可确认的源日期或目标日期"
                )
                return@forEach
            }
            val sourceDate = source.date ?: return@forEach
            val targetDate = target.date ?: return@forEach
            val sourceEvidence = source.span ?: return@forEach
            val targetEvidence = target.span ?: return@forEach
            val targetAnchor = targetEvidence.startOffset
            val time = NoticeTimeParser.findNearest(input, targetAnchor, targetStart, targetEnd)
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

/** 「某天课程改到/换到某教室」规则；若同一句还有目标日期短语，则交由调课规则处理。 */
object CourseRoomChangeRule : InboxRule {
    override val id: String = "course-room-change-v1"
    private val markerPattern = Regex("""(换到|改到|调整到|调整至)""")

    override fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome {
        val markers = markerPattern.findAll(input.plainText).toList()
        if (markers.isEmpty()) return RuleOutcome()
        val actions = ArrayList<ProposedAction>()
        val issues = ArrayList<ParseIssue>()

        markers.forEach { marker ->
            if (NoticeNegationGuard.isNegated(input.plainText, marker.range.first, marker.range.last + 1)) return@forEach
            val sentenceBounds = NoticeTextContext.boundsAround(
                input.plainText,
                marker.range.first,
                NoticeTextContext.sentenceBoundaryChars
            )
            val hasTargetDateMentionAfter = dates.any {
                it.span != null && it.span.startOffset > marker.range.last && it.span.startOffset < sentenceBounds.second
            }
            if (hasTargetDateMentionAfter) return@forEach
            val room = NoticeRoomParser.find(input, marker.range.last + 1, sentenceBounds.second)
                ?: return@forEach
            val clauseBounds = NoticeTextContext.boundsAround(
                input.plainText,
                marker.range.first,
                NoticeTextContext.clauseBoundaryChars
            )
            val selection = CourseMentionMatcher.selectNear(
                input,
                ctx.courses,
                marker.range.first,
                clauseBounds.first,
                clauseBounds.second
            )
            if (selection.ambiguous) {
                issues += ParseIssue(
                    kind = ParseIssueKind.COURSE_NOT_MATCHED,
                    message = "换教室通知中的课程指代不唯一"
                )
                return@forEach
            }
            val mention = selection.mention
            if (mention == null) {
                issues += ParseIssue(
                    kind = ParseIssueKind.COURSE_NOT_MATCHED,
                    message = "通知疑似换教室，但未能在当前课表中匹配课程"
                )
                return@forEach
            }
            val nearestBefore = dates.filter {
                it.span != null && it.span.endOffset <= marker.range.first && it.span.startOffset >= sentenceBounds.first
            }.maxByOrNull { it.span?.endOffset ?: 0 }
            if (nearestBefore != null && !nearestBefore.resolved) {
                issues += ParseIssue(
                    kind = ParseIssueKind.DATE_UNRESOLVED,
                    message = "换教室日期无法确认",
                    spans = listOfNotNull(nearestBefore.span)
                )
                return@forEach
            }
            val dateResolution = nearestBefore?.takeIf { it.resolved }
                ?: selectUniqueResolvedDateBefore(dates, marker.range.first)
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

/** 独立停课规则；调课提案覆盖的同源停课会在 parser 汇总阶段被抑制。否定与辟谣只作守卫，不生成反向动作。 */
object CourseCancellationRule : InboxRule {
    override val id: String = "course-cancel-v1"
    private val cancellationPattern = Regex("""(取消上课|暂停上课|课程取消|不上课|停课|不上)""")
    private val compoundNonCancellationPreviousChars = setOf('跟', '谈', '顾', '比', '称', '赶')

    override fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome {
        val matches = cancellationPattern.findAll(input.plainText).toList()
        if (matches.isEmpty()) return RuleOutcome()
        val positiveMatches = matches.filterNot { match ->
            NoticeNegationGuard.isNegated(input.plainText, match.range.first, match.range.last + 1) ||
                isCompoundNonCancellation(input.plainText, match)
        }
        if (positiveMatches.isEmpty()) return RuleOutcome()

        val actions = ArrayList<ProposedAction>()
        val issues = ArrayList<ParseIssue>()
        val emittedKeys = HashSet<String>()
        positiveMatches.forEach { match ->
            val sentenceBounds = NoticeTextContext.boundsAround(
                input.plainText,
                match.range.first,
                NoticeTextContext.sentenceBoundaryChars
            )
            val clauseBounds = NoticeTextContext.boundsAround(
                input.plainText,
                match.range.first,
                NoticeTextContext.clauseBoundaryChars
            )
            val selection = CourseMentionMatcher.selectNear(
                input,
                ctx.courses,
                match.range.first,
                clauseBounds.first,
                clauseBounds.second
            )
            if (selection.ambiguous) {
                issues += ParseIssue(
                    kind = ParseIssueKind.COURSE_NOT_MATCHED,
                    message = "停课通知中的课程指代不唯一"
                )
                return@forEach
            }
            val mention = selection.mention
            if (mention == null) {
                issues += ParseIssue(
                    kind = ParseIssueKind.COURSE_NOT_MATCHED,
                    message = "通知疑似停课，但未能在当前课表中匹配课程"
                )
                return@forEach
            }
            val distance = kotlin.math.abs(match.range.first - mention.span.startOffset)
            if (distance > 80) return@forEach
            val date = selectDateNear(input, dates, match.range.first)
            if (date == null) {
                issues += ParseIssue(
                    kind = ParseIssueKind.MISSING_DATE,
                    message = "停课通知缺少可确认日期",
                    spans = listOf(mention.span)
                )
                return@forEach
            }
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
        return RuleOutcome(actions = actions, issues = issues.distinct())
    }

    private fun isCompoundNonCancellation(text: String, match: MatchResult): Boolean {
        if (match.value != "不上" && match.value != "不上课") return false
        val previous = text.getOrNull(match.range.first - 1) ?: return false
        return previous in compoundNonCancellationPreviousChars
    }
}

/** 普通会议/讲座/活动规则。缺少精确时刻时不创建占用窗口，避免伪造日程。 */
object PersonalEventRule : InboxRule {
    override val id: String = "personal-event-v1"
    private val triggerPattern = Regex("""(报告会|讲座|开会|会议|活动)""")

    override fun parse(input: ExtractedText, ctx: ParseContext, dates: List<DateResolution>): RuleOutcome {
        val triggers = triggerPattern.findAll(input.plainText).toList()
        if (triggers.isEmpty()) return RuleOutcome()
        val trigger = triggers.firstOrNull { match ->
            !NoticeNegationGuard.isNegated(input.plainText, match.range.first, match.range.last + 1) &&
                !NoticeActionStateGuard.hasEventCancellation(input.plainText, match.range.first, match.range.last + 1)
        }
        if (trigger == null) {
            return RuleOutcome(
                issues = listOf(ParseIssue(ParseIssueKind.NO_ACTION, "通知未肯定活动举行或明确表示活动取消，未生成日程提案"))
            )
        }
        val dateResolution = selectDateNear(input, dates, trigger.range.first)
        val dateValue = dateResolution?.date
        if (dateResolution == null || dateValue == null) {
            return RuleOutcome(
                issues = listOf(ParseIssue(ParseIssueKind.MISSING_DATE, "活动通知缺少可确认日期"))
            )
        }
        val time = selectTimeNear(input, trigger.range.first)
        if (time == null) {
            return RuleOutcome(
                issues = listOf(ParseIssue(ParseIssueKind.TIME_NOT_FOUND, "活动通知缺少可确认时刻"))
            )
        }
        val sentenceBounds = NoticeTextContext.boundsAround(
            input.plainText,
            trigger.range.first,
            NoticeTextContext.sentenceBoundaryChars
        )
        val room = NoticeRoomParser.find(input, sentenceBounds.first, sentenceBounds.second)
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
        val triggers = triggerPattern.findAll(input.plainText).toList()
        if (triggers.isEmpty()) return RuleOutcome()
        val trigger = triggers.firstOrNull { match ->
            !NoticeNegationGuard.isNegated(input.plainText, match.range.first, match.range.last + 1) &&
                !NoticeActionStateGuard.hasCompletionState(input.plainText, match.range.first, match.range.last + 1)
        }
        if (trigger == null) {
            return RuleOutcome(
                issues = listOf(ParseIssue(ParseIssueKind.NO_ACTION, "通知未肯定提交要求或事项已完成，未生成待办提案"))
            )
        }
        val dateResolution = selectDateNear(input, dates, trigger.range.first)
        val dateValue = dateResolution?.date
        if (dateResolution == null || dateValue == null) {
            return RuleOutcome(
                issues = listOf(ParseIssue(ParseIssueKind.MISSING_DATE, "任务通知缺少可确认截止日期"))
            )
        }
        val time = selectTimeNear(input, trigger.range.first)
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

private fun selectUniqueResolvedDateBefore(dates: List<DateResolution>, anchor: Int): DateResolution? {
    val before = dates.filter {
        it.resolved && it.date != null && it.span != null && it.span.endOffset <= anchor
    }
    if (before.mapNotNull { it.date }.distinct().size != 1) return null
    return before.maxByOrNull { it.span?.endOffset ?: 0 }
}

private fun selectDateNear(input: ExtractedText, dates: List<DateResolution>, anchor: Int): DateResolution? {
    val clauseBounds = NoticeTextContext.boundsAround(input.plainText, anchor, NoticeTextContext.clauseBoundaryChars)
    nearestResolvedDate(dates, anchor, clauseBounds.first, clauseBounds.second)?.let { return it }
    val sentenceBounds = NoticeTextContext.boundsAround(input.plainText, anchor, NoticeTextContext.sentenceBoundaryChars)
    nearestResolvedDate(dates, anchor, sentenceBounds.first, sentenceBounds.second)?.let { return it }
    // 标题句/正文句拆分时，全文唯一日期可以作为无歧义上下文继承；多日期则必须留在本句绑定。
    val resolved = dates.filter { it.resolved && it.date != null && it.span != null }
    return if (resolved.mapNotNull { it.date }.distinct().size == 1) {
        resolved.minWithOrNull(
            compareBy<DateResolution> { resolution ->
                resolution.span?.let { span -> distanceToAnchor(span, anchor) } ?: Int.MAX_VALUE
            }.thenBy { it.span?.startOffset ?: 0 }
        )
    } else {
        null
    }
}

private fun selectTimeNear(input: ExtractedText, anchor: Int): ParsedClockTime? {
    val clauseBounds = NoticeTextContext.boundsAround(input.plainText, anchor, NoticeTextContext.clauseBoundaryChars)
    NoticeTimeParser.findNearest(input, anchor, clauseBounds.first, clauseBounds.second)?.let { return it }
    val sentenceBounds = NoticeTextContext.boundsAround(input.plainText, anchor, NoticeTextContext.sentenceBoundaryChars)
    NoticeTimeParser.findNearest(input, anchor, sentenceBounds.first, sentenceBounds.second)?.let { return it }
    // 全文唯一时刻可跨标题句继承；多个时刻时不猜测，避免把发布/参会时间接到错误动作上。
    val allTimes = NoticeTimeParser.findAll(input)
    return if (allTimes.map { it.time }.distinct().size == 1) allTimes.firstOrNull() else null
}

private fun nearestResolvedDate(
    dates: List<DateResolution>,
    anchor: Int,
    startOffset: Int,
    endOffset: Int
): DateResolution? {
    return dates.filter {
        it.resolved && it.date != null && it.span != null &&
            it.span.startOffset >= startOffset && it.span.endOffset <= endOffset
    }.minWithOrNull(
        compareBy<DateResolution> { resolution ->
            resolution.span?.let { span -> distanceToAnchor(span, anchor) } ?: Int.MAX_VALUE
        }
            .thenBy { if ((it.span?.startOffset ?: 0) >= anchor) 1 else 0 }
            .thenBy { it.span?.startOffset ?: 0 }
    )
}

private fun distanceToAnchor(span: TextSpan, anchor: Int): Int = when {
    anchor < span.startOffset -> span.startOffset - anchor
    anchor >= span.endOffset -> anchor - span.endOffset
    else -> 0
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
