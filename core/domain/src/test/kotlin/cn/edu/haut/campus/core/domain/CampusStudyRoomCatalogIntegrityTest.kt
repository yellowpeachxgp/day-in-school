package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.CourseScheduleRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * #13 自习室名录完整性回归：名录条目唯一性、楼宇引用一致性、跨校区同名不合并、
 * 空课表输入不得冒充「全部空闲」、来源诚实标注。
 */
class CampusStudyRoomCatalogIntegrityTest {

    @Test
    fun all17SongshanluStudyRoomsUniqueAndReferencedBuildingsExistInResolver() {
        val songshanRooms = CampusStudyRoomCatalog.SONGSHANLU_STUDY_ROOMS_MAP.values.flatten()
        assertEquals(17, songshanRooms.size)
        assertEquals("Songshanlu room entries must all be unique", 17, songshanRooms.toSet().size)
        assertEquals(17, CampusStudyRoomCatalog.ALL_SONGSHANLU_CANDIDATE_ROOMS.size)

        val resolverNames = ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS.map { it.name }.toSet()
        for (buildingKey in CampusStudyRoomCatalog.SONGSHANLU_STUDY_ROOMS_MAP.keys) {
            assertTrue(
                "Study-room building key must exist in ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS",
                buildingKey in resolverNames
            )
        }
        for (room in songshanRooms) {
            assertTrue(
                "Songshanlu room must carry a building prefix so same-numbered rooms never merge",
                room.startsWith("嵩山1教") || room.startsWith("嵩山2教") || room.startsWith("嵩山综合楼")
            )
        }

        val lhjRooms = CampusStudyRoomCatalog.LIANHUAJIE_STUDY_ROOMS_MAP.values.flatten()
        assertEquals(lhjRooms.size, lhjRooms.toSet().size)
    }

    @Test
    fun sameRoomNumberInDifferentBuildingsDoesNotMergeBusyState() {
        val busyT1 = listOf("嵩山路校区1号教学楼101")
        assertFalse(CampusStudyRoomCatalog.isRoomFree("嵩山1教101", busyT1))
        assertTrue(
            "嵩山2教101 in a different building must NOT merge with 1教101",
            CampusStudyRoomCatalog.isRoomFree("嵩山2教101", busyT1)
        )
        assertTrue(CampusStudyRoomCatalog.isRoomFree("嵩山综合楼201", busyT1))

        val busyT2 = listOf("嵩山路校区2号教学楼101")
        assertFalse(CampusStudyRoomCatalog.isRoomFree("嵩山2教101", busyT2))
        assertTrue(CampusStudyRoomCatalog.isRoomFree("嵩山1教101", busyT2))

        // 跨校区隔离：莲花街占用串不会标记嵩山路房间，反之亦然
        assertTrue(CampusStudyRoomCatalog.isRoomFree("嵩山1教101", listOf("3号教学楼3101")))
        assertTrue(CampusStudyRoomCatalog.isRoomFree("3101", listOf("嵩山路校区1号教学楼101")))
        // 无校区前缀的裸数字串绝不能归并到嵩山路房间键
        assertTrue(CampusStudyRoomCatalog.isRoomFree("嵩山1教101", listOf("101")))
    }

    @Test
    fun emptyScheduleInputMarksSummaryWithoutBasisNotAllFree() {
        val slot = CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS.first()

        val fromOccurrences = CampusStudyRoomCatalog.computeAvailabilityForOccurrences(
            occurrences = emptyList(),
            slot = slot,
            campusId = "songshanlu"
        )
        assertEquals(17, fromOccurrences.freeRoomsCount)
        assertFalse(
            "Empty occurrence input must be flagged hasScheduleBasis=false (unobserved != all free)",
            fromOccurrences.hasScheduleBasis
        )

        val fromDate = CampusStudyRoomCatalog.computeAvailabilityForDate(
            date = LocalDate.of(2026, 9, 7),
            term = null,
            courses = emptyList(),
            rules = emptyList(),
            periodTimes = emptyList(),
            slot = slot,
            campusId = "lianhuajie"
        )
        assertFalse(fromDate.hasScheduleBasis)

        val withRule = CampusStudyRoomCatalog.computeAvailabilityForDate(
            date = LocalDate.of(2026, 9, 7),
            term = null,
            courses = emptyList(),
            rules = listOf(
                CourseScheduleRule(
                    id = "r1", courseId = "c1", termId = "t1",
                    weekday = DayOfWeek.MONDAY,
                    startPeriod = 1, endPeriod = 2, weeks = setOf(1),
                    rawRoom = "5号教学楼5101"
                )
            ),
            periodTimes = emptyList(),
            slot = slot,
            campusId = "lianhuajie"
        )
        assertTrue(withRule.hasScheduleBasis)
    }

    @Test
    fun catalogProvenanceHonestlyMarksPersonalReferenceUnverifiedData() {
        val ss = CampusStudyRoomCatalog.catalogProvenanceForCampus("songshanlu")
        assertEquals("songshanlu", ss.campusKey)
        assertEquals(StudyRoomAvailabilityMeaning.PERSONAL_REFERENCE_NOT_REALTIME, ss.availabilityMeaning)
        assertNull("No official verification evidence exists - verifiedAt must stay null", ss.verifiedAt)
        assertTrue(ss.sourceReference!!.contains("非官方"))
        assertTrue(ss.note!!.contains("不代表"))

        val lhj = CampusStudyRoomCatalog.catalogProvenanceForCampus("lianhuajie")
        assertEquals("lianhuajie", lhj.campusKey)
        assertEquals(StudyRoomAvailabilityMeaning.PERSONAL_REFERENCE_NOT_REALTIME, lhj.availabilityMeaning)
        assertNull(lhj.verifiedAt)

        assertEquals("lianhuajie", CampusStudyRoomCatalog.catalogProvenanceForCampus(null).campusKey)
        assertEquals("lianhuajie", CampusStudyRoomCatalog.catalogProvenanceForCampus("unknown_x").campusKey)
        assertEquals("songshanlu", CampusStudyRoomCatalog.catalogProvenanceForCampus("嵩山").campusKey)
        assertEquals("嵩山路", CampusStudyRoomCatalog.campusShortLabel("嵩山"))
    }
}
