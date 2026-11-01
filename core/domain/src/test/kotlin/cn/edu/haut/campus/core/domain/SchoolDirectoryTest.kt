package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.AcademicSystemProfile
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CampusProfile
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SchoolProfile
import org.junit.Assert.assertEquals
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
}
