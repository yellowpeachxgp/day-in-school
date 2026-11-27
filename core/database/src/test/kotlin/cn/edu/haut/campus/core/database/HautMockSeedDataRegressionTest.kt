package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #5 真实种子数据回归：莲花街楼宇/别名/课表种子不得重新引入不存在的
 * 「2号楼/2号教学楼」或已停业的第二/第三餐厅；32号楼（土建楼）必须保持独立身份。
 */
class HautMockSeedDataRegressionTest {

    private val removedSecondBuildingText = Regex("(?<!\\d)0?2号(教学)?楼")
    private val closedCanteenText = Regex("第二餐厅|第三餐厅|(?<![第一二三四五六七八九十])[二三]餐厅")
    private val closedCanteenIds = setOf("b_canteen2", "b_canteen3")

    @Test
    fun seedNeverReintroducesLianhuajie2ndBuildingOrClosedCanteens() {
        val buildings = HautMockSeedData.lianhuajieBuildings
        val aliases = HautMockSeedData.defaultBuildingAliases
        val buildingIds = buildings.map { it.id }.toSet()

        assertEquals(buildings.size, buildingIds.size)
        assertTrue("b_t2 必须是 32号楼（土建楼），不是 2号楼", buildings.single { it.id == "b_t2" }.name.contains("32号楼"))
        assertEquals("32号楼", buildings.single { it.id == "b_t2" }.shortName)

        assertFalse(buildingIds.contains("b_canteen2"))
        assertFalse(buildingIds.contains("b_canteen3"))
        assertEquals(
            setOf("b_canteen1", "b_canteen4", "b_canteen_small"),
            buildings.filter { it.category == CampusCategory.CANTEEN }.map { it.id }.toSet()
        )
        assertTrue("所有别名都必须指向真实楼宇", aliases.all { it.buildingId in buildingIds })
        assertTrue("排课建筑引用必须指向真实楼宇", HautMockSeedData.defaultRules.all { it.buildingId == null || it.buildingId in buildingIds })
        assertTrue("单日覆盖建筑引用必须指向真实楼宇", HautMockSeedData.defaultOccurrenceOverrides.all { it.buildingId == null || it.buildingId in buildingIds })

        val activeTexts = buildings.flatMap {
            listOf(it.id, it.name, it.shortName, it.description) + it.entrances
        } + aliases.flatMap {
            listOf(it.id, it.buildingId, it.rawNameOrPattern)
        } + HautMockSeedData.defaultRules.flatMap {
            listOf(it.id, it.courseId, it.termId, it.rawRoom) + listOfNotNull(it.buildingId)
        } + HautMockSeedData.defaultOccurrenceOverrides.flatMap {
            listOfNotNull(it.id, it.termId, it.courseRuleId, it.courseId, it.room, it.buildingId, it.reason, it.moveId, it.moveRole)
        }

        for (text in activeTexts) {
            assertFalse("莲花街不得重新引入 2号楼/2号教学楼：$text", removedSecondBuildingText.containsMatchIn(text))
            assertFalse("停业第二/第三餐厅不得出现在真实种子：$text", closedCanteenText.containsMatchIn(text))
            assertFalse("停业餐厅 ID 不得出现在真实种子：$text", text in closedCanteenIds)
        }
        assertTrue(buildings.none { it.name == "2号楼" || it.shortName == "2号楼" })
        assertTrue(aliases.none { it.rawNameOrPattern == "2号楼" || it.rawNameOrPattern == "2号教学楼" })
        assertTrue(aliases.none { it.buildingId in closedCanteenIds })
        assertTrue(HautMockSeedData.defaultRules.none { it.buildingId in closedCanteenIds })
        assertTrue(HautMockSeedData.defaultOccurrenceOverrides.none { it.buildingId in closedCanteenIds })
    }

    @Test
    fun resolverKeeps32ndBuildingButCannotResolveRemoved2ndBuildingOrClosedCanteens() {
        val buildings = HautMockSeedData.lianhuajieBuildings
        val aliases = HautMockSeedData.defaultBuildingAliases

        val resolved32 = ClassroomBuildingResolver.resolve("32号楼32106", buildings, aliases)
        assertEquals("b_t2", resolved32.building?.id)
        assertEquals(BuildingMatchStatus.EXACT_VERIFIED, resolved32.status)

        // 用真实种子而不是合成别名锁定解析路径：2号楼与二/三餐厅绝不能解析到 32号楼或活跃餐厅。
        for (text in listOf("2号楼", "2号楼2106", "2206", "2号教学楼", "2号教学楼2106", "第二餐厅", "第三餐厅", "二餐厅", "三餐厅")) {
            val resolved = ClassroomBuildingResolver.resolve(text, buildings, aliases)
            assertNotEquals("「$text」不得误解析到 32号楼（土建楼）", "b_t2", resolved.building?.id)
            assertTrue("「$text」不得成为 EXACT_VERIFIED 活跃楼宇", resolved.status != BuildingMatchStatus.EXACT_VERIFIED)
            assertNull("「$text」在莲花街真实种子中应无活跃楼宇", resolved.building)
        }
    }
}
