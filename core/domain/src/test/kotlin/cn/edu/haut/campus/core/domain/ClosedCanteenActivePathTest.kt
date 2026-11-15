package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * #5 已停业餐厅（第二餐厅 b_canteen2 / 第三餐厅 b_canteen3）数据分级回归：
 * 活跃推荐/搜索/解析路径绝不泄漏已停业餐厅，同时用户历史评价数据原样保留。
 */
class ClosedCanteenActivePathTest {

    private val closedIds = CampusDiningRecommender.CLOSED_CANTEEN_BUILDING_IDS

    private val lianhuajieBuildings = listOf(
        Building(
            id = "b_canteen1", campusId = "lianhuajie", name = "第一餐厅", shortName = "一餐厅",
            latitude = 34.833440, longitude = 113.548450, category = CampusCategory.CANTEEN,
            entrances = listOf("西门"), description = "一餐厅",
            verifiedAt = LocalDate.of(2026, 11, 10)
        ),
        Building(
            id = "b_canteen4", campusId = "lianhuajie", name = "第四餐厅", shortName = "四餐厅",
            latitude = 34.832260, longitude = 113.541300, category = CampusCategory.CANTEEN,
            entrances = listOf("东门"), description = "四餐厅",
            verifiedAt = LocalDate.of(2026, 11, 10)
        )
    )
    private val seedStyleAliases = listOf(
        BuildingAlias("al_cant1_a", "b_canteen1", "第一餐厅", 1.0f, true),
        BuildingAlias("al_cant1_b", "b_canteen1", "一餐厅", 1.0f, true),
        BuildingAlias("al_cant4_a", "b_canteen4", "第四餐厅", 1.0f, true),
        BuildingAlias("al_cant4_b", "b_canteen4", "四餐厅", 1.0f, true)
    )

    @Test
    fun closedCanteenIdsAreTheSingleSourceAndAbsentFromActiveCatalogs() {
        assertEquals(setOf("b_canteen2", "b_canteen3"), closedIds)
        assertTrue(CampusDiningRecommender.CANTEENS.none { it.buildingId in closedIds })
        assertTrue(CampusDiningRecommender.OPERATING_CANTEENS.none { it.buildingId in closedIds })
        assertTrue(CampusDiningRecommender.DEFAULT_DISH_CATALOG.none { it.canteenBuildingId in closedIds })
        assertTrue(CampusDiningRecommender.CANTEENS.all { it.isOperating })
    }

    @Test
    fun closedCanteenReviewsStayArchivedButNeverSurfaceAsActiveSuggestions() {
        val history = listOf(
            CanteenDishReview(
                id = "rev_c2_1", canteenBuildingId = "b_canteen2",
                dishName = "已下架鸡公煲", stallLocation = "二楼", stars = 5,
                comment = "停业前的记录", updatedAtEpochMs = 1_700_000_000_000L
            ),
            CanteenDishReview(
                id = "rev_c3_1", canteenBuildingId = "b_canteen3",
                dishName = "已下架黄焖鸡", stallLocation = "一楼", stars = 4,
                updatedAtEpochMs = 1_700_000_100_000L
            ),
            CanteenDishReview(
                id = "rev_c1_1", canteenBuildingId = "b_canteen1",
                dishName = "一楼大众自选快餐", stallLocation = "一楼", stars = 5,
                updatedAtEpochMs = 1_700_000_200_000L
            )
        )

        // 历史评价归档兼容：codec 原样往返，不丢字段
        val decoded = CanteenDishReviewCodec.decodeList(CanteenDishReviewCodec.encodeList(history))
        assertEquals(3, decoded.size)
        assertTrue(decoded.any { it.canteenBuildingId == "b_canteen2" })
        assertTrue(decoded.any { it.canteenBuildingId == "b_canteen3" })

        // 聚合榜单：已停业餐厅历史残留被剔除，非目录内的其他食堂 ID 不受影响
        val aggregated = CampusDiningRecommender.aggregateDishRatings(decoded)
        assertTrue(aggregated.none { it.canteenBuildingId in closedIds })
        assertTrue(aggregated.any { it.canteenBuildingId == "b_canteen1" })

        // 推荐路径：无论带不带历史评价，候选绝不包含已停业餐厅
        val now = Instant.parse("2026-11-09T04:00:00Z")
        val rec = CampusDiningRecommender.buildRecommendation(
            date = LocalDate.of(2026, 11, 9),
            now = now,
            occurrences = emptyList(),
            buildings = emptyList(),
            userReviews = decoded
        )
        assertTrue(rec.allCandidates.isNotEmpty())
        assertTrue(rec.allCandidates.none { it.profile.buildingId in closedIds })
        assertTrue(rec.notificationTitle.isNotBlank())
        assertFalse(rec.notificationTitle.contains("第二餐厅"))
        assertFalse(rec.notificationTitle.contains("第三餐厅"))
    }

    @Test
    fun customNonCatalogCanteenReviewsStillSurfaceWhileClosedOnesAreFiltered() {
        // 定向过滤而非一刀切：未登记的自定义食堂评价仍可上榜（仅已停业 ID 被屏蔽）
        val reviews = listOf(
            CanteenDishReview(
                id = "rev_custom", canteenBuildingId = "b_custom_vendor_x",
                dishName = "用户自建档口面", stallLocation = "自建档口", stars = 5,
                updatedAtEpochMs = 1_700_000_000_000L
            ),
            CanteenDishReview(
                id = "rev_closed", canteenBuildingId = "b_canteen2",
                dishName = "停业档口", stallLocation = "旧窗口", stars = 5,
                updatedAtEpochMs = 1_700_000_000_000L
            )
        )
        val aggregated = CampusDiningRecommender.aggregateDishRatings(reviews)
        assertTrue(aggregated.any { it.canteenBuildingId == "b_custom_vendor_x" })
        assertTrue(aggregated.none { it.canteenBuildingId == "b_canteen2" })

        // 按餐厅过滤路径同样生效
        val filtered = CampusDiningRecommender.aggregateDishRatings(reviews, canteenBuildingIdFilter = "b_canteen2")
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun searchAndResolverPathsCannotReachClosedCanteenBuildings() {
        val mergedBuildings = lianhuajieBuildings + ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS
        val mergedAliases = seedStyleAliases + ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES

        // 「第二餐厅」「第三餐厅」文本在楼宇解析器中无解（别名已随字典 V18 清理）
        for (text in listOf("第二餐厅", "第三餐厅", "二餐厅", "三餐厅")) {
            val resolved = ClassroomBuildingResolver.resolve(text, mergedBuildings, mergedAliases)
            assertTrue(
                "Closed-canteen text must never EXACT-match a building",
                resolved.status != BuildingMatchStatus.EXACT_VERIFIED
            )
            assertNull(resolved.building)
        }
        // 营业中的餐厅仍可解析
        val open = ClassroomBuildingResolver.resolve("第四餐厅", mergedBuildings, mergedAliases)
        assertEquals("b_canteen4", open.building?.id)

        // 楼宇目录层面：解析器/种子名录本身不含已停业餐厅条目
        assertTrue(ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS.none { it.id in closedIds })
        assertTrue(ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES.none { it.buildingId in closedIds })
    }
}
