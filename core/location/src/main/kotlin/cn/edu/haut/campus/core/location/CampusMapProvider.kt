package cn.edu.haut.campus.core.location

import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityEvidence
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.CapabilityState
import cn.edu.haut.campus.core.model.CapabilityStatus
import cn.edu.haut.campus.core.model.CustomCampusZone
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

object HautSongshanluMapProvider : CampusMapProvider {
    override val providerId: String = "haut-songshanlu-gis"
    override val schoolId: SchoolId = SchoolDirectory.HAUT_SCHOOL_ID
    override val campusId: CampusId = SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID
    override val displayName: String = "河南工业大学嵩山路校区步行路网"
    override val centerLatitude: Double = CampusMapNavigator.SONGSHANLU_CENTER_LAT
    override val centerLongitude: Double = CampusMapNavigator.SONGSHANLU_CENTER_LNG
    override val geofenceRadiusMeters: Int = 2000
    override val evidence: CapabilityEvidence = CapabilityEvidence(
        level = VerificationLevel.SOURCE_CONFIRMED,
        sourceReference = "core/domain/src/main/kotlin/cn/edu/haut/campus/core/domain/ClassroomBuildingResolver.kt",
        note = "嵩山路校区（郑州市中原区嵩山南路140号）8栋核心教学与生活楼宇及电子区划围栏"
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

enum class CampusDetectionSource(val label: String) {
    LIVE_GPS_CUSTOM_POLYGON("电子区划实时定位识别"),
    LIVE_GPS_GEOFENCE("GPS 校区围栏自动识别"),
    COURSE_CLASSROOM_HINT("课表教室文本智能识别"),
    MANUAL_SELECTION("手动切换校区")
}

data class DetectedCampusResult(
    val campusId: String,
    val campusName: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val source: CampusDetectionSource,
    val customZone: CustomCampusZone? = null,
    val isLowAccuracyGps: Boolean = false,
    val gpsAccuracyMeters: Float? = null
)

/**
 * 校区自动识别引擎：融合 GPS 电子区划多边形（Ray-Casting）、莲花街/嵩山路双校区电子围栏与课表教室文本识别。
 */
object CampusGeofenceRecognizer {

    fun detectActiveCampus(
        userLocation: UserGeoLocation?,
        customZones: List<CustomCampusZone>,
        classroomHints: Iterable<String> = emptyList(),
        manualCampusId: String? = null
    ): DetectedCampusResult {
        // 1. 优先响应用户显式手动选择的自定义电子区划
        if (!manualCampusId.isNullOrBlank() && manualCampusId != "auto") {
            customZones.firstOrNull { it.campusId == manualCampusId }?.let { zone ->
                return DetectedCampusResult(
                    campusId = zone.campusId,
                    campusName = zone.campusName,
                    centerLatitude = zone.centerLatitude,
                    centerLongitude = zone.centerLongitude,
                    source = CampusDetectionSource.MANUAL_SELECTION,
                    customZone = zone
                )
            }
            if (manualCampusId == SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value || manualCampusId == "songshanlu") {
                return DetectedCampusResult(
                    campusId = SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value,
                    campusName = "嵩山路校区",
                    centerLatitude = CampusMapNavigator.SONGSHANLU_CENTER_LAT,
                    centerLongitude = CampusMapNavigator.SONGSHANLU_CENTER_LNG,
                    source = CampusDetectionSource.MANUAL_SELECTION
                )
            }
            if (manualCampusId == SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value || manualCampusId == "lianhuajie") {
                return DetectedCampusResult(
                    campusId = SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value,
                    campusName = "莲花街校区",
                    centerLatitude = CampusMapNavigator.CAMPUS_CENTER_LAT,
                    centerLongitude = CampusMapNavigator.CAMPUS_CENTER_LNG,
                    source = CampusDetectionSource.MANUAL_SELECTION
                )
            }
        }

        // 2. 实时 GPS 电子区划与双校区电子围栏自动识别
        if (userLocation != null) {
            val lowAcc = userLocation.accuracyMeters > 90f
            customZones.firstOrNull { it.containsWgs84Point(userLocation.latitude, userLocation.longitude) }?.let { zone ->
                return DetectedCampusResult(
                    campusId = zone.campusId,
                    campusName = zone.campusName,
                    centerLatitude = zone.centerLatitude,
                    centerLongitude = zone.centerLongitude,
                    source = CampusDetectionSource.LIVE_GPS_CUSTOM_POLYGON,
                    customZone = zone,
                    isLowAccuracyGps = lowAcc,
                    gpsAccuracyMeters = userLocation.accuracyMeters
                )
            }
            if (CustomCampusZone.isPointInPolygon(
                    userLocation.latitude,
                    userLocation.longitude,
                    ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON
                ) || (userLocation.isInsideSongshanluCampus && !lowAcc)
            ) {
                return DetectedCampusResult(
                    campusId = SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value,
                    campusName = "嵩山路校区",
                    centerLatitude = CampusMapNavigator.SONGSHANLU_CENTER_LAT,
                    centerLongitude = CampusMapNavigator.SONGSHANLU_CENTER_LNG,
                    source = CampusDetectionSource.LIVE_GPS_GEOFENCE,
                    isLowAccuracyGps = lowAcc,
                    gpsAccuracyMeters = userLocation.accuracyMeters
                )
            }
            if (CustomCampusZone.isPointInPolygon(
                    userLocation.latitude,
                    userLocation.longitude,
                    ClassroomBuildingResolver.HAUT_LIANHUAJIE_POLYGON
                ) || userLocation.isInsideLianhuajieCampus
            ) {
                return DetectedCampusResult(
                    campusId = SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value,
                    campusName = "莲花街校区",
                    centerLatitude = CampusMapNavigator.CAMPUS_CENTER_LAT,
                    centerLongitude = CampusMapNavigator.CAMPUS_CENTER_LNG,
                    source = CampusDetectionSource.LIVE_GPS_GEOFENCE,
                    isLowAccuracyGps = lowAcc,
                    gpsAccuracyMeters = userLocation.accuracyMeters
                )
            }
        }

        // 3. 课表教室/考试地点字符串识别（莲花街 vs 嵩山路）
        val hintedCampus = ClassroomBuildingResolver.detectCampusHint(classroomHints)
        if (hintedCampus == SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID) {
            return DetectedCampusResult(
                campusId = SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value,
                campusName = "嵩山路校区",
                centerLatitude = CampusMapNavigator.SONGSHANLU_CENTER_LAT,
                centerLongitude = CampusMapNavigator.SONGSHANLU_CENTER_LNG,
                source = CampusDetectionSource.COURSE_CLASSROOM_HINT
            )
        }

        // 4. 若用户添加了自定义电子区划但当前不在校内，优先展示最新自定义校区；否则默认莲花街校区
        customZones.firstOrNull()?.let { zone ->
            return DetectedCampusResult(
                campusId = zone.campusId,
                campusName = zone.campusName,
                centerLatitude = zone.centerLatitude,
                centerLongitude = zone.centerLongitude,
                source = CampusDetectionSource.MANUAL_SELECTION,
                customZone = zone
            )
        }

        return DetectedCampusResult(
            campusId = SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value,
            campusName = "莲花街校区",
            centerLatitude = CampusMapNavigator.CAMPUS_CENTER_LAT,
            centerLongitude = CampusMapNavigator.CAMPUS_CENTER_LNG,
            source = if (hintedCampus != null) CampusDetectionSource.COURSE_CLASSROOM_HINT else CampusDetectionSource.MANUAL_SELECTION
        )
    }
}

class CampusMapProviderRegistry(
    providers: List<CampusMapProvider> = listOf(HautLianhuajieMapProvider, HautSongshanluMapProvider)
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
