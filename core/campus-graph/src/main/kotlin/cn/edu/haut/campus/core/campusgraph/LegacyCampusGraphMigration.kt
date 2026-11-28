package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.domain.CampusDiningRecommender
import cn.edu.haut.campus.core.domain.CampusHospitalPharmacyCatalog
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import java.time.LocalDate

/**
 * 把 W1-07 之前散落在 domain/database/model 的静态校园目录迁成统一 Campus Graph。
 *
 * 该对象只做 deterministic transformation，不访问网络、文件、Room 或 Android API；
 * 迁移结果可作为宿主内置 DATA_PACKAGE 基线，与后续用户覆盖/众包上报按快照优先级合并。
 */
object LegacyCampusGraphMigration {
    val HAUT_SCHOOL_ID: SchoolId = SchoolId("haut")
    val LIANHUAJIE_CAMPUS_ID: CampusId = CampusId("lianhuajie")
    val SONGSHANLU_CAMPUS_ID: CampusId = CampusId("songshanlu")

    private const val BUILDING_SEED_SOURCE = "core/database/HautMockSeedData.kt"
    private const val RESOLVER_SOURCE = "core/domain/ClassroomBuildingResolver.kt"
    private const val STUDY_ROOM_SOURCE = "core/domain/CampusStudyRoomCatalog.kt"
    private const val DINING_SOURCE = "core/domain/CampusDiningRecommender.kt"
    private const val PHARMACY_SOURCE = "core/domain/CampusHospitalPharmacyCatalog.kt"
    private const val CLUB_SOURCE = "core/model/PresetCampusCommunityCatalog.kt"

    fun migrateHautLegacySnapshot(): CampusGraphSnapshot {
        val entities = mutableListOf<CampusGraphEntity>()
        val buildingsByLegacyId = mutableMapOf<String, BuildingNode>()
        val floorsByKey = mutableMapOf<String, FloorNode>()
        val clubVenueRoomsByKey = mutableMapOf<String, RoomNode>()

        val lianhuajieCampusRef = campusRef(LIANHUAJIE_CAMPUS_ID)
        val songshanluCampusRef = campusRef(SONGSHANLU_CAMPUS_ID)
        entities += CampusNode(
            ref = lianhuajieCampusRef,
            name = "莲花街校区",
            aliases = listOf("莲花街", "长椿路校区"),
            polygon = ClassroomBuildingResolver.HAUT_LIANHUAJIE_POLYGON,
            provenance = provenance(
                campusId = LIANHUAJIE_CAMPUS_ID,
                sourceSystem = RESOLVER_SOURCE,
                sourceRecordId = "HAUT_LIANHUAJIE_POLYGON",
                verifiedAt = null,
                sourceReference = "legacy 校区电子区划多边形迁移",
                note = "多边形来自既有校区识别数据；迁移不追加新的官方核验声明",
                completeness = SnapshotCompleteness.PARTIAL
            ),
            attributes = mapOf("campusKey" to "lianhuajie")
        )
        entities += CampusNode(
            ref = songshanluCampusRef,
            name = "嵩山路校区",
            aliases = listOf("嵩山路", "嵩山", "嵩山校区", "齐礼阎"),
            polygon = ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON,
            provenance = provenance(
                campusId = SONGSHANLU_CAMPUS_ID,
                sourceSystem = RESOLVER_SOURCE,
                sourceRecordId = "HAUT_SONGSHANLU_POLYGON",
                verifiedAt = null,
                sourceReference = "legacy 嵩山路校区电子区划多边形迁移",
                note = "多边形来自既有校区识别数据；迁移不追加新的官方核验声明",
                completeness = SnapshotCompleteness.PARTIAL
            ),
            attributes = mapOf("campusKey" to "songshanlu")
        )

        for (seed in LegacyHautLianhuajieBuildingSeed.buildings) {
            val aliases = LegacyHautLianhuajieBuildingSeed.aliases
                .filter { it.buildingId == seed.id }
                .map { CampusGraphAlias(it.value, it.confidence, it.verified, pattern = it.value.startsWith("^")) }
            val building = migrateBuilding(
                campusRef = lianhuajieCampusRef,
                legacyId = seed.id,
                name = seed.name,
                shortName = seed.shortName,
                latitude = seed.latitude,
                longitude = seed.longitude,
                category = seed.category,
                entrances = seed.entrances,
                description = seed.description,
                verifiedAt = seed.verifiedAt,
                legacyAliases = aliases,
                sourceSystem = BUILDING_SEED_SOURCE,
                coordinatePrecision = "official-gis-centroid",
                entities = entities
            )
            buildingsByLegacyId[seed.id] = building
        }

        for (legacy in ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS) {
            val building = migrateDomainBuilding(
                legacy = legacy,
                aliases = ClassroomBuildingResolver.HAUT_SONGSHANLU_ALIASES,
                campusRef = songshanluCampusRef,
                entities = entities
            )
            buildingsByLegacyId[legacy.id] = building
        }

        migrateStudyRooms(
            entities = entities,
            buildingsByLegacyId = buildingsByLegacyId,
            floorsByKey = floorsByKey,
            campusId = LIANHUAJIE_CAMPUS_ID,
            campusRef = lianhuajieCampusRef,
            roomsMap = CampusStudyRoomCatalog.LIANHUAJIE_STUDY_ROOMS_MAP,
            provenanceInfo = CampusStudyRoomCatalog.LIANHUAJIE_CATALOG_PROVENANCE
        )
        migrateStudyRooms(
            entities = entities,
            buildingsByLegacyId = buildingsByLegacyId,
            floorsByKey = floorsByKey,
            campusId = SONGSHANLU_CAMPUS_ID,
            campusRef = songshanluCampusRef,
            roomsMap = CampusStudyRoomCatalog.SONGSHANLU_STUDY_ROOMS_MAP,
            provenanceInfo = CampusStudyRoomCatalog.SONGSHANLU_CATALOG_PROVENANCE
        )

        migrateDiningFacilities(entities, buildingsByLegacyId)
        migrateHospitalPharmacyService(entities, buildingsByLegacyId)
        migrateClubVenues(
            entities = entities,
            buildingsByLegacyId = buildingsByLegacyId,
            clubVenueRoomsByKey = clubVenueRoomsByKey,
            lianhuajieCampusRef = lianhuajieCampusRef,
            songshanluCampusRef = songshanluCampusRef
        )

        return CampusGraphSnapshot.of(entities)
    }

    private fun campusRef(campusId: CampusId): PlaceRef =
        CampusGraphIds.build(HAUT_SCHOOL_ID, campusId, CampusGraphEntityType.CAMPUS, campusId.value)

    private fun graphRef(campusId: CampusId, type: CampusGraphEntityType, slug: String): PlaceRef =
        CampusGraphIds.build(HAUT_SCHOOL_ID, campusId, type, slug)

    /**
     * legacy 入口没有独立稳定标识，唯一可用的事实是方向/位置标签。
     * 因而稳定 key 只由楼宇 legacy ID 与规范化标签组成，绝不混入列表序号；
     * 同一楼宇下重复或规范化后冲突的标签必须显式拒绝，等待上游补充可区分的语义标签。
     */
    internal fun stableEntranceKeysByLabel(
        legacyBuildingId: String,
        entrances: List<String>
    ): LinkedHashMap<String, String> {
        val duplicatedLabels = entrances.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicatedLabels.isEmpty()) {
            "同一楼宇的 legacy 入口标签重复，无法生成无位置序号稳定 ID：" + duplicatedLabels.joinToString()
        }
        val result = linkedMapOf<String, String>()
        for (entrance in entrances) {
            val semanticLabel = CampusGraphIds.normalizeSlug(entrance)
            val stableKey = legacyBuildingId + "-entrance-" + semanticLabel
            require(result.values.none { it == stableKey }) {
                "同一楼宇的 legacy 入口标签规范化后冲突，无法生成稳定 ID：" + entrance
            }
            result[entrance] = stableKey
        }
        return result
    }

    private fun provenance(
        campusId: CampusId,
        sourceSystem: String,
        sourceRecordId: String?,
        verifiedAt: LocalDate?,
        sourceReference: String,
        note: String?,
        completeness: SnapshotCompleteness = SnapshotCompleteness.COMPLETE,
        sourceLevel: CampusGraphSourceLevel = CampusGraphSourceLevel.DATA_PACKAGE
    ): CampusGraphProvenance = CampusGraphProvenance(
        sourceLevel = sourceLevel,
        provenance = CampusGraphProvenance.legacySnapshot(
            schoolId = HAUT_SCHOOL_ID,
            providerId = "haut-legacy-campus-catalog",
            sourceSystem = sourceSystem,
            sourceRecordId = sourceRecordId,
            contentRevision = "w1-07-migration"
        ).copy(completeness = completeness),
        sourceReference = sourceReference,
        verifiedAt = verifiedAt,
        note = note
    )

    private fun migrateBuilding(
        campusRef: PlaceRef,
        legacyId: String,
        name: String,
        shortName: String,
        latitude: Double?,
        longitude: Double?,
        category: CampusCategory,
        entrances: List<String>,
        description: String,
        verifiedAt: LocalDate?,
        legacyAliases: List<CampusGraphAlias>,
        sourceSystem: String,
        coordinatePrecision: String,
        entities: MutableList<CampusGraphEntity>
    ): BuildingNode {
        val campusId = campusRef.campusId
        val buildingRef = graphRef(campusId, CampusGraphEntityType.BUILDING, legacyId)
        val provenance = provenance(
            campusId = campusId,
            sourceSystem = sourceSystem,
            sourceRecordId = legacyId,
            verifiedAt = verifiedAt,
            sourceReference = "legacy 楼宇目录迁移",
            note = "迁移保留原核验日期；不将宿主内置目录等同于校方重新核验"
        )
        val location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
        val building = BuildingNode(
            ref = buildingRef,
            name = name,
            shortName = shortName,
            legacyBuildingId = legacyId,
            category = category,
            parent = campusRef,
            location = location,
            // aliases 是直接解析可使用的“已核验显示别名”；verified=false 的 legacy 别名
            // 只保留在 legacyAliases 中进入模糊候选，绝不能在这里被提升为精确匹配证据。
            aliases = (listOf(shortName) + legacyAliases.filter { !it.pattern && it.verified }.map { it.value })
                .filter { it.isNotBlank() }
                .distinct(),
            legacyAliases = legacyAliases,
            provenance = provenance,
            attributes = mapOf(
                "description" to description,
                "coordinatePrecision" to coordinatePrecision,
                "migrationSource" to sourceSystem
            )
        )
        entities += building
        for ((entrance, stableKey) in stableEntranceKeysByLabel(legacyId, entrances)) {
            val entranceRef = graphRef(
                campusId,
                CampusGraphEntityType.ENTRANCE,
                stableKey
            )
            entities += EntranceNode(
                ref = entranceRef,
                name = name + "·" + entrance,
                buildingRef = buildingRef,
                label = entrance,
                location = location,
                provenance = provenance.copy(
                    provenance = provenance.provenance.copy(
                        sourceRecordId = legacyId + "/entrance/" + CampusGraphIds.normalizeSlug(entrance)
                    )
                ),
                attributes = mapOf(
                    "coordinatePrecision" to "building-center",
                    "migrationSource" to sourceSystem
                )
            )
        }
        return building
    }

    private fun migrateDomainBuilding(
        legacy: Building,
        aliases: List<BuildingAlias>,
        campusRef: PlaceRef,
        entities: MutableList<CampusGraphEntity>
    ): BuildingNode = migrateBuilding(
        campusRef = campusRef,
        legacyId = legacy.id,
        name = legacy.name,
        shortName = legacy.shortName,
        latitude = legacy.latitude,
        longitude = legacy.longitude,
        category = legacy.category,
        entrances = legacy.entrances,
        description = legacy.description,
        verifiedAt = legacy.verifiedAt,
        legacyAliases = aliases.filter { it.buildingId == legacy.id }.map {
            CampusGraphAlias(it.rawNameOrPattern, it.confidence, it.verified, pattern = it.rawNameOrPattern.startsWith("^"))
        },
        sourceSystem = RESOLVER_SOURCE,
        coordinatePrecision = "campus-block-approximate",
        entities = entities
    )

    private fun migrateStudyRooms(
        entities: MutableList<CampusGraphEntity>,
        buildingsByLegacyId: Map<String, BuildingNode>,
        floorsByKey: MutableMap<String, FloorNode>,
        campusId: CampusId,
        campusRef: PlaceRef,
        roomsMap: Map<String, List<String>>,
        provenanceInfo: cn.edu.haut.campus.core.domain.CampusStudyRoomCatalogProvenance
    ) {
        for ((buildingDisplayName, roomNames) in roomsMap) {
            val building = findBuildingByDisplayName(buildingDisplayName, buildingsByLegacyId.values, campusId)
                ?: error("自习室名录楼宇未迁入 Campus Graph：" + buildingDisplayName)
            for (roomName in roomNames) {
                val level = inferFloorIndex(roomName)
                val floor = level?.let {
                    ensureFloor(
                        entities = entities,
                        floorsByKey = floorsByKey,
                        building = building,
                        levelIndex = it,
                        sourceRecordId = roomName,
                        provenanceInfo = provenanceInfo
                    )
                }
                val roomRef = graphRef(
                    campusId,
                    CampusGraphEntityType.ROOM,
                    building.legacyBuildingId + "-" + roomName
                )
                val roomProvenance = provenance(
                    campusId = campusId,
                    sourceSystem = STUDY_ROOM_SOURCE,
                    sourceRecordId = building.legacyBuildingId + "/" + roomName,
                    verifiedAt = provenanceInfo.verifiedAt,
                    sourceReference = provenanceInfo.sourceReference ?: "legacy 自习室候选名录",
                    note = provenanceInfo.note,
                    completeness = SnapshotCompleteness.PARTIAL
                )
                entities += RoomNode(
                    ref = roomRef,
                    name = roomName,
                    buildingRef = building.ref,
                    floorRef = floor?.ref,
                    roomNumber = roomName,
                    roomKind = CampusGraphRoomKind.CLASSROOM,
                    parent = floor?.ref ?: building.ref,
                    location = building.location,
                    provenance = roomProvenance,
                    attributes = mapOf(
                        "studyCandidate" to "true",
                        "availabilityMeaning" to provenanceInfo.availabilityMeaning.label,
                        "catalogCampusKey" to provenanceInfo.campusKey,
                        "coordinatePrecision" to "building-center",
                        "migrationSource" to STUDY_ROOM_SOURCE
                    )
                )
            }
        }
    }

    private fun ensureFloor(
        entities: MutableList<CampusGraphEntity>,
        floorsByKey: MutableMap<String, FloorNode>,
        building: BuildingNode,
        levelIndex: Int,
        sourceRecordId: String,
        provenanceInfo: cn.edu.haut.campus.core.domain.CampusStudyRoomCatalogProvenance
    ): FloorNode {
        val key = building.ref.id + "#" + levelIndex
        floorsByKey[key]?.let { return it }
        val campusId = building.ref.campusId
        val floorRef = graphRef(
            campusId,
            CampusGraphEntityType.FLOOR,
            building.legacyBuildingId + "-f" + levelIndex
        )
        val floor = FloorNode(
            ref = floorRef,
            name = building.shortName + levelIndex + "F",
            buildingRef = building.ref,
            levelIndex = levelIndex,
            floorLabel = levelIndex.toString() + "F",
            location = building.location,
            provenance = provenance(
                campusId = campusId,
                sourceSystem = STUDY_ROOM_SOURCE,
                sourceRecordId = sourceRecordId,
                verifiedAt = provenanceInfo.verifiedAt,
                sourceReference = "由自习室房间号推导楼层；" + (provenanceInfo.sourceReference ?: "legacy 自习室候选名录"),
                note = "楼层是房间号规则推导结果，不代表官方楼层平面图",
                completeness = SnapshotCompleteness.PARTIAL
            ),
            attributes = mapOf(
                "floorInference" to "room-number",
                "migrationSource" to STUDY_ROOM_SOURCE
            )
        )
        floorsByKey[key] = floor
        entities += floor
        return floor
    }

    private fun migrateDiningFacilities(
        entities: MutableList<CampusGraphEntity>,
        buildingsByLegacyId: Map<String, BuildingNode>
    ) {
        val dishesByCanteen = CampusDiningRecommender.DEFAULT_DISH_CATALOG.groupBy { it.canteenBuildingId }
        for (profile in CampusDiningRecommender.OPERATING_CANTEENS) {
            val campusId = if (CampusStudyRoomCatalog.isSongshanCampus(profile.campusId)) SONGSHANLU_CAMPUS_ID else LIANHUAJIE_CAMPUS_ID
            val building = buildingsByLegacyId[profile.buildingId]
                ?: error("餐厅楼宇未迁入 Campus Graph：" + profile.buildingId)
            val facilityProvenance = provenance(
                campusId = campusId,
                sourceSystem = DINING_SOURCE,
                sourceRecordId = profile.buildingId,
                verifiedAt = null,
                sourceReference = "legacy 餐厅与菜品目录迁移",
                note = "餐厅菜品是宿主内置参考目录，不代表实时营业、库存或价格",
                completeness = SnapshotCompleteness.PARTIAL
            )
            val facilityRef = graphRef(campusId, CampusGraphEntityType.FACILITY, profile.buildingId + "-dining")
            val catalogItems = dishesByCanteen[profile.buildingId].orEmpty().map { dish ->
                ServiceCatalogItem(
                    id = dish.id,
                    name = dish.dishName,
                    category = "dish",
                    summary = dish.sampleComment,
                    attributes = mapOf(
                        "stallLocation" to dish.stallLocation,
                        "priceYuan" to dish.priceYuan.toString(),
                        "mealSlots" to dish.mealSlots.joinToString(",") { it.name },
                        "baseStars" to dish.baseStars.toString(),
                        "baseReviewCount" to dish.baseReviewCount.toString(),
                        "tags" to dish.defaultTags.joinToString(","),
                        "canteenShortName" to dish.canteenShortName
                    ),
                    provenance = facilityProvenance.copy(
                        provenance = facilityProvenance.provenance.copy(sourceRecordId = dish.id)
                    )
                )
            }
            entities += FacilityNode(
                ref = facilityRef,
                name = profile.name,
                kind = CampusGraphFacilityKind.DINING,
                buildingRef = building.ref,
                parent = building.ref,
                location = GeoPoint(profile.latitude, profile.longitude),
                aliases = listOf(profile.shortName),
                catalogItems = catalogItems,
                provenance = facilityProvenance,
                attributes = mapOf(
                    "areaLabel" to profile.areaLabel,
                    "nearbyBuildingIds" to profile.nearbyBuildingIds.joinToString(","),
                    "brunchDishes" to profile.brunchDishes.joinToString("；"),
                    "lunchDinnerDishes" to profile.lunchDinnerDishes.joinToString("；"),
                    "lateDishes" to profile.lateDishes.joinToString("；"),
                    "migrationSource" to DINING_SOURCE
                )
            )
        }
    }

    private fun migrateHospitalPharmacyService(
        entities: MutableList<CampusGraphEntity>,
        buildingsByLegacyId: Map<String, BuildingNode>
    ) {
        val building = buildingsByLegacyId["b_hospital"] ?: error("校医院楼宇未迁入 Campus Graph")
        val campusId = LIANHUAJIE_CAMPUS_ID
        val serviceProvenance = provenance(
            campusId = campusId,
            sourceSystem = PHARMACY_SOURCE,
            sourceRecordId = "campus-hospital-pharmacy",
            verifiedAt = null,
            sourceReference = "校医院现场价格公示册实拍原图转录（contributor=Lollipop2233，采集日期=2026-06-23）",
            note = "全部药品条目保留原图转录与待核实状态；不代表实时库存、在售状态或医保结算金额",
            completeness = SnapshotCompleteness.PARTIAL
        )
        val catalogItems = CampusHospitalPharmacyCatalog.ITEMS.map { item ->
            ServiceCatalogItem(
                id = item.id,
                name = item.name,
                category = item.category.label,
                summary = item.verificationNote,
                attributes = buildMap {
                    put("specification", item.specification)
                    put("dosageForm", item.dosageForm)
                    put("unit", item.unit)
                    put("disclosedPriceYuan", item.disclosedPriceYuan)
                    put("manufacturer", item.manufacturer)
                    put("approvalNumber", item.approvalNumber)
                    put("sourcePage", item.sourcePage.toString())
                    put("capturedDateIso", item.capturedDateIso)
                    put("contributorHandle", item.contributorHandle)
                    put("contributorGithubUrl", item.contributorGithubUrl)
                    put("verificationStatus", item.verificationStatus)
                    item.verificationNote?.let { put("verificationNote", it) }
                },
                provenance = serviceProvenance.copy(
                    provenance = serviceProvenance.provenance.copy(sourceRecordId = item.id)
                )
            )
        }
        val serviceRef = graphRef(campusId, CampusGraphEntityType.SERVICE, "campus-hospital-pharmacy")
        entities += ServiceNode(
            ref = serviceRef,
            name = "校医院药房",
            kind = CampusGraphServiceKind.PHARMACY,
            buildingRef = building.ref,
            parent = building.ref,
            location = building.location,
            catalogItems = catalogItems,
            provenance = serviceProvenance,
            attributes = buildMap {
                put("policyWechatUrl", CampusHospitalPharmacyCatalog.POLICY_WECHAT_URL)
                put("outpatientReimbursementReference", CampusHospitalPharmacyCatalog.OUTPATIENT_REIMBURSEMENT_REFERENCE_SUMMARY)
                CampusHospitalPharmacyCatalog.MEDICAL_INSURANCE_NOTES.forEachIndexed { index, note ->
                    put("insuranceNote" + (index + 1) + "Title", note.first)
                    put("insuranceNote" + (index + 1), note.second)
                }
                put("migrationSource", PHARMACY_SOURCE)
            }
        )
    }

    private fun migrateClubVenues(
        entities: MutableList<CampusGraphEntity>,
        buildingsByLegacyId: Map<String, BuildingNode>,
        clubVenueRoomsByKey: MutableMap<String, RoomNode>,
        lianhuajieCampusRef: PlaceRef,
        songshanluCampusRef: PlaceRef
    ) {
        val extraBuildings = mutableMapOf<String, BuildingNode>()
        for (club in PresetCampusCommunityCatalog.defaultCampusClubs) {
            val campusId = if (club.campusName.contains("嵩山")) SONGSHANLU_CAMPUS_ID else LIANHUAJIE_CAMPUS_ID
            val campusRef = if (campusId == SONGSHANLU_CAMPUS_ID) songshanluCampusRef else lianhuajieCampusRef
            var building: BuildingNode? = null
            var room: RoomNode? = null

            if (club.buildingName.isNotBlank()) {
                building = buildingsByLegacyId.values.firstOrNull {
                    // 只把已核验的非正则别名当作同一楼宇；F01 这类 verified=false 的
                    // 片区近似别名不得让社团场地继承 E/F 公寓区中心坐标。
                    it.name == club.buildingName || it.shortName == club.buildingName ||
                        it.legacyAliases.any { alias ->
                            !alias.pattern && alias.verified && alias.value == club.buildingName
                        }
                } ?: extraBuildings.getOrPut(club.buildingName) {
                    val buildingRef = graphRef(campusId, CampusGraphEntityType.BUILDING, club.buildingName)
                    val provenance = provenance(
                        campusId = campusId,
                        sourceSystem = CLUB_SOURCE,
                        sourceRecordId = club.id + "/building",
                        verifiedAt = null,
                        sourceReference = "社团目录结构化场地楼宇名迁移",
                        note = "楼宇名来自社团目录；缺少独立坐标与测绘楼层，未拔高为已核实楼宇",
                        completeness = SnapshotCompleteness.PARTIAL
                    )
                    BuildingNode(
                        ref = buildingRef,
                        name = club.buildingName,
                        shortName = club.buildingName,
                        legacyBuildingId = "club-building-" + CampusGraphIds.normalizeSlug(club.buildingName),
                        category = CampusCategory.DORMITORY,
                        parent = campusRef,
                        location = null,
                        provenance = provenance,
                        attributes = buildMap {
                            put("coordinatePrecision", "unknown")
                            put("migrationSource", CLUB_SOURCE)
                            if (club.buildingName == "学生宿舍F01") {
                                put("containingAreaLegacyBuildingId", "b_dorm_ef")
                            }
                        }
                    ).also { entities += it }
                }

                if (club.roomNumber.isNotBlank()) {
                    val roomKey = building.ref.id + "#" + club.roomNumber
                    room = clubVenueRoomsByKey.getOrPut(roomKey) {
                        val roomRef = graphRef(
                            campusId,
                            CampusGraphEntityType.ROOM,
                            CampusGraphIds.slugOf(building.ref) + "-" + club.roomNumber
                        )
                        RoomNode(
                            ref = roomRef,
                            name = club.roomNumber,
                            buildingRef = building.ref,
                            roomNumber = club.roomNumber,
                            roomKind = CampusGraphRoomKind.SERVICE_ROOM,
                            parent = building.ref,
                            location = building.location,
                            provenance = provenance(
                                campusId = campusId,
                                sourceSystem = CLUB_SOURCE,
                                sourceRecordId = club.id + "/room",
                                verifiedAt = null,
                                sourceReference = "社团目录结构化场地房间号迁移",
                                note = "房间号来自社团目录；缺少独立坐标与室内测绘",
                                completeness = SnapshotCompleteness.PARTIAL
                            ),
                            attributes = mapOf(
                                "coordinatePrecision" to "unknown",
                                "migrationSource" to CLUB_SOURCE
                            )
                        ).also { entities += it }
                    }
                }
            }

            val parentRef = room?.ref ?: building?.ref ?: campusRef
            val facilityProvenance = provenance(
                campusId = campusId,
                sourceSystem = CLUB_SOURCE,
                sourceRecordId = club.id,
                verifiedAt = null,
                sourceReference = "预置社团活动场地目录迁移",
                note = "社团场地按现有纠错后的宿主目录迁移；未提供精确坐标的场地不编造坐标",
                completeness = SnapshotCompleteness.PARTIAL
            )
            val facilityRef = graphRef(campusId, CampusGraphEntityType.FACILITY, club.id + "-venue")
            entities += FacilityNode(
                ref = facilityRef,
                name = club.name + "活动场地",
                kind = CampusGraphFacilityKind.CLUB_VENUE,
                buildingRef = building?.ref,
                roomRef = room?.ref,
                parent = parentRef,
                location = room?.location ?: building?.location,
                provenance = facilityProvenance,
                attributes = buildMap {
                    put("clubId", club.id)
                    put("clubName", club.name)
                    put("clubCategory", club.category.label)
                    put("affiliatedUnit", club.affiliatedUnit)
                    put("activityVenue", club.activityVenue)
                    if (club.buildingName.isNotBlank()) put("buildingName", club.buildingName)
                    if (club.roomNumber.isNotBlank()) put("roomNumber", club.roomNumber)
                    put("highlights", club.highlights.joinToString("；"))
                    put("migrationSource", CLUB_SOURCE)
                }
            )
        }
    }

    private fun findBuildingByDisplayName(
        displayName: String,
        buildings: Collection<BuildingNode>,
        campusId: CampusId
    ): BuildingNode? = buildings.firstOrNull { building ->
        building.ref.campusId == campusId && (
            building.name == displayName ||
                building.shortName == displayName ||
                building.aliases.any { it == displayName } ||
                building.legacyAliases.any { !it.pattern && it.verified && it.value == displayName }
            )
    }

    private fun inferFloorIndex(roomText: String): Int? {
        val digits = Regex("\\d+").findAll(roomText).lastOrNull()?.value ?: return null
        return when (digits.length) {
            3 -> digits.substring(0, 1).toIntOrNull()
            4 -> digits.substring(1, 2).toIntOrNull()
            5 -> digits.substring(2, 3).toIntOrNull()
            else -> null
        }
    }
}
