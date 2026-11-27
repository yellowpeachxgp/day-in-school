package cn.edu.haut.campus.feature.map.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.location.CampusDetectionSource
import cn.edu.haut.campus.core.location.DetectedCampusResult
import cn.edu.haut.campus.core.location.GisBuildingFootprint
import cn.edu.haut.campus.core.location.GisCampusVectorDataset
import cn.edu.haut.campus.core.location.GisGeoPoint
import cn.edu.haut.campus.core.location.GisIndoorFloorPlan
import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisIndoorRoomCategory
import cn.edu.haut.campus.core.location.GisMapStyleMode
import cn.edu.haut.campus.core.location.GisSearchResultItem
import cn.edu.haut.campus.core.location.InAppNavigationPlan
import cn.edu.haut.campus.core.location.InAppNavigationStep
import cn.edu.haut.campus.core.location.RouteGeoPoint
import cn.edu.haut.campus.core.location.UserGeoLocation
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CustomCampusZone
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.ResolvedClassroomLocation
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.feature.map.state.MapUiState
import cn.edu.haut.campus.feature.map.ui.CampusMapScreenContent
import java.time.LocalDate

/**
 * 校园地图 Preview/Roborazzi 状态矩阵夹具。
 *
 * 这些夹具只构造不可变 UiState，不访问 Repository、定位系统、网络或真实瓦片缓存；
 * 瓦片加载/失败/离线场景使用无瓦片内存命中与无官方数据集输入来表达当前 UI 的诚实降级渲染。
 */
internal object MapPreviewFixtures {
    private val verifiedAt = LocalDate.of(2026, 1, 15)

    val teachingBuilding = Building(
        id = "b_teaching_3",
        campusId = "lianhuajie",
        name = "3号教学楼",
        shortName = "3教",
        latitude = 34.83135,
        longitude = 113.54435,
        category = CampusCategory.TEACHING_BUILDING,
        entrances = listOf("南门"),
        description = "公共教学与考试楼",
        verifiedAt = verifiedAt
    )
    val libraryBuilding = Building(
        id = "b_library",
        campusId = "lianhuajie",
        name = "图书馆",
        shortName = "图书馆",
        latitude = 34.83192,
        longitude = 113.54372,
        category = CampusCategory.LIBRARY,
        entrances = listOf("东门"),
        description = "自习与馆藏服务",
        verifiedAt = verifiedAt
    )
    val gateBuilding = Building(
        id = "b_south_gate",
        campusId = "lianhuajie",
        name = "莲花街南门",
        shortName = "南门",
        latitude = 34.82872,
        longitude = 113.54532,
        category = CampusCategory.GATE,
        entrances = listOf("主入口"),
        description = "步行进出校园常用入口",
        verifiedAt = verifiedAt
    )
    val campusBuildings = listOf(teachingBuilding, libraryBuilding, gateBuilding)

    private val footprintRing = listOf(
        GisGeoPoint(34.83125, 113.54422),
        GisGeoPoint(34.83148, 113.54422),
        GisGeoPoint(34.83148, 113.54449),
        GisGeoPoint(34.83125, 113.54449)
    )
    val footprint = GisBuildingFootprint(
        featureId = "fp_teaching_3",
        polygonId = "poly_teaching_3",
        name = teachingBuilding.name,
        alias = teachingBuilding.shortName,
        minLevelIndex = 0,
        maxLevelIndex = 2,
        description = teachingBuilding.description,
        centerLat = teachingBuilding.latitude,
        centerLon = teachingBuilding.longitude,
        outerRing = footprintRing,
        matchedBuildingId = teachingBuilding.id
    )
    val gisDataset = GisCampusVectorDataset(
        landusePolygons = emptyList(),
        roadLines = emptyList(),
        buildings = listOf(footprint),
        servicePois = emptyList(),
        fetchedAtMillis = 1_763_980_800_000L,
        fromNetwork = false
    )

    val room201 = GisIndoorRoom(
        id = "room_3201",
        name = "3201",
        rawCategory = "教室",
        category = GisIndoorRoomCategory.CLASSROOM,
        levelIndex = 1,
        floorLabel = "2F",
        parentPolygonId = footprint.polygonId,
        centerLat = teachingBuilding.latitude,
        centerLon = teachingBuilding.longitude,
        outerRing = footprintRing
    )
    val stairRoom = GisIndoorRoom(
        id = "room_stair",
        name = "东楼梯",
        rawCategory = "楼梯",
        category = GisIndoorRoomCategory.STAIRS_ELEVATOR,
        levelIndex = 1,
        floorLabel = "2F",
        parentPolygonId = footprint.polygonId,
        centerLat = teachingBuilding.latitude + 0.00003,
        centerLon = teachingBuilding.longitude + 0.00004,
        outerRing = footprintRing
    )
    val floorPlan = GisIndoorFloorPlan(
        buildingFeatureId = footprint.featureId,
        levelIndex = 1,
        floorLabel = "2F",
        outlineRings = listOf(footprintRing),
        rooms = listOf(room201, stairRoom)
    )

    val navigationPlan = InAppNavigationPlan(
        originLabel = "莲花街南门",
        originLatitude = gateBuilding.latitude,
        originLongitude = gateBuilding.longitude,
        originIsLiveGps = false,
        destinationBuilding = teachingBuilding,
        rawClassroom = "3201",
        straightDistanceMeters = 310,
        estimatedWalkDistanceMeters = 420,
        estimatedWalkMinutes = 6,
        bearingDegrees = 18,
        compassDirectionLabel = "北偏东",
        waypointBuilding = null,
        routePoints = listOf(
            RouteGeoPoint(gateBuilding.latitude, gateBuilding.longitude, "南门"),
            RouteGeoPoint(teachingBuilding.latitude, teachingBuilding.longitude, "3教")
        ),
        steps = listOf(
            InAppNavigationStep(0, "从南门出发", "沿中轴路向北步行", 180),
            InAppNavigationStep(1, "到达3号教学楼", "从南门入口进入", 240)
        )
    )

    val unresolvedResolution = ResolvedClassroomLocation(
        rawRoom = "实验楼X999",
        status = BuildingMatchStatus.UNRESOLVED,
        building = null,
        roomNumberHint = "X999",
        confidence = 0.1f,
        candidateBuildings = campusBuildings
    )

    val officialSearchResult = GisSearchResultItem(
        id = "search_3201",
        name = "3201",
        isIndoorRoom = true,
        categoryLabel = "教室",
        levelIndex = 1,
        floorLabel = "2F",
        parentPolygonId = footprint.polygonId,
        buildingFeatureId = footprint.featureId,
        buildingName = teachingBuilding.name,
        matchedBuildingId = teachingBuilding.id,
        latitude = teachingBuilding.latitude,
        longitude = teachingBuilding.longitude,
        subtitle = "3号教学楼 · 2F"
    )

    private val campusPolygon = listOf(
        GeoPoint(34.8282, 113.5418),
        GeoPoint(34.8346, 113.5418),
        GeoPoint(34.8346, 113.5484),
        GeoPoint(34.8282, 113.5484)
    )

    private val detectedLianhuajie = DetectedCampusResult(
        campusId = SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value,
        campusName = "莲花街校区",
        centerLatitude = 34.8312,
        centerLongitude = 113.5442,
        source = CampusDetectionSource.MANUAL_SELECTION
    )

    private val userLocation = UserGeoLocation(
        latitude = 34.8304,
        longitude = 113.5440,
        accuracyMeters = 18f,
        bearingDegrees = 32f,
        provider = "preview",
        timestampMillis = 1_763_980_800_000L,
        isInsideLianhuajieCampus = true,
        distanceToCampusCenterMeters = 92,
        isInsideSongshanluCampus = false,
        distanceToSongshanluCenterMeters = 10_000
    )

    fun baseState(): MapUiState = MapUiState(
        activeSchool = SchoolDirectory.hautProfile(),
        preferences = AppPreferencesState(),
        mapProviderAvailable = true,
        selectedCampusPreferenceId = "auto",
        communityClubCount = 3,
        communityCompetitionCount = 5,
        electricityBadge = "23.6度",
        electricitySubtitle = "1号楼 101 · 电表",
        isFullScreenMap = false,
        showOfficialWebGisDialog = false,
        showMapHospitalPharmacyDialog = false,
        searchQuery = "",
        selectedCategory = null,
        selectedBuilding = teachingBuilding,
        manualOriginBuilding = null,
        inAppNavigationActive = true,
        expandRouteSteps = false,
        gisStyleMode = GisMapStyleMode.OFFICIAL_VECTOR_HYBRID,
        gisDataset = gisDataset,
        selectedIndoorFloorIndex = 1,
        activeIndoorFloorPlan = floorPlan,
        isLoadingIndoorFloorPlan = false,
        selectedIndoorRoom = null,
        showIndoorSecondaryMap = false,
        officialSearchResults = emptyList(),
        isOfficialRouteActive = false,
        isDrawingZoneMode = false,
        drawingZoneVertices = emptyList(),
        drawingZoneBuildings = emptyList(),
        drawingZoneName = "",
        drawingSchoolName = "河南工业大学",
        showZoneImportExportDialog = false,
        zoneAdaptationJsonInput = "",
        zoneAdaptationFeedback = null,
        zoneAdaptationExportJson = "{}",
        pendingPinPoint = null,
        pendingPinName = "",
        hasLocationPermission = true,
        isFollowingUser = false,
        userLocation = userLocation,
        effectiveHeadingDegrees = 32f,
        customZones = emptyList(),
        detectedCampus = detectedLianhuajie,
        isLianhuajieActive = true,
        activeCampusPolygon = campusPolygon,
        allBuildings = campusBuildings,
        campusBuildings = campusBuildings,
        filteredBuildings = campusBuildings,
        targetBuilding = teachingBuilding,
        resolution = null,
        activeBuildingFootprint = footprint,
        navigationPlan = null,
        mapCenterLatGcj = 34.8312,
        mapCenterLonGcj = 113.5442,
        mapZoom = 16.3f,
        defaultCenterLatGcj = 34.8312,
        defaultCenterLonGcj = 113.5442
    )

    val content = baseState()
    val needsLocationPermission = baseState().copy(
        isFullScreenMap = true,
        hasLocationPermission = false,
        userLocation = null,
        effectiveHeadingDegrees = null
    )
    val tilesLoading = baseState().copy(
        isFullScreenMap = true,
        gisDataset = null,
        gisStyleMode = GisMapStyleMode.STREET_STANDARD
    )
    val tilesFailed = tilesLoading.copy(mapZoom = 16.8f)
    val offline = baseState().copy(
        isFullScreenMap = true,
        gisDataset = null,
        officialSearchResults = emptyList(),
        isOfficialRouteActive = false
    )
    val searchEmpty = baseState().copy(
        searchQuery = "不存在的教室",
        filteredBuildings = emptyList(),
        selectedBuilding = null,
        targetBuilding = null,
        officialSearchResults = emptyList()
    )
    val unresolvedBuilding = baseState().copy(
        isFullScreenMap = true,
        searchQuery = "实验楼X999",
        resolution = unresolvedResolution
    )
    val route = baseState().copy(
        isFullScreenMap = true,
        navigationPlan = navigationPlan,
        selectedIndoorRoom = room201,
        isOfficialRouteActive = true
    )
    val indoorMap = baseState().copy(
        isFullScreenMap = true,
        showIndoorSecondaryMap = true,
        selectedIndoorRoom = room201,
        navigationPlan = navigationPlan
    )
    val indoorLoading = indoorMap.copy(
        activeIndoorFloorPlan = null,
        isLoadingIndoorFloorPlan = true,
        selectedIndoorRoom = null
    )
    val customZone = baseState().copy(
        isFullScreenMap = true,
        isDrawingZoneMode = true,
        drawingZoneVertices = campusPolygon.take(3),
        drawingZoneBuildings = listOf(teachingBuilding.copy(id = "custom_preview_building", campusId = "custom")),
        drawingZoneName = "示例自定义校区"
    )
    val officialGis = baseState().copy(
        isFullScreenMap = true,
        gisDataset = gisDataset,
        officialSearchResults = listOf(officialSearchResult),
        searchQuery = "3201"
    )
    val officialGisDialog = officialGis.copy(showOfficialWebGisDialog = true)
    val hospitalPharmacy = baseState().copy(showMapHospitalPharmacyDialog = true)
    val unsupportedSchool = baseState().copy(
        activeSchool = SchoolProfile(id = SchoolId("unsupported-preview"), displayName = "未收录示例大学"),
        mapProviderAvailable = false,
        isLianhuajieActive = false,
        gisDataset = null,
        allBuildings = emptyList(),
        campusBuildings = emptyList(),
        filteredBuildings = emptyList(),
        selectedBuilding = null,
        targetBuilding = null,
        activeBuildingFootprint = null,
        navigationPlan = null
    )

    val matrix: Map<String, MapUiState> = linkedMapOf(
        "content" to content,
        "needs_location_permission" to needsLocationPermission,
        "tiles_loading" to tilesLoading,
        "tiles_failed" to tilesFailed,
        "offline" to offline,
        "search_empty" to searchEmpty,
        "unresolved_building" to unresolvedBuilding,
        "route" to route,
        "indoor_map" to indoorMap,
        "indoor_loading" to indoorLoading,
        "custom_zone" to customZone,
        "official_gis" to officialGis,
        "official_gis_dialog" to officialGisDialog,
        "hospital_pharmacy" to hospitalPharmacy,
        "unsupported_school" to unsupportedSchool
    )
}

@Composable
internal fun MapPreviewScenario(state: MapUiState) {
    HautCampusTheme {
        CampusMapScreenContent(state = state, onAction = {})
    }
}

@Preview(name = "map-content", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
internal fun MapPreviewContent() = MapPreviewScenario(MapPreviewFixtures.content)

@Preview(name = "map-needs-location-permission", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
internal fun MapPreviewNeedsPermission() = MapPreviewScenario(MapPreviewFixtures.needsLocationPermission)

@Preview(name = "map-route", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
internal fun MapPreviewRoute() = MapPreviewScenario(MapPreviewFixtures.route)

@Preview(name = "map-indoor", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
internal fun MapPreviewIndoor() = MapPreviewScenario(MapPreviewFixtures.indoorMap)

@Preview(name = "map-custom-zone", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
internal fun MapPreviewCustomZone() = MapPreviewScenario(MapPreviewFixtures.customZone)

@Preview(name = "map-unsupported-school", widthDp = 412, heightDp = 892, showBackground = true)
@Composable
internal fun MapPreviewUnsupportedSchool() = MapPreviewScenario(MapPreviewFixtures.unsupportedSchool)
