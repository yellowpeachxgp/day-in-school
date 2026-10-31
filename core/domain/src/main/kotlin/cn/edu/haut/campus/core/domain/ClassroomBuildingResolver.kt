package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.ResolvedClassroomLocation

object ClassroomBuildingResolver {

    private val ROOM_NUMBER_REGEX = Regex("([A-Za-z]?\\d{3,4})")

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
        val directBuilding = buildings.firstOrNull { b ->
            trimmed.contains(b.name, ignoreCase = true) ||
                (b.shortName.length >= 2 && trimmed.contains(b.shortName, ignoreCase = true))
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
                trimmed.contains(pattern, ignoreCase = true)
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

        // 3. 常见纯四位数字教室号启发式（如 "3206" 可能指 3 号教学楼 206，但未经验证，必须标记为模糊待确认）
        val fourDigitMatch = Regex("^(\\d)(\\d{3})$").find(trimmed)
        if (fourDigitMatch != null) {
            val buildingNum = fourDigitMatch.groupValues[1]
            val candidate = buildings.firstOrNull {
                it.name.contains("${buildingNum}号") || it.shortName.contains("${buildingNum}号")
            }
            if (candidate != null) {
                return ResolvedClassroomLocation(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    building = candidate,
                    roomNumberHint = fourDigitMatch.groupValues[2],
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
