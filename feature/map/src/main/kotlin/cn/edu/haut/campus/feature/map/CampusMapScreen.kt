package cn.edu.haut.campus.feature.map

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachBrandLight
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachOk
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.location.InAppNavigationPlan
import cn.edu.haut.campus.core.location.UserGeoLocation
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

private object CampusTileCache {
    private val memoryCache = LruCache<String, ImageBitmap>(128)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun get(key: String): ImageBitmap? = synchronized(memoryCache) { memoryCache.get(key) }

    fun put(key: String, bitmap: ImageBitmap) = synchronized(memoryCache) {
        memoryCache.put(key, bitmap)
    }

    suspend fun loadTileIfNeeded(
        cacheDir: File,
        z: Int,
        x: Int,
        y: Int,
        onLoaded: () -> Unit
    ) {
        val key = "${z}_${x}_${y}"
        if (get(key) != null) return
        if (!inFlight.add(key)) return

        try {
            val bitmap = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "haut_map_tiles_v2").apply { mkdirs() }
                val diskFile = File(dir, "$key.png")
                if (diskFile.exists() && diskFile.length() > 256L) {
                    runCatching {
                        BitmapFactory.decodeFile(diskFile.absolutePath)?.asImageBitmap()
                    }.getOrNull()?.let { return@withContext it }
                }

                val subdomain = ((x + y) % 4 + 4) % 4 + 1
                val urls = listOf(
                    "https://webrd0$subdomain.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=2&style=8&x=$x&y=$y&z=$z",
                    "https://tile.openstreetmap.org/$z/$x/$y.png"
                )
                for (urlStr in urls) {
                    val downloaded = runCatching {
                        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 6000
                            readTimeout = 6000
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36")
                        }
                        if (conn.responseCode == 200) {
                            val bytes = conn.inputStream.use { it.readBytes() }
                            if (bytes.size > 256) {
                                diskFile.writeBytes(bytes)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } else null
                        } else null
                    }.getOrNull()
                    if (downloaded != null) return@withContext downloaded
                }
                null
            }
            if (bitmap != null) {
                put(key, bitmap)
                onLoaded()
            }
        } finally {
            inFlight.remove(key)
        }
    }
}

@Composable
fun CampusMapScreen(
    repository: CampusRepository,
    initialClassroomQuery: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())
    val aliases by repository.buildingAliasesFlow.collectAsState(initial = emptyList())

    var searchQuery by remember(initialClassroomQuery) {
        mutableStateOf(initialClassroomQuery.orEmpty())
    }
    var selectedCategory by remember { mutableStateOf<CampusCategory?>(null) }
    var selectedBuilding by remember { mutableStateOf<Building?>(null) }
    var manualOriginBuilding by remember { mutableStateOf<Building?>(null) }
    var inAppNavigationActive by remember { mutableStateOf(true) }
    var expandRouteSteps by remember { mutableStateOf(false) }

    var hasLocationPermission by remember {
        mutableStateOf(CampusMapNavigator.hasLocationPermission(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasLocationPermission = grants.values.any { it } || CampusMapNavigator.hasLocationPermission(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationPermission = CampusMapNavigator.hasLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 实时 GPS 定位流与电子罗盘朝向传感器流
    val userLocationFlow = remember(hasLocationPermission, context) {
        CampusMapNavigator.observeUserLocation(context)
    }
    val userLocation by userLocationFlow.collectAsState(initial = null)

    val deviceHeadingFlow = remember(context) {
        CampusMapNavigator.observeDeviceHeadingDegrees(context)
    }
    val sensorHeadingDegrees by deviceHeadingFlow.collectAsState(initial = null)
    val effectiveHeadingDegrees = sensorHeadingDegrees ?: userLocation?.bearingDegrees

    val resolution = remember(searchQuery, buildings, aliases) {
        if (searchQuery.isBlank()) null
        else ClassroomBuildingResolver.resolve(searchQuery, buildings, aliases)
    }

    // 真实地图视口中心（GCJ-02 坐标）与缩放级别
    val defaultCenterGcj = remember {
        CampusMapNavigator.wgs84ToGcj02(CampusMapNavigator.CAMPUS_CENTER_LAT, CampusMapNavigator.CAMPUS_CENTER_LNG)
    }
    var mapCenterLatGcj by remember { mutableStateOf(defaultCenterGcj.first) }
    var mapCenterLonGcj by remember { mutableStateOf(defaultCenterGcj.second) }
    var mapZoom by remember { mutableFloatStateOf(16.2f) }

    LaunchedEffect(resolution, buildings) {
        if (resolution?.building != null) {
            val b = resolution.building!!
            selectedBuilding = b
            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
            mapCenterLatGcj = glat
            mapCenterLonGcj = glon
            inAppNavigationActive = true
        } else if (selectedBuilding == null && buildings.isNotEmpty()) {
            selectedBuilding = buildings.first()
        }
    }

    val filteredBuildings = remember(buildings, selectedCategory, searchQuery, resolution) {
        buildings.filter { b ->
            val catOk = selectedCategory == null || b.category == selectedCategory
            val textOk = searchQuery.isBlank() ||
                b.name.contains(searchQuery, ignoreCase = true) ||
                b.shortName.contains(searchQuery, ignoreCase = true) ||
                b.description?.contains(searchQuery, ignoreCase = true) == true ||
                resolution?.building?.id == b.id
            catOk && textOk
        }
    }

    val targetBuilding = selectedBuilding ?: filteredBuildings.firstOrNull()
    val navigationPlan: InAppNavigationPlan? = remember(
        targetBuilding,
        userLocation,
        manualOriginBuilding,
        buildings,
        searchQuery
    ) {
        if (targetBuilding == null) null
        else CampusMapNavigator.buildInAppNavigationPlan(
            userLocation = if (manualOriginBuilding == null) userLocation else null,
            fallbackOriginBuilding = manualOriginBuilding
                ?: buildings.firstOrNull { it.id == "b_gate_south" }
                ?: buildings.firstOrNull(),
            destinationBuilding = targetBuilding,
            allBuildings = buildings,
            rawClassroom = searchQuery.takeIf { it.isNotBlank() }
        )
    }

    // 全屏沉浸式交互地图容器（彻底移除外层 verticalScroll，保证单指拖拽与双指缩放 100% 顺滑响应）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF2EFE9))
    ) {
        // 1. 底层全屏实景瓦片地图 + 电子罗盘朝向扇形 + 校园路网动态折线
        RealCampusTileMap(
            buildings = buildings,
            selectedBuilding = targetBuilding,
            userLocation = userLocation,
            deviceHeadingDegrees = effectiveHeadingDegrees,
            navigationPlan = if (inAppNavigationActive) navigationPlan else null,
            centerLatGcj = mapCenterLatGcj,
            centerLonGcj = mapCenterLonGcj,
            zoom = mapZoom,
            onViewportChange = { newLat, newLon, newZoom ->
                mapCenterLatGcj = newLat
                mapCenterLonGcj = newLon
                mapZoom = newZoom
            },
            onSelectBuilding = { clicked ->
                selectedBuilding = clicked
                inAppNavigationActive = true
            }
        )

        // 2. 顶部悬浮搜索栏、分类筛选与实时朝向罗盘徽章
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
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 2.dp
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                text = "搜索教室或楼宇（如 1204、图书馆）",
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
                                IconButton(onClick = { searchQuery = "" }) {
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
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            unfocusedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 实时电子罗盘朝向指示胶囊
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 2.dp,
                    modifier = Modifier.clickable {
                        if (!hasLocationPermission) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
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
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        val headingInt = effectiveHeadingDegrees?.roundToInt()
                        Text(
                            text = if (headingInt != null) {
                                "${CampusMapNavigator.bearingToCompassLabel(headingInt)} ${headingInt}°"
                            } else if (!hasLocationPermission) {
                                "授权定位"
                            } else {
                                "罗盘就绪"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }

            // 分类筛选胶囊行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                XPeachFilterPill(
                    label = "全部",
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null }
                )
                for (category in CampusCategory.entries) {
                    XPeachFilterPill(
                        label = category.label,
                        selected = selectedCategory == category,
                        onClick = {
                            selectedCategory = if (selectedCategory == category) null else category
                        }
                    )
                }
            }

            // 模糊教室人工确认条
            if (resolution != null && resolution.status != BuildingMatchStatus.EXACT_VERIFIED) {
                XPeachCard(
                    contentPadding = PaddingValues(10.dp),
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "确认归属：",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false
                        )
                        for (candidate in buildings.filter {
                            it.category == CampusCategory.TEACHING_BUILDING || it.category == CampusCategory.LAB
                        }) {
                            XPeachFilterPill(
                                label = candidate.shortName,
                                selected = selectedBuilding?.id == candidate.id,
                                onClick = {
                                    scope.launch {
                                        repository.confirmBuildingAliasForRoom(searchQuery, candidate.id)
                                        selectedBuilding = candidate
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

        // 3. 右侧中部悬浮地图控制按钮组（放大、缩小、全览路线、回到定位）
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            XPeachIconButton(
                icon = Icons.Default.Add,
                contentDescription = "放大地图",
                onClick = { mapZoom = (mapZoom + 0.55f).coerceIn(14.8f, 18.5f) }
            )
            XPeachIconButton(
                icon = Icons.Default.Remove,
                contentDescription = "缩小地图",
                onClick = { mapZoom = (mapZoom - 0.55f).coerceIn(14.8f, 18.5f) }
            )
            XPeachIconButton(
                icon = Icons.Default.ZoomOutMap,
                contentDescription = "全览路线",
                onClick = {
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
                        mapZoom = 16.0f
                    } else {
                        mapCenterLatGcj = defaultCenterGcj.first
                        mapCenterLonGcj = defaultCenterGcj.second
                        mapZoom = 16.2f
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
                    } else if (userLocation?.isInsideLianhuajieCampus == true) {
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
                        mapZoom = 16.2f
                    }
                }
            )
        }

        // 4. 底部悬浮楼宇快选与动态路网导航面板
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 横向快选地标条（一指滑动即可切换目的地，不遮挡地图主视野）
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
                        onClick = {
                            selectedBuilding = b
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
                    allBuildings = buildings,
                    hasLiveGps = userLocation?.isInsideLianhuajieCampus == true,
                    deviceHeadingDegrees = effectiveHeadingDegrees,
                    manualOriginBuilding = manualOriginBuilding,
                    inAppNavigationActive = inAppNavigationActive,
                    expandRouteSteps = expandRouteSteps,
                    onToggleExpandSteps = { expandRouteSteps = !expandRouteSteps },
                    onSelectOriginBuilding = { origin ->
                        manualOriginBuilding = origin
                        inAppNavigationActive = true
                    },
                    onToggleInAppNavigation = {
                        inAppNavigationActive = !inAppNavigationActive
                    },
                    onOpenExternalMap = {
                        CampusMapNavigator.openWalkingNavigation(context, targetBuilding)
                    }
                )
            }
        }
    }
}

@Composable
private fun FloatingRouteBottomSheet(
    building: Building,
    plan: InAppNavigationPlan,
    allBuildings: List<Building>,
    hasLiveGps: Boolean,
    deviceHeadingDegrees: Float?,
    manualOriginBuilding: Building?,
    inAppNavigationActive: Boolean,
    expandRouteSteps: Boolean,
    onToggleExpandSteps: () -> Unit,
    onSelectOriginBuilding: (Building?) -> Unit,
    onToggleInAppNavigation: () -> Unit,
    onOpenExternalMap: () -> Unit
) {
    // 计算用户当前朝向与路线初始方向之间的偏角提示
    val headingHint = remember(deviceHeadingDegrees, plan.bearingDegrees) {
        if (deviceHeadingDegrees == null) {
            "方向：${plan.compassDirectionLabel} (${plan.bearingDegrees}°)"
        } else {
            var diff = (plan.bearingDegrees - deviceHeadingDegrees.roundToInt()) % 360
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            when {
                abs(diff) <= 22 -> "当前朝向正对路线方向"
                diff > 22 -> "请向右转约 ${abs(diff)}° 对准路线"
                else -> "请向左转约 ${abs(diff)}° 对准路线"
            }
        }
    }

    XPeachCard(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
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
                    .padding(end = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = building.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    XPeachStatusBadge(
                        text = "步行 ${plan.estimatedWalkDistanceMeters}m · ${plan.estimatedWalkMinutes}分钟"
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${plan.originLabel} → ${building.shortName} · $headingHint",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            XPeachFilterPill(
                label = if (expandRouteSteps) "收起路线" else "分步详情 (${plan.steps.size})",
                selected = expandRouteSteps,
                onClick = onToggleExpandSteps
            )
        }

        if (expandRouteSteps) {
            Spacer(modifier = Modifier.height(10.dp))

            // 起点快速切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "起点：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                XPeachFilterPill(
                    label = if (hasLiveGps) "实时定位" else "南门",
                    selected = manualOriginBuilding == null,
                    onClick = { onSelectOriginBuilding(null) }
                )
                for (b in allBuildings.filter { it.id != building.id }) {
                    XPeachFilterPill(
                        label = b.shortName,
                        selected = manualOriginBuilding?.id == b.id,
                        onClick = { onSelectOriginBuilding(b) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (step in plan.steps) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${step.stepIndex}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = step.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${step.distanceMeters}m",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onToggleInAppNavigation,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (inAppNavigationActive) "隐藏路线折线" else "显示动态路线",
                    maxLines = 1,
                    softWrap = false
                )
            }

            FilledTonalButton(
                onClick = onOpenExternalMap,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("高德步行", maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * 全屏真实瓦片地图组件（Web Mercator Slippy Map + 顺滑拖拽缩放 + 电子罗盘朝向扇形 + 校园路网多节点动态路径）
 */
@Composable
private fun RealCampusTileMap(
    buildings: List<Building>,
    selectedBuilding: Building?,
    userLocation: UserGeoLocation?,
    deviceHeadingDegrees: Float?,
    navigationPlan: InAppNavigationPlan?,
    centerLatGcj: Double,
    centerLonGcj: Double,
    zoom: Float,
    onViewportChange: (Double, Double, Float) -> Unit,
    onSelectBuilding: (Building) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tileLoadGeneration by remember { mutableIntStateOf(0) }
    var canvasWidthPx by remember { mutableFloatStateOf(1080f) }
    var canvasHeightPx by remember { mutableFloatStateOf(1920f) }

    val tileZ = zoom.roundToInt().coerceIn(15, 18)
    val scaleFactor = 2.0.pow((zoom - tileZ).toDouble()).toFloat()
    val baseTileRenderPx = 256f * context.resources.displayMetrics.density * 0.90f * scaleFactor

    fun latLonToTileXY(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z.toDouble())
        val x = (lon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = (1.0 - asinh(tan(latRad)) / PI) / 2.0 * n
        return x to y
    }

    fun tileXYToLatLon(tx: Double, ty: Double, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z.toDouble())
        val lon = tx / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1.0 - 2.0 * ty / n)))
        val lat = Math.toDegrees(latRad)
        return lat to lon
    }

    val (centerTileX, centerTileY) = remember(centerLatGcj, centerLonGcj, tileZ) {
        latLonToTileXY(centerLatGcj, centerLonGcj, tileZ)
    }

    // 使用 rememberUpdatedState 保持 pointerInput(Unit) 手势协程永不因坐标更新而中断重启！
    val currentCenterLat by rememberUpdatedState(centerLatGcj)
    val currentCenterLon by rememberUpdatedState(centerLonGcj)
    val currentZoom by rememberUpdatedState(zoom)
    val currentTileZ by rememberUpdatedState(tileZ)
    val currentBaseTilePx by rememberUpdatedState(baseTileRenderPx)
    val currentBuildings by rememberUpdatedState(buildings)
    val currentOnViewportChange by rememberUpdatedState(onViewportChange)
    val currentOnSelectBuilding by rememberUpdatedState(onSelectBuilding)

    fun gcjToScreenDynamic(latGcj: Double, lonGcj: Double): Offset {
        val (cTx, cTy) = latLonToTileXY(currentCenterLat, currentCenterLon, currentTileZ)
        val (tx, ty) = latLonToTileXY(latGcj, lonGcj, currentTileZ)
        val sx = (canvasWidthPx / 2f) + ((tx - cTx).toFloat() * currentBaseTilePx)
        val sy = (canvasHeightPx / 2f) + ((ty - cTy).toFloat() * currentBaseTilePx)
        return Offset(sx, sy)
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val hit = currentBuildings.minByOrNull { b ->
                        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
                        val pt = gcjToScreenDynamic(glat, glon)
                        hypot((pt.x - tapOffset.x).toDouble(), (pt.y - tapOffset.y).toDouble())
                    }
                    if (hit != null) {
                        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(hit.latitude, hit.longitude)
                        val pt = gcjToScreenDynamic(glat, glon)
                        if (hypot((pt.x - tapOffset.x).toDouble(), (pt.y - tapOffset.y).toDouble()) <= 96.0) {
                            currentOnSelectBuilding(hit)
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val zNow = currentTileZ
                    val tilePxNow = currentBaseTilePx
                    val (cTx, cTy) = latLonToTileXY(currentCenterLat, currentCenterLon, zNow)
                    val newZoom = (currentZoom + (ln(gestureZoom.toDouble()) / ln(2.0)).toFloat())
                        .coerceIn(14.8f, 18.5f)
                    val dTileX = -pan.x / tilePxNow
                    val dTileY = -pan.y / tilePxNow
                    val (newLat, newLon) = tileXYToLatLon(
                        cTx + dTileX,
                        cTy + dTileY,
                        zNow
                    )
                    currentOnViewportChange(
                        newLat.coerceIn(34.812, 34.852),
                        newLon.coerceIn(113.520, 113.568),
                        newZoom
                    )
                }
            }
    ) {
        val gen = tileLoadGeneration
        if (gen < 0) return@Canvas

        canvasWidthPx = size.width
        canvasHeightPx = size.height

        fun gcjToScreen(latGcj: Double, lonGcj: Double): Offset {
            val (tx, ty) = latLonToTileXY(latGcj, lonGcj, tileZ)
            val sx = (size.width / 2f) + ((tx - centerTileX).toFloat() * baseTileRenderPx)
            val sy = (size.height / 2f) + ((ty - centerTileY).toFloat() * baseTileRenderPx)
            return Offset(sx, sy)
        }

        // 1. 地图底色
        drawRect(color = Color(0xFFF2EFE9))

        // 2. 绘制覆盖视口的真实街道/校园瓦片
        val halfTilesW = (size.width / 2f) / baseTileRenderPx + 1f
        val halfTilesH = (size.height / 2f) / baseTileRenderPx + 1f
        val minTx = floor(centerTileX - halfTilesW).toInt()
        val maxTx = ceil(centerTileX + halfTilesW).toInt()
        val minTy = floor(centerTileY - halfTilesH).toInt()
        val maxTy = ceil(centerTileY + halfTilesH).toInt()

        for (tx in minTx..maxTx) {
            for (ty in minTy..maxTy) {
                val leftPx = (size.width / 2f) + ((tx - centerTileX).toFloat() * baseTileRenderPx)
                val topPx = (size.height / 2f) + ((ty - centerTileY).toFloat() * baseTileRenderPx)
                val key = "${tileZ}_${tx}_${ty}"
                val bmp = CampusTileCache.get(key)
                if (bmp != null) {
                    drawImage(
                        image = bmp,
                        dstOffset = IntOffset(leftPx.roundToInt(), topPx.roundToInt()),
                        dstSize = IntSize(ceil(baseTileRenderPx).toInt(), ceil(baseTileRenderPx).toInt())
                    )
                } else {
                    scope.launch {
                        CampusTileCache.loadTileIfNeeded(
                            cacheDir = context.cacheDir,
                            z = tileZ,
                            x = tx,
                            y = ty,
                            onLoaded = { tileLoadGeneration++ }
                        )
                    }
                }
            }
        }

        // 3. 绘制校园路网动态规划路径（双层描边道路折线 + 沿途方向箭头 + 转向节点）
        if (navigationPlan != null && navigationPlan.routePoints.size >= 2) {
            val screenPts = navigationPlan.routePoints.map { pt ->
                val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(pt.latitude, pt.longitude)
                gcjToScreen(glat, glon)
            }

            // 第一步：绘制起点到路网接入点的引导虚线
            if (screenPts.size >= 2) {
                drawLine(
                    color = XPeachBrandLight.copy(alpha = 0.65f),
                    start = screenPts[0],
                    end = screenPts[1],
                    strokeWidth = 6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )
            }

            // 第二步：绘制完整校园道路折线（深蓝外描边 + 亮蓝内芯）
            val routePath = Path().apply {
                screenPts.forEachIndexed { idx, offset ->
                    if (idx == 0) moveTo(offset.x, offset.y)
                    else lineTo(offset.x, offset.y)
                }
            }
            drawPath(
                path = routePath,
                color = Color(0xFF004B8D),
                style = Stroke(
                    width = 14f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = routePath,
                color = Color(0xFF0EA5E9),
                style = Stroke(
                    width = 9f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 第三步：沿每个路段绘制白色行进方向指示箭头 (>)
            for (i in 0 until screenPts.size - 1) {
                val p1 = screenPts[i]
                val p2 = screenPts[i + 1]
                val segLen = hypot((p2.x - p1.x).toDouble(), (p2.y - p1.y).toDouble()).toFloat()
                if (segLen >= 38f) {
                    val angle = atan2((p2.y - p1.y).toDouble(), (p2.x - p1.x).toDouble())
                    val count = (segLen / 75f).toInt().coerceAtLeast(1)
                    for (k in 1..count) {
                        val ratio = k.toFloat() / (count + 1).toFloat()
                        val cx = p1.x + (p2.x - p1.x) * ratio
                        val cy = p1.y + (p2.y - p1.y) * ratio
                        val wing = 7.5f
                        val lx = cx - (wing * cos(angle - PI / 4)).toFloat()
                        val ly = cy - (wing * sin(angle - PI / 4)).toFloat()
                        val rx = cx - (wing * cos(angle + PI / 4)).toFloat()
                        val ry = cy - (wing * sin(angle + PI / 4)).toFloat()
                        drawLine(
                            color = Color.White,
                            start = Offset(lx, ly),
                            end = Offset(cx, cy),
                            strokeWidth = 2.8f,
                            cap = StrokeCap.Round
                        )
                        drawLine(
                            color = Color.White,
                            start = Offset(rx, ry),
                            end = Offset(cx, cy),
                            strokeWidth = 2.8f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // 第四步：绘制沿途转向路口圆点
            for (i in 1 until screenPts.size - 1) {
                drawCircle(
                    color = Color.White,
                    radius = 5.5f,
                    center = screenPts[i]
                )
                drawCircle(
                    color = Color(0xFF004B8D),
                    radius = 3.2f,
                    center = screenPts[i]
                )
            }
        }

        // 4. 绘制各校内楼宇锚点与紧凑白底标签
        val labelBgPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(238, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val labelBorderPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(200, 0, 102, 204)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val labelTextPaint = Paint().apply {
            isAntiAlias = true
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = android.graphics.Color.rgb(17, 24, 39)
        }

        for (b in buildings) {
            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
            val pt = gcjToScreen(glat, glon)
            if (pt.x < -80f || pt.x > size.width + 80f || pt.y < -80f || pt.y > size.height + 80f) {
                continue
            }
            val isTarget = b.id == selectedBuilding?.id

            if (isTarget) {
                drawCircle(
                    color = XPeachBrandLight.copy(alpha = 0.24f),
                    radius = 32f,
                    center = pt
                )
            }
            drawCircle(
                color = Color.White,
                radius = if (isTarget) 15f else 10.5f,
                center = pt
            )
            drawCircle(
                color = if (isTarget) XPeachBrandLight else Color(0xFF334155),
                radius = if (isTarget) 11f else 7.5f,
                center = pt
            )

            if (isTarget || zoom >= 15.8f) {
                val text = if (isTarget) "终 · ${b.shortName}" else b.shortName
                val textW = labelTextPaint.measureText(text)
                val rectLeft = pt.x - textW / 2f - 10f
                val rectTop = pt.y - 48f
                val rectRight = pt.x + textW / 2f + 10f
                val rectBottom = pt.y - 18f
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    rectLeft,
                    rectTop,
                    rectRight,
                    rectBottom,
                    10f,
                    10f,
                    labelBgPaint
                )
                if (isTarget) {
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        rectLeft,
                        rectTop,
                        rectRight,
                        rectBottom,
                        10f,
                        10f,
                        labelBorderPaint
                    )
                }
                drawContext.canvas.nativeCanvas.drawText(
                    text,
                    pt.x - textW / 2f,
                    pt.y - 25f,
                    labelTextPaint
                )
            }
        }

        // 5. 绘制用户当前位置蓝点 + 实时电子罗盘朝向扇形光束
        val originLatWgs = userLocation?.latitude ?: navigationPlan?.originLatitude
        val originLonWgs = userLocation?.longitude ?: navigationPlan?.originLongitude
        if (originLatWgs != null && originLonWgs != null) {
            val (uLatGcj, uLonGcj) = CampusMapNavigator.wgs84ToGcj02(originLatWgs, originLonWgs)
            val userPt = gcjToScreen(uLatGcj, uLonGcj)
            val headingDeg = deviceHeadingDegrees ?: navigationPlan?.bearingDegrees?.toFloat()

            // 绘制朝向视野扇形（0°=正北 -> Canvas 角度为 -90°）
            if (headingDeg != null) {
                val canvasCenterAngle = headingDeg - 90f
                val coneRadius = 68f
                drawArc(
                    color = XPeachBrandLight.copy(alpha = 0.26f),
                    startAngle = canvasCenterAngle - 28f,
                    sweepAngle = 56f,
                    useCenter = true,
                    topLeft = Offset(userPt.x - coneRadius, userPt.y - coneRadius),
                    size = Size(coneRadius * 2f, coneRadius * 2f)
                )

                // 绘制朝向三角尖端
                val rad = Math.toRadians(canvasCenterAngle.toDouble())
                val tip = Offset(
                    userPt.x + (cos(rad) * 24.0).toFloat(),
                    userPt.y + (sin(rad) * 24.0).toFloat()
                )
                val leftBase = Offset(
                    userPt.x + (cos(rad - 0.5) * 12.0).toFloat(),
                    userPt.y + (sin(rad - 0.5) * 12.0).toFloat()
                )
                val rightBase = Offset(
                    userPt.x + (cos(rad + 0.5) * 12.0).toFloat(),
                    userPt.y + (sin(rad + 0.5) * 12.0).toFloat()
                )
                val pointerPath = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(leftBase.x, leftBase.y)
                    lineTo(rightBase.x, rightBase.y)
                    close()
                }
                drawPath(path = pointerPath, color = XPeachBrandLight)
            }

            drawCircle(
                color = XPeachOk.copy(alpha = 0.22f),
                radius = 28f,
                center = userPt
            )
            drawCircle(
                color = Color.White,
                radius = 13f,
                center = userPt
            )
            drawCircle(
                color = if (userLocation != null) XPeachOk else XPeachBrandLight,
                radius = 9.5f,
                center = userPt
            )
        }
    }
}
