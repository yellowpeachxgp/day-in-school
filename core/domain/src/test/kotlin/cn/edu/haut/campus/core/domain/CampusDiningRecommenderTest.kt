package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class CampusDiningRecommenderTest {

    private val testBuildings = listOf(
        Building(
            id = "b_t3",
            name = "3号教学楼",
            shortName = "3教",
            latitude = 34.829710,
            longitude = 113.547850,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("北门"),
            description = "3号教学楼",
            verifiedAt = LocalDate.of(2026, 11, 5)
        ),
        Building(
            id = "b_eng",
            name = "工程训练中心",
            shortName = "训练中心",
            latitude = 34.831230,
            longitude = 113.542300,
            category = CampusCategory.LAB,
            entrances = listOf("东门"),
            description = "工程训练中心",
            verifiedAt = LocalDate.of(2026, 11, 5)
        )
    )

    @Test
    fun resolveClassBlocksForDay_mergesConsecutiveMorningClassesAndComputesOneHourBeforeEnd() {
        val date = LocalDate.of(2026, 11, 7)
        val occ1 = ClassOccurrence(
            occurrenceId = "o1",
            courseId = "c1",
            ruleId = "r1",
            overrideId = null,
            courseName = "高等数学",
            teacher = "张老师",
            colorArgb = 0xFF2563EB,
            date = date,
            teachingWeek = 10,
            weekday = DayOfWeek.SATURDAY,
            startPeriod = 1,
            endPeriod = 2,
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(9, 45),
            rawRoom = "3号教学楼3202",
            buildingId = "b_t3",
            buildingName = "3号教学楼",
            isMakeupOrOverridden = false,
            overrideReason = null
        )
        val occ2 = occ1.copy(
            occurrenceId = "o2",
            courseName = "数据结构",
            rawRoom = "工程训练中心304",
            buildingId = "b_eng",
            startPeriod = 3,
            endPeriod = 4,
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 45)
        )
        val occ3 = occ1.copy(
            occurrenceId = "o3",
            courseName = "计算机网络",
            rawRoom = "3号教学楼3105",
            buildingId = "b_t3",
            startPeriod = 5,
            endPeriod = 6,
            startTime = LocalTime.of(14, 0),
            endTime = LocalTime.of(15, 45)
        )

        val blocks = CampusDiningRecommender.resolveClassBlocksForDay(
            date = date,
            occurrences = listOf(occ1, occ2, occ3),
            buildings = testBuildings
        )

        assertEquals(2, blocks.size)
        val morningBlock = blocks[0]
        assertEquals(LocalTime.of(8, 0), morningBlock.startTime)
        assertEquals(LocalTime.of(11, 45), morningBlock.endTime)
        assertEquals("数据结构", morningBlock.courseTitle)
        assertEquals("b_eng", morningBlock.buildingId)
        val triggerLocalTime = morningBlock.recommendTriggerInstant.atZone(HAUT_ZONE_ID).toLocalTime()
        assertEquals(LocalTime.of(10, 45), triggerLocalTime)
    }

    @Test
    fun buildRecommendation_detectsOneHourWindowAndSupportsShufflingCanteens() {
        val date = LocalDate.of(2026, 11, 7)
        val occ = ClassOccurrence(
            occurrenceId = "o2",
            courseId = "c2",
            ruleId = "r2",
            overrideId = null,
            courseName = "金工实习",
            teacher = "李老师",
            colorArgb = 0xFF2563EB,
            date = date,
            teachingWeek = 10,
            weekday = DayOfWeek.SATURDAY,
            startPeriod = 3,
            endPeriod = 4,
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 45),
            rawRoom = "工程训练中心201",
            buildingId = "b_eng",
            buildingName = "工程训练中心",
            isMakeupOrOverridden = false,
            overrideReason = null
        )
        val nowAt1050 = date.atTime(10, 50).atZone(HAUT_ZONE_ID).toInstant()
        val rec0 = CampusDiningRecommender.buildRecommendation(
            date = date,
            now = nowAt1050,
            occurrences = listOf(occ),
            buildings = testBuildings,
            shuffleOffset = 0
        )

        assertTrue(rec0.isWithinOneHourBeforeEnd)
        assertEquals(55L, rec0.minutesUntilClassEnd)
        assertEquals(DiningMealSlot.LUNCH, rec0.mealSlot)
        assertEquals("b_canteen4", rec0.primaryCandidate.profile.buildingId)
        assertEquals(5, rec0.allCandidates.size)
        assertNotNull(rec0.primaryCandidate.walkMinutes)
        assertTrue(rec0.notificationTitle.contains("距下课还有 55 分钟"))

        val rec1 = CampusDiningRecommender.buildRecommendation(
            date = date,
            now = nowAt1050,
            occurrences = listOf(occ),
            buildings = testBuildings,
            shuffleOffset = 1
        )
        assertNotEquals(rec0.primaryCandidate.profile.buildingId, rec1.primaryCandidate.profile.buildingId)
    }

    @Test
    fun canteenDishReviews_codecAndRecommendationRespectStarRatings() {
        val reviews = listOf(
            CanteenDishReview(
                id = "b_canteen4:招牌脆皮鸡米饭",
                canteenBuildingId = "b_canteen4",
                dishName = "招牌脆皮鸡米饭",
                stallLocation = "一楼东侧",
                priceYuan = 12,
                stars = 1,
                tags = listOf("避雷"),
                comment = "今天炸老了",
                updatedAtEpochMs = 1_762_500_000_000L
            ),
            CanteenDishReview(
                id = "b_canteen4:自选秘制叉烧饭",
                canteenBuildingId = "b_canteen4",
                dishName = "自选秘制叉烧饭",
                stallLocation = "二楼窗口",
                priceYuan = 13,
                stars = 5,
                tags = listOf("分量足", "味道正"),
                comment = "叉烧入味",
                updatedAtEpochMs = 1_762_500_100_000L
            )
        )

        val encoded = CanteenDishReviewCodec.encodeList(reviews)
        val decoded = CanteenDishReviewCodec.decodeList(encoded)
        assertEquals(2, decoded.size)
        assertEquals(1, decoded[0].stars)
        assertEquals("自选秘制叉烧饭", decoded[1].dishName)

        val aggregated = CampusDiningRecommender.aggregateDishRatings(decoded, "b_canteen4")
        val avoided = aggregated.first { it.dishName == "招牌脆皮鸡米饭" }
        val favorited = aggregated.first { it.dishName == "自选秘制叉烧饭" }
        assertTrue(avoided.isAvoidedByUser)
        assertTrue(favorited.isFavoritedByUser)

        val date = LocalDate.of(2026, 11, 7)
        val nowAt1200 = date.atTime(12, 0).atZone(HAUT_ZONE_ID).toInstant()
        val rec = CampusDiningRecommender.buildRecommendation(
            date = date,
            now = nowAt1200,
            occurrences = emptyList(),
            buildings = testBuildings,
            shuffleOffset = 0,
            userReviews = decoded
        )
        val canteen4Candidate = rec.allCandidates.first { it.profile.buildingId == "b_canteen4" }
        // 5★ 菜品应优先出现在推荐列表中，1★ 踩雷菜品应被剔除
        assertTrue(canteen4Candidate.recommendedDishes.any { it.contains("自选秘制叉烧饭") })
        assertTrue(canteen4Candidate.recommendedDishes.none { it.contains("招牌脆皮鸡米饭") })
    }

    @Test
    fun allAvoidedDishesStayExcludedRatherThanReturningAsFallback() {
        val profile = CampusDiningRecommender.CANTEENS.first()
        val reviews = profile.lunchDinnerDishes.mapIndexed { index, name ->
            CanteenDishReview("avoid-$index", profile.buildingId, name, stars = 1)
        }
        val date = LocalDate.of(2026, 11, 7)
        val recommendation = CampusDiningRecommender.buildRecommendation(date,
            date.atTime(18, 0).atZone(HAUT_ZONE_ID).toInstant(), emptyList(), emptyList(), userReviews = reviews)
        assertTrue(recommendation.allCandidates.first { it.profile.buildingId == profile.buildingId }.recommendedDishes.isEmpty())
    }

    @Test
    fun breakfastFavoritesAreNotRecommendedAtDinner() {
        val dish = CampusDiningRecommender.DEFAULT_DISH_CATALOG.first { it.mealSlots == setOf(DiningMealSlot.BRUNCH) }
        val review = CanteenDishReview("favorite", dish.canteenBuildingId, dish.dishName, stars = 5)
        val date = LocalDate.of(2026, 11, 7)
        val dinner = CampusDiningRecommender.buildRecommendation(date,
            date.atTime(18, 0).atZone(HAUT_ZONE_ID).toInstant(), emptyList(), emptyList(), userReviews = listOf(review))
        assertTrue(dinner.allCandidates.flatMap { it.recommendedDishes }.none { it.contains(dish.dishName) })
        val brunch = CampusDiningRecommender.buildRecommendation(date,
            date.atTime(9, 0).atZone(HAUT_ZONE_ID).toInstant(), emptyList(), emptyList(), userReviews = listOf(review))
        assertTrue(brunch.allCandidates.flatMap { it.recommendedDishes }.any { it.contains(dish.dishName) })
    }

    @Test
    fun presetWeightsAreNotPresentedAsRealReviewCounts() {
        val rating = CampusDiningRecommender.aggregateDishRatings().first()
        assertTrue(rating.hasPresetRating)
        assertEquals(0, rating.totalReviewCount)
        assertTrue(rating.displayComment.startsWith("预设示例："))
    }
}
