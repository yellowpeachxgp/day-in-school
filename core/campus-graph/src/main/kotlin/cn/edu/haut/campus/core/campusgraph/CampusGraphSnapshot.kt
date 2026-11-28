package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.PlaceRef
import java.time.LocalDate

data class CampusGraphCatalogItemHit(
    val owner: CampusGraphEntity,
    val item: ServiceCatalogItem,
    val matchedText: String
)

data class CampusGraphSearchResult(
    val entity: CampusGraphEntity,
    val score: Int,
    val matchedText: String
)

data class CampusGraphRoomResolution(
    val rawRoom: String,
    val status: BuildingMatchStatus,
    val room: RoomNode? = null,
    val building: BuildingNode? = null,
    val roomNumberHint: String? = null,
    val confidence: Float = 0f,
    val matchedAlias: String? = null,
    val candidateBuildings: List<BuildingNode> = emptyList(),
    val reason: String? = null
)

/**
 * 不可变校园实体图快照。快照只做内存索引与查询，不负责持久化；
 * 同一稳定 ID 的合并优先级固定为 DATA_PACKAGE > USER_OVERRIDE > CROWD_REPORT。
 */
class CampusGraphSnapshot private constructor(
    entitiesInput: List<CampusGraphEntity>
) {
    val entities: List<CampusGraphEntity> = entitiesInput.toList()

    private val byRef: Map<PlaceRef, CampusGraphEntity>

    init {
        val duplicated = entitiesInput.groupingBy { it.ref }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "Campus Graph 快照包含重复稳定 ID：" + duplicated.joinToString { it.id }
        }
        byRef = entitiesInput.associateBy { it.ref }
    }

    fun entity(ref: PlaceRef): CampusGraphEntity? = byRef[ref]

    fun entitiesOfType(
        type: CampusGraphEntityType,
        campusId: CampusId? = null
    ): List<CampusGraphEntity> = entities.filter { entity ->
        entity.type == type && (campusId == null || entity.ref.campusId == campusId)
    }

    fun campuses(): List<CampusNode> = entities.filterIsInstance<CampusNode>()

    fun campus(campusId: CampusId): CampusNode? = campuses().firstOrNull { it.ref.campusId == campusId }

    fun buildings(campusId: CampusId? = null): List<BuildingNode> =
        entities.filterIsInstance<BuildingNode>().filter { campusId == null || it.ref.campusId == campusId }

    fun floors(buildingRef: PlaceRef? = null): List<FloorNode> =
        entities.filterIsInstance<FloorNode>().filter { buildingRef == null || it.buildingRef == buildingRef }

    fun rooms(buildingRef: PlaceRef? = null, campusId: CampusId? = null): List<RoomNode> =
        entities.filterIsInstance<RoomNode>().filter {
            (buildingRef == null || it.buildingRef == buildingRef) &&
                (campusId == null || it.ref.campusId == campusId)
        }

    fun facilities(
        kind: CampusGraphFacilityKind? = null,
        campusId: CampusId? = null
    ): List<FacilityNode> = entities.filterIsInstance<FacilityNode>().filter {
        (kind == null || it.kind == kind) && (campusId == null || it.ref.campusId == campusId)
    }

    fun entrances(buildingRef: PlaceRef? = null): List<EntranceNode> =
        entities.filterIsInstance<EntranceNode>().filter { buildingRef == null || it.buildingRef == buildingRef }

    fun indoorNodes(buildingRef: PlaceRef? = null): List<IndoorNode> =
        entities.filterIsInstance<IndoorNode>().filter { buildingRef == null || it.buildingRef == buildingRef }

    fun services(
        kind: CampusGraphServiceKind? = null,
        campusId: CampusId? = null
    ): List<ServiceNode> = entities.filterIsInstance<ServiceNode>().filter {
        (kind == null || it.kind == kind) && (campusId == null || it.ref.campusId == campusId)
    }

    fun procedures(campusId: CampusId? = null): List<ProcedureNode> =
        entities.filterIsInstance<ProcedureNode>().filter { campusId == null || it.ref.campusId == campusId }

    /**
     * 查询服务/设施挂载的目录项。空查询返回完整目录并保持迁移顺序；
     * 非空查询匹配名称、类别、摘要与全部属性值，用于承接药品、菜品等 legacy 查询。
     */
    fun searchCatalogItems(
        query: String,
        serviceKind: CampusGraphServiceKind? = null,
        facilityKind: CampusGraphFacilityKind? = null,
        campusId: CampusId? = null
    ): List<CampusGraphCatalogItemHit> {
        val normalized = query.trim().lowercase()
        return entities.flatMap { entity ->
            if (campusId != null && entity.ref.campusId != campusId) return@flatMap emptyList<CampusGraphCatalogItemHit>()
            val ownerMatches = when {
                entity is ServiceNode && serviceKind != null -> entity.kind == serviceKind
                entity is FacilityNode && facilityKind != null -> entity.kind == facilityKind
                serviceKind == null && facilityKind == null -> entity is ServiceNode || entity is FacilityNode
                else -> false
            }
            if (!ownerMatches) return@flatMap emptyList<CampusGraphCatalogItemHit>()
            val items = when (entity) {
                is ServiceNode -> entity.catalogItems
                is FacilityNode -> entity.catalogItems
                else -> emptyList<ServiceCatalogItem>()
            }
            items.mapNotNull { item ->
                if (normalized.isEmpty()) {
                    return@mapNotNull CampusGraphCatalogItemHit(entity, item, item.name)
                }
                val candidates = buildList {
                    add(item.name)
                    item.category?.let(::add)
                    item.summary?.let(::add)
                    addAll(item.attributes.values)
                }
                val matched = candidates.firstOrNull { it.lowercase().contains(normalized) }
                matched?.let { CampusGraphCatalogItemHit(entity, item, it) }
            }
        }
    }

    fun buildingByLegacyId(legacyBuildingId: String, campusId: CampusId? = null): BuildingNode? =
        buildings(campusId).firstOrNull { it.legacyBuildingId == legacyBuildingId }

    /** 名称/别名包含检索；返回稳定排序与命中证据，不做模糊纠错或跨校区猜测。 */
    fun search(
        query: String,
        types: Set<CampusGraphEntityType> = CampusGraphEntityType.entries.toSet(),
        campusId: CampusId? = null
    ): List<CampusGraphSearchResult> {
        val normalized = normalizeText(query)
        if (normalized.isEmpty()) return emptyList()
        return entities.mapNotNull { entity ->
            if (entity.type !in types) return@mapNotNull null
            if (campusId != null && entity.ref.campusId != campusId) return@mapNotNull null
            val candidates = listOf(entity.name) + entity.aliases
            var bestScore = 0
            var bestText = ""
            for (candidate in candidates) {
                val normalizedCandidate = normalizeText(candidate)
                if (normalizedCandidate.isEmpty()) continue
                val score = when {
                    normalizedCandidate == normalized -> 100
                    normalizedCandidate.startsWith(normalized) -> 82
                    normalizedCandidate.contains(normalized) -> 64
                    normalized.startsWith(normalizedCandidate) && normalizedCandidate.length >= 2 -> 42
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score
                    bestText = candidate
                }
            }
            if (bestScore == 0) null else CampusGraphSearchResult(entity, bestScore, bestText)
        }.sortedWith(
            compareByDescending<CampusGraphSearchResult> { it.score }
                .thenBy { it.entity.type.wireId }
                .thenBy { it.entity.ref.id }
        )
    }

    /**
     * 保守解析教务教室文本。纯数字房号在没有校区上下文且图内存在多校区楼宇时绝不自动落点；
     * 该规则与 legacy ClassroomBuildingResolver 的跨校区防跳楼语义保持一致。
     */
    fun resolveRoomText(rawRoom: String, campusId: CampusId? = null): CampusGraphRoomResolution {
        val trimmed = rawRoom.trim()
        if (trimmed.isEmpty()) {
            return CampusGraphRoomResolution(rawRoom, BuildingMatchStatus.UNRESOLVED, reason = "empty-room-text")
        }
        val inlineCampusId = if (campusId == null) detectExplicitInlineCampus(trimmed) else null
        val effectiveCampusId = campusId ?: inlineCampusId
        val allBuildingPool = buildings()
        val buildingPool = buildings(effectiveCampusId)
        val roomPool = rooms(campusId = effectiveCampusId)
        val roomNumberHint = ROOM_NUMBER_REGEX.findAll(trimmed).lastOrNull()?.value
        val multiCampusPool = allBuildingPool.map { it.ref.campusId }.distinct().size > 1
        val pureNumericRoom = Regex("^\\d{4,5}$").matches(trimmed)

        if (!(pureNumericRoom && effectiveCampusId == null && multiCampusPool)) {
            exactRoomMatch(trimmed, roomPool)?.let { matched ->
            return when (matched.size) {
                1 -> {
                    val room = matched.single()
                    CampusGraphRoomResolution(
                        rawRoom = rawRoom,
                        status = BuildingMatchStatus.EXACT_VERIFIED,
                        room = room,
                        building = byRef[room.buildingRef] as? BuildingNode,
                        roomNumberHint = room.roomNumber,
                        confidence = 0.98f,
                        matchedAlias = room.name
                    )
                }
                else -> CampusGraphRoomResolution(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    roomNumberHint = roomNumberHint,
                    confidence = 0.45f,
                    candidateBuildings = matched.mapNotNull { byRef[it.buildingRef] as? BuildingNode }.distinctBy { it.ref }.take(4),
                    reason = "multiple-exact-room-matches"
                )
            }
        }
        }

        val directMatches = directBuildingMatches(trimmed, buildingPool)
        if (directMatches.isNotEmpty()) {
            val directCampuses = directMatches.map { it.ref.campusId }.distinct()
            if (effectiveCampusId == null && directCampuses.size > 1) {
                return CampusGraphRoomResolution(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    building = null,
                    roomNumberHint = roomNumberHint,
                    confidence = 0.50f,
                    matchedAlias = "跨校区同名楼宇待确认",
                    candidateBuildings = directMatches.take(4),
                    reason = "direct-building-cross-campus"
                )
            }
            val bestLength = directMatches.maxOf { it.name.length }
            val bestMatches = directMatches.filter { it.name.length == bestLength }.distinctBy { it.ref }
            if (bestMatches.size > 1) {
                return CampusGraphRoomResolution(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    roomNumberHint = roomNumberHint,
                    confidence = 0.50f,
                    candidateBuildings = bestMatches.take(4),
                    reason = "multiple-direct-building-matches"
                )
            }
            val building = bestMatches.single()
            val room = roomNumberHint?.let { hint -> findRoomInBuilding(hint, building) }
            return CampusGraphRoomResolution(
                rawRoom = rawRoom,
                status = BuildingMatchStatus.EXACT_VERIFIED,
                room = room,
                building = building,
                roomNumberHint = roomNumberHint,
                confidence = 1.0f,
                matchedAlias = building.name,
                reason = if (room == null) "building-matched-room-not-in-graph" else null
            )
        }

        val aliasPool = if (effectiveCampusId == null && multiCampusPool) {
            buildingPool.map { it.copy(legacyAliases = it.legacyAliases.filterNot { alias -> alias.pattern }) }
        } else {
            buildingPool
        }
        val aliasMatches = aliasBuildingMatches(trimmed, aliasPool)
        if (aliasMatches.isNotEmpty()) {
            val aliasCampuses = aliasMatches.map { it.building.ref.campusId }.distinct()
            if (effectiveCampusId == null && aliasCampuses.size > 1) {
                return CampusGraphRoomResolution(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    building = null,
                    roomNumberHint = roomNumberHint,
                    confidence = 0.50f,
                    matchedAlias = "跨校区楼宇别名待确认",
                    candidateBuildings = aliasMatches.map { it.building }.distinctBy { it.ref }.take(4),
                    reason = "alias-cross-campus"
                )
            }
            val bestConfidence = aliasMatches.maxOf { it.alias.confidence }
            val best = aliasMatches.filter { it.alias.confidence == bestConfidence }
            val distinctBuildings = best.map { it.building }.distinctBy { it.ref }
            if (distinctBuildings.size > 1) {
                return CampusGraphRoomResolution(
                    rawRoom = rawRoom,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    roomNumberHint = roomNumberHint,
                    confidence = minOf(bestConfidence, 0.55f),
                    matchedAlias = best.first().alias.value,
                    candidateBuildings = distinctBuildings.take(4),
                    reason = "multiple-alias-buildings"
                )
            }
            val match = best.first()
            val building = match.building
            val room = roomNumberHint?.let { hint -> findRoomInBuilding(hint, building) }
            val exact = !match.alias.pattern && match.alias.verified && match.alias.confidence >= 0.85f
            return CampusGraphRoomResolution(
                rawRoom = rawRoom,
                status = if (exact) BuildingMatchStatus.EXACT_VERIFIED else BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                room = room,
                building = building,
                roomNumberHint = roomNumberHint,
                confidence = if (match.alias.pattern) minOf(match.alias.confidence, 0.70f) else match.alias.confidence,
                matchedAlias = match.alias.value,
                candidateBuildings = if (exact) emptyList() else listOf(building) + buildingPool.filter { it.ref != building.ref }.take(2),
                reason = if (exact) null else "alias-needs-confirmation"
            )
        }

        numericInference(trimmed, buildingPool, allBuildingPool, multiCampusPool, effectiveCampusId)?.let { inferred ->
            return inferred.copy(rawRoom = rawRoom, roomNumberHint = roomNumberHint)
        }

        return CampusGraphRoomResolution(
            rawRoom = rawRoom,
            status = BuildingMatchStatus.UNRESOLVED,
            roomNumberHint = roomNumberHint,
            candidateBuildings = buildingPool.take(4),
            reason = "no-building-or-room-match"
        )
    }

    private fun detectExplicitInlineCampus(trimmed: String): CampusId? {
        val normalizedInput = normalizeText(trimmed)
        if (normalizedInput.isEmpty()) return null
        return campuses().firstOrNull { campus ->
            (listOf(campus.name) + campus.aliases).any { term ->
                term.length >= 2 && normalizedInput.contains(normalizeText(term))
            }
        }?.ref?.campusId
    }

    private fun exactRoomMatch(trimmed: String, roomPool: List<RoomNode>): List<RoomNode>? {
        val normalized = normalizeText(trimmed)
        val exact = roomPool.filter {
            normalizeText(it.roomNumber) == normalized || normalizeText(it.name) == normalized
        }
        return exact.ifEmpty { null }
    }

    private fun directBuildingMatches(trimmed: String, pool: List<BuildingNode>): List<BuildingNode> {
        return pool.filter { building ->
            trimmed.contains(building.name, ignoreCase = true) ||
                (building.shortName.length >= 2 && trimmed.contains(building.shortName, ignoreCase = true)) ||
                building.aliases.any { it.length >= 2 && trimmed.contains(it, ignoreCase = true) }
        }.sortedByDescending { it.name.length }
    }

    private data class AliasMatch(val alias: CampusGraphAlias, val building: BuildingNode)

    private fun aliasBuildingMatches(trimmed: String, pool: List<BuildingNode>): List<AliasMatch> {
        val matches = mutableListOf<AliasMatch>()
        for (building in pool) {
            for (alias in building.legacyAliases.sortedWith(compareByDescending<CampusGraphAlias> { it.verified }.thenByDescending { it.confidence }.thenByDescending { it.value.length })) {
                val matched = if (alias.pattern) {
                    try {
                        Regex(alias.value, RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                } else {
                    trimmed.contains(alias.value, ignoreCase = true)
                }
                if (matched) matches += AliasMatch(alias, building)
            }
        }
        return matches
    }

    private fun findRoomInBuilding(hint: String, building: BuildingNode): RoomNode? {
        val normalized = normalizeText(hint)
        return rooms(buildingRef = building.ref).firstOrNull {
            normalizeText(it.roomNumber) == normalized || normalizeText(it.name) == normalized
        }
    }

    private fun numericInference(
        trimmed: String,
        scopedPool: List<BuildingNode>,
        allPool: List<BuildingNode>,
        multiCampusPool: Boolean,
        campusId: CampusId?
    ): CampusGraphRoomResolution? {
        val match = Regex("^(\\d{1,2})(\\d{3})$").find(trimmed) ?: return null
        val buildingNum = match.groupValues[1]
        val roomSuffix = match.groupValues[2]
        val allMatches = allPool.filter { matchesExactBuildingNumber(it, buildingNum) }
        if (allMatches.isEmpty()) return null

        if (campusId != null) {
            val sameCampusMatches = allMatches.filter { it.ref.campusId == campusId }
            when {
                sameCampusMatches.size == 1 -> {
                    val building = sameCampusMatches.single()
                    val room = findRoomInBuilding(roomSuffix, building)
                    return CampusGraphRoomResolution(
                        rawRoom = trimmed,
                        status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                        room = room,
                        building = building,
                        roomNumberHint = roomSuffix,
                        confidence = 0.65f,
                        matchedAlias = buildingNum + "XXX 同校区编号推断（待确认）",
                        candidateBuildings = listOf(building) + scopedPool.filter { it.ref != building.ref }.take(3),
                        reason = "numeric-room-inference"
                    )
                }
                sameCampusMatches.size > 1 -> {
                    return CampusGraphRoomResolution(
                        rawRoom = trimmed,
                        status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                        building = null,
                        roomNumberHint = roomSuffix,
                        confidence = 0.40f,
                        matchedAlias = buildingNum + "XXX 多候选待确认",
                        candidateBuildings = sameCampusMatches.take(4),
                        reason = "numeric-room-multiple-candidates"
                    )
                }
                else -> {
                    return CampusGraphRoomResolution(
                        rawRoom = trimmed,
                        status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                        building = null,
                        roomNumberHint = roomSuffix,
                        confidence = 0.0f,
                        matchedAlias = "当前校区无" + buildingNum + "号教学楼（禁止跨校区自动导航，待人工确认）",
                        candidateBuildings = scopedPool.take(4),
                        reason = "numeric-room-not-in-current-campus"
                    )
                }
            }
        }

        if (multiCampusPool) {
            return CampusGraphRoomResolution(
                rawRoom = trimmed,
                status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                building = null,
                roomNumberHint = roomSuffix,
                confidence = 0.35f,
                matchedAlias = buildingNum + "XXX 缺少校区上下文（待确认校区）",
                candidateBuildings = allMatches.take(4),
                reason = "numeric-room-needs-campus-context"
            )
        }

        return when (allMatches.size) {
            1 -> {
                val building = allMatches.single()
                val room = findRoomInBuilding(roomSuffix, building)
                CampusGraphRoomResolution(
                    rawRoom = trimmed,
                    status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                    room = room,
                    building = building,
                    roomNumberHint = roomSuffix,
                    confidence = 0.55f,
                    matchedAlias = buildingNum + "XXX 编号推断",
                    candidateBuildings = scopedPool.take(4),
                    reason = "numeric-room-inference-single-campus-pool"
                )
            }
            else -> CampusGraphRoomResolution(
                rawRoom = trimmed,
                status = BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                building = null,
                roomNumberHint = roomSuffix,
                confidence = 0.40f,
                matchedAlias = buildingNum + "XXX 多候选待确认",
                candidateBuildings = allMatches.take(4),
                reason = "numeric-room-multiple-candidates"
            )
        }
    }

    private fun matchesExactBuildingNumber(building: BuildingNode, buildingNum: String): Boolean {
        val normalizedNum = buildingNum.trimStart('0').ifEmpty { buildingNum }
        val exactNumRegex = Regex("(?<!\\d)0?" + Regex.escape(normalizedNum) + "(号(教学)?楼|教)")
        return exactNumRegex.containsMatchIn(building.name) || exactNumRegex.containsMatchIn(building.shortName)
    }

    companion object {
        private val ROOM_NUMBER_REGEX = Regex("([A-Za-z]?\\d{3,5})")

        fun of(entities: List<CampusGraphEntity>): CampusGraphSnapshot = CampusGraphSnapshot(entities)

        /**
         * 合并三层来源。高优先级覆盖低优先级；同优先级后到的修订覆盖先到版本。
         * 输入层必须与其声明的 sourceLevel 一致；众包上报只在 reviewBy 当天及之前有效，
         * 过期上报不会进入快照，避免临时纠错沉淀成永久事实。
         */
        fun merge(
            dataPackageEntities: List<CampusGraphEntity>,
            userOverrideEntities: List<CampusGraphEntity> = emptyList(),
            crowdEntities: List<CampusGraphEntity> = emptyList(),
            now: LocalDate = LocalDate.now()
        ): CampusGraphSnapshot {
            val merged = LinkedHashMap<PlaceRef, CampusGraphEntity>()
            fun absorb(layer: List<CampusGraphEntity>, expectedLevel: CampusGraphSourceLevel) {
                for (entity in layer) {
                    require(entity.provenance.sourceLevel == expectedLevel) {
                        "合并输入层与 sourceLevel 不一致：" + entity.ref.id
                    }
                    val current = merged[entity.ref]
                    if (current == null || entity.provenance.sourceLevel.priority >= current.provenance.sourceLevel.priority) {
                        merged[entity.ref] = entity
                    }
                }
            }
            val activeCrowdEntities = crowdEntities.filter { entity ->
                entity.provenance.reviewBy?.let { reviewBy -> !reviewBy.isBefore(now) } == true
            }
            absorb(dataPackageEntities, CampusGraphSourceLevel.DATA_PACKAGE)
            absorb(userOverrideEntities, CampusGraphSourceLevel.USER_OVERRIDE)
            absorb(activeCrowdEntities, CampusGraphSourceLevel.CROWD_REPORT)
            return CampusGraphSnapshot(merged.values.toList())
        }

        private fun normalizeText(raw: String): String =
            raw.trim().lowercase().filter { it.isLetterOrDigit() }
    }
}
