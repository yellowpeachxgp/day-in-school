package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicSystemProfile
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CampusProfile
import cn.edu.haut.campus.core.model.CustomCampusZone
import cn.edu.haut.campus.core.model.CustomCampusZoneCodec
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SchoolProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolDirectoryTest {

    @Test
    fun `内置目录支持中文拼音首字母和别名搜索`() {
        val directory = SchoolDirectory.builtIn()
        assertEquals(1, directory.search("河南工业大学").size)
        assertEquals(1, directory.search("henangongyedaxue").size)
        assertEquals(1, directory.search("hngydx").size)
        assertEquals(1, directory.search("HAUT").size)
    }

    @Test
    fun `协议类型筛选不会把尚未实现的协议算作支持`() {
        val directory = SchoolDirectory.builtIn()
        val result = directory.search("河南", protocolFamily = AcademicProtocolFamily.ZF_LEGACY)
        assertTrue(result.isEmpty())
        assertEquals(1, directory.search("河南", protocolFamily = AcademicProtocolFamily.ZF_MODERN).size)
    }

    @Test
    fun `本地覆盖只替换相同稳定学校标识的目录项`() {
        val override = SchoolProfile(
            id = SchoolDirectory.HAUT_SCHOOL_ID,
            displayName = "河南工业大学（本地配置）",
            campuses = listOf(CampusProfile(CampusId("haut-test"), "测试校区", isPrimary = true)),
            academicSystems = listOf(
                AcademicSystemProfile(
                    providerId = "local",
                    protocolFamily = AcademicProtocolFamily.ZF_MODERN,
                    baseUrl = "https://example.invalid"
                )
            )
        )
        val directory = SchoolDirectory.builtIn(listOf(override))
        assertEquals("河南工业大学（本地配置）", directory.find(SchoolId("haut"))?.displayName)
        assertEquals("测试校区", directory.find(SchoolId("haut"))?.primaryCampus?.name)
    }

    @Test
    fun `模糊纠错仅匹配足够长的拉丁词`() {
        val directory = SchoolDirectory.builtIn()
        assertTrue(directory.search("henangongyedacue").single().approximate)
        assertTrue(directory.search("河 南 工 业 大 学").isNotEmpty())
        assertTrue(directory.search("河楠工业大学").isEmpty())
    }

    @Test
    fun `重名学校保持独立且可按城市区分`() {
        val north = SchoolProfile(SchoolId("example-north"), "示例大学", city = "甲市")
        val south = SchoolProfile(SchoolId("example-south"), "示例大学", city = "乙市")
        val directory = SchoolDirectory(listOf(north, south))
        assertEquals(2, directory.search("示例大学").size)
        assertEquals(south.id, directory.search("示例大学", city = "乙市").single().school.id)
    }

    @Test
    fun `只有源码证据时不把学校能力标记为可用`() {
        val school = SchoolDirectory.hautProfile()
        assertTrue(school.academicSystems.single().capabilityStatuses.values.none { it.isAvailable })
    }

    @Test
    fun `河南工业大学内置莲花街与嵩山路双校区并支持教室文本识别`() {
        val school = SchoolDirectory.hautProfile()
        assertEquals(
            listOf(SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID, SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID),
            school.campuses.map { it.id }
        )
        assertEquals(
            SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID,
            ClassroomBuildingResolver.detectCampusHint(listOf("嵩山路校区 嵩山1教204"))
        )
        assertEquals(
            SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID,
            ClassroomBuildingResolver.detectCampusHint(listOf("莲花街校区 1号教学楼1104"))
        )
        val resolvedSongshan = ClassroomBuildingResolver.resolve(
            rawRoom = "嵩山1教302",
            buildings = ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS,
            aliases = ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES
        )
        assertEquals(BuildingMatchStatus.EXACT_VERIFIED, resolvedSongshan.status)
        assertEquals("b_ss_t1", resolvedSongshan.building?.id)
    }

    @Test
    fun `支持电子区划多边形射线法识别与校区适配JSON导入导出`() {
        val zone = CustomCampusZone(
            campusId = "custom-east",
            campusName = "东校区",
            schoolName = "示例大学",
            centerLatitude = 34.7800,
            centerLongitude = 113.6800,
            polygonVertices = listOf(
                GeoPoint(34.7750, 113.6750),
                GeoPoint(34.7750, 113.6850),
                GeoPoint(34.7850, 113.6850),
                GeoPoint(34.7850, 113.6750)
            ),
            buildings = ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS.take(2)
        )
        assertTrue(zone.containsWgs84Point(34.7800, 113.6800))
        assertFalse(zone.containsWgs84Point(34.8312, 113.5442))

        val json = CustomCampusZoneCodec.encodeList(listOf(zone), pretty = true)
        val decoded = CustomCampusZoneCodec.decodeList(json)
        assertEquals(1, decoded.size)
        assertEquals("custom-east", decoded.single().campusId)
        assertEquals("东校区", decoded.single().campusName)
        assertEquals(4, decoded.single().polygonVertices.size)
        assertEquals(2, decoded.single().buildings.size)
    }
}
