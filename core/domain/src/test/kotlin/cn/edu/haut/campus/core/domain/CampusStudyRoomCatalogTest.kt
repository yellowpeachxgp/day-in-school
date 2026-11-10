package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.CourseScheduleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class CampusStudyRoomCatalogTest {

    @Test
    fun `resolveActiveSlotIndex maps hours to 5 study period slots`() {
        assertEquals(0, CampusStudyRoomCatalog.resolveActiveSlotIndex(8))
        assertEquals(1, CampusStudyRoomCatalog.resolveActiveSlotIndex(10))
        assertEquals(2, CampusStudyRoomCatalog.resolveActiveSlotIndex(14))
        assertEquals(3, CampusStudyRoomCatalog.resolveActiveSlotIndex(16))
        assertEquals(4, CampusStudyRoomCatalog.resolveActiveSlotIndex(20))
    }

    @Test
    fun `findBusyRawRooms and computeFreeRoomsCount filter overlapping rules`() {
        val rules = listOf(
            CourseScheduleRule(
                id = "r1",
                courseId = "c1",
                termId = "2026-2027-1",
                weekday = DayOfWeek.MONDAY,
                startPeriod = 1,
                endPeriod = 2,
                weeks = setOf(1, 2, 3),
                rawRoom = "莲5号教学楼5101",
                buildingId = "lh_b5"
            ),
            CourseScheduleRule(
                id = "r2",
                courseId = "c2",
                termId = "2026-2027-1",
                weekday = DayOfWeek.MONDAY,
                startPeriod = 3,
                endPeriod = 4,
                weeks = setOf(1, 2, 3),
                rawRoom = "莲6号教学楼6101",
                buildingId = "lh_b6"
            )
        )

        val slot12 = CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS[0]
        val busy = CampusStudyRoomCatalog.findBusyRawRooms(
            rules = rules,
            termId = "2026-2027-1",
            teachingWeek = 2,
            weekday = DayOfWeek.MONDAY,
            slot = slot12
        )

        assertEquals(listOf("莲5号教学楼5101"), busy)
        assertFalse(CampusStudyRoomCatalog.isRoomFree("5101", busy))
        assertTrue(CampusStudyRoomCatalog.isRoomFree("6101", busy))

        val totalCandidates = CampusStudyRoomCatalog.ALL_LIANHUAJIE_CANDIDATE_ROOMS.size
        val freeCount = CampusStudyRoomCatalog.computeFreeRoomsCount(
            rules = rules,
            termId = "2026-2027-1",
            teachingWeek = 2,
            weekday = DayOfWeek.MONDAY,
            slot = slot12
        )
        assertEquals(totalCandidates - 1, freeCount)
    }

    @Test
    fun computeAvailabilityHonorsSelectedCampusSingleDayEditsCancellationsMakeupAndHolidays() {
        val term = cn.edu.haut.campus.core.model.Term(
            id = "2026-2027-1",
            academicYear = "2026-2027",
            termNumber = 1,
            startDate = java.time.LocalDate.of(2026, 8, 31),
            weekStartDay = DayOfWeek.MONDAY,
            weekCount = 20,
            timeTableId = "tt_study",
            current = true
        )
        val pts = listOf(
            cn.edu.haut.campus.core.model.PeriodTime("p1", "tt_study", 1, java.time.LocalTime.of(8, 30), java.time.LocalTime.of(9, 15), "上午"),
            cn.edu.haut.campus.core.model.PeriodTime("p2", "tt_study", 2, java.time.LocalTime.of(9, 20), java.time.LocalTime.of(10, 5), "上午"),
            cn.edu.haut.campus.core.model.PeriodTime("p3", "tt_study", 3, java.time.LocalTime.of(10, 25), java.time.LocalTime.of(11, 10), "上午"),
            cn.edu.haut.campus.core.model.PeriodTime("p4", "tt_study", 4, java.time.LocalTime.of(11, 15), java.time.LocalTime.of(12, 0), "上午")
        )
        val courseLhj = cn.edu.haut.campus.core.model.Course(
            id = "c_lhj",
            name = "数据结构",
            colorArgb = 0xFF2563EB,
            source = cn.edu.haut.campus.core.model.CourseSource.OFFICIAL_JW
        )
        val courseSs = courseLhj.copy(id = "c_ss", name = "工程制图")
        val ruleLhj = CourseScheduleRule(
            id = "r_lhj_1",
            courseId = courseLhj.id,
            termId = term.id,
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = setOf(1, 2, 3),
            rawRoom = "5号教学楼5101"
        )
        val ruleSs = CourseScheduleRule(
            id = "r_ss_1",
            courseId = courseSs.id,
            termId = term.id,
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = setOf(1, 2, 3),
            rawRoom = "嵩山路校区1号教学楼101"
        )
        val slot12 = CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS[0]
        val w2Mon = java.time.LocalDate.of(2026, 9, 7)

        // 1) Dual-campus switching: Lianhuajie counts 59 candidates (58 free); Songshanlu counts 17 candidates (16 free)
        val sumLhj = CampusStudyRoomCatalog.computeAvailabilityForDate(
            date = w2Mon,
            term = term,
            courses = listOf(courseLhj, courseSs),
            rules = listOf(ruleLhj, ruleSs),
            periodTimes = pts,
            slot = slot12,
            campusId = "lianhuajie"
        )
        assertEquals("莲花街", sumLhj.campusShortLabel)
        assertEquals(59, sumLhj.totalCandidateRoomsCount)
        assertEquals(58, sumLhj.freeRoomsCount)

        val sumSs = CampusStudyRoomCatalog.computeAvailabilityForDate(
            date = w2Mon,
            term = term,
            courses = listOf(courseLhj, courseSs),
            rules = listOf(ruleLhj, ruleSs),
            periodTimes = pts,
            slot = slot12,
            campusId = "songshanlu"
        )
        assertEquals("嵩山路", sumSs.campusShortLabel)
        assertEquals(17, sumSs.totalCandidateRoomsCount)
        assertEquals(16, sumSs.freeRoomsCount)

        // 2) Single-day room change (5101 -> 5201) + standalone makeup class in 6101:
        //    5101 becomes free, while 5201 and 6101 become busy -> 57 free!
        val singleDayRoomMove = cn.edu.haut.campus.core.model.OccurrenceOverride(
            id = "ov_move_room",
            termId = term.id,
            courseId = courseLhj.id,
            courseRuleId = ruleLhj.id,
            date = w2Mon,
            startPeriod = 1,
            endPeriod = 2,
            room = "5号教学楼5201",
            canceled = false
        )
        val singleDayMakeup = cn.edu.haut.campus.core.model.OccurrenceOverride(
            id = "ov_makeup_6101",
            termId = term.id,
            courseId = courseLhj.id,
            courseRuleId = null,
            date = w2Mon,
            startPeriod = 1,
            endPeriod = 2,
            room = "6号教学楼6101",
            canceled = false
        )
        val busyWithOverrides = CampusStudyRoomCatalog.findBusyRawRoomsForDate(
            date = w2Mon,
            term = term,
            courses = listOf(courseLhj),
            rules = listOf(ruleLhj),
            periodTimes = pts,
            occurrenceOverrides = listOf(singleDayRoomMove, singleDayMakeup),
            slot = slot12
        )
        assertTrue("Original 5101 must be free after single-day room move", CampusStudyRoomCatalog.isRoomFree("5101", busyWithOverrides))
        assertFalse("Moved-in 5201 must NOT be reported conflict-free!", CampusStudyRoomCatalog.isRoomFree("5201", busyWithOverrides))
        assertFalse("Makeup room 6101 must NOT be reported conflict-free!", CampusStudyRoomCatalog.isRoomFree("6101", busyWithOverrides))

        // 3) Single-day cancellation: 5101 becomes free (all 59 free)
        val singleDayCancel = singleDayRoomMove.copy(id = "ov_cancel_5101", canceled = true, room = null)
        val sumCanceled = CampusStudyRoomCatalog.computeAvailabilityForDate(
            date = w2Mon,
            term = term,
            courses = listOf(courseLhj),
            rules = listOf(ruleLhj),
            periodTimes = pts,
            occurrenceOverrides = listOf(singleDayCancel),
            slot = slot12,
            campusId = "lianhuajie"
        )
        assertEquals(59, sumCanceled.freeRoomsCount)

        // 4) Holiday & makeup rescheduled Sunday (Sunday 2026-09-13 follows Monday's schedule via CalendarOverride!)
        val holidayMon = cn.edu.haut.campus.core.model.CalendarOverride(
            id = "cal_hol",
            termId = term.id,
            date = w2Mon,
            overrideType = cn.edu.haut.campus.core.model.CalendarOverrideType.HOLIDAY_NO_CLASS,
            title = "放假停课"
        )
        val w2SunMakeup = java.time.LocalDate.of(2026, 9, 13)
        val makeupSun = cn.edu.haut.campus.core.model.CalendarOverride(
            id = "cal_makeup_sun",
            termId = term.id,
            date = w2SunMakeup,
            overrideType = cn.edu.haut.campus.core.model.CalendarOverrideType.MAKEUP_FOLLOW_DATE,
            followDate = w2Mon,
            title = "补周一课表"
        )
        val sumOnHoliday = CampusStudyRoomCatalog.computeAvailabilityForDate(
            date = w2Mon,
            term = term,
            courses = listOf(courseLhj),
            rules = listOf(ruleLhj),
            periodTimes = pts,
            calendarOverrides = listOf(holidayMon, makeupSun),
            slot = slot12,
            campusId = "lianhuajie"
        )
        assertEquals(59, sumOnHoliday.freeRoomsCount)

        val sumOnMakeupSunday = CampusStudyRoomCatalog.computeAvailabilityForDate(
            date = w2SunMakeup,
            term = term,
            courses = listOf(courseLhj),
            rules = listOf(ruleLhj),
            periodTimes = pts,
            calendarOverrides = listOf(holidayMon, makeupSun),
            slot = slot12,
            campusId = "lianhuajie"
        )
        assertEquals(58, sumOnMakeupSunday.freeRoomsCount)
    }
}
