package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TermDateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class TeachingWeekAndWeeksParserTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsHugeRangeBeforeAllocation() { WeeksSetParser.parse("1-2147483647") }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfBoundSingleWeek() { WeeksSetParser.parse("2147483647") }


    private val sampleTerm = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 9, 7), // 周一
        weekStartDay = DayOfWeek.MONDAY,
        weekCount = 20,
        current = true
    )

    @Test
    fun `parse odd, even, and composite week strings accurately into Set of Int`() {
        assertEquals(
            (1..16).toSet(),
            WeeksSetParser.parse("1-16周")
        )
        assertEquals(
            setOf(1, 3, 5, 7, 9, 11, 13, 15),
            WeeksSetParser.parse("1-15周(单)")
        )
        assertEquals(
            setOf(2, 4, 6, 8, 10, 12, 14, 16),
            WeeksSetParser.parse("2-16周（双）")
        )
        assertEquals(
            setOf(1, 2, 3, 4, 8, 10, 12, 14, 16),
            WeeksSetParser.parse("1-4周,7-14周(双),16周")
        )
    }

    @Test
    fun `format week set into human readable labels`() {
        assertEquals("1-16周", WeeksSetParser.format((1..16).toSet()))
        assertEquals("1-15单周", WeeksSetParser.format(setOf(1, 3, 5, 7, 9, 11, 13, 15)))
        assertEquals("2-16双周", WeeksSetParser.format(setOf(2, 4, 6, 8, 10, 12, 14, 16)))
        assertEquals("第9周", WeeksSetParser.format(setOf(9)))
        assertEquals("1-4,8,10-12周", WeeksSetParser.format(setOf(1, 2, 3, 4, 8, 10, 11, 12)))
    }

    @Test
    fun `resolveDate handles before term, in term including Sunday, and after term boundaries`() {
        val before = TeachingWeekCalculator.resolveDate(LocalDate.of(2026, 9, 1), sampleTerm)
        assertEquals(TermDateStatus.BEFORE_TERM, before.status)
        assertNull(before.teachingWeek)

        // 第 1 周周日 (2026-09-13)
        val week1Sunday = TeachingWeekCalculator.resolveDate(LocalDate.of(2026, 9, 13), sampleTerm)
        assertEquals(TermDateStatus.IN_TERM, week1Sunday.status)
        assertEquals(1, week1Sunday.teachingWeek)
        assertEquals(DayOfWeek.SUNDAY, week1Sunday.weekdayUsed)

        // 2026-10-31 周六 -> 第 8 周周六
        val oct31 = TeachingWeekCalculator.resolveDate(LocalDate.of(2026, 10, 31), sampleTerm)
        assertEquals(TermDateStatus.IN_TERM, oct31.status)
        assertEquals(8, oct31.teachingWeek)
        assertEquals(DayOfWeek.SATURDAY, oct31.weekdayUsed)

        // 超过 20 周
        val after = TeachingWeekCalculator.resolveDate(LocalDate.of(2027, 2, 1), sampleTerm)
        assertEquals(TermDateStatus.AFTER_TERM, after.status)
        assertNull(after.teachingWeek)
    }

    @Test
    fun `resolveDate respects holiday and makeup reschedule overrides`() {
        val overrides = listOf(
            CalendarOverride(
                id = "ovr_national_day",
                termId = sampleTerm.id,
                date = LocalDate.of(2026, 10, 1),
                overrideType = CalendarOverrideType.HOLIDAY_NO_CLASS,
                title = "国庆节放假停课"
            ),
            CalendarOverride(
                id = "ovr_makeup_sat",
                termId = sampleTerm.id,
                date = LocalDate.of(2026, 10, 10), // 周六调休补课
                overrideType = CalendarOverrideType.MAKEUP_FOLLOW_DATE,
                followDate = LocalDate.of(2026, 10, 2), // 补第 4 周周五的课
                title = "国庆调休：按第 4 周周五课表上课"
            )
        )

        val holiday = TeachingWeekCalculator.resolveDate(LocalDate.of(2026, 10, 1), sampleTerm, overrides)
        assertEquals(TermDateStatus.HOLIDAY_NO_CLASS, holiday.status)
        assertEquals("国庆节放假停课", holiday.calendarNotice)

        val makeup = TeachingWeekCalculator.resolveDate(LocalDate.of(2026, 10, 10), sampleTerm, overrides)
        assertEquals(TermDateStatus.MAKEUP_RESCHEDULED, makeup.status)
        assertEquals(4, makeup.teachingWeek)
        assertEquals(DayOfWeek.FRIDAY, makeup.weekdayUsed)
    }
}
