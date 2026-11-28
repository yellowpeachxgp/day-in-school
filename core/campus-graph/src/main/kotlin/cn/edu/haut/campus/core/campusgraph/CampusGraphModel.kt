package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import java.time.Instant
import java.time.LocalDate

/**
 * 校园目录是学校/校区级共享数据，不属于任何真实用户账号。
 * 现有 [DataProvenance] schema 仍要求 accountId；在 W1 内核过渡期内使用这个不透明哨兵值，
 * 只表示“共享目录来源”，绝不写入用户 Profile，也不能与真实学号或 XPEACH 账号互换。
 */
val CAMPUS_GRAPH_SHARED_ACCOUNT_ID: AccountId = AccountId("shared-campus-graph")

/** 同一图实体的来源优先级：数据包最高，用户本地覆盖其次，众包上报最低。 */
enum class CampusGraphSourceLevel(val priority: Int) {
    DATA_PACKAGE(3),
    USER_OVERRIDE(2),
    CROWD_REPORT(1)
}

/** 图实体的来源上下文；内嵌既有 [DataProvenance]，不另造信任等级或证据类型。 */
data class CampusGraphProvenance(
    val sourceLevel: CampusGraphSourceLevel,
    val provenance: DataProvenance,
    val sourceReference: String? = null,
    val verifiedAt: LocalDate? = null,
    val reviewBy: LocalDate? = null,
    val note: String? = null
) {
    init {
        require(sourceReference == null || sourceReference.isNotBlank()) { "来源说明不能为空字符串" }
        require((sourceReference?.length ?: 0) <= 600) { "来源说明过长" }
        require(note == null || note.length <= 600) { "来源备注过长" }
        require(reviewBy == null || verifiedAt == null || !reviewBy.isBefore(verifiedAt)) {
            "复核截止日期不能早于核验日期"
        }
        require(sourceLevel != CampusGraphSourceLevel.CROWD_REPORT || reviewBy != null) {
            "众包上报必须带复核/过期日期，不能形成永久事实"
        }
    }

    companion object {
        /** 迁移宿主内置目录时的统一 provenance；sourceReference 必须指向原事实源而非声称学校已核验。 */
        fun legacySnapshot(
            schoolId: SchoolId,
            providerId: String,
            sourceSystem: String,
            sourceRecordId: String? = null,
            trust: VerificationLevel = VerificationLevel.SOURCE_CONFIRMED,
            fetchedAt: Instant? = null,
            contentRevision: String? = null
        ): DataProvenance = DataProvenance(
            providerId = providerId,
            schoolId = schoolId,
            accountId = CAMPUS_GRAPH_SHARED_ACCOUNT_ID,
            sourceSystem = sourceSystem,
            sourceRecordId = sourceRecordId,
            fetchedAt = fetchedAt,
            completeness = SnapshotCompleteness.COMPLETE,
            trust = trust,
            schemaVersion = 1,
            contentRevision = contentRevision
        )
    }
}

/** 楼宇别名保留 legacy 匹配语义；pattern=true 时 value 是正则，不得当普通展示名。 */
data class CampusGraphAlias(
    val value: String,
    val confidence: Float,
    val verified: Boolean,
    val pattern: Boolean = false
) {
    init {
        require(value.isNotBlank() && value.length <= 160) { "楼宇别名为空或过长" }
        require(value.none { it.isISOControl() }) { "楼宇别名不能包含控制字符" }
        require(confidence in 0f..1f) { "楼宇别名置信度必须在 0..1" }
    }
}

/** 服务或设施下的目录项（菜品、药品、窗口服务项等）。 */
data class ServiceCatalogItem(
    val id: String,
    val name: String,
    val category: String? = null,
    val summary: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val provenance: CampusGraphProvenance? = null
) {
    init {
        require(id.isNotBlank() && id.length <= 160 && id.none { it.isISOControl() || it.isWhitespace() }) {
            "目录项 ID 为空、过长或包含空白/控制字符"
        }
        require(name.isNotBlank() && name.length <= 240) { "目录项名称为空或过长" }
        validateAttributes(attributes, "目录项属性")
    }
}

sealed interface CampusGraphEntity {
    val ref: PlaceRef
    val type: CampusGraphEntityType
    val name: String
    val aliases: List<String>
    val parent: PlaceRef?
    val location: GeoPoint?
    val provenance: CampusGraphProvenance
    val attributes: Map<String, String>
}

data class CampusNode(
    override val ref: PlaceRef,
    override val name: String,
    override val aliases: List<String> = emptyList(),
    override val location: GeoPoint? = null,
    val polygon: List<GeoPoint> = emptyList(),
    override val provenance: CampusGraphProvenance,
    override val attributes: Map<String, String> = emptyMap()
) : CampusGraphEntity {
    override val type: CampusGraphEntityType get() = CampusGraphEntityType.CAMPUS
    override val parent: PlaceRef? get() = null

    init { validateCampusGraphEntity(this) }
}

data class BuildingNode(
    override val ref: PlaceRef,
    override val name: String,
    val shortName: String,
    val legacyBuildingId: String,
    val category: CampusCategory,
    override val parent: PlaceRef,
    override val location: GeoPoint? = null,
    override val aliases: List<String> = emptyList(),
    val legacyAliases: List<CampusGraphAlias> = emptyList(),
    val polygon: List<GeoPoint> = emptyList(),
    val floorCount: Int? = null,
    override val provenance: CampusGraphProvenance,
    override val attributes: Map<String, String> = emptyMap()
) : CampusGraphEntity {
    override val type: CampusGraphEntityType get() = CampusGraphEntityType.BUILDING

    init {
        validateCampusGraphEntity(this)
        require(shortName.isNotBlank()) { "楼宇简称不能为空" }
        require(legacyBuildingId.isNotBlank() && legacyBuildingId.length <= 96) { "legacy 楼宇 ID 为空或过长" }
        require(floorCount == null || floorCount >= 0) { "楼层数不能为负" }
        require(legacyAliases.map { it.value }.distinct().size == legacyAliases.size) { "楼宇别名重复" }
    }
}

data class FloorNode(
    override val ref: PlaceRef,
    override val name: String,
    val buildingRef: PlaceRef,
    val levelIndex: Int,
    val floorLabel: String,
    override val parent: PlaceRef = buildingRef,
    override val location: GeoPoint? = null,
    override val aliases: List<String> = emptyList(),
    override val provenance: CampusGraphProvenance,
    override val attributes: Map<String, String> = emptyMap()
) : CampusGraphEntity {
    override val type: CampusGraphEntityType get() = CampusGraphEntityType.FLOOR

    init {
        validateCampusGraphEntity(this)
        require(floorLabel.isNotBlank()) { "楼层标签不能为空" }
    }
}

enum class CampusGraphRoomKind {
    CLASSROOM,
    STUDY_ROOM,
    LABORATORY,
    OFFICE,
    SERVICE_ROOM,
    DORMITORY,
    OTHER
}

data class RoomNode(
    override val ref: PlaceRef,
    override val name: String,
    val buildingRef: PlaceRef,
    val floorRef: PlaceRef? = null,
    val roomNumber: String,
    val roomKind: CampusGraphRoomKind = CampusGraphRoomKind.CLASSROOM,
    val capacity: Int? = null,
    override val parent: PlaceRef = floorRef ?: buildingRef,
    override val location: GeoPoint? = null,
    override val aliases: List<String> = emptyList(),
    override val provenance: CampusGraphProvenance,
    override val attributes: Map<String, String> = emptyMap()
) : CampusGraphEntity {
    override val type: CampusGraphEntityType get() = CampusGraphEntityType.ROOM

    init {
        validateCampusGraphEntity(this)
        require(roomNumber.isNotBlank() && roomNumber.length <= 64) { "房间号为空或过长" }
        require(capacity == null || capacity > 0) { "房间容量必须为正数" }
    }
}

enum class CampusGraphFacilityKind {
    DINING,
    STUDY,
    MEDICAL,
    PHARMACY,
    EXPRESS,
    CLUB_VENUE,
    LIBRARY,
    SPORTS,
    SERVICE_DESK,
    OTHER
}

data class FacilityNode(
    override val ref: PlaceRef,
    override val name: String,
    val kind: CampusGraphFacilityKind,
    val buildingRef: PlaceRef? = null,
    val floorRef: PlaceRef? = null,
    val roomRef: PlaceRef? = null,
    val openingHours: String? = null,
    val catalogItems: List<ServiceCatalogItem> = emptyList(),
    override val parent: PlaceRef?,
    override val location: GeoPoint? = null,
    override val aliases: List<String> = emptyList(),
    override val provenance: CampusGraphProvenance,
    override val attributes: Map<String, String> = emptyMap()
) : CampusGraphEntity {
    override val type: CampusGraphEntityType get() = CampusGraphEntityType.FACILITY

    init {
        validateCampusGraphEntity(this)
        require(openingHours == null || openingHours.isNotBlank()) { "开放时间不能为空字符串" }
        require(catalogItems.map { it.id }.distinct().size == catalogItems.size) { "设施目录项 ID 重复" }
    }
}

data class EntranceNode(
    override val ref: PlaceRef,
    override val name: String,
    val buildingRef: PlaceRef,
    val label: String,
    override val parent: PlaceRef = buildingRef,
    override val location: GeoPoint? = null,
    override val aliases: List<String> = emptyList(),
    override val provenance: CampusGraphProvenance,
    override val attributes: Map<String, String> = emptyMap()
) : CampusGraphEntity {
    override val type: CampusGraphEntityType get() = CampusGraphEntityType.ENTRANCE

    init {
        validateCampusGraphEntity(this)
        require(label.isNotBlank()) { "入口标签不能为空" }
    }
}

enum class CampusGraphIndoorNodeKind {
    ROOM_CENTER,
    ENTRANCE,
    CORRIDOR,
    STAIRS,
    ELEVATOR,
    CAMPUS_TRANSITION
}

data class IndoorNode(
    override val ref: PlaceRef,
    override val name: String,
    val buildingRef: PlaceRef,
    val floorRef: PlaceRef? = null,
    val roomRef: PlaceRef? = null,
    val nodeKind: CampusGraphIndoorNodeKind,
    override val parent: PlaceRef = roomRef ?: floorRef ?: buildingRef,
    override val location: GeoPoint? = null,
    override val aliases: List<String> = emptyList(),
    override val provenance: CampusGraphProvenance,
    override val attributes: Map<String, String> = emptyMap()
) : CampusGraphEntity {
    override val type: CampusGraphEntityType get() = CampusGraphEntityType.INDOOR_NODE

    init { validateCampusGraphEntity(this) }
}

enum class CampusGraphServiceKind {
    LIBRARY,
    MEDICAL,
    PHARMACY,
    STUDENT_AFFAIRS,
    EXPRESS,
    DINING,
    CLUB,
    OTHER
}

data class ServiceNode(
    override val ref: PlaceRef,
    override val name: String,
    val kind: CampusGraphServiceKind,
    val buildingRef: PlaceRef? = null,
    val officeHours: String? = null,
    val contacts: List<String> = emptyList(),
    val catalogItems: List<ServiceCatalogItem> = emptyList(),
    override val parent: PlaceRef?,
    override val location: GeoPoint? = null,
    override val aliases: List<String> = emptyList(),
    override val provenance: CampusGraphProvenance,
    override val attributes: Map<String, String> = emptyMap()
) : CampusGraphEntity {
    override val type: CampusGraphEntityType get() = CampusGraphEntityType.SERVICE

    init {
        validateCampusGraphEntity(this)
        require(contacts.all { it.isNotBlank() && it.length <= 240 }) { "服务联系方式为空或过长" }
        require(catalogItems.map { it.id }.distinct().size == catalogItems.size) { "服务目录项 ID 重复" }
    }
}

data class CampusGraphProcedureStep(
    val id: String,
    val title: String,
    val description: String? = null,
    val placeRef: PlaceRef? = null,
    val materials: List<String> = emptyList(),
    val dependsOn: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank() && id.length <= 96 && id.none { it.isWhitespace() || it.isISOControl() }) {
            "流程步骤 ID 为空、过长或包含空白/控制字符"
        }
        require(title.isNotBlank()) { "流程步骤标题不能为空" }
        require(materials.all { it.isNotBlank() }) { "流程材料不能为空字符串" }
        require(dependsOn.all { it.isNotBlank() }) { "流程依赖步骤 ID 不能为空字符串" }
    }
}

data class CampusGraphProcedureQuestion(
    val id: String,
    val prompt: String,
    val affectsStepIds: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank() && id.length <= 96 && id.none { it.isWhitespace() || it.isISOControl() }) {
            "流程问题 ID 为空、过长或包含空白/控制字符"
        }
        require(prompt.isNotBlank()) { "流程问题不能为空" }
    }
}

data class ProcedureNode(
    override val ref: PlaceRef,
    override val name: String,
    val serviceRef: PlaceRef? = null,
    val steps: List<CampusGraphProcedureStep> = emptyList(),
    val questions: List<CampusGraphProcedureQuestion> = emptyList(),
    val materials: List<String> = emptyList(),
    val dependsOn: List<PlaceRef> = emptyList(),
    override val parent: PlaceRef?,
    override val location: GeoPoint? = null,
    override val aliases: List<String> = emptyList(),
    override val provenance: CampusGraphProvenance,
    override val attributes: Map<String, String> = emptyMap()
) : CampusGraphEntity {
    override val type: CampusGraphEntityType get() = CampusGraphEntityType.PROCEDURE

    init {
        validateCampusGraphEntity(this)
        val stepIds = steps.map { it.id }
        require(stepIds.distinct().size == stepIds.size) { "流程步骤 ID 重复" }
        require(questions.map { it.id }.distinct().size == questions.size) { "流程问题 ID 重复" }
        require(steps.all { step -> step.dependsOn.all { it in stepIds } }) { "流程步骤依赖必须指向本流程步骤" }
        require(materials.all { it.isNotBlank() }) { "流程材料不能为空字符串" }
    }
}

internal fun validateCampusGraphEntity(entity: CampusGraphEntity) {
    require(CampusGraphIds.typeOf(entity.ref) == entity.type) {
        "图实体类型与稳定 ID 类型段不一致：" + entity.ref.id
    }
    require(entity.name.isNotBlank() && entity.name.length <= 240) { "图实体名称为空或过长" }
    require(entity.aliases.all { it.isNotBlank() && it.length <= 160 && it.none { ch -> ch.isISOControl() } }) {
        "图实体别名不能为空、过长或包含控制字符"
    }
    require(entity.provenance.provenance.schoolId == entity.ref.schoolId) {
        "图实体 DataProvenance 学校与稳定 ID 不一致"
    }
    validateParentInvariant(entity)
    validateReferenceInvariant(entity)
    validateAttributes(entity.attributes, "图实体属性")
}

private fun validateParentInvariant(entity: CampusGraphEntity) {
    val parent = entity.parent
    if (entity.type == CampusGraphEntityType.CAMPUS) {
        require(parent == null) { "校区实体不能有父节点：" + entity.ref.id }
        return
    }
    require(parent != null) { "非校区图实体必须有父节点：" + entity.ref.id }
    require(parent.schoolId == entity.ref.schoolId && parent.campusId == entity.ref.campusId) {
        "图实体父节点不能跨学校/校区：" + entity.ref.id
    }
    val parentType = CampusGraphIds.typeOf(parent)
    val allowedParents = when (entity.type) {
        CampusGraphEntityType.CAMPUS -> emptySet()
        CampusGraphEntityType.BUILDING -> setOf(CampusGraphEntityType.CAMPUS)
        CampusGraphEntityType.FLOOR -> setOf(CampusGraphEntityType.BUILDING)
        CampusGraphEntityType.ROOM -> setOf(CampusGraphEntityType.BUILDING, CampusGraphEntityType.FLOOR)
        CampusGraphEntityType.FACILITY -> setOf(
            CampusGraphEntityType.CAMPUS,
            CampusGraphEntityType.BUILDING,
            CampusGraphEntityType.FLOOR,
            CampusGraphEntityType.ROOM
        )
        CampusGraphEntityType.ENTRANCE -> setOf(CampusGraphEntityType.BUILDING)
        CampusGraphEntityType.INDOOR_NODE -> setOf(
            CampusGraphEntityType.BUILDING,
            CampusGraphEntityType.FLOOR,
            CampusGraphEntityType.ROOM,
            CampusGraphEntityType.ENTRANCE
        )
        CampusGraphEntityType.SERVICE -> setOf(
            CampusGraphEntityType.CAMPUS,
            CampusGraphEntityType.BUILDING,
            CampusGraphEntityType.FLOOR,
            CampusGraphEntityType.ROOM,
            CampusGraphEntityType.FACILITY
        )
        CampusGraphEntityType.PROCEDURE -> setOf(CampusGraphEntityType.CAMPUS, CampusGraphEntityType.SERVICE)
    }
    require(parentType in allowedParents) {
        "图实体父节点类型不合法：" + entity.ref.id + " parentType=" + parentType.wireId
    }
}

private fun validateReferenceInvariant(entity: CampusGraphEntity) {
    fun requireRefType(ref: PlaceRef, expected: CampusGraphEntityType, label: String) {
        require(CampusGraphIds.typeOf(ref) == expected) {
            label + "必须是 " + expected.wireId + " 稳定 ID：" + ref.id
        }
        require(ref.schoolId == entity.ref.schoolId && ref.campusId == entity.ref.campusId) {
            label + "不能跨学校/校区：" + ref.id
        }
    }

    when (entity) {
        is FloorNode -> {
            requireRefType(entity.buildingRef, CampusGraphEntityType.BUILDING, "楼层所属楼宇")
            require(entity.parent == entity.buildingRef) { "楼层 parent 必须等于所属楼宇：" + entity.ref.id }
        }
        is RoomNode -> {
            requireRefType(entity.buildingRef, CampusGraphEntityType.BUILDING, "房间所属楼宇")
            entity.floorRef?.let { requireRefType(it, CampusGraphEntityType.FLOOR, "房间所属楼层") }
            require(entity.parent == (entity.floorRef ?: entity.buildingRef)) {
                "房间 parent 必须等于所属楼层或楼宇：" + entity.ref.id
            }
        }
        is EntranceNode -> {
            requireRefType(entity.buildingRef, CampusGraphEntityType.BUILDING, "入口所属楼宇")
            require(entity.parent == entity.buildingRef) { "入口 parent 必须等于所属楼宇：" + entity.ref.id }
        }
        is IndoorNode -> {
            requireRefType(entity.buildingRef, CampusGraphEntityType.BUILDING, "室内节点所属楼宇")
            entity.floorRef?.let { requireRefType(it, CampusGraphEntityType.FLOOR, "室内节点所属楼层") }
            entity.roomRef?.let { requireRefType(it, CampusGraphEntityType.ROOM, "室内节点所属房间") }
            val parentType = CampusGraphIds.typeOf(entity.parent)
            if (parentType == CampusGraphEntityType.ENTRANCE) {
                require(entity.nodeKind == CampusGraphIndoorNodeKind.ENTRANCE) {
                    "入口类室内节点才能挂到入口 parent：" + entity.ref.id
                }
                require(entity.floorRef == null && entity.roomRef == null) {
                    "挂到入口 parent 的室内节点不能再声明楼层/房间引用：" + entity.ref.id
                }
            } else {
                val expectedParent = entity.roomRef ?: entity.floorRef ?: entity.buildingRef
                require(entity.parent == expectedParent) {
                    "室内节点 parent 必须等于最具体的房间/楼层/楼宇引用：" + entity.ref.id
                }
            }
        }
        is FacilityNode -> {
            entity.buildingRef?.let { requireRefType(it, CampusGraphEntityType.BUILDING, "设施所属楼宇") }
            entity.floorRef?.let { requireRefType(it, CampusGraphEntityType.FLOOR, "设施所属楼层") }
            entity.roomRef?.let { requireRefType(it, CampusGraphEntityType.ROOM, "设施所属房间") }
            val expectedParent = entity.roomRef ?: entity.floorRef ?: entity.buildingRef
            val parent = requireNotNull(entity.parent) { "设施 parent 不能为空：" + entity.ref.id }
            if (expectedParent == null) {
                require(CampusGraphIds.typeOf(parent) == CampusGraphEntityType.CAMPUS) {
                    "无楼宇/楼层/房间引用的设施必须挂到校区 parent：" + entity.ref.id
                }
            } else {
                require(entity.parent == expectedParent) {
                    "设施 parent 必须等于最具体的房间/楼层/楼宇引用：" + entity.ref.id
                }
            }
        }
        is ServiceNode -> {
            entity.buildingRef?.let { requireRefType(it, CampusGraphEntityType.BUILDING, "服务所属楼宇") }
            val parent = requireNotNull(entity.parent) { "服务 parent 不能为空：" + entity.ref.id }
            when (CampusGraphIds.typeOf(parent)) {
                CampusGraphEntityType.BUILDING -> require(entity.buildingRef == parent) {
                    "服务挂到楼宇 parent 时 buildingRef 必须一致：" + entity.ref.id
                }
                CampusGraphEntityType.CAMPUS -> require(entity.buildingRef == null) {
                    "服务挂到校区 parent 时不能同时声明楼宇引用：" + entity.ref.id
                }
                else -> Unit
            }
        }
        is ProcedureNode -> {
            entity.serviceRef?.let { requireRefType(it, CampusGraphEntityType.SERVICE, "流程所属服务") }
            entity.dependsOn.forEach { requireRefType(it, CampusGraphEntityType.PROCEDURE, "流程依赖") }
            val parent = requireNotNull(entity.parent) { "流程 parent 不能为空：" + entity.ref.id }
            if (entity.serviceRef == null) {
                require(CampusGraphIds.typeOf(parent) == CampusGraphEntityType.CAMPUS) {
                    "无服务引用的流程必须挂到校区 parent：" + entity.ref.id
                }
            } else {
                require(entity.parent == entity.serviceRef) {
                    "流程 parent 必须等于所属服务引用：" + entity.ref.id
                }
            }
        }
        is CampusNode, is BuildingNode -> Unit
    }
}

internal fun validateAttributes(attributes: Map<String, String>, label: String) {
    attributes.forEach { (key, value) ->
        require(key.isNotBlank() && key.length <= 96 && key.none { it.isWhitespace() || it.isISOControl() }) {
            label + "键为空、过长或包含空白/控制字符"
        }
        require(value.length <= 1200 && value.none { it.isISOControl() }) {
            label + "值过长或包含控制字符"
        }
    }
}
