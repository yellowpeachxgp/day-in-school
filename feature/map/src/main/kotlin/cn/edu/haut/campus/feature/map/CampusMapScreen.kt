package cn.edu.haut.campus.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachBrandLight
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachGlassSurface
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachOk
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.CampusHospitalPharmacyCatalog
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.HospitalMedicineCategory
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.location.CampusGeofenceRecognizer
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.location.CampusMapProviderRegistry
import cn.edu.haut.campus.core.location.CampusTileCacheCoordinator
import cn.edu.haut.campus.core.location.TileRenderLookup
import cn.edu.haut.campus.core.location.GisBuildingFootprint
import cn.edu.haut.campus.core.location.GisCampusVectorDataset
import cn.edu.haut.campus.core.location.GisGeoPoint
import cn.edu.haut.campus.core.location.GisIndoorFloorPlan
import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisLanduseType
import cn.edu.haut.campus.core.location.GisMapStyleMode
import cn.edu.haut.campus.core.location.GisSearchResultItem
import cn.edu.haut.campus.core.location.HautOfficialGisClient
import cn.edu.haut.campus.core.location.InAppNavigationPlan
import cn.edu.haut.campus.core.location.UserGeoLocation
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CustomCampusZone
import cn.edu.haut.campus.core.model.CustomCampusZoneCodec
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.feature.map.ui.indoor.BuildingIndoorMapDialog
import cn.edu.haut.campus.feature.map.ui.components.FloatingRouteBottomSheet
import cn.edu.haut.campus.feature.map.ui.components.MapHospitalPharmacyDialog
import cn.edu.haut.campus.feature.map.ui.components.OfficialGisWebViewDialog
import cn.edu.haut.campus.feature.map.ui.map.CampusTileCache
import cn.edu.haut.campus.feature.map.ui.map.RealCampusTileMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

@Composable
fun CampusMapScreen(
    repository: CampusRepository,
    initialClassroomQuery: String? = null,
    onNavigateToElectricity: () -> Unit = {},
    onNavigateToEmptyClassrooms: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToCompetitions: () -> Unit = onNavigateToCommunity,
    onNavigateToSchoolCenter: () -> Unit = {},
    onNavigateToHospitalPharmacy: () -> Unit = {},
    activeSchool: SchoolProfile = remember { SchoolDirectory.hautProfile() },
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
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

    BackHandler(enabled = isFullScreenMap) {
        isFullScreenMap = false
    }

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

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasLocationPermission = grants.values.any { it } || CampusMapNavigator.hasLocationPermission(context)
        isFollowingUser = true
        locationRefreshEpoch++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationPermission = CampusMapNavigator.hasLocationPermission(context)
                locationRefreshEpoch++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    // 独立二级建筑室内平面图弹窗（与室外地图解耦，专用于清晰查看楼内 1F~11F 教室/实验室/电梯/卫生间分布）
    if (showIndoorSecondaryMap && targetBuilding != null && activeBuildingFootprint?.hasIndoorFloors == true) {
        BuildingIndoorMapDialog(
            building = targetBuilding,
            footprint = activeBuildingFootprint,
            floorPlan = activeIndoorFloorPlan,
            isLoadingFloorPlan = isLoadingIndoorFloorPlan,
            selectedFloorIndex = selectedIndoorFloorIndex,
            selectedRoom = selectedIndoorRoom,
            onSelectFloor = { floorIdx ->
                selectedIndoorFloorIndex = floorIdx
            },
            onSelectRoom = { room ->
                selectedIndoorRoom = room
            },
            onConfirmRoomForOutdoorRoute = { room ->
                selectedIndoorRoom = room
                searchQuery = room.name
                inAppNavigationActive = true
                showIndoorSecondaryMap = false
                isFullScreenMap = true
            },
            onDismiss = {
                showIndoorSecondaryMap = false
            }
        )
    }

    if (showOfficialWebGisDialog) {
        OfficialGisWebViewDialog(
            onDismiss = { showOfficialWebGisDialog = false },
            onOpenExternalBrowser = {
                runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(HautOfficialGisClient.OFFICIAL_GIS_WEB_URL)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        )
    }

    if (showMapHospitalPharmacyDialog) {
        MapHospitalPharmacyDialog(
            onDismiss = { showMapHospitalPharmacyDialog = false },
            onOpenUrl = { url ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        )
    }

    if (showZoneImportExportDialog) {
        val currentExportJson = remember(detectedCampus, customZones, drawingZoneVertices, drawingZoneName, drawingZoneBuildings) {
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
        XPeachAlertDialog(
            onDismissRequest = { showZoneImportExportDialog = false },
            title = {
                Text(
                    text = "导入或分享校区",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "粘贴他人分享的配置，或复制当前配置：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = zoneAdaptationJsonInput.ifBlank { currentExportJson },
                        onValueChange = { zoneAdaptationJsonInput = it },
                        label = { Text("校区配置") },
                        minLines = 6,
                        maxLines = 10,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (zoneAdaptationFeedback != null) {
                        Text(
                            text = zoneAdaptationFeedback!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val textToCopy = zoneAdaptationJsonInput.ifBlank { currentExportJson }
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("campus_zone_json", textToCopy))
                                zoneAdaptationFeedback = "已复制到剪贴板"
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("复制配置")
                        }
                        if (customZones.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        repository.saveCustomCampusZonesJson("[]")
                                        repository.setSelectedCampusId("auto")
                                        zoneAdaptationFeedback = "已清空自定义校区"
                                    }
                                }
                            ) {
                                Text("清空")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val raw = zoneAdaptationJsonInput.ifBlank { currentExportJson }
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
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("导入")
                }
            },
            dismissButton = {
                TextButton(onClick = { showZoneImportExportDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (pendingPinPoint != null) {
        val pt = pendingPinPoint!!
        XPeachAlertDialog(
            onDismissRequest = { pendingPinPoint = null },
            title = {
                Text(
                    text = "添加校区建筑",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "坐标：${"%.5f".format(pt.latitude)}, ${"%.5f".format(pt.longitude)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = pendingPinName,
                        onValueChange = { pendingPinName = it },
                        label = { Text("建筑或地标名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
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
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPinPoint = null }) {
                    Text("取消")
                }
            }
        )
    }

    if (!isFullScreenMap) {
        // 默认展示「校园生活 + 地图预览」一体化概览页
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            XPeachTopBar(
                title = "校园",
                subtitle = "${activeSchool.displayName} · ${detectedCampus.campusName}",
                badgeText = detectedCampus.source.label,
                actions = {
                    if (isLianhuajieActive) {
                        XPeachFilterPill(
                            label = "网页地图",
                            selected = false,
                            onClick = { showOfficialWebGisDialog = true }
                        )
                    }
                    XPeachIconButton(
                        icon = Icons.Default.ZoomOutMap,
                        contentDescription = "展开全屏地图",
                        onClick = { isFullScreenMap = true }
                    )
                }
            )

            var isPreviewMapTouching by remember { mutableStateOf(false) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState(), enabled = !isPreviewMapTouching)
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (detectedCampus.isLowAccuracyGps) {
                    XPeachWell(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "当前定位精度较低 (±${detectedCampus.gpsAccuracyMeters?.roundToInt() ?: 100}m)，电子围栏自动识别存在不确定性，可在下方手动切换校区。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 0. 统一教室/实验室/楼宇搜索与校区切换卡片（与全屏地图无缝共享状态）
                XPeachCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "搜索教室、楼宇或服务设施",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        searchQuery = ""
                                        selectedIndoorRoom = null
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清空",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 官方 GIS 实时教室/房间/POI 搜索结果列表
                    if (searchQuery.isNotBlank() && officialSearchResults.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val displayedSearchResults = officialSearchResults.take(6)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 204.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            displayedSearchResults.forEachIndexed { idx, item ->
                                if (idx > 0) {
                                    XPeachInsetDivider(verticalPadding = 4.dp)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            handleSelectGisSearchResult(item, openSecondaryIndoorMap = item.isIndoorRoom)
                                            if (!item.isIndoorRoom) {
                                                isFullScreenMap = true
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = item.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            item.floorLabel?.let { floorLabel ->
                                                XPeachStatusDotText(
                                                    text = floorLabel,
                                                    dotColor = XPeachTheme.readablePrimary
                                                )
                                            }
                                        }
                                        Text(
                                            text = item.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        if (item.isIndoorRoom) {
                                            XPeachFilterPill(
                                                label = "室内平面",
                                                selected = true,
                                                compact = true,
                                                onClick = {
                                                    handleSelectGisSearchResult(item, openSecondaryIndoorMap = true)
                                                }
                                            )
                                        }
                                        XPeachFilterPill(
                                            label = if (item.isIndoorRoom) "室外路线" else "查看位置",
                                            selected = !item.isIndoorRoom,
                                            compact = true,
                                            onClick = {
                                                handleSelectGisSearchResult(item, openSecondaryIndoorMap = false)
                                                isFullScreenMap = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 校区与图层模式一体切换栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        XPeachFilterPill(
                            label = "自动识别",
                            selected = preferences.selectedCampusId == "auto",
                            onClick = { scope.launch { repository.setSelectedCampusId("auto") } }
                        )
                        XPeachFilterPill(
                            label = "莲花街校区",
                            selected = preferences.selectedCampusId == SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value,
                            onClick = {
                                scope.launch {
                                    repository.setSelectedCampusId(SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value)
                                }
                            }
                        )
                        XPeachFilterPill(
                            label = "嵩山路校区",
                            selected = preferences.selectedCampusId == SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value,
                            onClick = {
                                scope.launch {
                                    repository.setSelectedCampusId(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value)
                                }
                            }
                        )
                        for (zone in customZones) {
                            XPeachFilterPill(
                                label = zone.campusName,
                                selected = preferences.selectedCampusId == zone.campusId,
                                onClick = {
                                    scope.launch { repository.setSelectedCampusId(zone.campusId) }
                                }
                            )
                        }
                        XPeachFilterPill(
                            label = "+ 自定义校区",
                            selected = isDrawingZoneMode,
                            onClick = {
                                isDrawingZoneMode = true
                                drawingZoneVertices = emptyList()
                                drawingZoneBuildings = emptyList()
                                isFullScreenMap = true
                            }
                        )
                        XPeachFilterPill(
                            label = "导入/分享",
                            selected = false,
                            onClick = {
                                zoneAdaptationFeedback = null
                                zoneAdaptationJsonInput = ""
                                showZoneImportExportDialog = true
                            }
                        )
                    }
                }

                if (mapProvider == null && customZones.isEmpty()) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "暂未收录该校地图数据",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "暂未收录该校地图，可通过「+ 自定义校区」添加",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onNavigateToSchoolCenter,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("切换学校")
                        }
                    }
                }

                // 1. 交互式校园室外地图卡片（室内平面图拆分为独立二级地图页）
                XPeachCard(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = targetBuilding?.name ?: "校园地图",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    if (targetBuilding != null && navigationPlan != null) {
                                        append("步行约 ${navigationPlan.estimatedWalkMinutes} 分钟 (${navigationPlan.estimatedWalkDistanceMeters}m)")
                                        if (selectedIndoorRoom != null) {
                                            append(" · ${selectedIndoorRoom!!.floorLabel} ${selectedIndoorRoom!!.name}")
                                        } else if (!resolution?.roomNumberHint.isNullOrBlank()) {
                                            val precisionNote = if (resolution?.status == BuildingMatchStatus.EXACT_VERIFIED) {
                                                "已定位至楼栋"
                                            } else {
                                                "片区近似待确认，非单栋/房间精确坐标"
                                            }
                                            append(" · 房间 ${resolution!!.roomNumberHint}（$precisionNote）")
                                        }
                                    } else {
                                        append("双指缩放/拖拽或点击右侧按钮调整视野")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (activeBuildingFootprint?.hasIndoorFloors == true) {
                                XPeachFilterPill(
                                    label = "室内平面",
                                    selected = false,
                                    compact = true,
                                    onClick = { showIndoorSecondaryMap = true }
                                )
                            }
                            XPeachFilterPill(
                                label = "全屏地图",
                                selected = true,
                                compact = true,
                                onClick = { isFullScreenMap = true }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 室外底图模式切换条（不再混杂楼层按钮）
                    if (isLianhuajieActive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (mode in GisMapStyleMode.entries) {
                                XPeachFilterPill(
                                    label = mode.shortLabel,
                                    selected = gisStyleMode == mode,
                                    onClick = {
                                        if (mode == GisMapStyleMode.OFFICIAL_25D_2026) {
                                            CampusTileCache.clearFailureCooldowns()
                                        }
                                        gisStyleMode = mode
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(268.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                width = 0.8.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                    ) {
                        RealCampusTileMap(
                            buildings = campusBuildings,
                            selectedBuilding = targetBuilding,
                            userLocation = if (manualOriginBuilding == null) userLocation else null,
                            deviceHeadingDegrees = effectiveHeadingDegrees,
                            navigationPlan = if (inAppNavigationActive) navigationPlan else null,
                            centerLatGcj = mapCenterLatGcj,
                            centerLonGcj = mapCenterLonGcj,
                            zoom = mapZoom.coerceIn(12.6f, 18.8f),
                            styleMode = if (isLianhuajieActive) gisStyleMode else GisMapStyleMode.STREET_STANDARD,
                            gisDataset = if (isLianhuajieActive) gisDataset else null,
                            activeBuildingFootprint = activeBuildingFootprint,
                            selectedIndoorRoom = selectedIndoorRoom,
                            campusPolygonVertices = activeCampusPolygon,
                            onTouchActiveChange = { isPreviewMapTouching = it },
                            onViewportChange = { newLat, newLon, newZoom ->
                                mapCenterLatGcj = newLat
                                mapCenterLonGcj = newLon
                                mapZoom = newZoom.coerceIn(12.6f, 18.8f)
                            },
                            onSelectBuilding = { clicked ->
                                selectedBuilding = clicked
                                inAppNavigationActive = true
                                isFullScreenMap = true
                            }
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            XPeachIconButton(
                                icon = Icons.Default.Add,
                                contentDescription = "放大地图",
                                onClick = { mapZoom = (mapZoom + 0.55f).coerceIn(12.6f, 18.8f) }
                            )
                            XPeachIconButton(
                                icon = Icons.Default.Remove,
                                contentDescription = "缩小地图",
                                onClick = { mapZoom = (mapZoom - 0.55f).coerceIn(12.6f, 18.8f) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 热门教学楼与地标一键导航条
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "常用地点：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                        for (b in campusBuildings.take(12)) {
                            XPeachFilterPill(
                                label = b.shortName,
                                selected = selectedBuilding?.id == b.id,
                                onClick = {
                                    selectedBuilding = b
                                    selectedIndoorRoom = null
                                    val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
                                    mapCenterLatGcj = glat
                                    mapCenterLonGcj = glon
                                    inAppNavigationActive = true
                                }
                            )
                        }
                    }
                }

                // 2. 校园生活与社群 Bento 矩阵（2×2）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = onNavigateToElectricity
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "宿舍电费",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            )
                            XPeachStatusDotText(
                                text = elecBadge,
                                dotColor = XPeachTheme.readablePrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = elecSub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = onNavigateToEmptyClassrooms
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "空闲教室",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 4.dp)
                            )
                            XPeachStatusDotText(
                                text = "教学楼",
                                dotColor = XPeachTheme.readablePrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "按教学楼与节次查询",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = onNavigateToCommunity
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "合作社团",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            XPeachStatusDotText(
                                text = "${preferences.campusCommunityCatalog.clubs.size} 社团",
                                dotColor = XPeachTheme.readablePrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "校园社团与交流群",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    val competitionsCount = preferences.campusCommunityCatalog.competitions.size
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = onNavigateToCompetitions
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "竞赛指南",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            XPeachStatusDotText(
                                text = "$competitionsCount 赛事",
                                dotColor = XPeachTheme.readablePrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "学科竞赛日程与报名信息",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (activeSchool.id == SchoolDirectory.HAUT_SCHOOL_ID) {
                    XPeachCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        onClick = { showMapHospitalPharmacyDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "校医院药品价格公示与医保说明",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "收录 ${CampusHospitalPharmacyCatalog.ITEMS.size} 项西药/中成药公示单价 · 致谢 @${CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            XPeachStatusDotText(
                                text = "检索 ›",
                                dotColor = XPeachTheme.readablePrimary
                            )
                        }
                    }
                }

                // 3. 校区地标与建筑目录
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "校园地点",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "共 ${filteredBuildings.size} 处",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        XPeachFilterPill(
                            label = "全部",
                            selected = selectedCategory == null,
                            compact = true,
                            onClick = { selectedCategory = null }
                        )
                        for (category in CampusCategory.entries) {
                            XPeachFilterPill(
                                label = category.label,
                                selected = selectedCategory == category,
                                compact = true,
                                onClick = { selectedCategory = if (selectedCategory == category) null else category }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        filteredBuildings.forEachIndexed { idx, b ->
                            val gisInfo = HautOfficialGisClient.resolveFeatureIdForBuilding(b, gisDataset)
                            val hasIndoor = gisInfo != null && gisInfo.second >= 1
                            if (idx > 0) {
                                XPeachInsetDivider(verticalPadding = 4.dp)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        selectedBuilding = b
                                        selectedIndoorRoom = null
                                        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
                                        mapCenterLatGcj = glat
                                        mapCenterLonGcj = glon
                                        mapZoom = if (hasIndoor) 17.3f else 16.6f
                                        inAppNavigationActive = true
                                        isFullScreenMap = true
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = b.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (hasIndoor) {
                                            XPeachStatusDotText(
                                                text = "1-${gisInfo!!.second + 1}F",
                                                dotColor = XPeachTheme.readablePrimary
                                            )
                                        }
                                    }
                                    if (b.description.isNotBlank()) {
                                        Text(
                                            text = b.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (hasIndoor) {
                                        XPeachFilterPill(
                                            label = "室内图",
                                            selected = false,
                                            compact = true,
                                            onClick = {
                                                selectedBuilding = b
                                                selectedIndoorRoom = null
                                                showIndoorSecondaryMap = true
                                            }
                                        )
                                    }
                                    XPeachFilterPill(
                                        label = "去这里",
                                        selected = selectedBuilding?.id == b.id,
                                        compact = true,
                                        onClick = {
                                            selectedBuilding = b
                                            selectedIndoorRoom = null
                                            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
                                            mapCenterLatGcj = glat
                                            mapCenterLonGcj = glon
                                            mapZoom = 16.8f
                                            inAppNavigationActive = true
                                            isFullScreenMap = true
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(92.dp))
            }
        }
        return
    }

    // 全屏沉浸式交互地图容器（彻底移除外层 verticalScroll，保证单指拖拽与双指缩放 100% 顺滑响应）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (XPeachTheme.isDark) Color(0xFF0F151E) else Color(0xFFF2EFE9))
    ) {
        // 1. 底层全屏室外地图画布（室内平面图已拆分为独立二级地图页，室外地图仅聚焦楼宇与步行路线）
        RealCampusTileMap(
            buildings = if (isDrawingZoneMode) (campusBuildings + drawingZoneBuildings) else campusBuildings,
            selectedBuilding = targetBuilding,
            userLocation = if (manualOriginBuilding == null) userLocation else null,
            deviceHeadingDegrees = effectiveHeadingDegrees,
            navigationPlan = if (inAppNavigationActive && !isDrawingZoneMode) navigationPlan else null,
            centerLatGcj = mapCenterLatGcj,
            centerLonGcj = mapCenterLonGcj,
            zoom = mapZoom,
            styleMode = if (isLianhuajieActive) gisStyleMode else GisMapStyleMode.STREET_STANDARD,
            gisDataset = if (isLianhuajieActive) gisDataset else null,
            activeBuildingFootprint = activeBuildingFootprint,
            selectedIndoorRoom = selectedIndoorRoom,
            campusPolygonVertices = activeCampusPolygon,
            drawingPolygonVertices = drawingZoneVertices,
            isDrawingZoneMode = isDrawingZoneMode,
            onTapMapWgs84 = { wgsLat, wgsLon ->
                if (isDrawingZoneMode) {
                    drawingZoneVertices = drawingZoneVertices + GeoPoint(wgsLat, wgsLon)
                }
            },
            onViewportChange = { newLat, newLon, newZoom ->
                if (abs(newLat - mapCenterLatGcj) > 1e-5 || abs(newLon - mapCenterLonGcj) > 1e-5) {
                    isFollowingUser = false
                }
                mapCenterLatGcj = newLat
                mapCenterLonGcj = newLon
                mapZoom = newZoom
                if (expandRouteSteps) {
                    expandRouteSteps = false
                }
            },
            onSelectBuilding = { clicked ->
                if (selectedBuilding?.id != clicked.id) {
                    selectedIndoorRoom = null
                }
                selectedBuilding = clicked
                inAppNavigationActive = true
            }
        )

        // 2. 顶部一体化控制栏（返回 + 官方GIS教室/建筑搜索 + 罗盘 + 图层/分类胶囊 + 搜索下拉结果）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                XPeachGlassSurface(
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 2.dp,
                    modifier = Modifier.clickable { isFullScreenMap = false }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回校园页",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "收起",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                XPeachGlassSurface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 2.dp
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "搜索教室或地点",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        searchQuery = ""
                                        selectedIndoorRoom = null
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "清空",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedBorderColor = XPeachTheme.readablePrimary.copy(alpha = 0.45f),
                            unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 朝向指示胶囊
                XPeachGlassSurface(
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 2.dp,
                    modifier = Modifier.clickable {
                        if (!hasLocationPermission) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        } else {
                            isFollowingUser = true
                            locationRefreshEpoch++
                        }
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = "朝向",
                            tint = XPeachTheme.readablePrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        val headingInt = effectiveHeadingDegrees?.roundToInt()
                        Text(
                            text = if (headingInt != null) {
                                "${CampusMapNavigator.bearingToCompassLabel(headingInt)} ${headingInt}°"
                            } else if (!hasLocationPermission) {
                                "开启定位"
                            } else {
                                "朝向"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // 图层样式、官方网页版与建筑分类筛选行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLianhuajieActive) {
                    for (mode in GisMapStyleMode.entries) {
                        XPeachFilterPill(
                            label = mode.shortLabel,
                            selected = gisStyleMode == mode,
                            compact = true,
                            onClick = {
                                if (mode == GisMapStyleMode.OFFICIAL_25D_2026) {
                                    CampusTileCache.clearFailureCooldowns()
                                }
                                gisStyleMode = mode
                            }
                        )
                    }
                    XPeachFilterPill(
                        label = "网页地图",
                        selected = false,
                        compact = true,
                        onClick = { showOfficialWebGisDialog = true }
                    )
                }
                XPeachFilterPill(
                    label = "莲花街",
                    selected = detectedCampus.campusId == SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value,
                    compact = true,
                    onClick = {
                        scope.launch {
                            repository.setSelectedCampusId(SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value)
                        }
                    }
                )
                XPeachFilterPill(
                    label = "嵩山路",
                    selected = detectedCampus.campusId == SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value,
                    compact = true,
                    onClick = {
                        scope.launch {
                            repository.setSelectedCampusId(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value)
                        }
                    }
                )
                for (zone in customZones) {
                    XPeachFilterPill(
                        label = zone.campusName,
                        selected = detectedCampus.campusId == zone.campusId,
                        compact = true,
                        onClick = {
                            scope.launch { repository.setSelectedCampusId(zone.campusId) }
                        }
                    )
                }
                XPeachFilterPill(
                    label = "全部建筑",
                    selected = selectedCategory == null,
                    compact = true,
                    onClick = { selectedCategory = null }
                )
                for (category in CampusCategory.entries) {
                    XPeachFilterPill(
                        label = category.label,
                        selected = selectedCategory == category,
                        compact = true,
                        onClick = {
                            selectedCategory = if (selectedCategory == category) null else category
                        }
                    )
                }
                XPeachFilterPill(
                    label = if (isDrawingZoneMode) "退出编辑" else "自定义校区",
                    selected = isDrawingZoneMode,
                    compact = true,
                    onClick = {
                        isDrawingZoneMode = !isDrawingZoneMode
                        if (isDrawingZoneMode && drawingZoneVertices.isEmpty()) {
                            drawingZoneName = ""
                            drawingZoneBuildings = emptyList()
                        }
                    }
                )
            }

            // 搜索结果下拉面板
            AnimatedVisibility(visible = searchQuery.isNotBlank() && officialSearchResults.isNotEmpty()) {
                XPeachCard(
                    contentPadding = PaddingValues(10.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "搜索结果",
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${officialSearchResults.size} 项",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (item in officialSearchResults.take(10)) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.clickable {
                                    handleSelectGisSearchResult(item)
                                }
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        item.floorLabel?.let { floorLabel ->
                                            Text(
                                                text = floorLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = XPeachTheme.readablePrimary,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 自定义校区边界绘制控制条
            if (isDrawingZoneMode) {
                XPeachCard(
                    contentPadding = PaddingValues(12.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)
                ) {
                    Text(
                        text = "绘制校区边界（${drawingZoneVertices.size} 个点，${drawingZoneBuildings.size} 处建筑）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "点击地图添加至少 3 个边界点",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = drawingZoneName,
                        onValueChange = { drawingZoneName = it },
                        placeholder = { Text("校区名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val (wLat, wLon) = CampusMapNavigator.gcj02ToWgs84(mapCenterLatGcj, mapCenterLonGcj)
                                pendingPinPoint = GeoPoint(wLat, wLon)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("添加建筑")
                        }
                        OutlinedButton(
                            enabled = drawingZoneVertices.isNotEmpty(),
                            onClick = {
                                drawingZoneVertices = drawingZoneVertices.dropLast(1)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("撤销")
                        }
                        Button(
                            enabled = drawingZoneVertices.size >= 3,
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
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
                        ) {
                            Text("保存")
                        }
                    }
                }
            }

            // 模糊教室 / 未知教室 / 片区近似点人工纠正条（保留推荐短名单，同时完整展示当前校区全部可选楼宇）
            if (resolution != null && resolution.status != BuildingMatchStatus.EXACT_VERIFIED) {
                val approxTarget = resolution.building
                val manualOptions = remember(resolution, campusBuildings, detectedCampus.campusId) {
                    ClassroomBuildingResolver.buildManualConfirmationOptions(
                        resolution = resolution,
                        buildings = campusBuildings,
                        campusContext = detectedCampus.campusId
                    )
                }
                XPeachCard(
                    contentPadding = PaddingValues(10.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (approxTarget != null) {
                                "片区近似/待确认：推荐「${approxTarget.name}」，也可从下方当前校区全部楼宇中手动纠正"
                            } else {
                                "位置待人工确认：已列出推荐项与当前校区全部可选教学/实验楼宇"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "确认楼宇：",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                            for (candidate in manualOptions.orderedChoices) {
                                val isDormZone = candidate.category == CampusCategory.DORMITORY
                                val isRecommended = candidate.id in manualOptions.recommendedIds
                                val pillLabel = when {
                                    isDormZone -> "${candidate.shortName}(片区近似)"
                                    isRecommended -> "推荐·${candidate.shortName}"
                                    else -> candidate.shortName
                                }
                                XPeachFilterPill(
                                    label = pillLabel,
                                    selected = selectedBuilding?.id == candidate.id,
                                    onClick = {
                                        scope.launch {
                                            repository.confirmBuildingAliasForRoom(
                                                rawRoom = searchQuery,
                                                buildingId = candidate.id,
                                                activeCampusId = detectedCampus.campusId
                                            )
                                            selectedBuilding = candidate
                                            isFollowingUser = false
                                            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
                                                candidate.latitude,
                                                candidate.longitude
                                            )
                                            mapCenterLatGcj = glat
                                            mapCenterLonGcj = glon
                                            inAppNavigationActive = true
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. 右侧中部悬浮地图控制按钮组（放大、缩小、全览路线、回到定位）
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            XPeachIconButton(
                icon = Icons.Default.Add,
                contentDescription = "放大地图",
                onClick = { mapZoom = (mapZoom + 0.55f).coerceIn(12.6f, 18.8f) }
            )
            XPeachIconButton(
                icon = Icons.Default.Remove,
                contentDescription = "缩小地图",
                onClick = { mapZoom = (mapZoom - 0.55f).coerceIn(12.6f, 18.8f) }
            )
            XPeachIconButton(
                icon = Icons.Default.ZoomOutMap,
                contentDescription = "全览路线",
                onClick = {
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
            )
            XPeachIconButton(
                icon = Icons.Default.MyLocation,
                contentDescription = "定位当前位置",
                onClick = {
                    if (!hasLocationPermission) {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
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
            )
        }

        // 4. 底部楼宇选择与路线导航面板（点击「查看室内平面图」可进入独立二级室内地图）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 86.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (b in filteredBuildings) {
                    val isSelected = b.id == targetBuilding?.id
                    XPeachFilterPill(
                        label = b.shortName,
                        selected = isSelected,
                        compact = true,
                        onClick = {
                            if (selectedBuilding?.id != b.id) {
                                selectedIndoorRoom = null
                            }
                            selectedBuilding = b
                            isFollowingUser = false
                            inAppNavigationActive = true
                            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
                            mapCenterLatGcj = glat
                            mapCenterLonGcj = glon
                        }
                    )
                }
            }

            if (targetBuilding != null && navigationPlan != null) {
                FloatingRouteBottomSheet(
                    building = targetBuilding,
                    plan = navigationPlan,
                    isOfficialGisRoute = isOfficialRouteActive,
                    activeFootprint = activeBuildingFootprint,
                    selectedIndoorRoom = selectedIndoorRoom,
                    allBuildings = buildings,
                    hasLiveGps = userLocation?.isInsideLianhuajieCampus == true ||
                        userLocation?.isInsideSongshanluCampus == true,
                    deviceHeadingDegrees = effectiveHeadingDegrees,
                    manualOriginBuilding = manualOriginBuilding,
                    inAppNavigationActive = inAppNavigationActive,
                    expandRouteSteps = expandRouteSteps,
                    onToggleExpandSteps = { expandRouteSteps = !expandRouteSteps },
                    onOpenIndoorSecondaryMap = {
                        showIndoorSecondaryMap = true
                    },
                    onSelectOriginBuilding = { origin ->
                        manualOriginBuilding = origin
                        if (origin == null) {
                            isFollowingUser = true
                            locationRefreshEpoch++
                        } else {
                            isFollowingUser = false
                        }
                        inAppNavigationActive = true
                    },
                    onToggleInAppNavigation = {
                        inAppNavigationActive = !inAppNavigationActive
                    },
                    onOpenOfficialWebGis = {
                        showOfficialWebGisDialog = true
                    },
                    onOpenExternalMap = {
                        CampusMapNavigator.openWalkingNavigation(context, targetBuilding)
                    },
                    onOpenHospitalPharmacy = onNavigateToHospitalPharmacy
                )
            }
        }
    }
}
