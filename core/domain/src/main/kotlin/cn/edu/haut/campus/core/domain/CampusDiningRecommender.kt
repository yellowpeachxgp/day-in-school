package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.ClassOccurrence
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

/**
 * 食堂预设档口菜品条目（含档口楼层位置、参考价格、基准评分与评价人数）。
 */
data class CampusDishCatalogItem(
    val id: String,
    val canteenBuildingId: String,
    val canteenShortName: String,
    val stallLocation: String,
    val dishName: String,
    val priceYuan: Int,
    val mealSlots: Set<DiningMealSlot>,
    val baseStars: Float,
    val baseReviewCount: Int,
    val defaultTags: List<String>,
    val sampleComment: String
)

/**
 * 用户对某食堂档口菜品的打星与简评记录。
 */
data class CanteenDishReview(
    val id: String,
    val canteenBuildingId: String,
    val dishName: String,
    val stallLocation: String = "",
    val priceYuan: Int? = null,
    val stars: Int, // 1..5
    val tags: List<String> = emptyList(),
    val comment: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * 融合基准口碑与用户本人打分后的菜品聚合评分模型。
 */
data class AggregatedDishRating(
    val dishKey: String,
    val canteenBuildingId: String,
    val canteenShortName: String,
    val stallLocation: String,
    val dishName: String,
    val priceYuan: Int?,
    val averageStars: Float,
    val totalReviewCount: Int,
    val myReview: CanteenDishReview?,
    val tags: List<String>,
    val displayComment: String
) {
    val isAvoidedByUser: Boolean
        get() = (myReview?.stars ?: 5) <= 2

    val isFavoritedByUser: Boolean
        get() = (myReview?.stars ?: 0) >= 4
}

data class DiningCanteenCandidate(
    val profile: CampusCanteenProfile,
    val distanceMeters: Int?,
    val walkMinutes: Int?,
    val averageStars: Float,
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

/**
 * 零依赖食堂菜品评价序列化编解码器（纯 Kotlin 实现，兼容 JVM 单元测试与 DataStore 持久化）。
 */
object CanteenDishReviewCodec {
    private const val RECORD_SEP = "\n"
    private const val FIELD_SEP = "\t"
    private const val TAG_SEP = "|"

    fun encodeList(reviews: List<CanteenDishReview>): String {
        if (reviews.isEmpty()) return ""
        return reviews.joinToString(RECORD_SEP) { r ->
            listOf(
                sanitize(r.id),
                sanitize(r.canteenBuildingId),
                sanitize(r.dishName),
                sanitize(r.stallLocation),
                r.priceYuan?.toString().orEmpty(),
                r.stars.coerceIn(1, 5).toString(),
                r.tags.joinToString(TAG_SEP) { sanitize(it) },
                sanitize(r.comment),
                r.updatedAtEpochMs.toString()
            ).joinToString(FIELD_SEP)
        }
    }

    fun decodeList(raw: String?): List<CanteenDishReview> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECORD_SEP).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split(FIELD_SEP)
            if (parts.size < 6) return@mapNotNull null
            val id = parts[0].trim().ifBlank { return@mapNotNull null }
            val canteenId = parts[1].trim().ifBlank { return@mapNotNull null }
            val dishName = parts[2].trim().ifBlank { return@mapNotNull null }
            val stall = parts.getOrNull(3)?.trim().orEmpty()
            val price = parts.getOrNull(4)?.trim()?.toIntOrNull()
            val stars = parts.getOrNull(5)?.trim()?.toIntOrNull()?.coerceIn(1, 5) ?: return@mapNotNull null
            val tags = parts.getOrNull(6)
                ?.split(TAG_SEP)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val comment = parts.getOrNull(7)?.trim().orEmpty()
            val updatedMs = parts.getOrNull(8)?.trim()?.toLongOrNull() ?: 0L
            CanteenDishReview(
                id = id,
                canteenBuildingId = canteenId,
                dishName = dishName,
                stallLocation = stall,
                priceYuan = price,
                stars = stars,
                tags = tags,
                comment = comment,
                updatedAtEpochMs = updatedMs
            )
        }
    }

    private fun sanitize(input: String): String =
        input.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replace('|', '/').trim()
}

object CampusDiningRecommender {

    const val LEAD_MINUTES_BEFORE_CLASS_END = 60L
    private const val MAX_CONSECUTIVE_BREAK_MINUTES = 25L

    val QUICK_REVIEW_TAGS: List<String> = listOf(
        "分量足",
        "出餐快",
        "性价比高",
        "味道正",
        "少油健康",
        "排队久",
        "偏咸偏辣",
        "避雷"
    )

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
            lateDishes = listOf("川味冒菜", "鲜肉馄饨", "炸串卷饼")
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

    val DEFAULT_DISH_CATALOG: List<CampusDishCatalogItem> = listOf(
        // 一餐厅
        CampusDishCatalogItem(
            id = "c1_huimian",
            canteenBuildingId = "b_canteen1",
            canteenShortName = "一餐厅",
            stallLocation = "二楼面食档",
            dishName = "二楼河南羊肉烩面",
            priceYuan = 11,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.7f,
            baseReviewCount = 68,
            defaultTags = listOf("味道正", "分量足"),
            sampleComment = "汤底浓郁，加辣椒油很香，免费加面。"
        ),
        CampusDishCatalogItem(
            id = "c1_tieban",
            canteenBuildingId = "b_canteen1",
            canteenShortName = "一餐厅",
            stallLocation = "三楼铁板档",
            dishName = "三楼铁板黑椒牛柳饭",
            priceYuan = 13,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.6f,
            baseReviewCount = 52,
            defaultTags = listOf("分量足", "味道正"),
            sampleComment = "现炒铁板黑椒汁足，配煎蛋很下饭。"
        ),
        CampusDishCatalogItem(
            id = "c1_zixuan",
            canteenBuildingId = "b_canteen1",
            canteenShortName = "一餐厅",
            stallLocation = "一楼中区",
            dishName = "一楼大众自选快餐",
            priceYuan = 9,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.4f,
            baseReviewCount = 84,
            defaultTags = listOf("出餐快", "性价比高"),
            sampleComment = "下课赶时间首选，两荤一素不到10元。"
        ),
        CampusDishCatalogItem(
            id = "c1_hulatang",
            canteenBuildingId = "b_canteen1",
            canteenShortName = "一餐厅",
            stallLocation = "一楼早餐档",
            dishName = "胡辣汤配水煎包",
            priceYuan = 6,
            mealSlots = setOf(DiningMealSlot.BRUNCH),
            baseStars = 4.8f,
            baseReviewCount = 91,
            defaultTags = listOf("性价比高", "味道正"),
            sampleComment = "早八前喝一碗热胡辣汤配焦底水煎包很顶饱。"
        ),
        // 二餐厅
        CampusDishCatalogItem(
            id = "c2_shaoya",
            canteenBuildingId = "b_canteen2",
            canteenShortName = "二餐厅",
            stallLocation = "一楼烧腊档",
            dishName = "广式脆皮烧鸭饭",
            priceYuan = 13,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.8f,
            baseReviewCount = 77,
            defaultTags = listOf("味道正", "分量足"),
            sampleComment = "鸭皮酥脆，酸梅酱解腻，去晚了容易卖完。"
        ),
        CampusDishCatalogItem(
            id = "c2_suancaiyu",
            canteenBuildingId = "b_canteen2",
            canteenShortName = "二餐厅",
            stallLocation = "二楼特色档",
            dishName = "老坛酸菜鱼米饭",
            priceYuan = 14,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.6f,
            baseReviewCount = 49,
            defaultTags = listOf("分量足", "味道正"),
            sampleComment = "无刺巴沙鱼片嫩滑，酸汤泡饭一绝。"
        ),
        CampusDishCatalogItem(
            id = "c2_zixuan",
            canteenBuildingId = "b_canteen2",
            canteenShortName = "二餐厅",
            stallLocation = "一楼自选区",
            dishName = "两荤一素自选菜",
            priceYuan = 10,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.5f,
            baseReviewCount = 63,
            defaultTags = listOf("出餐快", "性价比高"),
            sampleComment = "离1教2教和图书馆近，菜品每天换样。"
        ),
        // 三餐厅
        CampusDishCatalogItem(
            id = "c3_dapanji",
            canteenBuildingId = "b_canteen3",
            canteenShortName = "三餐厅",
            stallLocation = "一楼清真档",
            dishName = "新疆大盘鸡拌面",
            priceYuan = 13,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.9f,
            baseReviewCount = 112,
            defaultTags = listOf("分量足", "味道正", "性价比高"),
            sampleComment = "裤带面筋道裹满汤汁，土豆软糯，西区口碑王。"
        ),
        CampusDishCatalogItem(
            id = "c3_lamian",
            canteenBuildingId = "b_canteen3",
            canteenShortName = "三餐厅",
            stallLocation = "一楼拉面档",
            dishName = "兰州牛肉拉面",
            priceYuan = 10,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER, DiningMealSlot.LATE_DINNER),
            baseStars = 4.7f,
            baseReviewCount = 69,
            defaultTags = listOf("出餐快", "味道正"),
            sampleComment = "现拉面条，牛骨清汤很鲜。"
        ),
        CampusDishCatalogItem(
            id = "c3_shiguo",
            canteenBuildingId = "b_canteen3",
            canteenShortName = "三餐厅",
            stallLocation = "二楼风味档",
            dishName = "韩式石锅拌饭",
            priceYuan = 12,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.5f,
            baseReviewCount = 44,
            defaultTags = listOf("分量足"),
            sampleComment = "锅巴焦香，甜辣酱可以自己控制用量。"
        ),
        // 四餐厅
        CampusDishCatalogItem(
            id = "c4_cuipiji",
            canteenBuildingId = "b_canteen4",
            canteenShortName = "四餐厅",
            stallLocation = "一楼东侧",
            dishName = "招牌脆皮鸡米饭",
            priceYuan = 12,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.8f,
            baseReviewCount = 95,
            defaultTags = listOf("出餐快", "分量足", "性价比高"),
            sampleComment = "鸡排外酥里嫩，支持沙拉千岛双拼酱。"
        ),
        CampusDishCatalogItem(
            id = "c4_huajia",
            canteenBuildingId = "b_canteen4",
            canteenShortName = "四餐厅",
            stallLocation = "二楼特色档",
            dishName = "锡纸花甲粉",
            priceYuan = 12,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER, DiningMealSlot.LATE_DINNER),
            baseStars = 4.7f,
            baseReviewCount = 58,
            defaultTags = listOf("味道正"),
            sampleComment = "蒜蓉味足，花甲吐沙干净，配金针菇很鲜。"
        ),
        CampusDishCatalogItem(
            id = "c4_qingshi",
            canteenBuildingId = "b_canteen4",
            canteenShortName = "四餐厅",
            stallLocation = "二楼轻食档",
            dishName = "低脂鸡胸肉轻食碗",
            priceYuan = 13,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.6f,
            baseReviewCount = 37,
            defaultTags = listOf("少油健康"),
            sampleComment = "紫薯玉米加煎鸡胸肉，适合减脂期或晚课前吃。"
        ),
        // 小食堂
        CampusDishCatalogItem(
            id = "cs_xiaomian",
            canteenBuildingId = "b_canteen_small",
            canteenShortName = "小食堂",
            stallLocation = "一楼粉面档",
            dishName = "重庆豌杂小面",
            priceYuan = 10,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.7f,
            baseReviewCount = 64,
            defaultTags = listOf("出餐快", "味道正"),
            sampleComment = "豌豆软烂肉酱香，3教4教下课走两分钟就到。"
        ),
        CampusDishCatalogItem(
            id = "cs_gaijiao",
            canteenBuildingId = "b_canteen_small",
            canteenShortName = "小食堂",
            stallLocation = "一楼快餐档",
            dishName = "下课快手盖浇饭",
            priceYuan = 10,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.5f,
            baseReviewCount = 51,
            defaultTags = listOf("出餐快", "性价比高"),
            sampleComment = "盛菜速度快，适合下午还有实验课赶时间。"
        ),
        CampusDishCatalogItem(
            id = "cs_reganmian",
            canteenBuildingId = "b_canteen_small",
            canteenShortName = "小食堂",
            stallLocation = "一楼早点档",
            dishName = "信阳热干面",
            priceYuan = 6,
            mealSlots = setOf(DiningMealSlot.BRUNCH),
            baseStars = 4.6f,
            baseReviewCount = 48,
            defaultTags = listOf("出餐快", "性价比高"),
            sampleComment = "芝麻酱浓稠，配咸菜花生米很地道。"
        ),
        // 嵩山路餐厅
        CampusDishCatalogItem(
            id = "ss_huimian",
            canteenBuildingId = "b_ss_canteen",
            canteenShortName = "嵩山餐厅",
            stallLocation = "一楼面食档",
            dishName = "河南羊肉烩面",
            priceYuan = 11,
            mealSlots = setOf(DiningMealSlot.LUNCH, DiningMealSlot.DINNER),
            baseStars = 4.6f,
            baseReviewCount = 39,
            defaultTags = listOf("分量足", "味道正"),
            sampleComment = "嵩山路校区经典午餐，量大管饱。"
        )
    )

    fun resolveMealSlot(time: LocalTime): DiningMealSlot = when {
        time <= LocalTime.of(10, 30) -> DiningMealSlot.BRUNCH
        time <= LocalTime.of(14, 15) -> DiningMealSlot.LUNCH
        time <= LocalTime.of(19, 15) -> DiningMealSlot.DINNER
        else -> DiningMealSlot.LATE_DINNER
    }

    /**
     * 聚合预设口碑与用户实测打分，生成按评分排序的食堂菜品榜（红黑榜）。
     */
    fun aggregateDishRatings(
        userReviews: List<CanteenDishReview> = emptyList(),
        canteenBuildingIdFilter: String? = null
    ): List<AggregatedDishRating> {
        val canteenMap = CANTEENS.associateBy { it.buildingId }
        val reviewsByKey = userReviews.groupBy { "${it.canteenBuildingId}:${it.dishName.trim()}" }
        val handledKeys = mutableSetOf<String>()
        val results = mutableListOf<AggregatedDishRating>()

        for (item in DEFAULT_DISH_CATALOG) {
            if (canteenBuildingIdFilter != null && item.canteenBuildingId != canteenBuildingIdFilter) continue
            val key = "${item.canteenBuildingId}:${item.dishName}"
            handledKeys += key
            val matchingReviews = reviewsByKey[key].orEmpty().sortedByDescending { it.updatedAtEpochMs }
            val latestMine = matchingReviews.firstOrNull()
            // 用户本人的评价权重相当于 5 票，使个人打星直接体现到评分与推荐优先级中
            val userWeight = matchingReviews.size * 5
            val weightedSum = item.baseStars * item.baseReviewCount + matchingReviews.sumOf { it.stars * 5 }
            val avg = (weightedSum / (item.baseReviewCount + userWeight)).coerceIn(1f, 5f)
            val mergedTags = ((latestMine?.tags.orEmpty()) + item.defaultTags).distinct().take(4)
            val comment = latestMine?.comment?.takeIf { it.isNotBlank() } ?: item.sampleComment
            val price = latestMine?.priceYuan ?: item.priceYuan

            results += AggregatedDishRating(
                dishKey = key,
                canteenBuildingId = item.canteenBuildingId,
                canteenShortName = item.canteenShortName,
                stallLocation = latestMine?.stallLocation?.takeIf { it.isNotBlank() } ?: item.stallLocation,
                dishName = item.dishName,
                priceYuan = price,
                averageStars = (avg * 10f).roundToInt() / 10f,
                totalReviewCount = item.baseReviewCount + matchingReviews.size,
                myReview = latestMine,
                tags = mergedTags,
                displayComment = comment
            )
        }

        // 追加用户自行新增但不在默认目录中的自定义档口菜品
        for ((key, list) in reviewsByKey) {
            if (key in handledKeys) continue
            val latest = list.maxByOrNull { it.updatedAtEpochMs } ?: continue
            if (canteenBuildingIdFilter != null && latest.canteenBuildingId != canteenBuildingIdFilter) continue
            val shortName = canteenMap[latest.canteenBuildingId]?.shortName ?: "食堂"
            val avg = list.map { it.stars }.average().toFloat().coerceIn(1f, 5f)
            results += AggregatedDishRating(
                dishKey = key,
                canteenBuildingId = latest.canteenBuildingId,
                canteenShortName = shortName,
                stallLocation = latest.stallLocation.ifBlank { "自建档口" },
                dishName = latest.dishName,
                priceYuan = latest.priceYuan,
                averageStars = (avg * 10f).roundToInt() / 10f,
                totalReviewCount = list.size,
                myReview = latest,
                tags = latest.tags.distinct().take(4),
                displayComment = latest.comment.ifBlank { "我的实测打分：${latest.stars} 星" }
            )
        }

        return results.sortedWith(
            compareByDescending<AggregatedDishRating> { it.myReview != null }
                .thenByDescending { it.averageStars }
                .thenByDescending { it.totalReviewCount }
        )
    }

    fun computeCanteenAverageRating(
        canteenBuildingId: String,
        userReviews: List<CanteenDishReview> = emptyList()
    ): Float {
        val dishes = aggregateDishRatings(userReviews, canteenBuildingId)
        if (dishes.isEmpty()) return 4.6f
        val avg = dishes.map { it.averageStars }.average().toFloat()
        return (avg * 10f).roundToInt() / 10f
    }

    /**
     * 将单日排课合并为连续上课区间：
     * 若两节课间隔 <= 25 分钟（如 1-2 节与 3-4 节连上），以连续课段的最后一节下课时刻作为就餐基准。
     */
    fun resolveClassBlocksForDay(
        date: LocalDate,
        occurrences: List<ClassOccurrence>,
        buildings: List<Building> = emptyList()
    ): List<CourseClassBlock> {
        val active = occurrences
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
                prev.lastCourseTitle = occ.courseName
                prev.lastClassroom = occ.rawRoom
                prev.lastBuildingId = resolvedBldId ?: prev.lastBuildingId
            } else {
                merged += MutableBlock(
                    startTime = occ.startTime,
                    endTime = occ.endTime,
                    lastCourseTitle = occ.courseName,
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
                ClassOccurrence(
                    occurrenceId = ev.eventKey,
                    courseId = ev.courseId ?: ev.eventKey,
                    ruleId = ev.eventKey,
                    overrideId = null,
                    courseName = ev.displayTitle,
                    teacher = "",
                    colorArgb = 0xFF2563EB,
                    date = date,
                    teachingWeek = 1,
                    weekday = date.dayOfWeek,
                    startPeriod = 1,
                    endPeriod = 2,
                    startTime = startLocal,
                    endTime = endLocal,
                    rawRoom = ev.locationText.orEmpty(),
                    buildingId = ev.buildingId,
                    buildingName = null,
                    isMakeupOrOverridden = false,
                    overrideReason = null,
                    userModified = false
                )
            }
            result += resolveClassBlocksForDay(date, syntheticOccurrences, buildings)
        }
        return result.sortedBy { it.recommendTriggerInstant }
    }

    /**
     * 针对当前时刻与当天课表生成就餐推荐（融合用户打星评价：优先推荐高分菜，自动剔除 <=2 星踩雷菜）。
     */
    fun buildRecommendation(
        date: LocalDate,
        now: Instant,
        occurrences: List<ClassOccurrence>,
        buildings: List<Building>,
        shuffleOffset: Int = 0,
        userReviews: List<CanteenDishReview> = emptyList()
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
                shuffleOffset = shuffleOffset,
                userReviews = userReviews
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
                shuffleOffset = shuffleOffset,
                userReviews = userReviews
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
                notificationTitle = "${slot.label}推荐 · ${primary.profile.name} ★${primary.averageStars}",
                notificationBody = "${primary.profile.areaLabel} · 推荐：$dishesText"
            )
        }
    }

    fun buildRecommendationForBlock(
        block: CourseClassBlock,
        now: Instant,
        buildings: List<Building>,
        shuffleOffset: Int = 0,
        userReviews: List<CanteenDishReview> = emptyList()
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
            shuffleOffset = shuffleOffset,
            userReviews = userReviews
        )
        val primary = candidates.first()
        val secondary = candidates.getOrNull(1)
        val walkText = primary.walkMinutes?.let { "步行约 ${it} 分钟" } ?: primary.profile.areaLabel
        val dishesText = primary.recommendedDishes.take(2).joinToString(" / ")
        val backupText = secondary?.let { "（备选：${it.profile.shortName}）" }.orEmpty()

        val title = if (minsLeft in 1..75) {
            "距下课还有 ${minsLeft} 分钟 · 推荐去${primary.profile.shortName} ★${primary.averageStars}"
        } else {
            "下课就餐推荐 · ${primary.profile.shortName} ★${primary.averageStars}"
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
        shuffleOffset: Int,
        userReviews: List<CanteenDishReview>
    ): List<DiningCanteenCandidate> {
        val campusId = when {
            classroomBuilding?.campusId == "songshanlu" -> "songshanlu"
            classroomText.contains("嵩山") -> "songshanlu"
            else -> "lianhuajie"
        }
        val pool = CANTEENS.filter { it.campusId == campusId }.ifEmpty { CANTEENS }
        val allAggregated = aggregateDishRatings(userReviews)

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

            val canteenDishes = allAggregated.filter { it.canteenBuildingId == profile.buildingId }
            val avgStars = if (canteenDishes.isNotEmpty()) {
                ((canteenDishes.map { it.averageStars }.average().toFloat() * 10f).roundToInt() / 10f)
            } else {
                4.6f
            }

            // 统计用户对该食堂的打星偏好：4-5 星加分，1-2 星踩雷扣分
            val userCanteenReviews = userReviews.filter { it.canteenBuildingId == profile.buildingId }
            val userRatingBonus = userCanteenReviews.fold(0) { acc, rev ->
                acc + when (rev.stars) {
                    5 -> 28
                    4 -> 14
                    2 -> -20
                    1 -> -35
                    else -> 0
                }
            }.coerceIn(-45, 45)

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

            val hasUserFavorite = canteenDishes.any { it.isFavoritedByUser }
            val reasonTag = when {
                hasUserFavorite && walkMinutes != null -> "含你打高分的常吃菜 · 步行约 ${walkMinutes} 分钟"
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
            val avoidedNames = canteenDishes.filter { it.isAvoidedByUser }.map { it.dishName }.toSet()
            val favoriteDishes = canteenDishes
                .filter { it.isFavoritedByUser && it.dishName !in avoidedNames }
                .sortedByDescending { it.myReview?.stars ?: 0 }
                .map { "${it.dishName} ★${it.averageStars}" }

            val filteredRaw = rawDishes.filter { it !in avoidedNames }.ifEmpty { rawDishes }
            val dishShift = abs(daySeed + shuffleOffset + idx) % filteredRaw.size.coerceAtLeast(1)
            val rotatedRaw = (filteredRaw.drop(dishShift) + filteredRaw.take(dishShift))
                .map { dName ->
                    val matched = canteenDishes.firstOrNull { it.dishName == dName }
                    if (matched != null) "$dName ★${matched.averageStars}" else dName
                }
            val finalDishes = (favoriteDishes + rotatedRaw).distinct().take(3)

            ScoredCanteen(
                candidate = DiningCanteenCandidate(
                    profile = profile,
                    distanceMeters = walkMeters,
                    walkMinutes = walkMinutes,
                    averageStars = avgStars,
                    recommendedDishes = finalDishes,
                    reasonTag = reasonTag
                ),
                score = distanceBonus + nearbyBonus + fastTurnaroundBonus + rotationBonus + userRatingBonus
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
