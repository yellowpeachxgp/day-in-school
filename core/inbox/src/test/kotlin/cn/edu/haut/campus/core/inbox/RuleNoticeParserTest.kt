package cn.edu.haut.campus.core.inbox

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class RuleNoticeParserTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("synthetic-school"),
        accountId = AccountId("synthetic-account"),
        profileId = ProfileId("synthetic-profile")
    )
    private val mathRef = EntityRef(EntityType.COURSE, "course-math", scope)
    private val physicsRef = EntityRef(EntityType.COURSE, "course-physics", scope)
    private val term = TermContext(
        termId = "2026-2027-1",
        startDate = LocalDate.of(2026, 9, 7),
        weekStartDay = DayOfWeek.MONDAY,
        weekCount = 20
    )
    private val context = ParseContext(
        scope = scope,
        referenceDate = LocalDate.of(2026, 11, 25), // 周三
        term = term,
        zone = zone,
        provenance = DataProvenance(
            providerId = "inbox-test",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "synthetic-notice",
            trust = VerificationLevel.UNVERIFIED
        ),
        courses = listOf(
            InboxCourseCandidate(mathRef, "高等数学", aliases = listOf("高数")),
            InboxCourseCandidate(physicsRef, "大学物理")
        )
    )
    private val parser = RuleNoticeParser()

    @Test
    fun parsesCancellationAsModifyCourseOccurrenceWithoutApplyingIt() {
        val report = parseReport("高等数学本周五第3-4节停课，请同学们留意后续安排")

        assertEquals(emptyList<ParseIssue>(), report.issues)
        val action = report.actions.single() as ProposedAction.ModifyCourseOccurrence
        assertEquals(mathRef, action.courseRef)
        assertEquals(LocalDate.of(2026, 11, 27), action.date)
        assertTrue(action.change is OccurrenceChange.Cancel)
        assertEquals(Confidence.HIGH, action.confidence["course"])
        assertEquals(Confidence.HIGH, action.confidence["date"])
        assertTrue(action.sourceSpans.isNotEmpty())
    }

    @Test
    fun parsesRescheduleTargetDateEveningTimeAndRoom() {
        val report = parseReport("高等数学本周五的课调整到下周一晚上7点，地点A203")

        val action = report.actions.single() as ProposedAction.ModifyCourseOccurrence
        assertEquals(mathRef, action.courseRef)
        assertEquals(LocalDate.of(2026, 11, 27), action.date)
        val change = action.change as OccurrenceChange.Reschedule
        assertEquals(LocalDate.of(2026, 11, 30), change.targetDate)
        assertEquals(LocalTime.of(19, 0), change.startTime)
        assertNull(change.endTime)
        assertEquals("A203", change.room)
        assertEquals(Confidence.HIGH, action.confidence["targetDate"])
        assertEquals(Confidence.MEDIUM, action.confidence["targetTime"])
    }

    @Test
    fun parsesRoomChangeOnlyWhenNoTargetDateFollowsMarker() {
        val report = parseReport("下周三大学物理改到B302")

        val action = report.actions.single() as ProposedAction.ModifyCourseOccurrence
        assertEquals(physicsRef, action.courseRef)
        assertEquals(LocalDate.of(2026, 12, 2), action.date)
        val change = action.change as OccurrenceChange.ChangeRoom
        assertEquals("B302", change.room)
    }

    @Test
    fun missingDateProducesDiagnosticInsteadOfFabricatedProposal() {
        val report = parseReport("高等数学停课，具体时间另行通知")

        assertTrue(report.actions.isEmpty())
        assertTrue(report.issues.any { it.kind == ParseIssueKind.MISSING_DATE })
    }

    @Test
    fun unmatchedCourseProducesDiagnosticInsteadOfGuessingEntityRef() {
        val report = parseReport("结构化方程本周五停课")

        assertTrue(report.actions.isEmpty())
        assertTrue(report.issues.any { it.kind == ParseIssueKind.COURSE_NOT_MATCHED })
    }

    @Test
    fun parsesEveningLectureAsUncertainPersonalEventNotOccupyingBusyTime() {
        val report = parseReport("今晚7点在A203举办就业讲座")

        val action = report.actions.single() as ProposedAction.CreateEvent
        val expectedStart = LocalDate.of(2026, 11, 25).atTime(19, 0).atZone(zone).toInstant()
        assertEquals(expectedStart, action.draft.start)
        assertNull(action.draft.end)
        assertEquals(TimelineTimeSemantics.UNCERTAIN, action.draft.timeSemantics)
        assertEquals(BusyLevel.FREE, action.draft.busy)
        assertEquals("A203", action.draft.placeText)
        assertTrue(action.draft.title.contains("就业讲座"))
    }

    @Test
    fun parsesSubmissionDeadlineWithLowConfidenceEndOfDayWhenClockIsMissing() {
        val report = parseReport("实验报告后天前提交")

        val action = report.actions.single() as ProposedAction.CreateTask
        val expectedDue = LocalDate.of(2026, 11, 27).atTime(23, 59).atZone(zone).toInstant()
        assertEquals(expectedDue, action.due)
        assertEquals(Confidence.LOW, action.confidence["dueTime"])
        assertTrue(action.title.contains("实验报告"))
    }

    @Test
    fun negatedOrRefutedCancellationWordingDoesNotProduceProposal() {
        val negatives = listOf(
            "明天高等数学不停课，正常上课",
            "明天高等数学未停课，正常上课",
            "明天高等数学没停课，正常上课",
            "明天高等数学并非停课",
            "停课的消息不实",
            "跟不上进度",
            "跟不上课"
        )

        negatives.forEach { text ->
            val report = parseReport(text)
            assertTrue(text, report.actions.isEmpty())
        }
    }

    @Test
    fun cancelledLectureDoesNotProduceCreateEvent() {
        listOf(
            "讲座取消不办了",
            "明天9点讲座取消不办了",
            "明天9点的会议取消"
        ).forEach { text ->
            val report = parseReport(text)
            assertTrue(text, report.actions.none { it is ProposedAction.CreateEvent })
            assertTrue(text, report.issues.any { it.kind == ParseIssueKind.NO_ACTION })
        }
    }

    @Test
    fun completedOrNegatedSubmissionDoesNotProduceCreateTask() {
        listOf(
            "实验报告上周三已提交",
            "实验报告明天不用提交",
            "实验报告明天无需提交"
        ).forEach { text ->
            val report = parseReport(text)
            assertTrue(text, report.actions.none { it is ProposedAction.CreateTask })
            assertTrue(text, report.issues.any { it.kind == ParseIssueKind.NO_ACTION })
        }
    }

    @Test
    fun nearestDateAndTimeInDeadlineClauseWinOverIntroductoryPublicationDate() {
        val report = parseReport("今天发放材料，明天9:00截止提交")

        val action = report.actions.single() as ProposedAction.CreateTask
        val expectedDue = LocalDate.of(2026, 11, 26).atTime(9, 0).atZone(zone).toInstant()
        assertEquals(expectedDue, action.due)
    }

    @Test
    fun morningQualifierNearestToClockOverridesDeadlineSuperlativeWan() {
        val report = parseReport("明天最晚上午9点提交")

        val action = report.actions.single() as ProposedAction.CreateTask
        val expectedDue = LocalDate.of(2026, 11, 26).atTime(9, 0).atZone(zone).toInstant()
        assertEquals(expectedDue, action.due)
    }

    @Test
    fun chineseSingleDigitMinutesAreConsumedCompletely() {
        val report = parseReport("明天下午3点5分提交")

        val action = report.actions.single() as ProposedAction.CreateTask
        val expectedDue = LocalDate.of(2026, 11, 26).atTime(15, 5).atZone(zone).toInstant()
        assertEquals(expectedDue, action.due)
        assertEquals(Confidence.HIGH, action.confidence["dueTime"])
    }

    @Test
    fun daypartModifierDoesNotCrossPunctuationBoundary() {
        val input = ExtractedText.fromPlainText("晚上8点结束，明天7点开始")
        val times = NoticeTimeParser.findAll(input)

        assertEquals(2, times.size)
        assertEquals(LocalTime.of(20, 0), times[0].time)
        assertEquals(LocalTime.of(7, 0), times[1].time)
        assertFalse(times[1].periodAdjusted)
    }

    @Test
    fun roomParserRejectsBareYearOrHeadcountDigitsButKeepsLegalRoomShapes() {
        assertNull(NoticeRoomParser.find(ExtractedText.fromPlainText("地点2024")))
        assertNull(NoticeRoomParser.find(ExtractedText.fromPlainText("在1500")))
        assertEquals("A203", NoticeRoomParser.find(ExtractedText.fromPlainText("地点A203"))?.room)
        assertEquals("101", NoticeRoomParser.find(ExtractedText.fromPlainText("地点101"))?.room)
        assertEquals("F01", NoticeRoomParser.find(ExtractedText.fromPlainText("地点F01"))?.room)
        assertEquals("教三楼302", NoticeRoomParser.find(ExtractedText.fromPlainText("地点教三楼302"))?.room)
    }

    @Test
    fun bareDigitsInEventNoticeAreNotAbsorbedAsRoom() {
        val report = parseReport("今晚7点在2024举办就业讲座")

        val action = report.actions.single() as ProposedAction.CreateEvent
        assertNull(action.draft.placeText)
    }

    @Test
    fun cancellationChoosesCourseInSameClauseInsteadOfLongestCourseElsewhere() {
        val report = parseReport("高数本周五停课；大学物理照常上课。")

        val action = report.actions.single() as ProposedAction.ModifyCourseOccurrence
        assertEquals(mathRef, action.courseRef)
        assertTrue(action.change is OccurrenceChange.Cancel)
    }

    @Test
    fun roomOnlyChangeDoesNotBorrowDateFromNextSentence() {
        val report = parseReport("本周五高等数学改到A203。下周一恢复原教室。")

        val action = report.actions.single() as ProposedAction.ModifyCourseOccurrence
        assertEquals(LocalDate.of(2026, 11, 27), action.date)
        val change = action.change as OccurrenceChange.ChangeRoom
        assertEquals("A203", change.room)
    }

    @Test
    fun uniqueHeadingDateAndCourseRemainAvailableToActionSentence() {
        val dateInHeading = parseReport("明天课程调整通知如下：\n高等数学第3-4节停课")
        val firstAction = dateInHeading.actions.single() as ProposedAction.ModifyCourseOccurrence
        assertEquals(mathRef, firstAction.courseRef)
        assertEquals(LocalDate.of(2026, 11, 26), firstAction.date)

        val courseInHeading = parseReport("高等数学课程调整通知如下：\n本周五停课")
        val secondAction = courseInHeading.actions.single() as ProposedAction.ModifyCourseOccurrence
        assertEquals(mathRef, secondAction.courseRef)
        assertEquals(LocalDate.of(2026, 11, 27), secondAction.date)
    }

    @Test
    fun uniqueHeadingSourceDateRemainsAvailableToReschedule() {
        val report = parseReport("本周五课程调整通知如下：\n高等数学改到下周三晚上7点")

        val action = report.actions.single() as ProposedAction.ModifyCourseOccurrence
        assertEquals(mathRef, action.courseRef)
        assertEquals(LocalDate.of(2026, 11, 27), action.date)
        val change = action.change as OccurrenceChange.Reschedule
        assertEquals(LocalDate.of(2026, 12, 2), change.targetDate)
        assertEquals(LocalTime.of(19, 0), change.startTime)
    }

    @Test
    fun unresolvedTargetTeachingWeekDoesNotFallBackToRoomChange() {
        val report = parseReport("高等数学本周五改到第21周周三，地点A203")

        assertTrue(report.actions.isEmpty())
        assertTrue(report.issues.any { it.kind == ParseIssueKind.DATE_UNRESOLVED })
    }

    @Test
    fun suspendPortReturnsSameActionsAsReport() = runBlocking {
        val input = ExtractedText.fromPlainText("高数本周五停课")
        val actions = parser.parse(input, context)

        val action = actions.single() as ProposedAction.ModifyCourseOccurrence
        assertEquals(mathRef, action.courseRef)
        assertEquals(Confidence.MEDIUM, action.confidence["course"])
    }

    @Test
    fun unrelatedTextDoesNotProduceActions() {
        val report = parseReport("天气转凉，请注意保暖")
        assertTrue(report.actions.isEmpty())
        assertFalse(report.issues.any { it.kind == ParseIssueKind.COURSE_NOT_MATCHED })
    }

    @Test
    fun extractedTextFactoryPreservesLineOffsetsForSourceSpans() {
        val input = ExtractedText.fromPlainText("第一行\n高等数学本周五停课")
        val line = input.lines[1]
        assertEquals(4, line.startOffset)
        assertEquals("高等数学本周五停课", line.text)
        val span = input.span(line.startOffset, line.startOffset + 4)
        assertEquals(1, span.lineIndex)
        assertNotNull(span)
    }

    private fun parseReport(text: String): RuleParseReport =
        parser.parseReport(ExtractedText.fromPlainText(text), context)
}
