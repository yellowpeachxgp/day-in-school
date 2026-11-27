package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * #16 「计算机服务社活动场地应为学生宿舍 F01 W111」独立回归：
 * 锁定预置目录结构化四字段，并确保地点解析保持诚实——F01/W111 可定位到 E/F 片区候选，
 * 但在没有单栋/房间测绘证据时不得冒充 EXACT_VERIFIED。
 */
class Issue16ComputerServiceVenueRegressionTest {

    @Test
    fun presetComputerServiceClubCarriesExactStructuredF01W111Venue() {
        val club = PresetCampusCommunityCatalog.defaultCampusClubs.single { it.id == "club_partner_computer_service" }
        assertEquals("计算机服务社", club.name)
        assertEquals("莲花街校区 学生宿舍F01 W111", club.activityVenue)
        assertEquals("莲花街校区", club.campusName)
        assertEquals("学生宿舍F01", club.buildingName)
        assertEquals("W111", club.roomNumber)
        assertFalse(club.activityVenue.contains("8号教学楼"))
        assertFalse(club.activityVenue.contains("大学生活动中心"))
        assertFalse(club.buildingName.orEmpty().contains("8号教学楼"))
    }

    @Test
    fun f01W111ResolvesOnlyAsFuzzyZoneCandidateUntilPreciseEvidenceExists() {
        val dormZone = Building(
            id = "b_dorm_ef",
            campusId = "lianhuajie",
            name = "学生公寓E/F区",
            shortName = "E/F公寓",
            latitude = 34.833150,
            longitude = 113.537750,
            category = CampusCategory.DORMITORY,
            entrances = listOf("东侧门禁"),
            description = "学生公寓E/F区",
            verifiedAt = LocalDate.of(2026, 11, 9)
        )
        val resolved = ClassroomBuildingResolver.resolve(
            rawRoom = "莲花街校区 学生宿舍F01 W111",
            buildings = listOf(dormZone),
            aliases = listOf(
                BuildingAlias("al_dorm_f01_a", "b_dorm_ef", "学生宿舍F01", 0.74f, false)
            )
        )
        assertEquals(BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION, resolved.status)
        assertEquals("b_dorm_ef", resolved.building?.id)
        assertEquals("W111", resolved.roomNumberHint)
        assertTrue("未取得 F01 单栋或 W111 房间测绘证据前不得声称精确核验", resolved.confidence < 1.0f)
    }

    @Test
    fun otherPresetClubsDoNotInheritComputerServiceVenue() {
        val others = PresetCampusCommunityCatalog.defaultCampusClubs.filter { it.id != "club_partner_computer_service" }
        assertTrue(others.isNotEmpty())
        assertTrue(others.none { it.roomNumber == "W111" })
        assertTrue(others.none { it.buildingName == "学生宿舍F01" })
        assertTrue(others.none { it.activityVenue.contains("学生宿舍F01 W111") })
    }
}
