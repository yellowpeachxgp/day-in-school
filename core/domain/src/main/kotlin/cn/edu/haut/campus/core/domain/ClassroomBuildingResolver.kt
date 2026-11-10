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

    // 河南工业大学嵩山路校区电子围栏多边形顶点（WGS-84，严格对齐郑州市中原区嵩山南路140号×伊河路西南象限院区，不越过嵩山南路与伊河路）
    val HAUT_SONGSHANLU_POLYGON = listOf(
        GeoPoint(34.73930, 113.61730),
        GeoPoint(34.73930, 113.62035),
        GeoPoint(34.74150, 113.62035),
        GeoPoint(34.74150, 113.61730)
    )

    // 注：莲花街校区楼宇坐标来自学校官方 gis.haut.edu.cn 矢量质心；
    // 嵩山路校区尚未接入官方楼宇级 GIS 测绘矢量，以下楼宇坐标为嵩山南路140号院区地块内近似定位点（非官方测绘级坐标），全端地图与就餐推荐统一复用此单一数据源。
    val HAUT_SONGSHANLU_BUILDINGS: List<Building> = listOf(
        Building(
            id = "b_ss_gate_east",
            campusId = "songshanlu",
            name = "嵩山路校区主校门（嵩山南路140号）",
            shortName = "嵩山路校门",
            latitude = 34.74055,
            longitude = 113.62015,
            category = CampusCategory.GATE,
            entrances = listOf("嵩山南路140号东正门"),
            description = "河南工业大学嵩山路校区东侧主校门（郑州市中原区嵩山南路140号，近伊河路交叉口）。",
            verifiedAt = LocalDate.of(2026, 11, 10)
        ),
        Building(
            id = "b_ss_t1",
            campusId = "songshanlu",
            name = "嵩山路1号教学楼",
            shortName = "嵩山1教",
            latitude = 34.74085,
            longitude = 113.61935,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南正门", "东侧门"),
            description = "嵩山路校区核心公共教学楼，承担嵩山路校区专业课与公共基础课教学。",
            verifiedAt = LocalDate.of(2026, 11, 10)
        ),
        Building(
            id = "b_ss_t2",
            campusId = "songshanlu",
            name = "嵩山路2号教学楼",
            shortName = "嵩山2教",
            latitude = 34.74115,
            longitude = 113.61880,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门", "西连廊"),
            description = "嵩山路校区第二教学楼及阶梯教室集中区。",
            verifiedAt = LocalDate.of(2026, 11, 10)
        ),
        Building(
            id = "b_ss_lab",
            campusId = "songshanlu",
            name = "嵩山路实验与综合楼",
            shortName = "嵩山综合楼",
            latitude = 34.74030,
            longitude = 113.61920,
            category = CampusCategory.LAB,
            entrances = listOf("北门"),
            description = "嵩山路校区机房、实验室与教学办公综合楼。",
            verifiedAt = LocalDate.of(2026, 11, 10)
        ),
        Building(
            id = "b_ss_lib",
            campusId = "songshanlu",
            name = "嵩山路校区图书馆",
            shortName = "嵩山图书馆",
            latitude = 34.74060,
            longitude = 113.61855,
            category = CampusCategory.LIBRARY,
            entrances = listOf("东正门"),
            description = "嵩山路校区图书借阅与自习阅览室。",
            verifiedAt = LocalDate.of(2026, 11, 10)
        ),
        Building(
            id = "b_ss_canteen",
            campusId = "songshanlu",
            name = "嵩山路学生餐厅",
            shortName = "嵩山餐厅",
            latitude = 34.73995,
            longitude = 113.61840,
            category = CampusCategory.CANTEEN,
            entrances = listOf("东入口", "北入口"),
            description = "嵩山路校区师生综合餐饮中心（院区地块近似坐标，未接入官方测绘级 GIS 矢量）。",
            verifiedAt = LocalDate.of(2026, 11, 10)
        ),
        Building(
            id = "b_ss_dorm",
            campusId = "songshanlu",
            name = "嵩山路学生公寓区",
            shortName = "嵩山公寓",
            latitude = 34.73970,
            longitude = 113.61785,
            category = CampusCategory.DORMITORY,
            entrances = listOf("东门禁通道"),
            description = "嵩山路校区学生宿舍楼群与生活服务区。",
            verifiedAt = LocalDate.of(2026, 11, 10)
        ),
        Building(
            id = "b_ss_sports",
            campusId = "songshanlu",
            name = "嵩山路田径运动场",
            shortName = "嵩山操场",
            latitude = 34.74105,
            longitude = 113.61790,
            category = CampusCategory.SPORTS,
            entrances = listOf("东看台入口"),
            description = "嵩山路校区标准田径跑道与篮球场。",
            verifiedAt = LocalDate.of(2026, 11, 10)
        )
    )

    val HAUT_SONGSHANLU_ALIASES: List<BuildingAlias> = listOf(
        BuildingAlias("al_ss_t1_1", "b_ss_t1", "嵩山路1号教学楼", 0.99f, true),
        BuildingAlias("al_ss_t1_2", "b_ss_t1", "嵩山1教", 0.97f, true),
        BuildingAlias("al_ss_t1_3", "b_ss_t1", "嵩山路校区1号教学楼", 1.0f, true),
        BuildingAlias("al_ss_t1_4", "b_ss_t1", "嵩1号楼", 0.98f, true),
        BuildingAlias("al_ss_t2_1", "b_ss_t2", "嵩山路2号教学楼", 0.99f, true),
        BuildingAlias("al_ss_t2_2", "b_ss_t2", "嵩山2教", 0.97f, true),
        BuildingAlias("al_ss_t2_3", "b_ss_t2", "嵩山路校区2号教学楼", 1.0f, true),
        BuildingAlias("al_ss_t2_4", "b_ss_t2", "嵩2号楼", 0.98f, true),
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
                Regex("(18|24|25|31|32|34|35|38|40)号(教学)?楼|^(31|32|34|35|38|40)\\d{3}$").containsMatchIn(text) -> {
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

    private fun normalizeCampusKey(raw: String?): String? {
        val clean = raw?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return when {
            clean.contains("songshan", ignoreCase = true) ||
                clean.contains("嵩山") ||
                clean.contains("齐礼阎") -> "songshanlu"
            clean.contains("lianhuajie", ignoreCase = true) ||
                clean.contains("莲花街") ||
                clean.contains("钟楼") ||
                clean.contains("长椿路") -> "lianhuajie"
            else -> clean.lowercase()
        }
    }

    private fun detectExplicitInlineCampus(roomText: String): String? {
        val clean = roomText.trim()
        if (clean.isEmpty()) return null
        return when {
            clean.contains("嵩山", ignoreCase = true) ||
                clean.contains("齐礼阎", ignoreCase = true) -> "songshanlu"
            clean.contains("莲花街", ignoreCase = true) ||
                clean.contains("钟楼", ignoreCase = true) ||
                clean.contains("长椿路", ignoreCase = true) ||
                clean.contains("训练中心", ignoreCase = true) ||
                Regex("莲0?\\d+号").containsMatchIn(clean) -> "lianhuajie"
            else -> null
        }
    }

    private fun buildingCampusKey(building: Building): String =
        normalizeCampusKey(building.campusId) ?: "lianhuajie"

    private fun matchesExactBuildingNumber(building: Building, buildingNum: String): Boolean {
        val normalizedNum = buildingNum.trimStart('0').ifEmpty { buildingNum }
        val exactNumRegex = Regex("(?<!\\d)0?${Regex.escape(normalizedNum)}(号(教学)?楼|教)")
        return exactNumRegex.containsMatchIn(building.name) || exactNumRegex.containsMatchIn(building.shortName)
    }

    data class ManualBuildingConfirmationOptions(
        val recommendedIds: Set<String>,
        val orderedChoices: List<Building>
    )

    /**
     * 为地图与课表人工纠正条生成候选建筑列表：
     * - 保留 `resolution` 的推荐短名单排在最前（`recommendedIds`）；
     * - 同时完整补齐当前校区全部适合教学/实验/自习/住宿参考的楼宇，确保真实楼宇不在短名单时仍可手动选择并保存别名；
     * - 严格按当前校区过滤，绝不把另一校区建筑混入当前校区确认列表。
     */
    fun buildManualConfirmationOptions(
        resolution: ResolvedClassroomLocation,
        buildings: List<Building>,
        campusContext: String? = null
    ): ManualBuildingConfirmationOptions {
        val inlineCampus = detectExplicitInlineCampus(resolution.rawRoom)
        val effectiveCampus = inlineCampus ?: normalizeCampusKey(campusContext)
        val sameCampusPool = if (effectiveCampus != null) {
            buildings.filter { buildingCampusKey(it) == effectiveCampus }
        } else {
            buildings
        }

        val recommended = (listOfNotNull(resolution.building) + resolution.candidateBuildings)
            .distinctBy { it.id }
            .filter { cand -> effectiveCampus == null || buildingCampusKey(cand) == effectiveCampus }

        val suitableCategories = setOf(
            CampusCategory.TEACHING_BUILDING,
            CampusCategory.LAB,
            CampusCategory.LIBRARY,
            CampusCategory.DORMITORY
        )
        val categoryPriority = mapOf(
            CampusCategory.TEACHING_BUILDING to 0,
            CampusCategory.LAB to 1,
            CampusCategory.LIBRARY to 2,
            CampusCategory.DORMITORY to 3
        )
        val allSuitableSameCampus = sameCampusPool
            .filter { it.category in suitableCategories }
            .sortedBy { categoryPriority[it.category] ?: 9 }

        return ManualBuildingConfirmationOptions(
            recommendedIds = recommended.map { it.id }.toSet(),
            orderedChoices = (recommended + allSuitableSameCampus).distinctBy { it.id }
        )
    }

    /**
     * 将教务原始教室字符串（如 "莲花街校区 1号教学楼1104"、"A座204"、"钟楼201"、"3206"）解析为校区建筑。
     * - 精确匹配已核验带明确校区归属的名称/别名 -> EXACT_VERIFIED
     * - 纯数字编号（如 2201、1104、3206）不足以单独证明校区：
     *   - 有明确校区上下文且该校区存在唯一对应教学楼 -> FUZZY_NEEDS_CONFIRMATION（仅限同校区候选，绝不跨校区跳楼）
     *   - 无校区上下文或与当前校区冲突 -> FUZZY_NEEDS_CONFIRMATION 且 building = null（保持待确认，禁止自动跨校区导航）
     * - 无法识别 -> UNRESOLVED
     */
    @JvmOverloads
    fun resolve(
        rawRoom: String,
        buildings: List<Building>,
        aliases: List<BuildingAlias>,
        campusContext: String? = null
    ): ResolvedClassroomLocation {
        val trimmed = rawRoom.trim()
        if (trimmed.isEmpty()) {
            return ResolvedClassroomLocation(
                rawRoom = rawRoom,
                status = BuildingMatchStatus.UNRESOLVED
            )
        }

        val inlineCampus = detectExplicitInlineCampus(trimmed)
        val effectiveCampus = inlineCampus ?: normalizeCampusKey(campusContext)
        val hasExplicitCampusMetadataInPool = buildings.any { it.campusId.isNotBlank() }
        val distinctCampusesInPool = buildings.map(::buildingCampusKey).toSet()
        val isMultiCampusPool = distinctCampusesInPool.size > 1
        val scopedBuildingPool = if (effectiveCampus != null) {
            buildings.filter { buildingCampusKey(it) == effectiveCampus }
        } else {
            buildings
        }

        val buildingMap = buildings.associateBy { it.id }
        val roomNumberHint = ROOM_NUMBER_REGEX.findAll(trimmed).lastOrNull()?.value

        // 1. 直接匹配建筑全称或简称（严格遵循校区边界；无校区上下文且多校区同名时保持待确认且 building = null）
        val directCandidates = (if (effectiveCampus != null) scopedBuildingPool else buildings).filter { b ->
            trimmed.contains(b.name, ignoreCase = true) ||
                (b.shortName.length >= 2 && trimmed.contains(b.shortName, ignoreCase = true))
        }
        if (directCandidates.isNotEmpty()) {
            val directCampuses = directCandidates.map(::buildingCampusKey).toSet()
            if (inlineCampus == null && effectiveCampus == null && directCampuses.size > 1) {
                return ResolvedClassroomLocation(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    building = null,
                    roomNumberHint = roomNumberHint,
                    confidence = 0.50f,
                    matchedAlias = "跨校区同名楼宇待确认",
                    candidateBuildings = directCandidates.take(4)
                )
            }
            val directBuilding = directCandidates.first()
            return ResolvedClassroomLocation(
                rawRoom = rawRoom,
                status = BuildingMatchStatus.EXACT_VERIFIED,
                building = directBuilding,
                roomNumberHint = roomNumberHint,
                confidence = 1.0f,
                matchedAlias = directBuilding.name
            )
        }

        // 2. 匹配别名表（按置信度与长度降序；纯数字正则别名不得在无校区上下文或异校区下越界生效）
        val sortedAliases = aliases.sortedWith(
            compareByDescending<BuildingAlias> { it.verified }
                .thenByDescending { it.confidence }
                .thenByDescending { it.rawNameOrPattern.length }
        )

        val matchedAliasEntries = mutableListOf<Pair<BuildingAlias, Building>>()
        for (alias in sortedAliases) {
            val pattern = alias.rawNameOrPattern
            val isRegexAlias = pattern.startsWith("^")
            val matched = if (isRegexAlias) {
                Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
            } else {
                trimmed.contains(pattern, ignoreCase = true)
            }
            if (!matched) continue
            val targetBuilding = buildingMap[alias.buildingId] ?: continue
            val targetCampus = buildingCampusKey(targetBuilding)

            // 已知当前校区时，绝不允许跨校区命中另一校区别名
            if (effectiveCampus != null && targetCampus != effectiveCampus) {
                continue
            }
            // 纯数字教室号正则（如 ^3[1-6]\d{2}$）不含校区文字：在全校多校区合并池且缺少明确校区上下文时跳过，交由 Step 3 统一做待确认隔离
            if (isRegexAlias && effectiveCampus == null && isMultiCampusPool) {
                continue
            }
            matchedAliasEntries += alias to targetBuilding
        }

        if (matchedAliasEntries.isNotEmpty()) {
            val distinctMatchedBuildings = matchedAliasEntries.map { it.second }.distinctBy { it.id }
            val distinctMatchedCampuses = distinctMatchedBuildings.map(::buildingCampusKey).toSet()
            if (inlineCampus == null && effectiveCampus == null && distinctMatchedCampuses.size > 1) {
                return ResolvedClassroomLocation(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    building = null,
                    roomNumberHint = roomNumberHint,
                    confidence = 0.50f,
                    matchedAlias = "跨校区楼宇待确认",
                    candidateBuildings = distinctMatchedBuildings.take(4)
                )
            }
            val (bestAlias, targetBuilding) = matchedAliasEntries.first()
            val isPureDigitRegexWithoutInlineCampus = bestAlias.rawNameOrPattern.startsWith("^") && inlineCampus == null
            val status = if (!isPureDigitRegexWithoutInlineCampus && bestAlias.verified && bestAlias.confidence >= 0.85f) {
                BuildingMatchStatus.EXACT_VERIFIED
            } else {
                BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION
            }
            return ResolvedClassroomLocation(
                rawRoom = rawRoom,
                status = status,
                building = targetBuilding,
                roomNumberHint = roomNumberHint,
                confidence = if (isPureDigitRegexWithoutInlineCampus) minOf(bestAlias.confidence, 0.70f) else bestAlias.confidence,
                matchedAlias = bestAlias.rawNameOrPattern,
                candidateBuildings = if (status == BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION) {
                    listOf(targetBuilding) + scopedBuildingPool.filter { it.id != targetBuilding.id }.take(2)
                } else emptyList()
            )
        }

        // 3. 纯四位/五位数字教室号启发式（如 "2201"、"1104"、"3206"、"31204"）：
        //    纯数字无法自证校区！必须结合明确校区上下文且严禁子串误配（如 2号 不得匹配 32号楼）与跨校区跳楼！
        val multiDigitMatch = Regex("^(\\d{1,2})(\\d{3})$").find(trimmed)
        if (multiDigitMatch != null) {
            val buildingNum = multiDigitMatch.groupValues[1]
            val roomSuffix = multiDigitMatch.groupValues[2]
            val allCampusNumberMatches = buildings.filter { matchesExactBuildingNumber(it, buildingNum) }

            if (effectiveCampus != null) {
                val sameCampusMatches = allCampusNumberMatches.filter { buildingCampusKey(it) == effectiveCampus }
                if (sameCampusMatches.size == 1) {
                    val candidate = sameCampusMatches.single()
                    return ResolvedClassroomLocation(
                        rawRoom = rawRoom,
                        status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                        building = candidate,
                        roomNumberHint = roomSuffix,
                        confidence = 0.65f,
                        matchedAlias = "${buildingNum}XXX 同校区编号推断（待确认）",
                        candidateBuildings = listOf(candidate) + scopedBuildingPool.filter { it.id != candidate.id }.take(3)
                    )
                }
                if (sameCampusMatches.size > 1) {
                    return ResolvedClassroomLocation(
                        rawRoom = rawRoom,
                        status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                        building = null,
                        roomNumberHint = roomSuffix,
                        confidence = 0.40f,
                        matchedAlias = "${buildingNum}XXX 多候选待确认",
                        candidateBuildings = sameCampusMatches.take(4)
                    )
                }
                // 当前校区无对应楼号（例如莲花街查 2201、嵩山路查 3206）：若其他校区存在该楼号则保持待确认且 building = null，严禁跨校区自动落点！
                if (allCampusNumberMatches.isNotEmpty()) {
                    return ResolvedClassroomLocation(
                        rawRoom = rawRoom,
                        status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                        building = null,
                        roomNumberHint = roomSuffix,
                        confidence = 0.0f,
                        matchedAlias = "当前校区无${buildingNum}号教学楼（禁止跨校区自动导航，待人工确认）",
                        candidateBuildings = scopedBuildingPool.take(4)
                    )
                }
                return ResolvedClassroomLocation(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.UNRESOLVED,
                    building = null,
                    roomNumberHint = roomSuffix,
                    candidateBuildings = scopedBuildingPool.take(4)
                )
            }

            // 无校区上下文（campusContext == null 且文本无校区词）：多校区合并建筑池严禁凭纯数字猜校区
            if (isMultiCampusPool) {
                if (allCampusNumberMatches.isNotEmpty()) {
                    return ResolvedClassroomLocation(
                        rawRoom = rawRoom,
                        status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                        building = null,
                        roomNumberHint = roomSuffix,
                        confidence = 0.35f,
                        matchedAlias = "${buildingNum}XXX 缺少校区上下文（待确认校区）",
                        candidateBuildings = allCampusNumberMatches.take(4)
                    )
                }
                return ResolvedClassroomLocation(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.UNRESOLVED,
                    building = null,
                    roomNumberHint = roomSuffix,
                    candidateBuildings = buildings.take(4)
                )
            }

            // 兼容仅含单一未标记校区的纯单测数据
            val singlePoolCandidate = allCampusNumberMatches.firstOrNull()
            if (singlePoolCandidate != null) {
                return ResolvedClassroomLocation(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    building = singlePoolCandidate,
                    roomNumberHint = roomSuffix,
                    confidence = 0.55f,
                    matchedAlias = "${buildingNum}XXX 编号推断",
                    candidateBuildings = buildings.take(4)
                )
            }
        }

        return ResolvedClassroomLocation(
            rawRoom = rawRoom,
            status = BuildingMatchStatus.UNRESOLVED,
            building = null,
            roomNumberHint = roomNumberHint,
            candidateBuildings = scopedBuildingPool.take(4)
        )
    }
}
