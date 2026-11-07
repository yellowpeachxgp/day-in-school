package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CourseOccurrence
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderEventType
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class DiningMealSlot(val label: String) {
    BRUNCH("早午餐"),
    LUNCH("午餐"),
    DINNER("晚餐"),
    LATE_DINNER("夜宵")
}

data class CampusCanteenProfile(
    val buildingId: String,
    val campusId: String,
    val name: String,
    val shortName: String,
    val areaLabel: String,
    val latitude: Double,
    val longitude: Double,
    val nearbyBuildingIds: Set<String>,
    val brunchDishes: List<String>,
    val lunchDinnerDishes: List<String>,
    val lateDishes: List<String>
)

data class DiningCanteenCandidate(
    val profile: CampusCanteenProfile,
    val distanceMeters: Int?,
    val walkMinutes: Int?,
    val recommendedDishes: List<String>,
    val reasonTag: String
)

data class CourseClassBlock(
    val date: LocalDate,
    val blockKey: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val startInstant: Instant,
    val endInstant: Instant,
    val recommendTriggerInstant: Instant,
    val courseTitle: String,
    val classroomText: String,
    val buildingId: String?,
    val hasNextClassSoon: Boolean
)

data class ClassEndMealRecommendation(
    val block: CourseClassBlock?,
    val mealSlot: DiningMealSlot,
    val minutesUntilClassEnd: Long?,
    val isWithinOneHourBeforeEnd: Boolean,
    val primaryCandidate: DiningCanteenCandidate,
    val allCandidates: List<DiningCanteenCandidate>,
    val notificationTitle: String,
    val notificationBody: String
)

object CampusDiningRecommender {

    const val LEAD_MINUTES_BEFORE_CLASS_END = 60L
    private const val MAX_CONSECUTIVE_BREAK_MINUTES = 25L

    val CANTEENS: List<CampusCanteenProfile> = listOf(
        CampusCanteenProfile(
            buildingId = "b_canteen1",
            campusId = "lianhuajie",
            name = "第一餐厅",
            shortName = "一餐厅",
            areaLabel = "东北生活区 · 近3/4教与A/B/C/D公寓",
            latitude = 34.833440,
            longitude = 113.548450,
            nearbyBuildingIds = setOf("b_t3", "b_t4", "b_bell", "b_dorm_ab", "b_dorm_cd", "b_express"),
            brunchDishes = listOf("胡辣汤配水煎包", "鲜肉大包配豆浆", "鸡蛋灌饼"),
            lunchDinnerDishes = listOf("一楼大众自选快餐", "二楼河南羊肉烩面", "三楼铁板黑椒牛柳饭", "黄焖鸡米饭", "山西刀削面"),
            lateDishes = listOf("砂锅麻辣烫", "铁板炒饭", "酸辣粉")
        ),
        CampusCanteenProfile(
            buildingId = "b_canteen2",
            campusId = "lianhuajie",
            name = "第二餐厅",
            shortName = "二餐厅",
            areaLabel = "北区中心湖畔 · 近1/2教与图书馆",
            latitude = 34.833950,
            longitude = 113.544600,
            nearbyBuildingIds = setOf("b_t1", "b_t2", "b_library", "b_dorm_ab", "b_dorm_gh", "b_admin"),
            brunchDishes = listOf("八宝粥配小笼包", "山东杂粮煎饼", "潼关肉夹馍"),
            lunchDinnerDishes = listOf("两荤一素自选菜", "广式脆皮烧鸭饭", "卤香猪脚饭", "老坛酸菜鱼米饭", "滇味过桥米线"),
            lateDishes = listOf("川味冒菜", "鲜肉混沌", "炸串卷饼")
        ),
        CampusCanteenProfile(
            buildingId = "b_canteen3",
            campusId = "lianhuajie",
            name = "第三餐厅",
            shortName = "三餐厅",
            areaLabel = "西生活区 · 近5/6/8号楼与K/L/M公寓",
            latitude = 34.832950,
            longitude = 113.539600,
            nearbyBuildingIds = setOf("b_t5", "b_t6", "b_t7", "b_t8", "b_t9", "b_t25", "b_stadium", "b_dorm_klm"),
            brunchDishes = listOf("清真牛肉包配油茶", "豆腐脑配油条", "东北烤冷面"),
            lunchDinnerDishes = listOf("新疆大盘鸡拌面", "兰州牛肉拉面", "牛羊肉泡馍", "韩式石锅拌饭", "孜然烤肉拌饭"),
            lateDishes = listOf("烤肉筋配热馕", "牛肉炒刀削", "鸡蛋炒米粉")
        ),
        CampusCanteenProfile(
            buildingId = "b_canteen4",
            campusId = "lianhuajie",
            name = "第四餐厅",
            shortName = "四餐厅",
            areaLabel = "中西区 · 近实训中心与实验楼群",
            latitude = 34.832260,
            longitude = 113.541300,
            nearbyBuildingIds = setOf("b_eng", "b_t34", "b_t38", "b_t40", "b_t8", "b_t18", "b_dorm_ef"),
            brunchDishes = listOf("现磨豆浆配掉渣饼", "鲜虾蒸饺", "皮蛋瘦肉粥"),
            lunchDinnerDishes = listOf("招牌脆皮鸡米饭", "锡纸花甲粉", "番茄肥牛米线", "咖喱滑蛋猪排饭", "低脂鸡胸肉轻食碗"),
            lateDishes = listOf("铁板烤冷面", "关东煮拼盘", "老麻抄手")
        ),
        CampusCanteenProfile(
            buildingId = "b_canteen_small",
            campusId = "lianhuajie",
            name = "小食堂（风味餐厅）",
            shortName = "小食堂",
            areaLabel = "东侧教学区旁 · 近2/3/4号教学楼",
            latitude = 34.830570,
            longitude = 113.549520,
            nearbyBuildingIds = setOf("b_t2", "b_t3", "b_t4", "b_t1", "b_hospital"),
            brunchDishes = listOf("信阳热干面", "鸡蛋手抓饼", "紫菜鲜肉小馄饨"),
            lunchDinnerDishes = listOf("下课快手盖浇饭", "重庆豌杂小面", "柳州螺蛳粉", "广式腊味煲仔饭", "鸭血粉丝汤"),
            lateDishes = listOf("瓦罐汤配拌粉", "老北京炸酱面", "脆底煎饺")
        ),
        CampusCanteenProfile(
            buildingId = "b_ss_canteen",
            campusId = "songshanlu",
            name = "嵩山路学生餐厅",
            shortName = "嵩山餐厅",
            areaLabel = "嵩山路生活区 · 近1/2号教学楼",
            latitude = 34.736950,
            longitude = 113.618100,
            nearbyBuildingIds = setOf("b_ss_t1", "b_ss_t2", "b_ss_lab", "b_ss_lib", "b_ss_dorm"),
            brunchDishes = listOf("胡辣汤配油条", "鲜肉包配小米粥", "鸡蛋灌饼"),
            lunchDinnerDishes = listOf("一楼自选快餐", "河南羊肉烩面", "黑椒牛柳盖浇饭", "骨汤麻辣烫"),
            lateDishes = listOf("砂锅米线", "鸡蛋炒饭")
        )
    )

    fun resolveMealSlot(time: LocalTime): DiningMealSlot = when {
        time <= LocalTime.of(10, 30) -> DiningMealSlot.BRUNCH
        time <= LocalTime.of(14, 15) -> DiningMealSlot.LUNCH
        time <= LocalTime.of(19, 15) -> DiningMealSlot.DINNER
        else -> DiningMealSlot.LATE_DINNER
    }

    /**
     * 将单日排课合并为连续上课区间：
     * 若两节课间隔 <= 25 分钟（如 1-2 节与 3-4 节连上），以连续课段的最后一节下课时刻作为就餐基准。
     */
    fun resolveClassBlocksForDay(
        date: LocalDate,
        occurrences: List<CourseOccurrence>,
        buildings: List<Building> = emptyList()
    ): List<CourseClassBlock> {
        val active = occurrences
            .filter { !it.canceled }
            .sortedWith(compareBy({ it.startTime }, { it.endTime }))
        if (active.isEmpty()) return emptyList()

        data class MutableBlock(
            val startTime: LocalTime,
            var endTime: LocalTime,
            var lastCourseTitle: String,
            var lastClassroom: String,
            var lastBuildingId: String?
        )

        val merged = mutableListOf<MutableBlock>()
        for (occ in active) {
            val resolvedBldId = occ.buildingId ?: ClassroomBuildingResolver.resolve(
                rawRoom = occ.rawRoom,
                buildings = buildings,
                aliases = emptyList()
            ).building?.id
            val prev = merged.lastOrNull()
            val gapMinutes = if (prev != null) {
                Duration.between(prev.endTime, occ.startTime).toMinutes()
            } else {
                Long.MAX_VALUE
            }
            if (prev != null && gapMinutes <= MAX_CONSECUTIVE_BREAK_MINUTES) {
                if (occ.endTime.isAfter(prev.endTime)) {
                    prev.endTime = occ.endTime
                }
                prev.lastCourseTitle = occ.title
                prev.lastClassroom = occ.rawRoom
                prev.lastBuildingId = resolvedBldId ?: prev.lastBuildingId
            } else {
                merged += MutableBlock(
                    startTime = occ.startTime,
                    endTime = occ.endTime,
                    lastCourseTitle = occ.title,
                    lastClassroom = occ.rawRoom,
                    lastBuildingId = resolvedBldId
                )
            }
        }

        return merged.mapIndexed { idx, blk ->
            val nextBlock = merged.getOrNull(idx + 1)
            val hasNextClassSoon = nextBlock != null &&
                Duration.between(blk.endTime, nextBlock.startTime).toMinutes() in 0..90
            val startInstant = date.atTime(blk.startTime).atZone(HAUT_ZONE_ID).toInstant()
            val endInstant = date.atTime(blk.endTime).atZone(HAUT_ZONE_ID).toInstant()
            val triggerInstant = endInstant.minus(Duration.ofMinutes(LEAD_MINUTES_BEFORE_CLASS_END))
            CourseClassBlock(
                date = date,
                blockKey = "$date:${blk.endTime}",
                startTime = blk.startTime,
                endTime = blk.endTime,
                startInstant = startInstant,
                endInstant = endInstant,
                recommendTriggerInstant = triggerInstant,
                courseTitle = blk.lastCourseTitle,
                classroomText = blk.lastClassroom.ifBlank { "教学楼" },
                buildingId = blk.lastBuildingId,
                hasNextClassSoon = hasNextClassSoon
            )
        }
    }

    /**
     * 从 ReminderEvent 列表构建连续上课区间（供后台 ReminderCoordinator 调度闹钟与发通知使用）。
     */
    fun resolveClassBlocksFromReminderEvents(
        events: List<ReminderEvent>,
        buildings: List<Building> = emptyList()
    ): List<CourseClassBlock> {
        val classEvents = events.filter { it.type == ReminderEventType.CLASS && !it.isCancelled }
        val byDate = classEvents.groupBy { it.startAt.atZone(HAUT_ZONE_ID).toLocalDate() }
        val result = mutableListOf<CourseClassBlock>()
        for ((date, dayEvents) in byDate.toSortedMap()) {
            val syntheticOccurrences = dayEvents.map { ev ->
                val startLocal = ev.startAt.atZone(HAUT_ZONE_ID).toLocalTime()
                val endLocal = (ev.endAt ?: ev.startAt.plus(Duration.ofMinutes(95)))
                    .atZone(HAUT_ZONE_ID)
                    .toLocalTime()
                CourseOccurrence(
                    occurrenceId = ev.eventKey,
                    courseId = ev.courseId ?: ev.eventKey,
                    ruleId = ev.eventKey,
                    title = ev.displayTitle,
                    teacher = "",
                    rawRoom = ev.locationText.orEmpty(),
                    buildingId = ev.buildingId,
                    buildingName = null,
                    date = date,
                    dayOfWeek = date.dayOfWeek,
                    startPeriod = 1,
                    endPeriod = 2,
                    startTime = startLocal,
                    endTime = endLocal,
                    colorArgb = 0xFF2563EB,
                    canceled = false,
                    overrideReason = null,
                    hasConflict = false
                )
            }
            result += resolveClassBlocksForDay(date, syntheticOccurrences, buildings)
        }
        return result.sortedBy { it.recommendTriggerInstant }
    }

    /**
     * 针对当前时刻与当天课表生成就餐推荐：
     * - 若当前有距离下课 <= 65 分钟（或刚下课 15 分钟内）的课段，自动锁定该课段推荐；
     * - 否则锁定当天下一节待下课的课段；若今日无课或已全部结束，则按当前时段提供全校食堂推荐。
     */
    fun buildRecommendation(
        date: LocalDate,
        now: Instant,
        occurrences: List<CourseOccurrence>,
        buildings: List<Building>,
        shuffleOffset: Int = 0
    ): ClassEndMealRecommendation {
        val blocks = resolveClassBlocksForDay(date, occurrences, buildings)
        val activeOneHourBlock = blocks.firstOrNull { blk ->
            val minsLeft = Duration.between(now, blk.endInstant).toMinutes()
            minsLeft in -15..65
        }
        val upcomingBlock = activeOneHourBlock ?: blocks.firstOrNull { blk ->
            now.isBefore(blk.endInstant)
        }

        return if (upcomingBlock != null) {
            buildRecommendationForBlock(
                block = upcomingBlock,
                now = now,
                buildings = buildings,
                shuffleOffset = shuffleOffset
            )
        } else {
            val zdt = now.atZone(HAUT_ZONE_ID)
            val slot = resolveMealSlot(zdt.toLocalTime())
            val candidates = rankCanteens(
                classroomBuilding = null,
                classroomText = "",
                mealSlot = slot,
                hasNextClassSoon = false,
                daySeed = date.toEpochDay().toInt(),
                shuffleOffset = shuffleOffset
            )
            val primary = candidates.first()
            val dishesText = primary.recommendedDishes.take(2).joinToString(" / ")
            ClassEndMealRecommendation(
                block = null,
                mealSlot = slot,
                minutesUntilClassEnd = null,
                isWithinOneHourBeforeEnd = false,
                primaryCandidate = primary,
                allCandidates = candidates,
                notificationTitle = "${slot.label}推荐 · ${primary.profile.name}",
                notificationBody = "${primary.profile.areaLabel} · 推荐：$dishesText"
            )
        }
    }

    fun buildRecommendationForBlock(
        block: CourseClassBlock,
        now: Instant,
        buildings: List<Building>,
        shuffleOffset: Int = 0
    ): ClassEndMealRecommendation {
        val building = block.buildingId?.let { id -> buildings.firstOrNull { it.id == id } }
            ?: ClassroomBuildingResolver.resolve(block.classroomText, buildings, emptyList()).building
        val mealSlot = resolveMealSlot(block.endTime)
        val minsLeft = Duration.between(now, block.endInstant).toMinutes()
        val isWithinOneHour = minsLeft in 1..65
        val daySeed = (block.date.toEpochDay().toInt() * 31) + block.endTime.hour
        val candidates = rankCanteens(
            classroomBuilding = building,
            classroomText = block.classroomText,
            mealSlot = mealSlot,
            hasNextClassSoon = block.hasNextClassSoon,
            daySeed = daySeed,
            shuffleOffset = shuffleOffset
        )
        val primary = candidates.first()
        val secondary = candidates.getOrNull(1)
        val walkText = primary.walkMinutes?.let { "步行约 ${it} 分钟" } ?: primary.profile.areaLabel
        val dishesText = primary.recommendedDishes.take(2).joinToString(" / ")
        val backupText = secondary?.let { "（备选：${it.profile.shortName}）" }.orEmpty()

        val title = if (minsLeft in 1..75) {
            "距下课还有 ${minsLeft} 分钟 · 推荐去${primary.profile.shortName}"
        } else {
            "下课就餐推荐 · ${primary.profile.shortName}"
        }
        val roomLabel = building?.shortName ?: block.classroomText.ifBlank { "当前教室" }
        val body = "当前在 $roomLabel，$walkText · 推荐：$dishesText$backupText"

        return ClassEndMealRecommendation(
            block = block,
            mealSlot = mealSlot,
            minutesUntilClassEnd = minsLeft,
            isWithinOneHourBeforeEnd = isWithinOneHour,
            primaryCandidate = primary,
            allCandidates = candidates,
            notificationTitle = title,
            notificationBody = body
        )
    }

    private fun rankCanteens(
        classroomBuilding: Building?,
        classroomText: String,
        mealSlot: DiningMealSlot,
        hasNextClassSoon: Boolean,
        daySeed: Int,
        shuffleOffset: Int
    ): List<DiningCanteenCandidate> {
        val campusId = when {
            classroomBuilding?.campusId == "songshanlu" -> "songshanlu"
            classroomText.contains("嵩山") -> "songshanlu"
            else -> "lianhuajie"
        }
        val pool = CANTEENS.filter { it.campusId == campusId }.ifEmpty { CANTEENS }

        data class ScoredCanteen(
            val candidate: DiningCanteenCandidate,
            val score: Int
        )

        val scored = pool.mapIndexed { idx, profile ->
            val straightMeters = classroomBuilding?.let { bld ->
                haversineMeters(bld.latitude, bld.longitude, profile.latitude, profile.longitude)
            }
            val walkMeters = straightMeters?.let { (it * 1.25).roundToInt() }
            val walkMinutes = walkMeters?.let { (it / 75.0).roundToInt().coerceAtLeast(2) }

            val isNearbyTeaching = classroomBuilding?.id in profile.nearbyBuildingIds
            val distanceBonus = when {
                walkMeters == null -> 0
                walkMeters <= 350 -> 45
                walkMeters <= 550 -> 30
                walkMeters <= 800 -> 15
                else -> 0
            }
            val nearbyBonus = if (isNearbyTeaching) 35 else 0
            val fastTurnaroundBonus = if (hasNextClassSoon && profile.buildingId == "b_canteen_small") 25 else 0
            val rotationBonus = abs((daySeed + idx * 17) % 18)

            val reasonTag = when {
                isNearbyTeaching && walkMinutes != null -> "距上课教学楼最近 · 步行约 ${walkMinutes} 分钟"
                hasNextClassSoon && profile.buildingId == "b_canteen_small" -> "近教学区 · 出餐快不耽误下节课"
                walkMinutes != null && walkMinutes <= 5 -> "下课顺路 · 步行约 ${walkMinutes} 分钟"
                profile.buildingId in setOf("b_canteen1", "b_canteen2") -> "下课顺路回寝 · 档口选择多"
                else -> profile.areaLabel
            }

            val rawDishes = when (mealSlot) {
                DiningMealSlot.BRUNCH -> profile.brunchDishes
                DiningMealSlot.LUNCH, DiningMealSlot.DINNER -> profile.lunchDinnerDishes
                DiningMealSlot.LATE_DINNER -> profile.lateDishes
            }
            val dishShift = abs(daySeed + shuffleOffset + idx) % rawDishes.size.coerceAtLeast(1)
            val rotatedDishes = (rawDishes.drop(dishShift) + rawDishes.take(dishShift)).take(3)

            ScoredCanteen(
                candidate = DiningCanteenCandidate(
                    profile = profile,
                    distanceMeters = walkMeters,
                    walkMinutes = walkMinutes,
                    recommendedDishes = rotatedDishes,
                    reasonTag = reasonTag
                ),
                score = distanceBonus + nearbyBonus + fastTurnaroundBonus + rotationBonus
            )
        }.sortedByDescending { it.score }
            .map { it.candidate }

        if (scored.size <= 1 || shuffleOffset == 0) return scored
        val shift = abs(shuffleOffset) % scored.size
        return scored.drop(shift) + scored.take(shift)
    }

    private fun haversineMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double
    ): Int {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (6_371_000.0 * c).roundToInt()
    }
}
