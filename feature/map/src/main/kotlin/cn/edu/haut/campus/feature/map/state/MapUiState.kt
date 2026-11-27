package cn.edu.haut.campus.feature.map.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.location.DetectedCampusResult
import cn.edu.haut.campus.core.location.GisBuildingFootprint
import cn.edu.haut.campus.core.location.GisCampusVectorDataset
import cn.edu.haut.campus.core.location.GisIndoorFloorPlan
import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisMapStyleMode
import cn.edu.haut.campus.core.location.GisSearchResultItem
import cn.edu.haut.campus.core.location.InAppNavigationPlan
import cn.edu.haut.campus.core.location.UserGeoLocation
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CustomCampusZone
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.ResolvedClassroomLocation
import cn.edu.haut.campus.core.model.SchoolProfile

/**
 * 校园地图不可变渲染快照。
 *
 * 快照只包含渲染所需字段；定位权限申请、外部地图、浏览器、剪贴板和页面跳转
 * 都通过 [MapUiEffect] 交给 Route 一次性执行。
 */
@Immutable
internal data class MapUiState(
    val activeSchool: SchoolProfile,
    val preferences: AppPreferencesState,
    val mapProviderAvailable: Boolean,
    val selectedCampusPreferenceId: String,
    val communityClubCount: Int,
    val communityCompetitionCount: Int,
    val electricityBadge: String,
    val electricitySubtitle: String,

    val isFullScreenMap: Boolean,
    val showOfficialWebGisDialog: Boolean,
    val showMapHospitalPharmacyDialog: Boolean,
    val searchQuery: String,
    val selectedCategory: CampusCategory?,
    val selectedBuilding: Building?,
    val manualOriginBuilding: Building?,
    val inAppNavigationActive: Boolean,
    val expandRouteSteps: Boolean,

    val gisStyleMode: GisMapStyleMode,
    val gisDataset: GisCampusVectorDataset?,
    val selectedIndoorFloorIndex: Int,
    val activeIndoorFloorPlan: GisIndoorFloorPlan?,
    val isLoadingIndoorFloorPlan: Boolean,
    val selectedIndoorRoom: GisIndoorRoom?,
    val showIndoorSecondaryMap: Boolean,
    val officialSearchResults: List<GisSearchResultItem>,
    val isOfficialRouteActive: Boolean,

    val isDrawingZoneMode: Boolean,
    val drawingZoneVertices: List<GeoPoint>,
    val drawingZoneBuildings: List<Building>,
    val drawingZoneName: String,
    val drawingSchoolName: String,
    val showZoneImportExportDialog: Boolean,
    val zoneAdaptationJsonInput: String,
    val zoneAdaptationFeedback: String?,
    val zoneAdaptationExportJson: String,
    val pendingPinPoint: GeoPoint?,
    val pendingPinName: String,

    val hasLocationPermission: Boolean,
    val isFollowingUser: Boolean,
    val userLocation: UserGeoLocation?,
    val effectiveHeadingDegrees: Float?,

    val customZones: List<CustomCampusZone>,
    val detectedCampus: DetectedCampusResult,
    val isLianhuajieActive: Boolean,
    val activeCampusPolygon: List<GeoPoint>,
    val allBuildings: List<Building>,
    val campusBuildings: List<Building>,
    val filteredBuildings: List<Building>,
    val targetBuilding: Building?,
    val resolution: ResolvedClassroomLocation?,
    val activeBuildingFootprint: GisBuildingFootprint?,
    val navigationPlan: InAppNavigationPlan?,

    val mapCenterLatGcj: Double,
    val mapCenterLonGcj: Double,
    val mapZoom: Float,
    val defaultCenterLatGcj: Double,
    val defaultCenterLonGcj: Double
)
