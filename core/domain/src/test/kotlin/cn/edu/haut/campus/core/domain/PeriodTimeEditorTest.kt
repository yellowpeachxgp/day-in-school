package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.OccurrenceRule
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PeriodTimeScheme
import cn.edu.haut.campus.core.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class PeriodTimeEditorTest {

    private fun period(no: Int, start: String, end: String): PeriodTime = PeriodTime(
        id = "pt_" + no,
        timeTableId = "tt",
        periodNo = no,
        startLocalTime = LocalTime.parse(start),
        endLocalTime = LocalTime.parse(end)
    )

    @Test
    fun validatePeriods_rejectsReverseOverlapDuplicateAndOutOfRange() {
        val reversed = PeriodTimeEditor.validatePeriods(listOf(period(1, "08:00", "08:00")))
        assertTrue(reversed.any { it.code == "REVERSED_TIME" })

        val overlap = PeriodTimeEditor.validatePeriods(
            listOf(period(1, "08:00", "09:00"), period(2, "08:30", "09:30"))
        )
        assertTrue(overlap.any { it.code == "OVERLAPPING_PERIODS" })

        val duplicate = PeriodTimeEditor.validatePeriods(
            listOf(period(1, "08:00", "09:00"), period(1, "09:00", "10:00"))
        )
        assertTrue(duplicate.any { it.code == "DUPLICATE_PERIOD" })

        val outOfRange = PeriodTimeEditor.validatePeriods(listOf(period(31, "08:00", "09:00")))
        assertTrue(outOfRange.any { it.code == "PERIOD_OUT_OF_RANGE" })
    }

    @Test
    fun validatePeriods_acceptsMonotonicAdjacentPeriods() {
        val issues = PeriodTimeEditor.validatePeriods(
            listOf(period(1, "08:00", "08:45"), period(2, "08:45", "09:30"), period(3, "09:40", "10:25"))
        )
        assertEquals(emptyList<PeriodTimeEditor.ValidationIssue>(), issues)
    }

    @Test
    fun validateScheme_rejectsOverlappingDateRanges() {
        val existing = PeriodTimeScheme(
            id = "scheme_a", termId = "term", timeTableId = "tt", name = "冬季",
            startDate = LocalDate.of(2026, 10, 1), endDate = LocalDate.of(2026, 12, 31)
        )
        val issues = PeriodTimeEditor.validateScheme(
            name = "考试周",
            startDate = LocalDate.of(2026, 12, 31),
            endDate = LocalDate.of(2027, 1, 7),
            periods = listOf(period(1, "08:00", "08:45")),
            existingSchemes = listOf(existing)
        )
        assertTrue(issues.any { it.code == "OVERLAPPING_SCHEME_RANGE" })
    }

    @Test
    fun validateOccurrenceRules_acceptsMultiDayRulesWithCustomTime() {
        val issues = PeriodTimeEditor.validateOccurrenceRules(
            listOf(
                OccurrenceRule(DayOfWeek.MONDAY, 1, 4, setOf(14, 15), "工程中心"),
                OccurrenceRule(DayOfWeek.WEDNESDAY, 7, 8, setOf(14), "实验室", LocalTime.of(13, 30), LocalTime.of(16, 30))
            )
        )
        assertEquals(emptyList<PeriodTimeEditor.ValidationIssue>(), issues)
    }

    @Test
    fun validateOccurrenceRules_rejectsInvalidMatrixInput() {
        val duplicate = OccurrenceRule(DayOfWeek.MONDAY, 1, 2, setOf(14), "工程中心")
        val issues = PeriodTimeEditor.validateOccurrenceRules(
            listOf(
                duplicate,
                duplicate,
                OccurrenceRule(DayOfWeek.TUESDAY, 4, 3, emptySet(), " ", LocalTime.of(10, 0), LocalTime.of(9, 0))
            )
        )
        assertTrue(issues.any { it.code == "DUPLICATE_OCCURRENCE" })
        assertTrue(issues.any { it.code == "REVERSED_PERIOD_RANGE" })
        assertTrue(issues.any { it.code == "EMPTY_WEEKS" })
        assertTrue(issues.any { it.code == "BLANK_ROOM" })
        assertTrue(issues.any { it.code == "REVERSED_CUSTOM_TIME" })

        val overlapping = PeriodTimeEditor.validateOccurrenceRules(
            listOf(
                OccurrenceRule(DayOfWeek.MONDAY, 1, 4, setOf(14, 15), "场地A"),
                OccurrenceRule(DayOfWeek.MONDAY, 3, 5, setOf(15), "场地B"),
                OccurrenceRule(DayOfWeek.MONDAY, 5, 6, setOf(15), "场地C")
            )
        )
        assertTrue(overlapping.any { it.code == "OVERLAPPING_OCCURRENCE" })
    }

    @Test
    fun scheduleEngine_usesDatedSchemeOnlyInsideClosedDateRange() {
        val term = Term(
            id = "2026-2027-1", academicYear = "2026-2027", termNumber = 1,
            startDate = LocalDate.of(2026, 8, 31), weekCount = 20, timeTableId = "tt", current = true
        )
        val course = Course("c1", name = "合成课", colorArgb = 1L, source = CourseSource.MANUAL)
        val rule = CourseScheduleRule("r1", course.id, term.id, DayOfWeek.MONDAY, 1, 1, (1..20).toSet(), "教室")
        val base = period(1, "08:00", "08:45")
        val schemeStart = LocalDate.of(2026, 10, 5)
        val schemeEnd = LocalDate.of(2026, 10, 19)
        val schemeRow = period(1, "09:10", "09:55").copy(
            id = "scheme_row_1", schemeId = "scheme", termId = term.id,
            validStartDate = schemeStart, validEndDate = schemeEnd, userModified = true
        )
        val inputs = listOf(base, schemeRow)

        fun resolve(date: LocalDate) = ScheduleEngine.resolveOccurrencesForDate(
            date = date, term = term, courses = listOf(course), rules = listOf(rule), periodTimes = inputs
        ).single()

        assertEquals(LocalTime.of(8, 0), resolve(LocalDate.of(2026, 9, 28)).startTime)
        assertEquals(LocalTime.of(9, 10), resolve(schemeStart).startTime)
        assertEquals(LocalTime.of(9, 10), resolve(schemeEnd).startTime)
        assertEquals(LocalTime.of(8, 0), resolve(LocalDate.of(2026, 10, 26)).startTime)
    }

    @Test
    fun reminderPlannerProjectsEventsUsingDatedSchemeTimes() {
        val term = Term(
            id = "2026-2027-1", academicYear = "2026-2027", termNumber = 1,
            startDate = LocalDate.of(2026, 8, 31), weekCount = 20, timeTableId = "tt", current = true
        )
        val course = Course("c1", name = "合成课", colorArgb = 1L, source = CourseSource.MANUAL)
        val rule = CourseScheduleRule("r1", course.id, term.id, DayOfWeek.MONDAY, 1, 1, (1..20).toSet(), "教室")
        val base = period(1, "08:00", "08:45")
        val schemeStart = LocalDate.of(2026, 10, 5)
        val schemeEnd = LocalDate.of(2026, 10, 19)
        val schemeRow = period(1, "09:10", "09:55").copy(
            id = "scheme_row_1", schemeId = "scheme", termId = term.id,
            validStartDate = schemeStart, validEndDate = schemeEnd, userModified = true
        )
        val events = ReminderPlanner.buildWindowEvents(
            now = ReminderRevision.schoolInstant(LocalDate.of(2026, 10, 4), LocalTime.of(7, 0)),
            horizonDays = 8,
            term = term,
            courses = listOf(course),
            rules = listOf(rule),
            periodTimes = listOf(base, schemeRow),
            calendarOverrides = emptyList(),
            occurrenceOverrides = emptyList(),
            buildings = emptyList()
        )
        assertEquals(2, events.size)
        assertEquals(ReminderRevision.schoolInstant(schemeStart, LocalTime.of(9, 10)), events[0].startAt)
        assertEquals(ReminderRevision.schoolInstant(LocalDate.of(2026, 10, 12), LocalTime.of(9, 10)), events[1].startAt)
    }
}
