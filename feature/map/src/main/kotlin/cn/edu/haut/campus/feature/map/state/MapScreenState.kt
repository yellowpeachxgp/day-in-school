package cn.edu.haut.campus.feature.map.state

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.location.CampusGeofenceRecognizer
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.location.CampusMapProviderRegistry
import cn.edu.haut.campus.core.location.GisBuildingFootprint
import cn.edu.haut.campus.core.location.GisCampusVectorDataset
import cn.edu.haut.campus.core.location.GisGeoPoint
import cn.edu.haut.campus.core.location.GisIndoorFloorPlan
import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisMapStyleMode
import cn.edu.haut.campus.core.location.GisSearchResultItem
import cn.edu.haut.campus.core.location.HautOfficialGisClient
import cn.edu.haut.campus.core.location.InAppNavigationPlan
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CustomCampusZone
import cn.edu.haut.campus.core.model.CustomCampusZoneCodec
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.feature.map.tiles.CampusTileCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

/**
 * 校园地图 Compose 状态 holder。
 *
 * 该函数承接原巨型 Screen 顶部的全部 Flow 聚合、派生状态、防抖搜索、GIS/室内图/路线加载
 * 与 UiAction 处理；它不渲染任何 UI，也不打开 Intent、剪贴板或权限对话框。
 */
@Composable
internal fun rememberMapScreenAssembly(
    repository: CampusRepository,
    initialClassroomQuery: String?,
    activeSchool: SchoolProfile,
    resetTrigger: Int,
    context: Context
): MapScreenAssembly {
    val scope = rememberCoroutineScope()

    var pendingEffect by remember { mutableStateOf<MapUiEffect?>(null) }
    var nextEffectId by remember { mutableStateOf(0L) }
    fun emitEffect(factory: (Long) -> MapUiEffect) {
        nextEffectId += 1L
        pendingEffect = factory(nextEffectId)
    }

    val mapProvider = remember(activeSchool.id, activeSchool.primaryCampus?.id) {
        CampusMapProviderRegistry.Default.resolve(
            activeSchool.id,
            activeSchool.primaryCampus?.id ?: SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID
        )
    }

    val preferences by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val exams by repository.examSchedulesFlow.collectAsState(initial = emptyList())
    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())
    val aliases by repository.buildingAliasesFlow.collectAsState(initial = emptyList())
    val onlineRooms by repository.utilityOnlineRoomsFlow.collectAsState(initial = emptyList())
    val utilityMeters by repository.utilityMetersFlow.collectAsState(initial = emptyList())
    val utilityReadings by repository.utilityReadingsFlow.collectAsState(initial = emptyList())

    val customZones = remember(preferences.customCampusZonesJson) {
        CustomCampusZoneCodec.decodeList(preferences.customCampusZonesJson)
    }

    var isFullScreenMap by remember(initialClassroomQuery) {
        mutableStateOf(!initialClassroomQuery.isNullOrBlank())
    }
    var showOfficialWebGisDialog by remember { mutableStateOf(false) }
    var showMapHospitalPharmacyDialog by remember { mutableStateOf(false) }


    var searchQuery by remember(initialClassroomQuery) {
        mutableStateOf(initialClassroomQuery.orEmpty())
    }
    var selectedCategory by remember { mutableStateOf<CampusCategory?>(null) }
    var selectedBuilding by remember { mutableStateOf<Building?>(null) }
    var manualOriginBuilding by remember { mutableStateOf<Building?>(null) }
    var inAppNavigationActive by remember { mutableStateOf(true) }
    var expandRouteSteps by remember { mutableStateOf(false) }

    // 河南工业大学官方 GIS (gis.haut.edu.cn) 深度集成状态
    var gisStyleMode by remember { mutableStateOf(GisMapStyleMode.OFFICIAL_VECTOR_HYBRID) }
    var gisDataset by remember { mutableStateOf<GisCampusVectorDataset?>(null) }
    var selectedIndoorFloorIndex by remember { mutableIntStateOf(0) }
    var activeIndoorFloorPlan by remember { mutableStateOf<GisIndoorFloorPlan?>(null) }
    var isLoadingIndoorFloorPlan by remember { mutableStateOf(false) }
    var selectedIndoorRoom by remember { mutableStateOf<GisIndoorRoom?>(null) }
    var showIndoorSecondaryMap by remember { mutableStateOf(false) }
    var officialSearchResults by remember { mutableStateOf<List<GisSearchResultItem>>(emptyList()) }
    var isOfficialRouteActive by remember { mutableStateOf(false) }

    // 电子区划自定义圈定校区与校区适配 JSON 导入导出状态
    var isDrawingZoneMode by remember { mutableStateOf(false) }
    var drawingZoneVertices by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    var drawingZoneBuildings by remember { mutableStateOf<List<Building>>(emptyList()) }
    var drawingZoneName by remember { mutableStateOf("") }
    var drawingSchoolName by remember { mutableStateOf(activeSchool.displayName) }
    var showZoneImportExportDialog by remember { mutableStateOf(false) }
    var zoneAdaptationJsonInput by remember { mutableStateOf("") }
    var zoneAdaptationFeedback by remember { mutableStateOf<String?>(null) }
    var pendingPinPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var pendingPinName by remember { mutableStateOf("") }

    var hasLocationPermission by remember {
        mutableStateOf(CampusMapNavigator.hasLocationPermission(context))
    }
    var locationRefreshEpoch by remember { mutableIntStateOf(0) }
    var isFollowingUser by remember { mutableStateOf(initialClassroomQuery.isNullOrBlank()) }


    // 实时 GPS 定位流与电子罗盘朝向传感器流
    val userLocationFlow = remember(hasLocationPermission, context, locationRefreshEpoch) {
        CampusMapNavigator.observeUserLocation(context)
    }
    val userLocation by userLocationFlow.collectAsState(initial = null)

    val deviceHeadingFlow = remember(context) {
        CampusMapNavigator.observeDeviceHeadingDegrees(context)
    }
    val sensorHeadingDegrees by deviceHeadingFlow.collectAsState(initial = null)
    val effectiveHeadingDegrees = sensorHeadingDegrees ?: userLocation?.bearingDegrees

    // 融合 GPS 电子区划、莲花街/嵩山路围栏与课表教室文本的校区自动识别结果
    val classroomHints = remember(rules, exams, initialClassroomQuery) {
        buildList {
            if (!initialClassroomQuery.isNullOrBlank()) add(initialClassroomQuery)
            rules.forEach { add(it.rawRoom) }
            exams.forEach {
                add(it.rawRoom)
                add(it.campusName)
            }
        }
    }
    val detectedCampus = remember(userLocation, customZones, classroomHints, preferences.selectedCampusId) {
        CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = userLocation,
            customZones = customZones,
            classroomHints = classroomHints,
            manualCampusId = preferences.selectedCampusId
        )
    }

    val isLianhuajieActive = remember(detectedCampus.campusId) {
        detectedCampus.customZone == null &&
            !detectedCampus.campusId.contains("songshan", ignoreCase = true)
    }

    // 自动加载官方 GIS 莲花街校区室外矢量 GeoJSON 与 55 处校园服务点位
    LaunchedEffect(isLianhuajieActive) {
        if (isLianhuajieActive && gisDataset == null) {
            gisDataset = HautOfficialGisClient.loadCampusDataset(context.cacheDir)
        }
    }

    val activeCampusPolygon = remember(detectedCampus) {
        val cz = detectedCampus.customZone
        when {
            cz != null -> cz.polygonVertices
            detectedCampus.campusId == SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value ||
                detectedCampus.campusId == "songshanlu" -> ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON
            else -> ClassroomBuildingResolver.HAUT_LIANHUAJIE_POLYGON
        }
    }

    val campusBuildings = remember(buildings, detectedCampus) {
        val cz = detectedCampus.customZone
        when {
            cz != null -> {
                cz.buildings.ifEmpty {
                    buildings.filter { it.campusId == detectedCampus.campusId }
                }
            }
            detectedCampus.campusId == SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value ||
                detectedCampus.campusId == "songshanlu" -> {
                buildings.filter { it.campusId.contains("songshan", ignoreCase = true) }
            }
            else -> {
                buildings.filter {
                    it.campusId.isBlank() || it.campusId.contains("lianhuajie", ignoreCase = true)
                }
            }
        }
    }

    val resolution = remember(searchQuery, buildings, aliases, detectedCampus.campusId) {
        if (searchQuery.isBlank()) null
        else ClassroomBuildingResolver.resolve(
            rawRoom = searchQuery,
            buildings = buildings,
            aliases = aliases,
            campusContext = detectedCampus.campusId
        )
    }

    // 真实地图视口中心（GCJ-02 坐标）与缩放级别
    val defaultCenterGcj = remember(detectedCampus.centerLatitude, detectedCampus.centerLongitude) {
        CampusMapNavigator.wgs84ToGcj02(detectedCampus.centerLatitude, detectedCampus.centerLongitude)
    }
    var mapCenterLatGcj by remember { mutableStateOf(defaultCenterGcj.first) }
    var mapCenterLonGcj by remember { mutableStateOf(defaultCenterGcj.second) }
    var mapZoom by remember { mutableFloatStateOf(16.3f) }

    // 切换校区（莲花街 / 嵩山路 / 自定义电子区划）时自动平移地图视口至目标校区中心
    LaunchedEffect(detectedCampus.campusId) {
        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
            detectedCampus.centerLatitude,
            detectedCampus.centerLongitude
        )
        mapCenterLatGcj = glat
        mapCenterLonGcj = glon
        selectedBuilding = campusBuildings.firstOrNull()
        manualOriginBuilding = null
        selectedIndoorRoom = null
    }

    // 当处于定位跟随模式时，用户坐标变化即实时同步更新地图视口中心
    LaunchedEffect(userLocation?.latitude, userLocation?.longitude, isFollowingUser) {
        val loc = userLocation
        if (isFollowingUser && loc != null &&
            (loc.isInsideLianhuajieCampus || loc.isInsideSongshanluCampus ||
                detectedCampus.customZone?.containsWgs84Point(loc.latitude, loc.longitude) == true)
        ) {
            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(loc.latitude, loc.longitude)
            mapCenterLatGcj = glat
            mapCenterLonGcj = glon
        }
    }

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
            isFullScreenMap = false
            expandRouteSteps = false
            manualOriginBuilding = null
            selectedIndoorRoom = null
            isFollowingUser = true
            locationRefreshEpoch++
            if (userLocation?.isInsideLianhuajieCampus == true || userLocation?.isInsideSongshanluCampus == true) {
                val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
                    userLocation!!.latitude,
                    userLocation!!.longitude
                )
                mapCenterLatGcj = glat
                mapCenterLonGcj = glon
                mapZoom = 16.6f
            } else {
                mapCenterLatGcj = defaultCenterGcj.first
                mapCenterLonGcj = defaultCenterGcj.second
                mapZoom = 16.3f
            }
        }
    }

    // 当用户输入搜索词时，防抖调用学校官方 GIS 全文检索接口 (/cmgis-server/map/search/v1/keyword)
    LaunchedEffect(searchQuery, gisDataset, campusBuildings) {
        val q = searchQuery.trim()
        if (q.isBlank() || !isLianhuajieActive) {
            officialSearchResults = emptyList()
            return@LaunchedEffect
        }
        delay(180L)
        officialSearchResults = HautOfficialGisClient.searchOfficialGis(
            query = q,
            dataset = gisDataset,
            allBuildings = campusBuildings
        )
        // 若本地解析未能直接命中，但官方 GIS 精确命中了某间教室/楼宇，自动关联对应楼宇与楼层
        val topExact = officialSearchResults.firstOrNull {
            it.name.equals(q, ignoreCase = true) ||
                it.name == HautOfficialGisClient.extractCleanRoomCode(q)
        }
        if (topExact != null) {
            topExact.levelIndex?.let { selectedIndoorFloorIndex = it }
            val matchedB = campusBuildings.firstOrNull { it.id == topExact.matchedBuildingId }
            if (matchedB != null && selectedBuilding?.id != matchedB.id) {
                selectedBuilding = matchedB
            }
        }
    }

    LaunchedEffect(resolution?.building?.id, resolution?.status, searchQuery, campusBuildings) {
        val b = resolution?.building
        if (resolution?.status == cn.edu.haut.campus.core.model.BuildingMatchStatus.EXACT_VERIFIED &&
            b != null &&
            campusBuildings.any { it.id == b.id }
        ) {
            selectedBuilding = b
            isFollowingUser = false
            val inferredFloor = HautOfficialGisClient.inferFloorIndexFromRoomQuery(searchQuery)
            if (inferredFloor != null) {
                selectedIndoorFloorIndex = inferredFloor
            }
            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
            mapCenterLatGcj = glat
            mapCenterLonGcj = glon
            if (searchQuery.isNotBlank()) {
                mapZoom = mapZoom.coerceAtLeast(17.4f)
            }
            inAppNavigationActive = true
        }
    }
    LaunchedEffect(campusBuildings.size) {
        if (selectedBuilding == null && campusBuildings.isNotEmpty()) {
            selectedBuilding = campusBuildings.first()
        }
    }

    val filteredBuildings = remember(campusBuildings, selectedCategory, searchQuery, resolution) {
        campusBuildings.filter { b ->
            val catOk = selectedCategory == null || b.category == selectedCategory
            val textOk = searchQuery.isBlank() ||
                b.name.contains(searchQuery, ignoreCase = true) ||
                b.shortName.contains(searchQuery, ignoreCase = true) ||
                b.description.contains(searchQuery, ignoreCase = true) ||
                resolution?.building?.id == b.id
            catOk && textOk
        }
    }

    val targetBuilding = selectedBuilding ?: filteredBuildings.firstOrNull()

    // 当前选中楼宇对应的官方 GIS 建筑轮廓与楼层信息
    val activeBuildingFootprint: GisBuildingFootprint? = remember(targetBuilding, gisDataset) {
        if (targetBuilding == null || !isLianhuajieActive) null
        else {
            val resolved = HautOfficialGisClient.resolveFeatureIdForBuilding(targetBuilding, gisDataset)
            val fid = resolved?.first
            val fromDataset = gisDataset?.buildings?.firstOrNull {
                it.featureId == fid || it.matchedBuildingId == targetBuilding.id
            }
            fromDataset ?: if (fid != null) {
                GisBuildingFootprint(
                    featureId = fid,
                    polygonId = null,
                    name = targetBuilding.name,
                    alias = targetBuilding.shortName,
                    minLevelIndex = 0,
                    maxLevelIndex = resolved.second,
                    description = targetBuilding.description,
                    centerLat = targetBuilding.latitude,
                    centerLon = targetBuilding.longitude,
                    outerRing = emptyList(),
                    matchedBuildingId = targetBuilding.id
                )
            } else null
        }
    }

    // 切换目标楼宇时，确保楼层索引处于该楼宇合法楼层区间内
    LaunchedEffect(activeBuildingFootprint?.featureId) {
        val maxLvl = activeBuildingFootprint?.maxLevelIndex ?: 0
        val inferred = HautOfficialGisClient.inferFloorIndexFromRoomQuery(searchQuery, maxLvl)
        selectedIndoorFloorIndex = inferred ?: selectedIndoorFloorIndex.coerceIn(0, maxLvl.coerceAtLeast(0))
    }

    // 自动加载当前楼宇、当前楼层的官方室内教室平面图 (indoorSource)
    LaunchedEffect(activeBuildingFootprint?.featureId, selectedIndoorFloorIndex, isLianhuajieActive) {
        val fp = activeBuildingFootprint
        if (!isLianhuajieActive || fp == null || !fp.hasIndoorFloors) {
            activeIndoorFloorPlan = null
            selectedIndoorRoom = null
            isLoadingIndoorFloorPlan = false
            return@LaunchedEffect
        }
        val safeFloor = selectedIndoorFloorIndex.coerceIn(fp.minLevelIndex, fp.maxLevelIndex.coerceAtLeast(0))
        isLoadingIndoorFloorPlan = true
        val floorPlan = try {
            HautOfficialGisClient.loadIndoorFloorPlan(
                cacheDir = context.cacheDir,
                buildingFeatureId = fp.featureId,
                levelIndex = safeFloor
            )
        } finally {
            isLoadingIndoorFloorPlan = false
        }
        activeIndoorFloorPlan = floorPlan
        val cleanRoomCode = HautOfficialGisClient.extractCleanRoomCode(searchQuery)
        selectedIndoorRoom = if (floorPlan != null && !cleanRoomCode.isNullOrBlank()) {
            floorPlan.rooms.firstOrNull {
                it.name.equals(cleanRoomCode, ignoreCase = true) ||
                    it.name.contains(cleanRoomCode, ignoreCase = true)
            }
        } else if (selectedIndoorRoom?.levelIndex == safeFloor &&
            floorPlan?.rooms?.any { it.id == selectedIndoorRoom?.id } == true
        ) {
            selectedIndoorRoom
        } else {
            null
        }
    }

    // 本地即时保底路径 + 异步升级为学校官方 GIS 室内外路径 (/cmgis-server/map/route/v3/find/0/0)
    val fallbackNavigationPlan: InAppNavigationPlan? = remember(
        targetBuilding,
        userLocation,
        manualOriginBuilding,
        campusBuildings,
        searchQuery
    ) {
        if (targetBuilding == null) null
        else CampusMapNavigator.buildInAppNavigationPlan(
            userLocation = if (manualOriginBuilding == null) userLocation else null,
            fallbackOriginBuilding = manualOriginBuilding
                ?: campusBuildings.firstOrNull { it.category == CampusCategory.GATE }
                ?: campusBuildings.firstOrNull(),
            destinationBuilding = targetBuilding,
            allBuildings = campusBuildings,
            rawClassroom = selectedIndoorRoom?.name?.takeIf { it.isNotBlank() }
                ?: searchQuery.takeIf { it.isNotBlank() }
        )
    }

    var officialNavigationPlan by remember { mutableStateOf<InAppNavigationPlan?>(null) }

    LaunchedEffect(
        targetBuilding?.id,
        userLocation?.latitude,
        userLocation?.longitude,
        manualOriginBuilding?.id,
        selectedIndoorRoom?.id,
        selectedIndoorFloorIndex,
        searchQuery,
        isLianhuajieActive
    ) {
        val dest = targetBuilding
        if (dest == null || !isLianhuajieActive) {
            officialNavigationPlan = null
            isOfficialRouteActive = false
            return@LaunchedEffect
        }
        val roomLabel = selectedIndoorRoom?.name?.takeIf { it.isNotBlank() }
            ?: searchQuery.takeIf { it.isNotBlank() }
        val roomCenter = selectedIndoorRoom?.let { GisGeoPoint(it.centerLat, it.centerLon) }
        val (plan, isOfficial) = HautOfficialGisClient.buildOfficialOrFallbackNavigationPlan(
            userLocation = if (manualOriginBuilding == null) userLocation else null,
            fallbackOriginBuilding = manualOriginBuilding
                ?: campusBuildings.firstOrNull { it.category == CampusCategory.GATE }
                ?: campusBuildings.firstOrNull(),
            destinationBuilding = dest,
            allBuildings = campusBuildings,
            rawClassroom = roomLabel,
            targetFloorIndex = if (activeBuildingFootprint?.hasIndoorFloors == true) selectedIndoorFloorIndex else null,
            targetRoomCenter = roomCenter
        )
        officialNavigationPlan = plan
        isOfficialRouteActive = isOfficial
    }

    val navigationPlan = officialNavigationPlan ?: fallbackNavigationPlan

    // 统一处理用户点击某条官方 GIS 搜索结果（教室/实验室/楼宇/服务点位）
    fun handleSelectGisSearchResult(item: GisSearchResultItem, openSecondaryIndoorMap: Boolean = item.isIndoorRoom) {
        val matchedB = campusBuildings.firstOrNull { it.id == item.matchedBuildingId }
        if (matchedB != null) {
            selectedBuilding = matchedB
        } else {
            val tempBuilding = Building(
                id = "gis_${item.id}",
                campusId = "lianhuajie",
                name = item.buildingName ?: item.name,
                shortName = item.name.take(8),
                latitude = item.latitude,
                longitude = item.longitude,
                category = CampusCategory.TEACHING_BUILDING,
                entrances = listOf("主入口"),
                description = item.subtitle,
                verifiedAt = LocalDate.now()
            )
            selectedBuilding = tempBuilding
        }
        item.levelIndex?.let { lvl ->
            selectedIndoorFloorIndex = lvl
        }
        isFollowingUser = false
        inAppNavigationActive = true
        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(item.latitude, item.longitude)
        mapCenterLatGcj = glat
        mapCenterLonGcj = glon
        mapZoom = 17.0f
        if (openSecondaryIndoorMap && item.isIndoorRoom) {
            showIndoorSecondaryMap = true
        }
    }

        val zoneAdaptationExportJson = remember(detectedCampus, customZones, drawingZoneVertices, drawingZoneName, drawingZoneBuildings) {
            when {
                drawingZoneVertices.size >= 3 -> {
                    val avgLat = drawingZoneVertices.map { it.latitude }.average()
                    val avgLng = drawingZoneVertices.map { it.longitude }.average()
                    CustomCampusZoneCodec.encodeZone(
                        CustomCampusZone(
                            campusId = "custom-${(drawingZoneName.ifBlank { "我的校区" }).hashCode().toUInt().toString(16)}",
                            campusName = drawingZoneName.ifBlank { "我的自定义校区" },
                            schoolName = drawingSchoolName.ifBlank { activeSchool.displayName },
                            centerLatitude = avgLat,
                            centerLongitude = avgLng,
                            polygonVertices = drawingZoneVertices,
                            buildings = drawingZoneBuildings
                        ),
                        pretty = true
                    )
                }
                detectedCampus.customZone != null -> {
                    CustomCampusZoneCodec.encodeZone(detectedCampus.customZone!!, pretty = true)
                }
                customZones.isNotEmpty() -> {
                    CustomCampusZoneCodec.encodeList(customZones, pretty = true)
                }
                else -> {
                    val isSongshan = detectedCampus.campusId.contains("songshan", ignoreCase = true)
                    CustomCampusZoneCodec.encodeZone(
                        CustomCampusZone(
                            campusId = detectedCampus.campusId,
                            campusName = detectedCampus.campusName,
                            schoolName = activeSchool.displayName,
                            centerLatitude = detectedCampus.centerLatitude,
                            centerLongitude = detectedCampus.centerLongitude,
                            polygonVertices = if (isSongshan) {
                                ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON
                            } else {
                                ClassroomBuildingResolver.HAUT_LIANHUAJIE_POLYGON
                            },
                            buildings = campusBuildings.take(8)
                        ),
                        pretty = true
                    )
                }
            }
        }

    val (elecBadge, elecSub) = remember(onlineRooms, utilityMeters, utilityReadings) {
        if (onlineRooms.isNotEmpty()) {
            val room = onlineRooms.first()
            val firstDev = decodeOnlineDevicesJson(room.devicesJson).firstOrNull()
            val b = firstDev?.balanceRaw?.takeIf { it.isNotBlank() } ?: "已绑定"
            b to room.roomName
        } else if (utilityMeters.isNotEmpty()) {
            val meter = utilityMeters.first()
            val latest = utilityReadings.filter { it.meterId == meter.id }
                .maxByOrNull { it.recordedAtEpochMs }
            val b = if (latest != null) "${latest.value}${meter.unit}" else "记录"
            b to "${meter.roomName} · ${meter.name}"
        } else {
            "电费" to "剩余电量与用电记录"
        }
    }

    val onAction: (MapUiAction) -> Unit = { action ->
        when (action) {
            is MapUiAction.SetFullScreenMap -> isFullScreenMap = action.enabled
            is MapUiAction.SetOfficialWebGisDialogVisible -> showOfficialWebGisDialog = action.visible
            is MapUiAction.SetHospitalPharmacyDialogVisible -> showMapHospitalPharmacyDialog = action.visible
            is MapUiAction.SetIndoorSecondaryMapVisible -> showIndoorSecondaryMap = action.visible
            is MapUiAction.UpdateSearchQuery -> searchQuery = action.value
            MapUiAction.ClearSearchQuery -> {
                searchQuery = ""
                selectedIndoorRoom = null
            }
            is MapUiAction.SelectCategory -> selectedCategory = action.category
            is MapUiAction.SelectGisSearchResult -> handleSelectGisSearchResult(
                item = action.item,
                openSecondaryIndoorMap = action.openSecondaryIndoorMap
            )
            is MapUiAction.SetSelectedBuilding -> selectedBuilding = action.building
            is MapUiAction.SetManualOriginBuilding -> manualOriginBuilding = action.building
            is MapUiAction.SetInAppNavigationActive -> inAppNavigationActive = action.active
            is MapUiAction.SetExpandRouteSteps -> expandRouteSteps = action.expanded
            is MapUiAction.SetDrawingZoneMode -> isDrawingZoneMode = action.enabled
            is MapUiAction.UpdateDrawingZoneVertices -> drawingZoneVertices = action.vertices
            is MapUiAction.UpdateDrawingZoneBuildings -> drawingZoneBuildings = action.buildings
            is MapUiAction.SetZoneAdaptationFeedback -> zoneAdaptationFeedback = action.value
            is MapUiAction.SetPendingPinPoint -> pendingPinPoint = action.point
            is MapUiAction.SetHasLocationPermission -> hasLocationPermission = action.granted
            is MapUiAction.SetFollowingUser -> isFollowingUser = action.following
            is MapUiAction.UpdateLocationRefreshEpoch -> locationRefreshEpoch = action.value
            is MapUiAction.UpdateMapCenterLatGcj -> mapCenterLatGcj = action.value
            is MapUiAction.UpdateMapCenterLonGcj -> mapCenterLonGcj = action.value
            is MapUiAction.UpdateMapZoom -> mapZoom = action.value
            is MapUiAction.SelectBuilding -> {
                if (action.clearIndoorSelection) selectedIndoorRoom = null
                selectedBuilding = action.building
                if (action.centerOnBuilding) {
                    val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(action.building.latitude, action.building.longitude)
                    mapCenterLatGcj = glat
                    mapCenterLonGcj = glon
                }
                action.zoom?.let { mapZoom = it }
                action.fullScreenMap?.let { isFullScreenMap = it }
                action.followUser?.let { isFollowingUser = it }
                if (action.activateNavigation) inAppNavigationActive = true
                if (action.openIndoorSecondaryMap) showIndoorSecondaryMap = true
            }
            is MapUiAction.SelectCampus -> scope.launch {
                repository.setSelectedCampusId(action.campusId)
            }
            is MapUiAction.SelectGisStyleMode -> {
                if (action.mode == GisMapStyleMode.OFFICIAL_25D_2026) {
                    CampusTileCache.clearFailureCooldowns()
                }
                gisStyleMode = action.mode
            }
            MapUiAction.StartCustomZoneDrawing -> {
                isDrawingZoneMode = true
                drawingZoneVertices = emptyList()
                drawingZoneBuildings = emptyList()
                isFullScreenMap = true
            }
            MapUiAction.ToggleCustomZoneDrawing -> {
                isDrawingZoneMode = !isDrawingZoneMode
                if (isDrawingZoneMode && drawingZoneVertices.isEmpty()) {
                    drawingZoneName = ""
                    drawingZoneBuildings = emptyList()
                }
            }
            is MapUiAction.UpdateDrawingZoneName -> drawingZoneName = action.value
            is MapUiAction.AddDrawingZoneVertex -> {
                if (isDrawingZoneMode) {
                    drawingZoneVertices = drawingZoneVertices + action.point
                }
            }
            MapUiAction.UndoDrawingZoneVertex -> {
                drawingZoneVertices = drawingZoneVertices.dropLast(1)
            }
            MapUiAction.AddPendingPinAtCenter -> {
                val (wLat, wLon) = CampusMapNavigator.gcj02ToWgs84(mapCenterLatGcj, mapCenterLonGcj)
                pendingPinPoint = GeoPoint(wLat, wLon)
            }
            is MapUiAction.UpdatePendingPinName -> pendingPinName = action.value
            MapUiAction.ConfirmPendingPin -> {
                val pt = pendingPinPoint
                if (pt != null) {
                    val name = pendingPinName.trim().ifBlank { "自定义教学楼${drawingZoneBuildings.size + 1}" }
                    val newBuilding = Building(
                        id = "custom_b_${System.currentTimeMillis()}",
                        campusId = "custom",
                        name = name,
                        shortName = name.take(6),
                        latitude = pt.latitude,
                        longitude = pt.longitude,
                        category = CampusCategory.TEACHING_BUILDING,
                        entrances = listOf("正门"),
                        description = drawingZoneName.ifBlank { "自定义校区" },
                        verifiedAt = LocalDate.now()
                    )
                    drawingZoneBuildings = drawingZoneBuildings + newBuilding
                    pendingPinName = ""
                    pendingPinPoint = null
                }
            }
            MapUiAction.DismissPendingPin -> pendingPinPoint = null
            MapUiAction.SaveCustomZone -> {
                val name = drawingZoneName.trim().ifBlank { "自定义校区${customZones.size + 1}" }
                val id = "custom-${name.hashCode().toUInt().toString(16)}"
                val avgLat = drawingZoneVertices.map { it.latitude }.average()
                val avgLng = drawingZoneVertices.map { it.longitude }.average()
                val newZone = CustomCampusZone(
                    campusId = id,
                    campusName = name,
                    schoolName = activeSchool.displayName,
                    centerLatitude = avgLat,
                    centerLongitude = avgLng,
                    polygonVertices = drawingZoneVertices,
                    buildings = drawingZoneBuildings.map { it.copy(campusId = id) }
                )
                val merged = (listOf(newZone) + customZones).distinctBy { it.campusId }
                scope.launch {
                    repository.saveCustomCampusZonesJson(
                        CustomCampusZoneCodec.encodeList(merged, pretty = false)
                    )
                    repository.setSelectedCampusId(id)
                    isDrawingZoneMode = false
                }
            }
            MapUiAction.PrepareZoneImportExport -> {
                zoneAdaptationFeedback = null
                zoneAdaptationJsonInput = ""
                showZoneImportExportDialog = true
            }
            is MapUiAction.SetZoneImportExportVisible -> showZoneImportExportDialog = action.visible
            is MapUiAction.UpdateZoneAdaptationJsonInput -> zoneAdaptationJsonInput = action.value
            MapUiAction.CopyZoneAdaptationJson -> {
                val textToCopy = zoneAdaptationJsonInput.ifBlank { zoneAdaptationExportJson }
                zoneAdaptationFeedback = "已复制到剪贴板"
                emitEffect { id ->
                    MapUiEffect.CopyText(
                        id = id,
                        label = "campus_zone_json",
                        text = textToCopy
                    )
                }
            }
            MapUiAction.ClearCustomZones -> {
                scope.launch {
                    repository.saveCustomCampusZonesJson("[]")
                    repository.setSelectedCampusId("auto")
                    zoneAdaptationFeedback = "已清空自定义校区"
                }
            }
            MapUiAction.ImportZoneAdaptationJson -> {
                val raw = zoneAdaptationJsonInput.ifBlank { zoneAdaptationExportJson }
                val parsed = CustomCampusZoneCodec.decodeList(raw)
                if (parsed.isEmpty()) {
                    zoneAdaptationFeedback = "配置格式无效，请检查后重试"
                } else {
                    val merged = (parsed + customZones).distinctBy { it.campusId }
                    scope.launch {
                        repository.saveCustomCampusZonesJson(
                            CustomCampusZoneCodec.encodeList(merged, pretty = false)
                        )
                        repository.setSelectedCampusId(parsed.first().campusId)
                        zoneAdaptationFeedback = "已导入「${parsed.first().campusName}」（${parsed.first().buildings.size} 处建筑）"
                        showZoneImportExportDialog = false
                    }
                }
            }
            is MapUiAction.ViewportChanged -> {
                if (action.fromFullScreenMap &&
                    (abs(action.centerLatGcj - mapCenterLatGcj) > 1e-5 || abs(action.centerLonGcj - mapCenterLonGcj) > 1e-5)
                ) {
                    isFollowingUser = false
                }
                mapCenterLatGcj = action.centerLatGcj
                mapCenterLonGcj = action.centerLonGcj
                mapZoom = if (action.fromFullScreenMap) action.zoom else action.zoom.coerceIn(12.6f, 18.8f)
                if (action.fromFullScreenMap && expandRouteSteps) {
                    expandRouteSteps = false
                }
            }
            MapUiAction.ZoomIn -> mapZoom = (mapZoom + 0.55f).coerceIn(12.6f, 18.8f)
            MapUiAction.ZoomOut -> mapZoom = (mapZoom - 0.55f).coerceIn(12.6f, 18.8f)
            MapUiAction.ShowRouteOverview -> {
                isFollowingUser = false
                if (navigationPlan != null) {
                    val (origLat, origLon) = CampusMapNavigator.wgs84ToGcj02(
                        navigationPlan.originLatitude,
                        navigationPlan.originLongitude
                    )
                    val (destLat, destLon) = CampusMapNavigator.wgs84ToGcj02(
                        navigationPlan.destinationBuilding.latitude,
                        navigationPlan.destinationBuilding.longitude
                    )
                    mapCenterLatGcj = (origLat + destLat) / 2.0
                    mapCenterLonGcj = (origLon + destLon) / 2.0
                    mapZoom = 15.4f
                } else {
                    mapCenterLatGcj = defaultCenterGcj.first
                    mapCenterLonGcj = defaultCenterGcj.second
                    mapZoom = 15.6f
                }
            }
            MapUiAction.LocateUser -> {
                if (!hasLocationPermission) {
                    emitEffect { id -> MapUiEffect.RequestLocationPermission(id) }
                } else {
                    manualOriginBuilding = null
                    isFollowingUser = true
                    locationRefreshEpoch++
                    if (userLocation?.isInsideLianhuajieCampus == true ||
                        userLocation?.isInsideSongshanluCampus == true
                    ) {
                        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
                            userLocation!!.latitude,
                            userLocation!!.longitude
                        )
                        mapCenterLatGcj = glat
                        mapCenterLonGcj = glon
                        mapZoom = 16.9f
                    } else {
                        mapCenterLatGcj = defaultCenterGcj.first
                        mapCenterLonGcj = defaultCenterGcj.second
                        mapZoom = 16.3f
                    }
                }
            }
            MapUiAction.FollowUser -> {
                if (!hasLocationPermission) {
                    emitEffect { id -> MapUiEffect.RequestLocationPermission(id) }
                } else {
                    isFollowingUser = true
                    locationRefreshEpoch++
                }
            }
            is MapUiAction.LocationPermissionResult -> {
                hasLocationPermission = action.anyPermissionGranted || CampusMapNavigator.hasLocationPermission(context)
                isFollowingUser = true
                locationRefreshEpoch++
            }
            MapUiAction.RefreshLocationPermission -> {
                hasLocationPermission = CampusMapNavigator.hasLocationPermission(context)
                locationRefreshEpoch++
            }
            is MapUiAction.SelectIndoorFloor -> selectedIndoorFloorIndex = action.floorIndex
            is MapUiAction.SelectIndoorRoom -> selectedIndoorRoom = action.room
            is MapUiAction.ConfirmIndoorRoomForOutdoorRoute -> {
                selectedIndoorRoom = action.room
                searchQuery = action.room.name
                inAppNavigationActive = true
                showIndoorSecondaryMap = false
                isFullScreenMap = true
            }
            is MapUiAction.ConfirmBuildingCandidate -> {
                scope.launch {
                    repository.confirmBuildingAliasForRoom(
                        rawRoom = searchQuery,
                        buildingId = action.candidate.id,
                        activeCampusId = detectedCampus.campusId
                    )
                    selectedBuilding = action.candidate
                    isFollowingUser = false
                    val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
                        action.candidate.latitude,
                        action.candidate.longitude
                    )
                    mapCenterLatGcj = glat
                    mapCenterLonGcj = glon
                    inAppNavigationActive = true
                }
            }
            is MapUiAction.SelectOriginBuilding -> {
                manualOriginBuilding = action.building
                if (action.building == null) {
                    isFollowingUser = true
                    locationRefreshEpoch++
                } else {
                    isFollowingUser = false
                }
                inAppNavigationActive = true
            }
            MapUiAction.ToggleRouteSteps -> expandRouteSteps = !expandRouteSteps
            MapUiAction.ToggleInAppNavigation -> inAppNavigationActive = !inAppNavigationActive
            MapUiAction.OpenExternalMap -> {
                targetBuilding?.let { target ->
                    emitEffect { id -> MapUiEffect.OpenWalkingNavigation(id, target) }
                }
            }
            MapUiAction.OpenOfficialGisExternalBrowser -> emitEffect { id ->
                MapUiEffect.OpenUrl(id, HautOfficialGisClient.OFFICIAL_GIS_WEB_URL)
            }
            is MapUiAction.OpenUrl -> emitEffect { id -> MapUiEffect.OpenUrl(id, action.url) }
            MapUiAction.NavigateToElectricity -> emitEffect { id ->
                MapUiEffect.Navigate(id, MapNavigationDestination.ELECTRICITY)
            }
            MapUiAction.NavigateToEmptyClassrooms -> emitEffect { id ->
                MapUiEffect.Navigate(id, MapNavigationDestination.EMPTY_CLASSROOMS)
            }
            MapUiAction.NavigateToCommunity -> emitEffect { id ->
                MapUiEffect.Navigate(id, MapNavigationDestination.COMMUNITY)
            }
            MapUiAction.NavigateToCompetitions -> emitEffect { id ->
                MapUiEffect.Navigate(id, MapNavigationDestination.COMPETITIONS)
            }
            MapUiAction.NavigateToSchoolCenter -> emitEffect { id ->
                MapUiEffect.Navigate(id, MapNavigationDestination.SCHOOL_CENTER)
            }
            MapUiAction.NavigateToHospitalPharmacy -> emitEffect { id ->
                MapUiEffect.Navigate(id, MapNavigationDestination.HOSPITAL_PHARMACY)
            }
        }
    }

    val state = MapUiState(
        activeSchool = activeSchool,
        preferences = preferences,
        mapProviderAvailable = mapProvider != null,
        selectedCampusPreferenceId = preferences.selectedCampusId,
        communityClubCount = preferences.campusCommunityCatalog.clubs.size,
        communityCompetitionCount = preferences.campusCommunityCatalog.competitions.size,
        electricityBadge = elecBadge,
        electricitySubtitle = elecSub,
        isFullScreenMap = isFullScreenMap,
        showOfficialWebGisDialog = showOfficialWebGisDialog,
        showMapHospitalPharmacyDialog = showMapHospitalPharmacyDialog,
        searchQuery = searchQuery,
        selectedCategory = selectedCategory,
        selectedBuilding = selectedBuilding,
        manualOriginBuilding = manualOriginBuilding,
        inAppNavigationActive = inAppNavigationActive,
        expandRouteSteps = expandRouteSteps,
        gisStyleMode = gisStyleMode,
        gisDataset = gisDataset,
        selectedIndoorFloorIndex = selectedIndoorFloorIndex,
        activeIndoorFloorPlan = activeIndoorFloorPlan,
        isLoadingIndoorFloorPlan = isLoadingIndoorFloorPlan,
        selectedIndoorRoom = selectedIndoorRoom,
        showIndoorSecondaryMap = showIndoorSecondaryMap,
        officialSearchResults = officialSearchResults,
        isOfficialRouteActive = isOfficialRouteActive,
        isDrawingZoneMode = isDrawingZoneMode,
        drawingZoneVertices = drawingZoneVertices,
        drawingZoneBuildings = drawingZoneBuildings,
        drawingZoneName = drawingZoneName,
        drawingSchoolName = drawingSchoolName,
        showZoneImportExportDialog = showZoneImportExportDialog,
        zoneAdaptationJsonInput = zoneAdaptationJsonInput,
        zoneAdaptationFeedback = zoneAdaptationFeedback,
        zoneAdaptationExportJson = zoneAdaptationExportJson,
        pendingPinPoint = pendingPinPoint,
        pendingPinName = pendingPinName,
        hasLocationPermission = hasLocationPermission,
        isFollowingUser = isFollowingUser,
        userLocation = userLocation,
        effectiveHeadingDegrees = effectiveHeadingDegrees,
        customZones = customZones,
        detectedCampus = detectedCampus,
        isLianhuajieActive = isLianhuajieActive,
        activeCampusPolygon = activeCampusPolygon,
        allBuildings = buildings,
        campusBuildings = campusBuildings,
        filteredBuildings = filteredBuildings,
        targetBuilding = targetBuilding,
        resolution = resolution,
        activeBuildingFootprint = activeBuildingFootprint,
        navigationPlan = navigationPlan,
        mapCenterLatGcj = mapCenterLatGcj,
        mapCenterLonGcj = mapCenterLonGcj,
        mapZoom = mapZoom,
        defaultCenterLatGcj = defaultCenterGcj.first,
        defaultCenterLonGcj = defaultCenterGcj.second
    )

    return MapScreenAssembly(
        state = state,
        onAction = onAction,
        pendingEffect = pendingEffect,
        consumeEffect = { pendingEffect = null }
    )
}
