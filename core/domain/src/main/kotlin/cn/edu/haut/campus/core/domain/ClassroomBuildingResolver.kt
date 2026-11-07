package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.ResolvedClassroomLocation
import java.time.LocalDate

object ClassroomBuildingResolver {

    private val ROOM_NUMBER_REGEX = Regex("([A-Za-z]?\\d{3,5})")

    // 河南工业大学莲花街校区电子围栏多边形顶点（WGS-84，对齐官方 gis.haut.edu.cn 边界）
    val HAUT_LIANHUAJIE_POLYGON = listOf(
        GeoPoint(34.82800, 113.53560),
        GeoPoint(34.82800, 113.55120),
        GeoPoint(34.83580, 113.55120),
        GeoPoint(34.83580, 113.53560)
    )

    // 河南工业大学嵩山路校区电子围栏多边形顶点（WGS-84，郑州市二七区嵩山南路140号）
    val HAUT_SONGSHANLU_POLYGON = listOf(
        GeoPoint(34.73520, 113.61620),
        GeoPoint(34.73520, 113.62220),
        GeoPoint(34.73980, 113.62220),
        GeoPoint(34.73980, 113.61620)
    )

    val HAUT_SONGSHANLU_BUILDINGS: List<Building> = listOf(
        Building(
            id = "b_ss_gate_east",
            campusId = "songshanlu",
            name = "嵩山路校区东门（正门）",
            shortName = "嵩山路东门",
            latitude = 34.73745,
            longitude = 113.62110,
            category = CampusCategory.GATE,
            entrances = listOf("嵩山南路140号主入口"),
            description = "河南工业大学嵩山路校区东侧主校门，紧邻地铁5号线齐礼阎站。",
            verifiedAt = LocalDate.of(2026, 3, 1)
        ),
        Building(
            id = "b_ss_t1",
            campusId = "songshanlu",
            name = "嵩山路1号教学楼",
            shortName = "嵩山1教",
            latitude = 34.73785,
            longitude = 113.61980,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南正门", "东侧门"),
            description = "嵩山路校区核心公共教学楼，承担嵩山路校区专业课与公共基础课教学。",
            verifiedAt = LocalDate.of(2026, 3, 1)
        ),
        Building(
            id = "b_ss_t2",
            campusId = "songshanlu",
            name = "嵩山路2号教学楼",
            shortName = "嵩山2教",
            latitude = 34.73830,
            longitude = 113.61920,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门", "西连廊"),
            description = "嵩山路校区第二教学楼及阶梯教室集中区。",
            verifiedAt = LocalDate.of(2026, 3, 1)
        ),
        Building(
            id = "b_ss_lab",
            campusId = "songshanlu",
            name = "嵩山路实验与综合楼",
            shortName = "嵩山综合楼",
            latitude = 34.73720,
            longitude = 113.61960,
            category = CampusCategory.LAB,
            entrances = listOf("北门"),
            description = "嵩山路校区机房、实验室与继续教育/专科教学办公综合楼。",
            verifiedAt = LocalDate.of(2026, 3, 1)
        ),
        Building(
            id = "b_ss_lib",
            campusId = "songshanlu",
            name = "嵩山路校区图书馆",
            shortName = "嵩山图书馆",
            latitude = 34.73760,
            longitude = 113.61860,
            category = CampusCategory.LIBRARY,
            entrances = listOf("东正门"),
            description = "嵩山路校区图书借阅与自习阅览室。",
            verifiedAt = LocalDate.of(2026, 3, 1)
        ),
        Building(
            id = "b_ss_canteen",
            campusId = "songshanlu",
            name = "嵩山路学生餐厅",
            shortName = "嵩山餐厅",
            latitude = 34.73695,
            longitude = 113.61810,
            category = CampusCategory.CANTEEN,
            entrances = listOf("东入口", "北入口"),
            description = "嵩山路校区师生综合餐饮中心。",
            verifiedAt = LocalDate.of(2026, 3, 1)
        ),
        Building(
            id = "b_ss_dorm",
            campusId = "songshanlu",
            name = "嵩山路学生公寓区",
            shortName = "嵩山公寓",
            latitude = 34.73670,
            longitude = 113.61750,
            category = CampusCategory.DORMITORY,
            entrances = listOf("东门禁通道"),
            description = "嵩山路校区学生宿舍楼群与生活服务区。",
            verifiedAt = LocalDate.of(2026, 3, 1)
        ),
        Building(
            id = "b_ss_sports",
            campusId = "songshanlu",
            name = "嵩山路田径运动场",
            shortName = "嵩山操场",
            latitude = 34.73840,
            longitude = 113.61760,
            category = CampusCategory.SPORTS,
            entrances = listOf("东看台入口"),
            description = "嵩山路校区标准田径跑道与篮球场。",
            verifiedAt = LocalDate.of(2026, 3, 1)
        )
    )

    val HAUT_SONGSHANLU_ALIASES: List<BuildingAlias> = listOf(
        BuildingAlias("al_ss_t1_1", "b_ss_t1", "嵩山路1号教学楼", 0.99f, true),
        BuildingAlias("al_ss_t1_2", "b_ss_t1", "嵩山1教", 0.97f, true),
        BuildingAlias("al_ss_t2_1", "b_ss_t2", "嵩山路2号教学楼", 0.99f, true),
        BuildingAlias("al_ss_t2_2", "b_ss_t2", "嵩山2教", 0.97f, true),
        BuildingAlias("al_ss_lab", "b_ss_lab", "嵩山综合楼", 0.95f, true),
        BuildingAlias("al_ss_lib", "b_ss_lib", "嵩山图书馆", 0.98f, true),
        BuildingAlias("al_ss_canteen", "b_ss_canteen", "嵩山餐厅", 0.96f, true),
        BuildingAlias("al_ss_sports", "b_ss_sports", "嵩山操场", 0.95f, true)
    )

    /**
     * 从教务课表教室、考试地点、院系或课程备注文本中智能识别所属工大校区（莲花街校区 vs 嵩山路校区）。
     */
    fun detectCampusHint(rawTexts: Iterable<String>): CampusId? {
        var songshanVotes = 0
        var lianhuajieVotes = 0
        for (raw in rawTexts) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            when {
                text.contains("嵩山路", ignoreCase = true) ||
                    text.contains("嵩山校区", ignoreCase = true) ||
                    text.contains("嵩山1教", ignoreCase = true) ||
                    text.contains("嵩山2教", ignoreCase = true) ||
                    text.contains("齐礼阎", ignoreCase = true) -> {
                    songshanVotes += 3
                }
                text.contains("嵩山", ignoreCase = true) -> {
                    songshanVotes += 2
                }
                text.contains("莲花街", ignoreCase = true) ||
                    text.contains("钟楼", ignoreCase = true) ||
                    text.contains("长椿路", ignoreCase = true) ||
                    text.contains("训练中心", ignoreCase = true) -> {
                    lianhuajieVotes += 3
                }
                Regex("(1|2|3|4|5|6|7|8|9|18|24|25|31|32|34|35|38|40)号(教学)?楼|[1-9]教|^[1-9]\\d{3}$|^(31|32|34|35|38|40)\\d{3}$").containsMatchIn(text) -> {
                    lianhuajieVotes += 1
                }
            }
        }
        return when {
            songshanVotes > 0 && songshanVotes >= lianhuajieVotes -> SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID
            lianhuajieVotes > 0 -> SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID
            else -> null
        }
    }

    /**
     * 将教务原始教室字符串（如 "莲花街校区 1号教学楼1104"、"A座204"、"钟楼201"、"3206"）解析为校区建筑。
     * - 精确匹配已核验别名 -> EXACT_VERIFIED
     * - 模糊匹配或未核验低置信度别名 -> FUZZY_NEEDS_CONFIRMATION（需用户确认，不可装作已识别成功）
     * - 无法识别 -> UNRESOLVED
     */
    fun resolve(
        rawRoom: String,
        buildings: List<Building>,
        aliases: List<BuildingAlias>
    ): ResolvedClassroomLocation {
        val trimmed = rawRoom.trim()
        if (trimmed.isEmpty()) {
            return ResolvedClassroomLocation(
                rawRoom = rawRoom,
                status = BuildingMatchStatus.UNRESOLVED
            )
        }

        val buildingMap = buildings.associateBy { it.id }
        val roomNumberHint = ROOM_NUMBER_REGEX.findAll(trimmed).lastOrNull()?.value

        // 1. 直接匹配建筑全称或简称
        val directBuilding = buildings.sortedByDescending { maxOf(it.name.length, it.shortName.length) }.firstOrNull { b ->
            listOf(b.name, b.shortName).any { name ->
                name.length >= 2 && Regex("(?<![0-9])" + Regex.escape(name), RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
            }
        }
        if (directBuilding != null) {
            return ResolvedClassroomLocation(
                rawRoom = rawRoom,
                status = BuildingMatchStatus.EXACT_VERIFIED,
                building = directBuilding,
                roomNumberHint = roomNumberHint,
                confidence = 1.0f,
                matchedAlias = directBuilding.name
            )
        }

        // 2. 匹配别名表（按置信度与长度降序）
        val sortedAliases = aliases.sortedWith(
            compareByDescending<BuildingAlias> { it.verified }
                .thenByDescending { it.confidence }
                .thenByDescending { it.rawNameOrPattern.length }
        )

        for (alias in sortedAliases) {
            val pattern = alias.rawNameOrPattern
            val matched = if (pattern.startsWith("^")) {
                Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
            } else {
                Regex("(?<![0-9])" + Regex.escape(pattern), RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
            }

            if (matched) {
                val targetBuilding = buildingMap[alias.buildingId] ?: continue
                val status = if (alias.verified && alias.confidence >= 0.85f) {
                    BuildingMatchStatus.EXACT_VERIFIED
                } else {
                    BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION
                }
                return ResolvedClassroomLocation(
                    rawRoom = rawRoom,
                    status = status,
                    building = targetBuilding,
                    roomNumberHint = roomNumberHint,
                    confidence = alias.confidence,
                    matchedAlias = alias.rawNameOrPattern,
                    candidateBuildings = if (status == BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION) {
                        listOf(targetBuilding) + buildings.filter { it.id != targetBuilding.id }.take(2)
                    } else emptyList()
                )
            }
        }

        // 3. 常见纯四位/五位数字教室号启发式（如 "3206" 指 3 号楼 206，"31204" 指 31 号楼 204，未经别名验证时标记为模糊待确认）
        val multiDigitMatch = Regex("^(\\d{1,2})(\\d{3})$").find(trimmed)
        if (multiDigitMatch != null) {
            val buildingNum = multiDigitMatch.groupValues[1]
            val candidate = buildings.firstOrNull {
                it.name.contains("${buildingNum}号") || it.shortName.contains("${buildingNum}号") || it.shortName == "${buildingNum}教"
            }
            if (candidate != null) {
                return ResolvedClassroomLocation(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    building = candidate,
                    roomNumberHint = multiDigitMatch.groupValues[2],
                    confidence = 0.55f,
                    matchedAlias = "${buildingNum}XXX 编号推断",
                    candidateBuildings = buildings.take(4)
                )
            }
        }

        return ResolvedClassroomLocation(
            rawRoom = rawRoom,
            status = BuildingMatchStatus.UNRESOLVED,
            roomNumberHint = roomNumberHint,
            candidateBuildings = buildings.take(4)
        )
    }
}
