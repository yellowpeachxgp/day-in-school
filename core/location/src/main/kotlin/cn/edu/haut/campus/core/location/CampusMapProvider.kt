package cn.edu.haut.campus.core.location

import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityEvidence
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.CapabilityState
import cn.edu.haut.campus.core.model.CapabilityStatus
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel

/**
 * 校区 GIS 地图与路网导航的通用 Provider 接口。
 *
 * 非工大学校不会加载莲花街校区建筑或路网节点，而是由 [CampusMapProviderRegistry]
 * 显式返回 `null` 与 [CapabilityState.UNSUPPORTED]，直到安装该校的校区地图包。
 */
interface CampusMapProvider {
    val providerId: String
    val schoolId: SchoolId
    val campusId: CampusId
    val displayName: String
    val centerLatitude: Double
    val centerLongitude: Double
    val geofenceRadiusMeters: Int
    val evidence: CapabilityEvidence

    fun buildWalkingRoute(
        userLocation: UserGeoLocation?,
        fallbackOriginBuilding: Building?,
        destinationBuilding: Building,
        allBuildings: List<Building>,
        rawClassroom: String? = null
    ): InAppNavigationPlan
}

object HautLianhuajieMapProvider : CampusMapProvider {
    override val providerId: String = "haut-lianhuajie-gis"
    override val schoolId: SchoolId = SchoolDirectory.HAUT_SCHOOL_ID
    override val campusId: CampusId = SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID
    override val displayName: String = "河南工业大学莲花街校区步行路网"
    override val centerLatitude: Double = CampusMapNavigator.CAMPUS_CENTER_LAT
    override val centerLongitude: Double = CampusMapNavigator.CAMPUS_CENTER_LNG
    override val geofenceRadiusMeters: Int = 3000
    override val evidence: CapabilityEvidence = CapabilityEvidence(
        level = VerificationLevel.SOURCE_CONFIRMED,
        sourceReference = "core/location/src/main/kotlin/cn/edu/haut/campus/core/location/CampusMapNavigator.kt",
        note = "莲花街校区 12 栋核心建筑与步行路网拓扑"
    )

    override fun buildWalkingRoute(
        userLocation: UserGeoLocation?,
        fallbackOriginBuilding: Building?,
        destinationBuilding: Building,
        allBuildings: List<Building>,
        rawClassroom: String?
    ): InAppNavigationPlan = CampusMapNavigator.buildInAppNavigationPlan(
        userLocation = userLocation,
        fallbackOriginBuilding = fallbackOriginBuilding,
        destinationBuilding = destinationBuilding,
        allBuildings = allBuildings,
        rawClassroom = rawClassroom
    )
}

class CampusMapProviderRegistry(
    providers: List<CampusMapProvider> = listOf(HautLianhuajieMapProvider)
) {
    private val bySchool = providers.groupBy { it.schoolId }

    fun resolve(schoolId: SchoolId, campusId: CampusId? = null): CampusMapProvider? {
        val candidates = bySchool[schoolId].orEmpty()
        if (candidates.isEmpty()) return null
        return if (campusId != null) {
            candidates.firstOrNull { it.campusId == campusId }
        } else {
            candidates.firstOrNull()
        }
    }

    fun capabilityStatus(
        schoolId: SchoolId,
        campusId: CampusId? = null,
        verifiedForAccount: Boolean = false,
        userConsent: Boolean = false,
        prerequisitesReady: Boolean = false
    ): CapabilityStatus {
        val provider = resolve(schoolId, campusId)
            ?: return CapabilityStatus(
                kind = CapabilityKind.CAMPUS_MAP,
                providerSupports = false,
                explicitState = CapabilityState.UNSUPPORTED
            )
        return CapabilityStatus(
            kind = CapabilityKind.CAMPUS_MAP,
            providerSupports = true,
            verifiedForAccount = verifiedForAccount,
            userConsent = userConsent,
            prerequisitesReady = prerequisitesReady,
            evidence = provider.evidence
        )
    }

    companion object {
        val Default = CampusMapProviderRegistry()
    }
}
