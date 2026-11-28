package cn.edu.haut.campus.core.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class RelativeDateResolverTest {
    private val term = TermContext(
        termId = "2026-2027-1",
        startDate = LocalDate.of(2026, 9, 7),
        weekStartDay = DayOfWeek.MONDAY,
        weekCount = 20
    )
    private val wednesday = LocalDate.of(2026, 11, 25)

    @Test
    fun resolvesColloquialRelativeDaysAndTonight() {
        assertResolution("明天", LocalDate.of(2026, 11, 26), DateTimeHint.NONE)
        assertResolution("后天", LocalDate.of(2026, 11, 27), DateTimeHint.NONE)
        assertResolution("大后天", LocalDate.of(2026, 11, 28), DateTimeHint.NONE)
        assertResolution("今晚", wednesday, DateTimeHint.EVENING)
        assertResolution("今天晚上", wednesday, DateTimeHint.EVENING)
        assertResolution("明晚", LocalDate.of(2026, 11, 26), DateTimeHint.EVENING)
    }

    @Test
    fun resolvesThisAndNextWeekWeekdaysInTeachingCalendarWeek() {
        assertResolution("本周五", LocalDate.of(2026, 11, 27), DateTimeHint.NONE)
        assertResolution("这周五", LocalDate.of(2026, 11, 27), DateTimeHint.NONE)
        assertResolution("下周一", LocalDate.of(2026, 11, 30), DateTimeHint.NONE)
        assertResolution("下周三", LocalDate.of(2026, 12, 2), DateTimeHint.NONE)
        assertResolution("下个星期三", LocalDate.of(2026, 12, 2), DateTimeHint.NONE)
    }

    @Test
    fun repeatedNextOrPreviousWeekAccumulatesDirectionAndKeepsLongestMatch() {
        val nextNextMonday = ChineseRelativeDateResolver.resolve("下下周一", wednesday, term)
        assertEquals(DateResolutionStatus.RESOLVED, nextNextMonday.status)
        assertEquals(LocalDate.of(2026, 12, 7), nextNextMonday.date)
        assertNotEquals(LocalDate.of(2026, 11, 30), nextNextMonday.date)
        assertEquals("下下周一", nextNextMonday.matchedText)

        val previousPreviousFriday = ChineseRelativeDateResolver.resolve("上上周五", wednesday, term)
        assertEquals(DateResolutionStatus.RESOLVED, previousPreviousFriday.status)
        assertEquals(LocalDate.of(2026, 11, 13), previousPreviousFriday.date)
        assertNotEquals(LocalDate.of(2026, 11, 20), previousPreviousFriday.date)
        assertEquals("上上周五", previousPreviousFriday.matchedText)

        val withMeasureWord = ChineseRelativeDateResolver.resolve("下下个星期一", wednesday, term)
        assertEquals(LocalDate.of(2026, 12, 7), withMeasureWord.date)
    }

    @Test
    fun resolveAllDoesNotLetSuffixOfDoubleWeekPhraseStealOneWeek() {
        val results = ChineseRelativeDateResolver.resolveAll("下下周一补课", wednesday, term)

        assertEquals(1, results.size)
        assertEquals("下下周一", results.single().matchedText)
        assertEquals(0, results.single().span?.startOffset)
        assertEquals(4, results.single().span?.endOffset)
        assertEquals(LocalDate.of(2026, 12, 7), results.single().date)
    }

    @Test
    fun thisWeekFridayDoesNotRollForwardWhenReferenceIsSaturday() {
        val saturday = LocalDate.of(2026, 11, 28)
        val result = ChineseRelativeDateResolver.resolve("本周五", saturday, term)
        assertEquals(DateResolutionStatus.RESOLVED, result.status)
        assertEquals(LocalDate.of(2026, 11, 27), result.date)
    }

    @Test
    fun bareWeekdayChoosesSameOrNextOccurrenceWithMediumConfidence() {
        val fromWednesday = ChineseRelativeDateResolver.resolve("周五", wednesday, term)
        assertEquals(LocalDate.of(2026, 11, 27), fromWednesday.date)
        assertEquals(Confidence.MEDIUM, fromWednesday.confidence)

        val saturday = LocalDate.of(2026, 11, 28)
        val fromSaturday = ChineseRelativeDateResolver.resolve("周五", saturday, term)
        assertEquals(LocalDate.of(2026, 12, 4), fromSaturday.date)
        assertEquals(Confidence.MEDIUM, fromSaturday.confidence)
    }

    @Test
    fun resolvesTeachingWeekWednesdayWithArabicAndChineseNumerals() {
        val expected = LocalDate.of(2026, 10, 28)
        listOf("第8周周三", "第 8 周 星期三", "第八周周三", "第八周礼拜三").forEach { phrase ->
            val result = ChineseRelativeDateResolver.resolve(phrase, wednesday, term)
            assertEquals(phrase, DateResolutionStatus.RESOLVED, result.status)
            assertEquals(phrase, expected, result.date)
            assertEquals(Confidence.HIGH, result.confidence)
        }
    }

    @Test
    fun teachingWeekOutsideTermIsExplicitlyUnresolved() {
        val result = ChineseRelativeDateResolver.resolve("第21周周三", wednesday, term)
        assertEquals(DateResolutionStatus.OUT_OF_TERM, result.status)
        assertNull(result.date)
        assertFalse(result.resolved)
        assertTrue(result.reason.orEmpty().contains("超出本学期"))
    }

    @Test
    fun resolveAllKeepsLongestPhraseAndReportsSourceOffsets() {
        val text = "请在下周三前反馈，今晚不要重复提醒"
        val results = ChineseRelativeDateResolver.resolveAll(text, wednesday, term)

        assertEquals(2, results.size)
        assertEquals("下周三", results[0].matchedText)
        assertEquals(2, results[0].span?.startOffset)
        assertEquals(5, results[0].span?.endOffset)
        assertEquals(LocalDate.of(2026, 12, 2), results[0].date)
        assertEquals("今晚", results[1].matchedText)
        assertEquals(DateTimeHint.EVENING, results[1].timeHint)
    }

    @Test
    fun unrecognizedInputNeverFabricatesDate() {
        val result = ChineseRelativeDateResolver.resolve("尽快安排", wednesday, term)
        assertEquals(DateResolutionStatus.UNRECOGNIZED, result.status)
        assertNull(result.date)
        assertNull(result.span)
    }

    private fun assertResolution(input: String, expectedDate: LocalDate, expectedHint: DateTimeHint) {
        val result = ChineseRelativeDateResolver.resolve(input, wednesday, term)
        assertEquals(input, DateResolutionStatus.RESOLVED, result.status)
        assertEquals(input, expectedDate, result.date)
        assertEquals(input, expectedHint, result.timeHint)
    }
}
