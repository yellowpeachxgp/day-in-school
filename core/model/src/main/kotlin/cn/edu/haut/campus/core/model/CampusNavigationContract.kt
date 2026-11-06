package cn.edu.haut.campus.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 底部主导航栏的强类型路由契约，统一主壳 (`MainActivity`)、桌面小组件 (`WidgetRefreshCoordinator`)
 * 与各业务模块之间的页面跳转标识，消除散落的字符串硬编码。
 */
enum class CampusBottomTabRoute(
    val routeKey: String,
    val label: String,
) {
    TODAY(routeKey = "today", label = "今日"),
    TIMETABLE(routeKey = "timetable", label = "课表"),
    GRADES(routeKey = "grades", label = "学业"),
    MAP(routeKey = "map", label = "地图"),
    SETTINGS(routeKey = "settings", label = "我的");

    companion object {
        val default: CampusBottomTabRoute = TODAY

        fun fromIndex(index: Int): CampusBottomTabRoute =
            entries.getOrElse(index) { default }

        fun fromRouteId(rawKey: String?): CampusBottomTabRoute? = fromKey(rawKey)

        fun fromKey(rawKey: String?): CampusBottomTabRoute? {
            if (rawKey.isNullOrBlank()) return null
            val normalized = rawKey.trim()
            return entries.firstOrNull {
                it.routeKey.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            }
        }
    }
}

/**
 * 二级全屏覆盖层（Overlay Hub）的强类型路由契约，统一今日 Bento 卡片、我的页快捷入口及新手引导跳转。
 */
enum class CampusOverlayRoute(
    val routeKey: String,
    val title: String,
) {
    IMPORTER(routeKey = "importer", title = "多源课表导入与校准"),
    SCHOOL_CENTER(routeKey = "school_center", title = "学校与校区切换中心"),
    CAMPUS_COMMUNITY(routeKey = "campus_community", title = "校园自习与学生社区"),
    LIBRARY_CATALOG(routeKey = "library_catalog", title = "图书馆馆藏检索"),
    LIBRARY_SEAT_CENTER(routeKey = "library_seat_center", title = "图书馆座位预约中心"),
    PARCEL_HUB(routeKey = "parcel_hub", title = "校园快递中心"),
    CLOUD_ACCOUNT_AND_BILLING(routeKey = "cloud_account_and_billing", title = "云端账号与桃子中心"),
    EXTENSION_STORE(routeKey = "extension_store", title = "扩展插件中心");

    companion object {
        fun fromKey(rawKey: String?): CampusOverlayRoute? {
            if (rawKey.isNullOrBlank()) return null
            val normalized = rawKey.trim()
            return entries.firstOrNull {
                it.routeKey.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true)
            }
        }
    }
}

/**
 * 桌面小组件、通知栏及外部 DeepLink 唤起主界面时的 Intent Extra 常量契约。
 */
object CampusDeepLinkContract {
    const val SCHEME: String = "dayinschool"
    const val HOST_WIDGET: String = "widget"

    const val EXTRA_NAVIGATE_TAB: String = "extra_navigate_tab"
    const val EXTRA_OPEN_OVERLAY: String = "extra_open_overlay"
    const val EXTRA_COURSE_ID: String = "extra_course_id"
    const val EXTRA_RAW_ROOM: String = "extra_raw_room"
    const val EXTRA_PROFILE_ID: String = "widget_profile_id"
    const val EXTRA_GENERATION: String = "widget_session_generation"
}

/**
 * 今日校园 Bento 首页可编排服务卡片的唯一规范枚举，统一 `CampusRepository`、`TodayScreen` 与 `SettingsScreen`
 * 的 ID、标题、副标题、默认单双列宽度与排序归一化逻辑。
 */
enum class HomeCardModule(
    val id: String,
    val title: String,
    val subtitle: String,
    val defaultFullWidth: Boolean = false,
    val defaultVisible: Boolean = true,
) {
    EXAM_SUMMARY(
        id = "EXAM_SUMMARY",
        title = "近期考试",
        subtitle = "考试倒计时与考场座位提示",
        defaultFullWidth = true,
        defaultVisible = true,
    ),
    STUDY_FOCUS(
        id = "STUDY_FOCUS",
        title = "自习专注",
        subtitle = "番茄钟计时与专注时长累计",
        defaultFullWidth = false,
        defaultVisible = true,
    ),
    DORM_ELECTRICITY(
        id = "DORM_ELECTRICITY",
        title = "宿舍电费",
        subtitle = "寝室剩余电量与日均用电预估",
        defaultFullWidth = false,
        defaultVisible = true,
    ),
    LIBRARY_SEAT(
        id = "LIBRARY_SEAT",
        title = "图书馆座位",
        subtitle = "在馆实时预约与明日准点抢座",
        defaultFullWidth = false,
        defaultVisible = true,
    ),
    PARCEL_HUB(
        id = "PARCEL_HUB",
        title = "驿站快递",
        subtitle = "取件码聚合与待取包裹提醒",
        defaultFullWidth = false,
        defaultVisible = true,
    ),
    LIBRARY_CATALOG(
        id = "LIBRARY_CATALOG",
        title = "馆藏检索",
        subtitle = "图书索书号与在馆可借状态查询",
        defaultFullWidth = false,
        defaultVisible = true,
    ),
    EMPTY_CLASSROOM(
        id = "EMPTY_CLASSROOM",
        title = "空闲教室",
        subtitle = "当前节次各教学楼空教室速查",
        defaultFullWidth = false,
        defaultVisible = true,
    ),
    CAMPUS_COMMUNITY(
        id = "CAMPUS_COMMUNITY",
        title = "校园社区",
        subtitle = "百团名录、交流频道与自习指引",
        defaultFullWidth = false,
        defaultVisible = true,
    );

    companion object {
        const val EMPTY_SET_SENTINEL: String = "_NONE_"

        val defaultOrder: List<HomeCardModule> = entries.toList()
        val defaultOrderIds: List<String> = entries.map { it.id }
        val defaultFullWidthIds: Set<String> =
            entries.filter { it.defaultFullWidth }.mapTo(linkedSetOf()) { it.id }
        val defaultHiddenIds: Set<String> =
            entries.filterNot { it.defaultVisible }.mapTo(linkedSetOf()) { it.id }

        private val byId: Map<String, HomeCardModule> = entries.associateBy { it.id }

        fun fromId(id: String?): HomeCardModule? = id?.trim()?.let(byId::get)

        fun normalizeOrderIds(rawOrder: Iterable<String>): List<String> {
            val validUserOrder = rawOrder.map { it.trim() }.filter { it in byId }
            return (validUserOrder + defaultOrderIds).distinct()
        }

        fun normalizeOrderModules(rawOrder: Iterable<String>): List<HomeCardModule> =
            normalizeOrderIds(rawOrder).mapNotNull(::fromId)

        fun normalizeHiddenIds(rawHidden: Set<String>): Set<String> =
            rawHidden.map { it.trim() }.filterTo(linkedSetOf()) { it in byId }

        fun normalizeFullWidthIds(rawFullWidth: Set<String>?): Set<String> = when {
            rawFullWidth == null -> defaultFullWidthIds
            rawFullWidth.isEmpty() || EMPTY_SET_SENTINEL in rawFullWidth -> emptySet()
            else -> rawFullWidth.map { it.trim() }.filterTo(linkedSetOf()) { it in byId }
        }
    }
}

/**
 * 主题、液态毛玻璃与时间轴缩放参数的全局规范默认值与夹取区间，消除多模块硬编码漂移。
 */
object ThemeVisualDefaults {
    const val DEFAULT_WALLPAPER_ALPHA: Float = 0.55f
    const val DEFAULT_WALLPAPER_BLUR_DP: Float = 10f
    const val DEFAULT_WALLPAPER_CARD_ALPHA: Float = 0.68f
    const val DEFAULT_TIMELINE_ZOOM_SCALE: Float = 2.0f

    const val MIN_GLASS_OPACITY: Float = 0.18f
    const val MAX_GLASS_OPACITY: Float = 0.98f
    const val MIN_BLUR_RADIUS_DP: Float = 0f
    const val MAX_BLUR_RADIUS_DP: Float = 35f

    val WALLPAPER_ALPHA_RANGE: ClosedFloatingPointRange<Float> = 0.05f..1.0f
    val WALLPAPER_BLUR_DP_RANGE: ClosedFloatingPointRange<Float> = MIN_BLUR_RADIUS_DP..MAX_BLUR_RADIUS_DP
    val WALLPAPER_CARD_ALPHA_RANGE: ClosedFloatingPointRange<Float> = MIN_GLASS_OPACITY..MAX_GLASS_OPACITY
    val TIMELINE_ZOOM_SCALE_RANGE: ClosedFloatingPointRange<Float> = 1.2f..3.6f

    fun clampWallpaperAlpha(value: Float): Float = value.coerceIn(WALLPAPER_ALPHA_RANGE)
    fun clampWallpaperBlurDp(value: Float): Float = value.coerceIn(WALLPAPER_BLUR_DP_RANGE)
    fun clampWallpaperCardAlpha(value: Float): Float = value.coerceIn(WALLPAPER_CARD_ALPHA_RANGE)
    fun clampTimelineZoomScale(value: Float): Float = value.coerceIn(TIMELINE_ZOOM_SCALE_RANGE)

    fun clampGlassOpacity(value: Float): Float = clampWallpaperCardAlpha(value)
    fun clampBlurRadiusDp(value: Float): Float = clampWallpaperBlurDp(value)
}

/**
 * 教务学年与学期代码 (`xnm` / `xqm`) 的动态推导工具，支持按当前日期自动推算默认同步学期。
 */
object AcademicTermResolver {
    const val AUTUMN_SEMESTER_CODE: String = "3"
    const val SPRING_SEMESTER_CODE: String = "12"
    const val SUMMER_SEMESTER_CODE: String = "16"

    /**
     * 根据给定日期推算正方教务学年码 `xnm`（如 2025 年 9 月至 2026 年 7 月均为 `"2025"`）。
     */
    fun defaultAcademicYearCode(today: LocalDate = LocalDate.now()): String {
        val startYear = if (today.monthValue >= 8) today.year else today.year - 1
        return startYear.toString()
    }

    /**
     * 根据给定日期推算正方教务学期码 `xqm`（2–7 月为第二学期 `"12"`，8–1 月为第一学期 `"3"`）。
     */
    fun defaultSemesterCode(today: LocalDate = LocalDate.now()): String {
        return if (today.monthValue in 2..7) SPRING_SEMESTER_CODE else AUTUMN_SEMESTER_CODE
    }

    /**
     * 根据给定日期推算标准学期 ID（如 `"2025-2026-2"`）。
     */
    fun defaultTermId(today: LocalDate = LocalDate.now()): String {
        val startYear = defaultAcademicYearCode(today).toIntOrNull() ?: 2025
        val endYear = startYear + 1
        val semNo = if (defaultSemesterCode(today) == SPRING_SEMESTER_CODE) "2" else "1"
        return "$startYear-$endYear-$semNo"
    }

    /**
     * 生成候选学年代码列表（供下拉或分段选择）。
     */
    fun candidateAcademicYearCodes(today: LocalDate = LocalDate.now(), count: Int = 4): List<String> {
        val baseYear = defaultAcademicYearCode(today).toIntOrNull() ?: 2025
        return (0 until count).map { offset -> (baseYear - offset).toString() }
    }

    fun recentAcademicYears(count: Int = 4, today: LocalDate = LocalDate.now()): List<String> =
        candidateAcademicYearCodes(today = today, count = count)

    /**
     * 格式化为人类可读的学年学期描述，如 `"2025-2026 学年 第 2 学期"`。
     */
    fun formatTermLabel(xnm: String, xqm: String): String {
        val startYear = xnm.trim().toIntOrNull() ?: 2025
        val endYear = startYear + 1
        val semNo = when (xqm.trim()) {
            AUTUMN_SEMESTER_CODE, "1" -> "1"
            SPRING_SEMESTER_CODE, "2" -> "2"
            SUMMER_SEMESTER_CODE, "3_SHORT" -> "3"
            else -> "2"
        }
        return "$startYear-$endYear 学年 第 $semNo 学期"
    }
}

/**
 * 统计指定自然日内已完成的专注时长（分钟），统一 `TodayScreen` 与 `SettingsScreen` 的汇总口径。
 */
fun List<FocusSessionDomain>.todayFocusMinutes(
    today: LocalDate,
    zoneId: ZoneId = HAUT_ZONE_ID,
): Int = sumOf { session ->
    val doneMs = session.completedAtEpochMs ?: session.expectedEndAtEpochMs
    val doneDate = Instant.ofEpochMilli(doneMs).atZone(zoneId).toLocalDate()
    if (doneDate == today) session.durationMinutes else 0
}

/**
 * 统计累计已完成的专注总时长（分钟）。
 */
fun List<FocusSessionDomain>.totalFocusMinutes(): Int =
    sumOf { it.durationMinutes }

