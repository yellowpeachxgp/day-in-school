package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CourseOccurrence
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
        val occ1 = CourseOccurrence(
            occurrenceId = "o1",
            courseId = "c1",
            ruleId = "r1",
            title = "高等数学",
            teacher = "张老师",
            rawRoom = "3号教学楼3202",
            buildingId = "b_t3",
            buildingName = "3号教学楼",
            date = date,
            dayOfWeek = DayOfWeek.SATURDAY,
            startPeriod = 1,
            endPeriod = 2,
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(9, 45),
            colorArgb = 0xFF2563EB,
            canceled = false,
            overrideReason = null,
            hasConflict = false
        )
        val occ2 = occ1.copy(
            occurrenceId = "o2",
            title = "数据结构",
            rawRoom = "工程训练中心304",
            buildingId = "b_eng",
            startPeriod = 3,
            endPeriod = 4,
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 45)
        )
        val occ3 = occ1.copy(
            occurrenceId = "o3",
            title = "计算机网络",
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

        // 1-2 节与 3-4 节间隔 15 分钟合并为一个上午连续课段（11:45 下课，10:45 触发推荐）
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
        val occ = CourseOccurrence(
            occurrenceId = "o2",
            courseId = "c2",
            ruleId = "r2",
            title = "金工实习",
            teacher = "李老师",
            rawRoom = "工程训练中心201",
            buildingId = "b_eng",
            buildingName = "工程训练中心",
            date = date,
            dayOfWeek = DayOfWeek.SATURDAY,
            startPeriod = 3,
            endPeriod = 4,
            startTime = LocalTime.of(10, 0),
            endTime = LocalTime.of(11, 45),
            colorArgb = 0xFF2563EB,
            canceled = false,
            overrideReason = null,
            hasConflict = false
        )
        // 10:50 距离 11:45 下课还有 55 分钟（处于下课前 1 小时窗口内）
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
        // 工程训练中心距离第四餐厅最近
        assertEquals("b_canteen4", rec0.primaryCandidate.profile.buildingId)
        assertEquals(5, rec0.allCandidates.size)
        assertNotNull(rec0.primaryCandidate.walkMinutes)
        assertTrue(rec0.notificationTitle.contains("距下课还有 55 分钟"))

        // 点击「换一家」轮换食堂
        val rec1 = CampusDiningRecommender.buildRecommendation(
            date = date,
            now = nowAt1050,
            occurrences = listOf(occ),
            buildings = testBuildings,
            shuffleOffset = 1
        )
        assertNotEquals(rec0.primaryCandidate.profile.buildingId, rec1.primaryCandidate.profile.buildingId)
    }
}
