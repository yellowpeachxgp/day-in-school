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
        validateReferenceConsistency()
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
     * 查询服务/设施挂载的目录项。空查询返回完整目录并保持迁移顺序。
     * 非空查询只命中 legacy 兼容的显式字段集：药品为名称/规格/剂型/厂家/批准文号，
     * 菜品为名称/档口位置/标签/示例评价；价格、贡献者、核验状态、评分计数等证据或
     * 元数据字段绝不进入自由文本命中。尚未声明 legacy 字段集的目录项只按名称查询。
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
                val candidates = legacyCatalogSearchCandidates(entity, item)
                val matched = candidates.firstOrNull { it.lowercase().contains(normalized) }
                matched?.let { CampusGraphCatalogItemHit(entity, item, it) }
            }
        }
    }

    private fun legacyCatalogSearchCandidates(
        entity: CampusGraphEntity,
        item: ServiceCatalogItem
    ): List<String> {
        fun attributeValues(keys: List<String>): List<String> = keys.mapNotNull { item.attributes[it] }
        return when {
            entity is ServiceNode && entity.kind == CampusGraphServiceKind.PHARMACY ->
                listOf(item.name) + attributeValues(PHARMACY_LEGACY_SEARCH_ATTRIBUTE_KEYS)

            entity is FacilityNode && entity.kind == CampusGraphFacilityKind.DINING ->
                listOfNotNull(item.name, item.summary) + attributeValues(DINING_LEGACY_SEARCH_ATTRIBUTE_KEYS)

            else -> listOf(item.name)
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
     * 保守解析教务教室文本。纯数字房号即使有校区上下文也不按裸房号精确落点，
     * 在没有校区上下文且图内存在多校区楼宇时更不自动落点；该规则与 legacy
     * ClassroomBuildingResolver 的同校区待确认、跨校区防跳楼语义保持一致。
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

        // 裸数字房号即使带校区上下文也不走 exact-room 快捷路径：房间→楼宇关联可能来自
        // verifiedAt=null 的自习室候选名录，不能用楼宇本体核验反推关联可信；后续别名/编号
        // 推断与 legacy 一致，最高只给 FUZZY_NEEDS_CONFIRMATION。
        if (!pureNumericRoom) {
            exactRoomMatch(trimmed, roomPool)?.let { matched ->
                return when (matched.size) {
                    1 -> {
                        val room = matched.single()
                        val building = byRef[room.buildingRef] as? BuildingNode
                        // 非纯数字的精确房间名（如嵩山1教101）仍以楼宇落点核验判定；
                        // W111 这类挂在未核验楼宇上的房间不得冒充 EXACT_VERIFIED。
                        val verified = building?.provenance?.verifiedAt != null
                        CampusGraphRoomResolution(
                            rawRoom = rawRoom,
                            status = if (verified) BuildingMatchStatus.EXACT_VERIFIED else BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                            room = room,
                            building = building,
                            roomNumberHint = room.roomNumber,
                            confidence = if (verified) 0.98f else 0.68f,
                            matchedAlias = room.name,
                            candidateBuildings = if (verified) emptyList() else listOfNotNull(building),
                            reason = if (verified) null else "room-needs-verification"
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
            val verified = building.provenance.verifiedAt != null
            return CampusGraphRoomResolution(
                rawRoom = rawRoom,
                status = if (verified) BuildingMatchStatus.EXACT_VERIFIED else BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION,
                room = room,
                building = building,
                roomNumberHint = roomNumberHint,
                confidence = if (verified) 1.0f else 0.70f,
                matchedAlias = building.name,
                candidateBuildings = if (verified) {
                    emptyList()
                } else {
                    listOf(building) + buildingPool.filter { it.ref != building.ref }.take(2)
                },
                reason = when {
                    !verified -> "direct-building-needs-verification"
                    room == null -> "building-matched-room-not-in-graph"
                    else -> null
                }
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
            val exact = !match.alias.pattern && match.alias.verified &&
                building.provenance.verifiedAt != null && match.alias.confidence >= 0.85f
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

    private fun validateReferenceConsistency() {
        entities.filterIsInstance<RoomNode>().forEach { room ->
            validateLocationConsistency(room.ref, room.buildingRef, room.floorRef, null)
        }
        entities.filterIsInstance<IndoorNode>().forEach { node ->
            validateLocationConsistency(node.ref, node.buildingRef, node.floorRef, node.roomRef)
            (byRef[node.parent] as? EntranceNode)?.let { entrance ->
                require(entrance.buildingRef == node.buildingRef) {
                    "室内节点入口 parent 与所属楼宇不一致：" + node.ref.id
                }
            }
        }
        entities.filterIsInstance<FacilityNode>().forEach { facility ->
            validateLocationConsistency(facility.ref, facility.buildingRef, facility.floorRef, facility.roomRef)
        }
        entities.filterIsInstance<ServiceNode>().forEach { service ->
            val expectedBuilding = service.buildingRef ?: return@forEach
            val parent = requireNotNull(service.parent) { "服务 parent 不能为空：" + service.ref.id }
            val parentBuilding = buildingContextOf(parent)
            if (parentBuilding != null) {
                require(parentBuilding == expectedBuilding) {
                    "服务 parent 层级与 buildingRef 不一致：" + service.ref.id
                }
            }
        }
    }

    private fun validateLocationConsistency(
        owner: PlaceRef,
        buildingRef: PlaceRef?,
        floorRef: PlaceRef?,
        roomRef: PlaceRef?
    ) {
        if (floorRef != null) {
            (byRef[floorRef] as? FloorNode)?.let { floor ->
                if (buildingRef != null) {
                    require(floor.buildingRef == buildingRef) {
                        "楼层引用与所属楼宇不一致：" + owner.id
                    }
                }
            }
        }
        if (roomRef != null) {
            (byRef[roomRef] as? RoomNode)?.let { room ->
                if (buildingRef != null) {
                    require(room.buildingRef == buildingRef) {
                        "房间引用与所属楼宇不一致：" + owner.id
                    }
                }
                if (floorRef != null) {
                    require(room.floorRef == floorRef) {
                        "房间引用与所属楼层不一致：" + owner.id
                    }
                }
            }
        }
    }

    private fun buildingContextOf(ref: PlaceRef?, visiting: Set<PlaceRef> = emptySet()): PlaceRef? {
        if (ref == null || ref in visiting) return null
        val nextVisiting = visiting + ref
        return when (val entity = byRef[ref]) {
            is BuildingNode -> entity.ref
            is FloorNode -> entity.buildingRef
            is RoomNode -> entity.buildingRef
            is EntranceNode -> entity.buildingRef
            is IndoorNode -> entity.buildingRef
            is FacilityNode -> entity.buildingRef
                ?: entity.roomRef?.let { buildingContextOf(it, nextVisiting) }
                ?: entity.floorRef?.let { buildingContextOf(it, nextVisiting) }
            is ServiceNode -> entity.buildingRef ?: buildingContextOf(entity.parent, nextVisiting)
            else -> null
        }
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
        private val PHARMACY_LEGACY_SEARCH_ATTRIBUTE_KEYS = listOf(
            "specification",
            "dosageForm",
            "manufacturer",
            "approvalNumber"
        )
        private val DINING_LEGACY_SEARCH_ATTRIBUTE_KEYS = listOf(
            "stallLocation",
            "tags"
        )

        fun of(entities: List<CampusGraphEntity>): CampusGraphSnapshot = CampusGraphSnapshot(entities)

        /**
         * 合并三层来源。高优先级覆盖低优先级；同优先级后到的修订覆盖先到版本。
         * 合并粒度是整个实体而非字段级合并：高优先级同稳定 ID 实体完整替换低优先级实体，
         * 不会从低优先级实体回填 location、aliases、attributes、catalogItems 等缺失字段。
         * 本 API 不含 tombstone/删除标记语义；某层缺少实体只表示该层没有覆盖，不能表达
         * “删除或隐藏低优先级实体”。输入层必须与其声明的 sourceLevel 一致；众包上报只在
         * reviewBy 当天及之前有效，过期上报不会进入快照，避免临时纠错沉淀成永久事实。
         */
        fun merge(
            dataPackageEntities: List<CampusGraphEntity>,
            userOverrideEntities: List<CampusGraphEntity> = emptyList(),
            crowdEntities: List<CampusGraphEntity> = emptyList(),
            now: LocalDate = LocalDate.now()
        ): CampusGraphSnapshot {
            fun requireLayer(layer: List<CampusGraphEntity>, expectedLevel: CampusGraphSourceLevel) {
                for (entity in layer) {
                    require(entity.provenance.sourceLevel == expectedLevel) {
                        "合并输入层与 sourceLevel 不一致：" + entity.ref.id
                    }
                }
            }
            // 层级错配是输入错误，必须先于众包过期过滤显式拒绝；
            // 否则错层且无/过期 reviewBy 的实体会被静默丢弃，破坏 fail-closed 语义。
            requireLayer(dataPackageEntities, CampusGraphSourceLevel.DATA_PACKAGE)
            requireLayer(userOverrideEntities, CampusGraphSourceLevel.USER_OVERRIDE)
            requireLayer(crowdEntities, CampusGraphSourceLevel.CROWD_REPORT)

            val merged = LinkedHashMap<PlaceRef, CampusGraphEntity>()
            fun absorb(layer: List<CampusGraphEntity>) {
                for (entity in layer) {
                    val current = merged[entity.ref]
                    if (current == null || entity.provenance.sourceLevel.priority >= current.provenance.sourceLevel.priority) {
                        merged[entity.ref] = entity
                    }
                }
            }
            val activeCrowdEntities = crowdEntities.filter { entity ->
                entity.provenance.reviewBy?.let { reviewBy -> !reviewBy.isBefore(now) } == true
            }
            absorb(dataPackageEntities)
            absorb(userOverrideEntities)
            absorb(activeCrowdEntities)
            return CampusGraphSnapshot(merged.values.toList())
        }

        private fun normalizeText(raw: String): String =
            raw.trim().lowercase().filter { it.isLetterOrDigit() }
    }
}
