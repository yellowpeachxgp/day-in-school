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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
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
    private val memoryCache = LruCache<String, ImageBitmap>(96)
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
    var inAppNavigationActive by remember { mutableStateOf(!initialClassroomQuery.isNullOrBlank()) }

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

    val userLocationFlow = remember(hasLocationPermission, context) {
        CampusMapNavigator.observeUserLocation(context)
    }
    val userLocation by userLocationFlow.collectAsState(initial = null)

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
    var mapZoom by remember { mutableFloatStateOf(16.3f) }

    LaunchedEffect(resolution, buildings) {
        if (resolution?.building != null) {
            val b = resolution.building!!
            selectedBuilding = b
            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
            mapCenterLatGcj = glat
            mapCenterLonGcj = glon
            if (!initialClassroomQuery.isNullOrBlank()) {
                inAppNavigationActive = true
            }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "校园地图",
            subtitle = "河南工业大学莲花街校区",
            badgeText = when {
                !hasLocationPermission -> "未授权定位"
                userLocation?.isInsideLianhuajieCampus == true -> "校内已定位"
                userLocation != null -> "已定位"
                else -> "定位中"
            },
            actions = {
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
                        } else if (userLocation != null) {
                            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
                                userLocation!!.latitude,
                                userLocation!!.longitude
                            )
                            mapCenterLatGcj = glat
                            mapCenterLonGcj = glon
                            mapZoom = 17.0f
                        } else {
                            mapCenterLatGcj = defaultCenterGcj.first
                            mapCenterLonGcj = defaultCenterGcj.second
                            mapZoom = 16.3f
                        }
                    }
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 定位权限引导横条（仅在未授权时显示）
            if (!hasLocationPermission) {
                XPeachCard(
                    contentPadding = PaddingValues(12.dp),
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp)
                        ) {
                            Text(
                                text = "开启位置权限",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "在地图上显示实时位置并规划校内步行路线",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("授权", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = "搜索教室或楼宇（如 1号教学楼1204、图书馆）",
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
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // 模糊教室归属人工确认条
            if (resolution != null && resolution.status != BuildingMatchStatus.EXACT_VERIFIED) {
                XPeachCard(
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                ) {
                    Text(
                        text = "确认「$searchQuery」所属楼宇",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
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

            // 分类筛选栏（横向滚动，杜绝文字挤压变形）
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
                        onClick = { selectedCategory = category }
                    )
                }
            }

            // 真实高德/OSM 瓦片底图 + 校内建筑标注 + 实时定位与步行路线矢量层
            XPeachCard(
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    RealCampusTileMap(
                        buildings = buildings,
                        selectedBuilding = targetBuilding,
                        userLocation = userLocation,
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
                            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
                                clicked.latitude,
                                clicked.longitude
                            )
                            mapCenterLatGcj = glat
                            mapCenterLonGcj = glon
                        }
                    )

                    // 右下角缩放与归位控制组
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        XPeachIconButton(
                            icon = Icons.Default.Add,
                            contentDescription = "放大地图",
                            onClick = { mapZoom = (mapZoom + 0.6f).coerceIn(15.0f, 18.2f) }
                        )
                        XPeachIconButton(
                            icon = Icons.Default.Remove,
                            contentDescription = "缩小地图",
                            onClick = { mapZoom = (mapZoom - 0.6f).coerceIn(15.0f, 18.2f) }
                        )
                        XPeachIconButton(
                            icon = Icons.Default.MyLocation,
                            contentDescription = "回校区中心",
                            onClick = {
                                if (userLocation?.isInsideLianhuajieCampus == true) {
                                    val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
                                        userLocation!!.latitude,
                                        userLocation!!.longitude
                                    )
                                    mapCenterLatGcj = glat
                                    mapCenterLonGcj = glon
                                    mapZoom = 16.8f
                                } else {
                                    mapCenterLatGcj = defaultCenterGcj.first
                                    mapCenterLonGcj = defaultCenterGcj.second
                                    mapZoom = 16.3f
                                }
                            }
                        )
                    }
                }
            }

            // 当前选中楼宇与路线面板
            if (targetBuilding != null && navigationPlan != null) {
                InAppNavigationCard(
                    building = targetBuilding,
                    plan = navigationPlan,
                    allBuildings = buildings,
                    hasLiveGps = userLocation != null,
                    manualOriginBuilding = manualOriginBuilding,
                    inAppNavigationActive = inAppNavigationActive,
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

            // 楼宇目录
            Text(
                text = "校内楼宇 (${filteredBuildings.size})",
                style = MaterialTheme.typography.titleMedium
            )

            for (building in filteredBuildings) {
                val isSelected = building.id == targetBuilding?.id
                val distanceFromUser = remember(userLocation, building) {
                    if (userLocation != null) {
                        CampusMapNavigator.calculateDistanceMeters(
                            userLocation!!.latitude,
                            userLocation!!.longitude,
                            building.latitude,
                            building.longitude
                        )
                    } else null
                }

                XPeachCard(
                    borderColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    onClick = {
                        selectedBuilding = building
                        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
                            building.latitude,
                            building.longitude
                        )
                        mapCenterLatGcj = glat
                        mapCenterLonGcj = glon
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = building.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                XPeachStatusBadge(text = building.category.label)
                            }
                            if (!building.description.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = building.description!!,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (distanceFromUser != null && distanceFromUser < 5000) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "距当前位置 ${distanceFromUser}m",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                selectedBuilding = building
                                val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(
                                    building.latitude,
                                    building.longitude
                                )
                                mapCenterLatGcj = glat
                                mapCenterLonGcj = glon
                                inAppNavigationActive = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsWalk,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("路线", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InAppNavigationCard(
    building: Building,
    plan: InAppNavigationPlan,
    allBuildings: List<Building>,
    hasLiveGps: Boolean,
    manualOriginBuilding: Building?,
    inAppNavigationActive: Boolean,
    onSelectOriginBuilding: (Building?) -> Unit,
    onToggleInAppNavigation: () -> Unit,
    onOpenExternalMap: () -> Unit
) {
    XPeachCard(
        borderColor = if (inAppNavigationActive) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        }
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
                Text(
                    text = building.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = building.entrances.joinToString(" / ").ifBlank { "正门入口" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            XPeachStatusBadge(
                text = if (inAppNavigationActive) "导航中" else building.shortName
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 三栏核心指标（简洁留白，绝不挤压）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            XPeachWell(modifier = Modifier.weight(1f)) {
                Text(
                    text = "步行距离",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "${plan.estimatedWalkDistanceMeters} 米",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    softWrap = false
                )
            }
            XPeachWell(modifier = Modifier.weight(1f)) {
                Text(
                    text = "预计用时",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "约 ${plan.estimatedWalkMinutes} 分钟",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false
                )
            }
            XPeachWell(modifier = Modifier.weight(1f)) {
                Text(
                    text = "行进方位",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = "${plan.compassDirectionLabel} ${plan.bearingDegrees}°",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 起点选择（横向滚动）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "起点：",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            XPeachFilterPill(
                label = if (hasLiveGps) "当前定位" else "南门",
                selected = manualOriginBuilding == null,
                onClick = { onSelectOriginBuilding(null) }
            )
            for (b in allBuildings.filter { it.id != building.id }.take(6)) {
                XPeachFilterPill(
                    label = b.shortName,
                    selected = manualOriginBuilding?.id == b.id,
                    onClick = { onSelectOriginBuilding(b) }
                )
            }
        }

        if (inAppNavigationActive) {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = step.detail,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${step.distanceMeters}m",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
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
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (inAppNavigationActive) "结束校内导航" else "显示校内路线",
                    maxLines = 1,
                    softWrap = false
                )
            }

            OutlinedButton(
                onClick = onOpenExternalMap,
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("外部地图", maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * 真实街道与校园瓦片地图组件（Web Mercator Slippy Map + 实时标注与路线绘制）
 */
@Composable
private fun RealCampusTileMap(
    buildings: List<Building>,
    selectedBuilding: Building?,
    userLocation: UserGeoLocation?,
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
    var canvasHeightPx by remember { mutableFloatStateOf(900f) }

    val tileZ = zoom.roundToInt().coerceIn(15, 18)
    val scaleFactor = 2.0.pow((zoom - tileZ).toDouble()).toFloat()
    val baseTileRenderPx = 256f * context.resources.displayMetrics.density * 0.85f * scaleFactor

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

    fun gcjToScreen(latGcj: Double, lonGcj: Double): Offset {
        val (tx, ty) = latLonToTileXY(latGcj, lonGcj, tileZ)
        val sx = (canvasWidthPx / 2f) + ((tx - centerTileX).toFloat() * baseTileRenderPx)
        val sy = (canvasHeightPx / 2f) + ((ty - centerTileY).toFloat() * baseTileRenderPx)
        return Offset(sx, sy)
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(buildings, centerLatGcj, centerLonGcj, zoom) {
                detectTapGestures { tapOffset ->
                    val hit = buildings.minByOrNull { b ->
                        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
                        val pt = gcjToScreen(glat, glon)
                        hypot((pt.x - tapOffset.x).toDouble(), (pt.y - tapOffset.y).toDouble())
                    }
                    if (hit != null) {
                        val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(hit.latitude, hit.longitude)
                        val pt = gcjToScreen(glat, glon)
                        if (hypot((pt.x - tapOffset.x).toDouble(), (pt.y - tapOffset.y).toDouble()) <= 84.0) {
                            onSelectBuilding(hit)
                        }
                    }
                }
            }
            .pointerInput(centerLatGcj, centerLonGcj, zoom, baseTileRenderPx) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val newZoom = (zoom + (ln(gestureZoom.toDouble()) / ln(2.0)).toFloat())
                        .coerceIn(15.0f, 18.2f)
                    val dTileX = -pan.x / baseTileRenderPx
                    val dTileY = -pan.y / baseTileRenderPx
                    val (newLat, newLon) = tileXYToLatLon(
                        centerTileX + dTileX,
                        centerTileY + dTileY,
                        tileZ
                    )
                    onViewportChange(
                        newLat.coerceIn(34.815, 34.845),
                        newLon.coerceIn(113.525, 113.570),
                        newZoom
                    )
                }
            }
    ) {
        // 读取 generation 以触发瓦片下载完成后的重绘
        val gen = tileLoadGeneration
        if (gen < 0) return@Canvas

        canvasWidthPx = size.width
        canvasHeightPx = size.height

        // 1. 底色背景
        drawRect(color = Color(0xFFF2EFE9))

        // 2. 计算当前视口覆盖的瓦片范围并绘制真实瓦片底图
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

        // 3. 绘制校内步行导航路线矢量与方向箭头
        if (navigationPlan != null) {
            val (origLatGcj, origLonGcj) = CampusMapNavigator.wgs84ToGcj02(
                navigationPlan.originLatitude,
                navigationPlan.originLongitude
            )
            val (destLatGcj, destLonGcj) = CampusMapNavigator.wgs84ToGcj02(
                navigationPlan.destinationBuilding.latitude,
                navigationPlan.destinationBuilding.longitude
            )
            val startPt = gcjToScreen(origLatGcj, origLonGcj)
            val endPt = gcjToScreen(destLatGcj, destLonGcj)
            val wp = navigationPlan.waypointBuilding
            val midPt = if (wp != null) {
                val (wLat, wLon) = CampusMapNavigator.wgs84ToGcj02(wp.latitude, wp.longitude)
                gcjToScreen(wLat, wLon)
            } else null

            val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), 0f)
            if (midPt != null) {
                drawLine(
                    color = XPeachBrandLight,
                    start = startPt,
                    end = midPt,
                    strokeWidth = 7f,
                    pathEffect = dash
                )
                drawLine(
                    color = XPeachBrandLight,
                    start = midPt,
                    end = endPt,
                    strokeWidth = 7f,
                    pathEffect = dash
                )
            } else {
                drawLine(
                    color = XPeachBrandLight,
                    start = startPt,
                    end = endPt,
                    strokeWidth = 7f,
                    pathEffect = dash
                )
            }

            // 终点箭头
            val fromPt = midPt ?: startPt
            val angle = atan2((endPt.y - fromPt.y).toDouble(), (endPt.x - fromPt.x).toDouble())
            val arrowLen = 20f
            val tip = Offset(
                endPt.x - (cos(angle) * 20.0).toFloat(),
                endPt.y - (sin(angle) * 20.0).toFloat()
            )
            val leftWing = Offset(
                tip.x - (arrowLen * cos(angle - PI / 6)).toFloat(),
                tip.y - (arrowLen * sin(angle - PI / 6)).toFloat()
            )
            val rightWing = Offset(
                tip.x - (arrowLen * cos(angle + PI / 6)).toFloat(),
                tip.y - (arrowLen * sin(angle + PI / 6)).toFloat()
            )
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(leftWing.x, leftWing.y)
                lineTo(rightWing.x, rightWing.y)
                close()
            }
            drawPath(path = path, color = XPeachBrandLight)
        }

        // 4. 绘制校内各建筑锚点与精简白底标签
        val labelBgPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(235, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val labelBorderPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(180, 0, 114, 188)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val labelTextPaint = Paint().apply {
            isAntiAlias = true
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = android.graphics.Color.rgb(15, 23, 42)
        }

        for (b in buildings) {
            val (glat, glon) = CampusMapNavigator.wgs84ToGcj02(b.latitude, b.longitude)
            val pt = gcjToScreen(glat, glon)
            if (pt.x < -60f || pt.x > size.width + 60f || pt.y < -60f || pt.y > size.height + 60f) {
                continue
            }
            val isTarget = b.id == selectedBuilding?.id

            if (isTarget) {
                drawCircle(
                    color = XPeachBrandLight.copy(alpha = 0.22f),
                    radius = 28f,
                    center = pt
                )
            }
            drawCircle(
                color = Color.White,
                radius = if (isTarget) 14f else 10f,
                center = pt
            )
            drawCircle(
                color = if (isTarget) XPeachBrandLight else Color(0xFF334155),
                radius = if (isTarget) 10f else 7f,
                center = pt
            )

            // 仅对选中楼宇或缩放级别较大时绘制紧凑白底标签，避免地图文字重叠
            if (isTarget || zoom >= 16.0f) {
                val text = b.shortName
                val textW = labelTextPaint.measureText(text)
                val rectLeft = pt.x - textW / 2f - 10f
                val rectTop = pt.y - 46f
                val rectRight = pt.x + textW / 2f + 10f
                val rectBottom = pt.y - 16f
                drawContext.canvas.nativeCanvas.drawRoundRect(
                    rectLeft,
                    rectTop,
                    rectRight,
                    rectBottom,
                    8f,
                    8f,
                    labelBgPaint
                )
                if (isTarget) {
                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        rectLeft,
                        rectTop,
                        rectRight,
                        rectBottom,
                        8f,
                        8f,
                        labelBorderPaint
                    )
                }
                drawContext.canvas.nativeCanvas.drawText(
                    text,
                    pt.x - textW / 2f,
                    pt.y - 23f,
                    labelTextPaint
                )
            }
        }

        // 5. 绘制用户实时 GPS 蓝点
        if (userLocation != null) {
            val (uLatGcj, uLonGcj) = CampusMapNavigator.wgs84ToGcj02(
                userLocation.latitude,
                userLocation.longitude
            )
            val userPt = gcjToScreen(uLatGcj, uLonGcj)
            drawCircle(
                color = XPeachOk.copy(alpha = 0.24f),
                radius = 30f,
                center = userPt
            )
            drawCircle(
                color = Color.White,
                radius = 13f,
                center = userPt
            )
            drawCircle(
                color = XPeachOk,
                radius = 9f,
                center = userPt
            )
        }
    }
}
