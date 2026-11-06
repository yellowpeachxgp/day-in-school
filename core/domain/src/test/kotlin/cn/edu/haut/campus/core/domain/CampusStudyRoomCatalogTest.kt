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
                rawWeeks = "1-3周",
                rawRoom = "莲5号教学楼5101",
                normalizedBuildingId = "lh_b5"
            ),
            CourseScheduleRule(
                id = "r2",
                courseId = "c2",
                termId = "2026-2027-1",
                weekday = DayOfWeek.MONDAY,
                startPeriod = 3,
                endPeriod = 4,
                weeks = setOf(1, 2, 3),
                rawWeeks = "1-3周",
                rawRoom = "莲6号教学楼6101",
                normalizedBuildingId = "lh_b6"
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
}
