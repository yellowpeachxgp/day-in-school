package cn.edu.haut.campus.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #13 「嵩山路空教室查询」独立回归：
 * 嵩山路必须有独立候选教室名录、正确响应校区别名、占用匹配不与莲花街或同号教室串线，
 * 且始终诚实标注为个人课表反推参考而非校方实时占用。
 */
class Issue13SongshanluStudyRoomsRegressionTest {

    @Test
    fun songshanluCatalogHasIndependentSeventeenRoomEntriesAcrossThreeBuildings() {
        val map = CampusStudyRoomCatalog.SONGSHANLU_STUDY_ROOMS_MAP
        assertEquals(
            listOf("嵩山路1号教学楼", "嵩山路2号教学楼", "嵩山路实验与综合楼"),
            map.keys.toList()
        )
        assertEquals(7, map.getValue("嵩山路1号教学楼").size)
        assertEquals(6, map.getValue("嵩山路2号教学楼").size)
        assertEquals(4, map.getValue("嵩山路实验与综合楼").size)

        val rooms = map.values.flatten()
        assertEquals(17, rooms.size)
        assertEquals(17, rooms.toSet().size)
        assertEquals(rooms, CampusStudyRoomCatalog.ALL_SONGSHANLU_CANDIDATE_ROOMS)
        assertTrue(rooms.all { it.startsWith("嵩山") })
        assertTrue("嵩山路房间不得退化成裸数字编号而跨校区串线", rooms.none { it.matches(Regex("\d{3,5}")) })

        val resolverBuildingNames = ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS.map { it.name }.toSet()
        assertTrue(map.keys.all { it in resolverBuildingNames })
    }

    @Test
    fun songshanCampusAliasesNeverFallBackToLianhuajieCatalog() {
        for (campus in listOf("songshanlu", "haut-songshanlu", "嵩山路校区", "嵩山", "齐礼阎")) {
            val rooms = CampusStudyRoomCatalog.studyRoomsMapForCampus(campus)
            assertEquals(CampusStudyRoomCatalog.SONGSHANLU_STUDY_ROOMS_MAP, rooms)
            assertEquals("嵩山路", CampusStudyRoomCatalog.campusShortLabel(campus))
            assertEquals("songshanlu", CampusStudyRoomCatalog.catalogProvenanceForCampus(campus).campusKey)
        }
        assertEquals(CampusStudyRoomCatalog.LIANHUAJIE_STUDY_ROOMS_MAP, CampusStudyRoomCatalog.studyRoomsMapForCampus(null))
        assertEquals(CampusStudyRoomCatalog.LIANHUAJIE_STUDY_ROOMS_MAP, CampusStudyRoomCatalog.studyRoomsMapForCampus("unknown-campus"))
    }

    @Test
    fun busyStateMatchesNormalizedSongshanRoomWithoutCrossBuildingOrCrossCampusMerge() {
        assertFalse(CampusStudyRoomCatalog.isRoomFree("嵩山1教101", listOf("嵩山路校区1号教学楼101")))
        assertFalse(CampusStudyRoomCatalog.isRoomFree("嵩山2教202", listOf("嵩山路校区2号教学楼202")))
        assertFalse(CampusStudyRoomCatalog.isRoomFree("嵩山综合楼301", listOf("嵩山路实验与综合楼301")))

        assertTrue("同号不同楼不得合并占用", CampusStudyRoomCatalog.isRoomFree("嵩山2教101", listOf("嵩山路校区1号教学楼101")))
        assertTrue("莲花街占用不得标记嵩山路房间", CampusStudyRoomCatalog.isRoomFree("嵩山1教101", listOf("3号教学楼3101")))
        assertTrue("嵩山路占用不得标记莲花街裸编号房间", CampusStudyRoomCatalog.isRoomFree("3101", listOf("嵩山路校区1号教学楼101")))
        assertTrue("裸 101 不具备楼宇上下文，不得错误占用嵩山1教101", CampusStudyRoomCatalog.isRoomFree("嵩山1教101", listOf("101")))
    }

    @Test
    fun provenanceHonestlyMarksPersonalReferenceNotOfficialRealtimeOccupancy() {
        val provenance = CampusStudyRoomCatalog.catalogProvenanceForCampus("songshanlu")
        assertEquals("songshanlu", provenance.campusKey)
        assertEquals(StudyRoomAvailabilityMeaning.PERSONAL_REFERENCE_NOT_REALTIME, provenance.availabilityMeaning)
        assertFalse(provenance.availabilityMeaning == StudyRoomAvailabilityMeaning.OFFICIAL_REALTIME_FEED)
        assertNull("无官方核验证据时 verifiedAt 必须保持为空", provenance.verifiedAt)
        assertTrue(provenance.sourceReference.orEmpty().contains("非官方"))
        assertTrue(provenance.note.orEmpty().contains("不代表"))
        assertTrue(provenance.note.orEmpty().contains("个人课表"))
    }
}
