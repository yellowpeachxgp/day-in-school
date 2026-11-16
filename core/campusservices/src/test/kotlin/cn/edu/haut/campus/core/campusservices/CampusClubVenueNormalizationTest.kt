package cn.edu.haut.campus.core.campusservices

import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #16 计算机服务社场地规范化回归（云端 JSON 解码层）：
 * - 预置目录与新场地值不被改写；
 * - 旧版「8号教学楼/大学生活动中心」值在结构化四字段上同步纠正；
 * - 其他社团不受该单点修正影响。
 */
class CampusClubVenueNormalizationTest {

    private fun clubJson(
        id: String,
        venue: String,
        campusName: String? = null,
        buildingName: String? = null,
        roomNumber: String? = null
    ): String {
        val extras = StringBuilder()
        campusName?.let { extras.append(",\"campus_name\":\"").append(it).append("\"") }
        buildingName?.let { extras.append(",\"building_name\":\"").append(it).append("\"") }
        roomNumber?.let { extras.append(",\"room_number\":\"").append(it).append("\"") }
        return "{\"school_id\":\"haut\",\"version\":3,\"clubs\":[{\"id\":\"" + id +
            "\",\"name\":\"测试社团\",\"category\":\"TECH_INNOVATION\",\"activity_venue\":\"" +
            venue + "\"" + extras.toString() + ",\"enabled\":true}]}"
    }

    @Test
    fun presetCatalogAlreadyCarriesStructuredF01W111Venue() {
        val club = PresetCampusCommunityCatalog.defaultCampusClubs
            .single { it.id == "club_partner_computer_service" }
        assertEquals("莲花街校区 学生宿舍F01 W111", club.activityVenue)
        assertEquals("莲花街校区", club.campusName)
        assertEquals("学生宿舍F01", club.buildingName)
        assertEquals("W111", club.roomNumber)
        assertEquals(3, PresetCampusCommunityCatalog.defaultCampusClubs.size)
    }

    @Test
    fun legacyVenueMismatchCombinationsAreCorrectedTogether() {
        // 旧版错位组合：场地为 8号教学楼，campus/building/room 字段各自漂移
        val payload = clubJson(
            id = "club_partner_computer_service",
            venue = "嵩山路校区 8号教学楼",
            campusName = "嵩山路校区",
            buildingName = "8号教学楼",
            roomNumber = "204"
        )
        val catalog = CampusCloudOidcHelper.parseCampusContentJsonString(payload)!!
        val club = catalog.clubs.single()
        assertEquals("莲花街校区 学生宿舍F01 W111", club.activityVenue)
        assertEquals("莲花街校区", club.campusName)
        assertEquals("学生宿舍F01", club.buildingName)
        assertEquals("W111", club.roomNumber)
    }

    @Test
    fun newerUserOrCloudProvidedVenueIsNotOverwritten() {
        // 云端下发的新版场地（不含旧版关键词）必须原样保留
        val payload = clubJson(
            id = "club_partner_computer_service",
            venue = "信息科学与工程学院楼 B205",
            campusName = "莲花街校区",
            buildingName = "信息科学与工程学院楼",
            roomNumber = "B205"
        )
        val club = CampusCloudOidcHelper.parseCampusContentJsonString(payload)!!.clubs.single()
        assertEquals("信息科学与工程学院楼 B205", club.activityVenue)
        assertEquals("信息科学与工程学院楼", club.buildingName)
        assertEquals("B205", club.roomNumber)
        assertEquals("莲花街校区", club.campusName)
    }

    @Test
    fun alreadyCorrectedVenueIsIdempotent() {
        // 已纠正值再次进入解码器不产生二阶改写
        val payload = clubJson(
            id = "club_partner_computer_service",
            venue = "莲花街校区 学生宿舍F01 W111",
            campusName = "莲花街校区",
            buildingName = "学生宿舍F01",
            roomNumber = "W111"
        )
        val club = CampusCloudOidcHelper.parseCampusContentJsonString(payload)!!.clubs.single()
        assertEquals("莲花街校区 学生宿舍F01 W111", club.activityVenue)
        assertEquals("W111", club.roomNumber)
    }

    @Test
    fun otherClubsWithSimilarVenueTextAreUnaffected() {
        // 仅 club_partner_computer_service 该一个 ID 会被纠偏；其余社团含相同字样也原样保留
        val otherA = clubJson(id = "club_other_activity", venue = "大学生活动中心 204")
        val otherB = clubJson(id = "club_other_8", venue = "莲花街校区 8号教学楼")
        val payload = "{\"school_id\":\"haut\",\"version\":3,\"clubs\":[" +
            otherA.substringAfter("\"clubs\":[").substringBeforeLast("]") + "," +
            otherB.substringAfter("\"clubs\":[").substringBeforeLast("]") + "]}"
        val clubs = CampusCloudOidcHelper.parseCampusContentJsonString(payload)!!.clubs
        assertEquals("大学生活动中心 204", clubs.single { c -> c.id == "club_other_activity" }.activityVenue)
        assertEquals("莲花街校区 8号教学楼", clubs.single { c -> c.id == "club_other_8" }.activityVenue)
        assertTrue(clubs.none { c -> c.id != "club_partner_computer_service" && c.roomNumber == "W111" })
    }

    @Test
    fun absentClubsArrayFallsBackToPresetCatalog() {
        // 云端响应缺少 clubs 数组 -> 回退预置目录（仍带 F01/W111 正确值）
        val catalog = CampusCloudOidcHelper.parseCampusContentJsonString("{\"school_id\":\"haut\",\"version\":3}")!!
        val club = catalog.clubs.single { it.id == "club_partner_computer_service" }
        assertEquals("莲花街校区 学生宿舍F01 W111", club.activityVenue)
        assertNull(CampusCloudOidcHelper.parseCampusContentJsonString(null))
        assertNotNull(catalog)
    }
}

