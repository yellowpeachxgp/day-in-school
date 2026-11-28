package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.packages.bundle.PackageBundle
import cn.edu.haut.campus.core.packages.datapack.DisDatapack
import cn.edu.haut.campus.core.packages.datapack.DisDatapackItem
import cn.edu.haut.campus.core.packages.datapack.DisDatapackKind
import cn.edu.haut.campus.core.packages.datapack.DisDatapackLoader
import cn.edu.haut.campus.core.packages.datapack.VerifiedDataPack
import cn.edu.haut.campus.core.packages.datapack.DataPackSource
import cn.edu.haut.campus.core.packages.manifest.PackageManifest
import java.time.LocalDate

/**
 * dis-datapack/v1 → Campus Graph 实体解码器。
 *
 * 独立分发的 datapack 必须先经 DisDatapackLoader.verify 完成 exact-byte、Schema 与 detached
 * 签名验证；嵌入 dis-campus-plugin/v1  zip 时则由外层 PackageVerifier 先验证宿主包签名。
 * 本解码器只做图索引投影：无法确定图身份/父级或依赖语义时 fail-closed；分支、材料有效期等
 * 完整 item 细节仍由 typed datapack 提供，不在图实体中伪造或静默截断。
 */
class DisDatapackCampusGraphDecoder(
    private val defaultCampusRef: PlaceRef? = null
) : CampusGraphDataPackageDecoder {

    override fun decode(manifest: PackageManifest, bundle: PackageBundle): List<CampusGraphEntity> {
        val jsonFiles = bundle.files.filter { it.path.endsWith(".json", ignoreCase = true) }
        require(jsonFiles.size == 1) { "嵌入 Campus Graph 数据包必须恰好包含一个 JSON datapack" }
        val decoded = DisDatapackLoader.decode(jsonFiles.single().copyOfContent())
        require(decoded.pack.packId == manifest.id) { "datapack packId 与宿主包清单 ID 不一致" }
        require(decoded.pack.version == manifest.version.value) { "datapack version 与宿主包清单版本不一致" }
        return decode(decoded.pack)
    }

    /** 独立分发 datapack 的推荐入口：签名验证结果显式传入后再投影。 */
    fun decodeVerified(verified: VerifiedDataPack): List<CampusGraphEntity> = decode(verified.pack)

    /**
     * 把已验证 datapack 投影为 Campus Graph 实体。
     *
     * v1 schema 中 opportunity-catalog、period-preset 与 pharmacy-catalog 不是图实体；indoor-graph 的
     * edge/level 语义还不能被当前 FloorNode/IndoorNode 模型承接。以上输入在此 fail-closed，
     * 待 W6-03/契约线明确映射后再开放。
     */
    fun decode(pack: DisDatapack): List<CampusGraphEntity> {
        val projected = when (pack.kind) {
            DisDatapackKind.PROCEDURE_GUIDE -> pack.items.map { item ->
                procedureEntity(pack, item as DisDatapackItem.ProcedureGuide)
            }
            DisDatapackKind.FACILITY_CATALOG -> pack.items.map { item ->
                facilityEntity(pack, item as DisDatapackItem.FacilityCatalog)
            }
            else -> throw IllegalArgumentException(
                "dis-datapack kind " + pack.kind.wireId + " 当前不能无损投影为 Campus Graph 实体"
            )
        }
        val duplicated = projected.groupingBy { it.ref }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "dis-datapack 投影包含重复 Campus Graph 稳定 ID：" + duplicated.joinToString { it.id }
        }
        return projected
    }

    private fun procedureEntity(pack: DisDatapack, item: DisDatapackItem.ProcedureGuide): ProcedureNode {
        val school = SchoolId(pack.schoolId)
        val explicitPlace = item.placeRef?.let { parseGraphRef(it, school) }
        val serviceRef = explicitPlace?.takeIf { CampusGraphIds.typeOf(it) == CampusGraphEntityType.SERVICE }
        val parent = when {
            serviceRef != null -> serviceRef
            explicitPlace != null && CampusGraphIds.typeOf(explicitPlace) == CampusGraphEntityType.CAMPUS -> explicitPlace
            explicitPlace != null -> throw IllegalArgumentException(
                "procedure-guide placeRef 必须指向 service/campus 才能无损投影：" + explicitPlace.id
            )
            defaultCampusRef != null -> defaultCampusRef.also { campusRef ->
                require(campusRef.schoolId == school) { "defaultCampusRef 学校与 datapack 不一致：" + campusRef.id }
                require(CampusGraphIds.typeOf(campusRef) == CampusGraphEntityType.CAMPUS) { "defaultCampusRef 必须是 campus 稳定 ID：" + campusRef.id }
            }
            else -> throw IllegalArgumentException("procedure-guide 缺少 placeRef 且未提供 defaultCampusRef")
        }
        require(item.dependsOn.isEmpty()) {
            "procedure-guide dependsOn 的 item-id/placeRef 语义尚未由 CT-06 明确，拒绝静默投影：" + item.id
        }
        val ref = CampusGraphIds.buildDatapackEntity(school, parent.campusId, CampusGraphEntityType.PROCEDURE, item.id)
        val steps = item.steps.map { step ->
            CampusGraphProcedureStep(
                id = step.id,
                title = step.title,
                description = step.instruction,
                placeRef = step.placeRef?.let { parseGraphRef(it, school) }?.also { stepRef ->
                require(stepRef.campusId == parent.campusId) { "流程步骤地点不能跨校区：" + stepRef.id }
                },
                materials = emptyList(),
                dependsOn = emptyList()
            )
        }
        val questions = item.questions.map { question ->
            CampusGraphProcedureQuestion(
                id = question.id,
                prompt = question.question,
                affectsStepIds = question.options.mapNotNull { it.nextStepId }.distinct()
            )
        }
        return ProcedureNode(
            ref = ref,
            name = item.title,
            serviceRef = serviceRef,
            steps = steps,
            questions = questions,
            materials = item.materials.map { it.name },
            dependsOn = emptyList(),
            parent = parent,
            provenance = provenance(pack, item, item.id),
            attributes = procedureAttributes(pack, item)
        )
    }

    private fun procedureAttributes(pack: DisDatapack, item: DisDatapackItem.ProcedureGuide): Map<String, String> {
        val attributes = LinkedHashMap<String, String>()
        item.summary?.let { attributes["summary"] = graphSafeText(it) }
        item.officeHours?.let { attributes["officeHours"] = graphSafeText(it) }
        if (item.contacts.isNotEmpty()) {
            val contacts = item.contacts.joinToString("; ") { contact -> contact.kind.wireId + ":" + graphSafeText(contact.value) }
            require(contacts.length <= 1100) { "流程 contacts 投影超出图属性上限：" + item.id }
            attributes["contacts"] = contacts
        }
        putPackAttributes(attributes, pack)
        return attributes
    }

    private fun facilityEntity(pack: DisDatapack, item: DisDatapackItem.FacilityCatalog): FacilityNode {
        val school = SchoolId(pack.schoolId)
        val parent = parseGraphRef(item.placeRef, school)
        val parentType = CampusGraphIds.typeOf(parent)
        require(parentType == CampusGraphEntityType.CAMPUS || parentType == CampusGraphEntityType.BUILDING) {
            "facility-catalog placeRef 必须是 campus/building 稳定 ID，不能无损推导 floor/room 层级：" + parent.id
        }
        val buildingRef = parent.takeIf { parentType == CampusGraphEntityType.BUILDING }
        val ref = CampusGraphIds.buildDatapackEntity(school, parent.campusId, CampusGraphEntityType.FACILITY, item.id)
        val attributes = LinkedHashMap<String, String>()
        item.summary?.let { attributes["summary"] = graphSafeText(it) }
        item.accessNotes?.let { attributes["accessNotes"] = graphSafeText(it) }
        attributes["facilityType"] = item.facilityType
        if (item.capabilities.isNotEmpty()) {
            val capabilities = item.capabilities.joinToString(",")
            require(capabilities.length <= 1100) { "设施 capabilities 投影超出图属性上限：" + item.id }
            attributes["capabilities"] = capabilities
        }
        putPackAttributes(attributes, pack)
        return FacilityNode(
            ref = ref,
            name = item.title,
            kind = facilityKind(item.facilityType),
            buildingRef = buildingRef,
            openingHours = item.openingHours?.let(::graphSafeText),
            parent = parent,
            provenance = provenance(pack, item, item.id),
            attributes = attributes
        )
    }

    private fun facilityKind(raw: String): CampusGraphFacilityKind = when (raw) {
        "dining" -> CampusGraphFacilityKind.DINING
        "study" -> CampusGraphFacilityKind.STUDY
        "medical" -> CampusGraphFacilityKind.MEDICAL
        "pharmacy" -> CampusGraphFacilityKind.PHARMACY
        "express" -> CampusGraphFacilityKind.EXPRESS
        "club-venue", "club" -> CampusGraphFacilityKind.CLUB_VENUE
        "library" -> CampusGraphFacilityKind.LIBRARY
        "sports" -> CampusGraphFacilityKind.SPORTS
        "service-desk", "office" -> CampusGraphFacilityKind.SERVICE_DESK
        else -> CampusGraphFacilityKind.OTHER
    }

    /**
     * Graph attributes reject ISO control characters, while the datapack schema legitimately
     * allows multiline human text. This reversible escaping is only for the projected display
     * copy; the typed datapack retains the original string byte-for-byte.
     */
    private fun graphSafeText(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch.isISOControl()) {
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(ch)
                }
            }
        }
        return out.toString()
    }

    private fun provenance(
        pack: DisDatapack,
        item: DisDatapackItem,
        sourceRecordId: String
    ): CampusGraphProvenance {
        val school = SchoolId(pack.schoolId)
        val override = item.provenance
        val verificationLevel = override?.verificationLevel ?: pack.verificationLevel
        val verifiedAt = override?.verifiedAt ?: pack.verifiedAt
        val reviewBy = override?.reviewBy ?: pack.reviewBy
        val sources = override?.sources ?: pack.sources
        return CampusGraphProvenance(
            sourceLevel = CampusGraphSourceLevel.DATA_PACKAGE,
            provenance = CampusGraphProvenance.legacySnapshot(
                schoolId = school,
                providerId = "dis-datapack",
                sourceSystem = "dis-datapack/v1",
                sourceRecordId = pack.packId + "@" + pack.version + "/" + sourceRecordId,
                trust = verificationLevel,
                contentRevision = pack.version
            ),
            sourceReference = sourceReference(sources),
            verifiedAt = LocalDate.parse(verifiedAt),
            reviewBy = LocalDate.parse(reviewBy),
            note = "datapack " + pack.packId + "@" + pack.version + "；catalog lifecycle 由 W5-02 另行判定"
        )
    }

    private fun sourceReference(sources: List<DataPackSource>): String {
        val first = sources.firstOrNull() ?: return "dis-datapack/v1 public sources"
        val suffix = if (sources.size > 1) " 等 " + sources.size + " 个公开来源" else " 公开来源"
        return first.title + suffix
    }

    private fun putPackAttributes(attributes: LinkedHashMap<String, String>, pack: DisDatapack) {
        attributes["datapackId"] = pack.packId
        attributes["datapackVersion"] = pack.version
        attributes["verificationLevel"] = pack.verificationLevel.name
    }

    private fun parseGraphRef(raw: String, school: SchoolId): PlaceRef {
        val segments = raw.split('/')
        require(segments.size == 4) { "Campus Graph placeRef 必须是四段稳定 ID：" + raw }
        require(segments[0] == school.value) { "Campus Graph placeRef 学校与 datapack 不一致：" + raw }
        return PlaceRef(raw, school, CampusId(segments[1]))
    }
}
