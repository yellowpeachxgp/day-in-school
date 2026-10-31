package cn.edu.haut.campus.feature.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachOk
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWarn
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.location.BuildingNavigationPlan
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.location.UserGeoLocation
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun CampusMapScreen(
    repository: CampusRepository,
    initialClassroomQuery: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())
    val aliases by repository.buildingAliasesFlow.collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf(initialClassroomQuery.orEmpty()) }
    var selectedCategory by remember { mutableStateOf<CampusCategory?>(null) }
    var selectedBuilding by remember { mutableStateOf<Building?>(null) }
    var isNavigatingInApp by remember { mutableStateOf(true) }
    var useCampusAnchorWhenOffCampus by remember { mutableStateOf(false) }

    // 设备定位权限状态与请求器
    var hasLocationPerm by remember {
        mutableStateOf(CampusMapNavigator.hasLocationPermission(context))
    }
    var locationRefreshTick by remember { mutableIntStateOf(0) }
    var liveUserLocation by remember { mutableStateOf<UserGeoLocation?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[android.Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            CampusMapNavigator.hasLocationPermission(context)
        hasLocationPerm = granted
        if (granted) {
            locationRefreshTick++
        }
    }

    // 进入校园页时若尚未授权定位权限，主动请求设备定位权限
    LaunchedEffect(Unit) {
        if (!hasLocationPerm) {
            permissionLauncher.launch(CampusMapNavigator.REQUIRED_LOCATION_PERMISSIONS)
        }
    }

    // 订阅实时 GPS / Fused 设备定位流
    LaunchedEffect(hasLocationPerm, locationRefreshTick) {
        if (hasLocationPerm) {
            CampusMapNavigator.observeUserLocation(context).collect { loc ->
                if (loc != null) {
                    liveUserLocation = loc
                }
            }
        }
    }

    LaunchedEffect(initialClassroomQuery) {
        if (!initialClassroomQuery.isNullOrBlank()) {
            searchQuery = initialClassroomQuery
            isNavigatingInApp = true
        }
    }

    val classroomResolution = remember(searchQuery, buildings, aliases) {
        if (searchQuery.isBlank()) null
        else ClassroomBuildingResolver.resolve(searchQuery, buildings, aliases)
    }

    LaunchedEffect(classroomResolution, buildings) {
        if (classroomResolution?.building != null) {
            selectedBuilding = classroomResolution.building
            isNavigatingInApp = true
        } else if (selectedBuilding == null && buildings.isNotEmpty()) {
            selectedBuilding = buildings.first()
        }
    }

    val effectiveOrigin: UserGeoLocation = remember(liveUserLocation, useCampusAnchorWhenOffCampus) {
        if (useCampusAnchorWhenOffCampus || liveUserLocation == null) {
            CampusMapNavigator.simulatedSouthGateAnchor()
        } else {
            liveUserLocation!!
        }
    }

    val activeTargetBuilding = selectedBuilding ?: classroomResolution?.building
    val navigationPlan: BuildingNavigationPlan? = remember(
        effectiveOrigin,
        activeTargetBuilding,
        buildings,
        classroomResolution
    ) {
        val target = activeTargetBuilding ?: return@remember null
        CampusMapNavigator.buildInAppNavigationPlan(
            origin = effectiveOrigin,
            destination = target,
            allBuildings = buildings,
            roomNumberHint = classroomResolution?.roomNumberHint
        )
    }

    val filteredBuildings = remember(buildings, selectedCategory, searchQuery, classroomResolution) {
        buildings.filter { b ->
            val catOk = selectedCategory == null || b.category == selectedCategory
            val queryOk = searchQuery.isBlank() ||
                b.name.contains(searchQuery, ignoreCase = true) ||
                b.shortName.contains(searchQuery, ignoreCase = true) ||
                b.description.contains(searchQuery, ignoreCase = true) ||
                classroomResolution?.building?.id == b.id
            catOk && queryOk
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            XPeachTopBar(
                title = "校园定位与导航",
                subtitle = "莲花街校区 · 实时 GPS 定位与 App 内路线",
                badgeText = if (hasLocationPerm && liveUserLocation != null) "GPS 已定位" else "待授权定位",
                actions = {
                    XPeachIconButton(
                        icon = Icons.Default.MyLocation,
                        contentDescription = "刷新我的定位",
                        onClick = {
                            if (hasLocationPerm) {
                                useCampusAnchorWhenOffCampus = false
                                locationRefreshTick++
                            } else {
                                permissionLauncher.launch(CampusMapNavigator.REQUIRED_LOCATION_PERMISSIONS)
                            }
                        }
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. 设备定位权限与实时位置状态条
            LocationPermissionAndStatusCard(
                hasPermission = hasLocationPerm,
                liveLocation = liveUserLocation,
                useCampusAnchor = useCampusAnchorWhenOffCampus,
                onRequestPermission = {
                    permissionLauncher.launch(CampusMapNavigator.REQUIRED_LOCATION_PERMISSIONS)
                },
                onToggleCampusAnchor = { useCampusAnchorWhenOffCampus = !useCampusAnchorWhenOffCampus }
            )

            // 2. 教室/建筑搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("输入教务教室（如 莲4号教学楼321、文科组团楼131）") },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            )

            // 3. 教务教室解析结果与模糊匹配人工确认卡片
            if (classroomResolution != null && searchQuery.isNotBlank()) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.surface,
                    borderColor = when (classroomResolution.status) {
                        BuildingMatchStatus.EXACT_VERIFIED -> XPeachOk.copy(alpha = 0.45f)
                        BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION -> XPeachWarn.copy(alpha = 0.45f)
                        BuildingMatchStatus.UNRESOLVED -> MaterialTheme.colorScheme.outlineVariant
                    }
                ) {
                    when (classroomResolution.status) {
                        BuildingMatchStatus.EXACT_VERIFIED -> {
                            val b = classroomResolution.building!!
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = XPeachOk,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "已定位教学楼：${b.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                XPeachStatusBadge(
                                    text = classroomResolution.roomNumberHint?.let { "${it}室" } ?: "已核验",
                                    containerColor = XPeachOk.copy(alpha = 0.12f),
                                    contentColor = XPeachOk,
                                    borderColor = XPeachOk.copy(alpha = 0.3f)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "教室「${classroomResolution.rawRoom}」 · 推荐入口：${b.entrances.joinToString(" / ")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION -> {
                            val candidate = classroomResolution.building
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.AutoMirrored.Filled.HelpOutline,
                                    contentDescription = null,
                                    tint = XPeachWarn,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "模糊匹配待确认（置信度 ${(classroomResolution.confidence * 100).toInt()}%）",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "「${classroomResolution.rawRoom}」推测位于「${candidate?.name ?: "未知楼宇"}」，确认后将永久记住该教室映射：",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            if (candidate != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                repository.confirmBuildingAliasForRoom(
                                                    rawRoom = classroomResolution.rawRoom,
                                                    buildingId = candidate.id
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("确认绑定至 ${candidate.shortName}")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            selectedBuilding = candidate
                                            isNavigatingInApp = true
                                        },
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("先在 App 内导航")
                                    }
                                }
                            }
                        }

                        BuildingMatchStatus.UNRESOLVED -> {
                            Text(
                                text = "尚未识别「${classroomResolution.rawRoom}」所属教学楼，请点击下方地图或建筑卡片选择目标。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 4. 交互式 App 内校园步行导航地图（含我的位置、路线矢量连线、方向箭头与点击选楼）
            InteractiveCampusNavigationMapCard(
                buildings = buildings,
                origin = effectiveOrigin,
                targetBuilding = activeTargetBuilding,
                navigationPlan = if (isNavigatingInApp) navigationPlan else null,
                onSelectBuilding = { clicked ->
                    selectedBuilding = clicked
                    isNavigatingInApp = true
                }
            )

            // 5. App 内实时分步步行导航面板
            if (navigationPlan != null) {
                InAppTurnByTurnNavigationCard(
                    plan = navigationPlan,
                    onOpenExternalMap = {
                        CampusMapNavigator.launchWalkingNavigation(context, navigationPlan.destination)
                    }
                )
            }

            // 6. 类别扁平筛选标签（XPeach .filter-pill 风格）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                XPeachFilterPill(
                    label = "全部楼宇 (${buildings.size})",
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

            // 7. 扁平建筑列表（显示实时直线距离与一键开启 App 内导航）
            for (building in filteredBuildings) {
                val isSelected = activeTargetBuilding?.id == building.id
                val distMeters = remember(effectiveOrigin, building) {
                    CampusMapNavigator.distanceBetweenMeters(
                        effectiveOrigin.latitude,
                        effectiveOrigin.longitude,
                        building.latitude,
                        building.longitude
                    )
                }
                val distText = if (distMeters >= 1000f) {
                    String.format("%.2f km", distMeters / 1000f)
                } else {
                    "${distMeters.roundToInt()} m"
                }

                XPeachCard(
                    containerColor = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    borderColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    onClick = {
                        selectedBuilding = building
                        isNavigatingInApp = true
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = building.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                XPeachStatusBadge(
                                    text = "距你 $distText",
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    borderColor = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${building.category.label} · 入口：${building.entrances.joinToString(" / ")}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = {
                                selectedBuilding = building
                                isNavigatingInApp = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Navigation,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isSelected) "导航中" else "App内导航", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = building.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationPermissionAndStatusCard(
    hasPermission: Boolean,
    liveLocation: UserGeoLocation?,
    useCampusAnchor: Boolean,
    onRequestPermission: () -> Unit,
    onToggleCampusAnchor: () -> Unit
) {
    if (!hasPermission) {
        XPeachCard(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "需要设备定位权限以启用 App 内实时导航",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "授权后可实时测算你与各教学楼的步行距离、方向角并在校内地图绘制步行路线。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    onClick = onRequestPermission,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.LocationSearching, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("授权定位")
                }
            }
        }
    } else {
        XPeachWell(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (liveLocation != null) XPeachOk else XPeachWarn)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            useCampusAnchor -> "当前起点：莲花街校区南大门基准点（校内路线预览模式）"
                            liveLocation != null -> "实时定位已就绪 (${String.format("%.4f, %.4f", liveLocation.latitude, liveLocation.longitude)} · ±${liveLocation.accuracyMeters.roundToInt()}m)"
                            else -> "已获定位权限，正在搜索 GPS/Fused 信号（暂以南大门为起点）"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                XPeachFilterPill(
                    label = if (useCampusAnchor) "切回真机GPS" else "南门起点预览",
                    selected = useCampusAnchor,
                    onClick = onToggleCampusAnchor
                )
            }
        }
    }
}

@Composable
private fun InteractiveCampusNavigationMapCard(
    buildings: List<Building>,
    origin: UserGeoLocation,
    targetBuilding: Building?,
    navigationPlan: BuildingNavigationPlan?,
    onSelectBuilding: (Building) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val okColor = XPeachOk
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    val textMeasurer: TextMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val minLat = remember(buildings) {
        (buildings.minOfOrNull { it.latitude } ?: 34.8265) - 0.0006
    }
    val maxLat = remember(buildings) {
        (buildings.maxOfOrNull { it.latitude } ?: 34.8330) + 0.0006
    }
    val minLng = remember(buildings) {
        (buildings.minOfOrNull { it.longitude } ?: 113.5455) - 0.0008
    }
    val maxLng = remember(buildings) {
        (buildings.maxOfOrNull { it.longitude } ?: 113.5535) + 0.0008
    }
    val latSpan = (maxLat - minLat).coerceAtLeast(0.002)
    val lngSpan = (maxLng - minLng).coerceAtLeast(0.002)

    fun mapGeoToOffset(lat: Double, lng: Double, width: Float, height: Float): Offset {
        val padX = 28f
        val padY = 24f
        val usableW = (width - padX * 2).coerceAtLeast(10f)
        val usableH = (height - padY * 2).coerceAtLeast(10f)
        val xRatio = ((lng - minLng) / lngSpan).toFloat().coerceIn(0f, 1f)
        val yRatio = (1.0 - (lat - minLat) / latSpan).toFloat().coerceIn(0f, 1f)
        return Offset(padX + xRatio * usableW, padY + yRatio * usableH)
    }

    XPeachCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "莲花街校区 App 内交互导航图（北↑）",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "点击图中任意楼宇节点即可切换导航终点",
                    style = MaterialTheme.typography.labelMedium,
                    color = mutedColor
                )
            }
            if (navigationPlan != null) {
                XPeachStatusBadge(
                    text = "${navigationPlan.formattedDistance} · 约${navigationPlan.estimatedWalkMinutes}分钟",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = primaryColor
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, outlineColor, RoundedCornerShape(10.dp))
                .onSizeChanged { canvasSize = it }
                .pointerInput(buildings, canvasSize) {
                    detectTapGestures { tapOffset ->
                        if (buildings.isEmpty() || canvasSize.width == 0) return@detectTapGestures
                        val nearest = buildings.minByOrNull { b ->
                            val pt = mapGeoToOffset(
                                b.latitude,
                                b.longitude,
                                canvasSize.width.toFloat(),
                                canvasSize.height.toFloat()
                            )
                            hypot((tapOffset.x - pt.x).toDouble(), (tapOffset.y - pt.y).toDouble())
                        }
                        if (nearest != null) {
                            onSelectBuilding(nearest)
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (buildings.isEmpty()) return@Canvas

                // 1. 校园主干道路网参考线
                val cols = 4
                val rows = 3
                for (c in 1..cols) {
                    val x = size.width * c / (cols + 1)
                    drawLine(
                        color = outlineColor.copy(alpha = 0.6f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
                for (r in 1..rows) {
                    val y = size.height * r / (rows + 1)
                    drawLine(
                        color = outlineColor.copy(alpha = 0.6f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val userPt = mapGeoToOffset(origin.latitude, origin.longitude, size.width, size.height)

                // 2. 绘制 App 内步行导航路线（起点 -> 途经点 -> 目标教学楼）
                if (targetBuilding != null) {
                    val targetPt = mapGeoToOffset(
                        targetBuilding.latitude,
                        targetBuilding.longitude,
                        size.width,
                        size.height
                    )
                    val waypoint = navigationPlan?.waypointBuilding
                    val pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)

                    if (waypoint != null) {
                        val wayPt = mapGeoToOffset(waypoint.latitude, waypoint.longitude, size.width, size.height)
                        drawLine(
                            color = primaryColor,
                            start = userPt,
                            end = wayPt,
                            strokeWidth = 3.dp.toPx(),
                            pathEffect = pathEffect
                        )
                        drawLine(
                            color = primaryColor,
                            start = wayPt,
                            end = targetPt,
                            strokeWidth = 3.dp.toPx(),
                            pathEffect = pathEffect
                        )
                    } else {
                        drawLine(
                            color = primaryColor,
                            start = userPt,
                            end = targetPt,
                            strokeWidth = 3.dp.toPx(),
                            pathEffect = pathEffect
                        )
                    }

                    // 绘制指向目标建筑的箭头
                    val angle = atan2((targetPt.y - userPt.y).toDouble(), (targetPt.x - userPt.x).toDouble())
                    val arrowLen = 10.dp.toPx()
                    val arrowAngle = Math.toRadians(28.0)
                    val tipX = (userPt.x + targetPt.x) / 2f
                    val tipY = (userPt.y + targetPt.y) / 2f
                    val leftX = tipX - (arrowLen * cos(angle - arrowAngle)).toFloat()
                    val leftY = tipY - (arrowLen * sin(angle - arrowAngle)).toFloat()
                    val rightX = tipX - (arrowLen * cos(angle + arrowAngle)).toFloat()
                    val rightY = tipY - (arrowLen * sin(angle + arrowAngle)).toFloat()
                    drawLine(primaryColor, Offset(tipX, tipY), Offset(leftX, leftY), strokeWidth = 2.5.dp.toPx())
                    drawLine(primaryColor, Offset(tipX, tipY), Offset(rightX, rightY), strokeWidth = 2.5.dp.toPx())
                }

                // 3. 绘制所有校园建筑节点与名称标签
                for (b in buildings) {
                    val pt = mapGeoToOffset(b.latitude, b.longitude, size.width, size.height)
                    val isTarget = targetBuilding?.id == b.id

                    if (isTarget) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.18f),
                            radius = 16.dp.toPx(),
                            center = pt
                        )
                    }
                    drawCircle(
                        color = surfaceColor,
                        radius = if (isTarget) 7.5.dp.toPx() else 5.5.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = if (isTarget) primaryColor else mutedColor,
                        radius = if (isTarget) 6.dp.toPx() else 4.dp.toPx(),
                        center = pt
                    )

                    val labelLayout = textMeasurer.measure(
                        text = b.shortName,
                        style = TextStyle(
                            color = if (isTarget) primaryColor else onSurfaceColor,
                            fontSize = if (isTarget) 11.sp else 10.sp,
                            fontWeight = if (isTarget) FontWeight.Bold else FontWeight.Medium
                        )
                    )
                    val textX = (pt.x - labelLayout.size.width / 2f)
                        .coerceIn(4f, size.width - labelLayout.size.width - 4f)
                    val textY = (pt.y + 8.dp.toPx())
                        .coerceIn(4f, size.height - labelLayout.size.height - 4f)
                    drawText(
                        textLayoutResult = labelLayout,
                        topLeft = Offset(textX, textY)
                    )
                }

                // 4. 绘制「我的位置」实时定位点（绿色圆点 + 精度光圈）
                drawCircle(
                    color = okColor.copy(alpha = 0.22f),
                    radius = 14.dp.toPx(),
                    center = userPt
                )
                drawCircle(
                    color = surfaceColor,
                    radius = 7.dp.toPx(),
                    center = userPt
                )
                drawCircle(
                    color = okColor,
                    radius = 5.dp.toPx(),
                    center = userPt
                )
                drawCircle(
                    color = okColor,
                    radius = 14.dp.toPx(),
                    center = userPt,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(okColor))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("我的位置", style = MaterialTheme.typography.labelMedium, color = mutedColor)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(primaryColor))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = targetBuilding?.let { "终点：${it.name}" } ?: "未选择终点",
                        style = MaterialTheme.typography.labelMedium,
                        color = primaryColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun InAppTurnByTurnNavigationCard(
    plan: BuildingNavigationPlan,
    onOpenExternalMap: () -> Unit
) {
    XPeachCard(
        containerColor = MaterialTheme.colorScheme.surface,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Explore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "App 内步行导航 → ${plan.destination.name}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            OutlinedButton(
                onClick = onOpenExternalMap,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("系统地图", fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3 格核心导航指标（扁平 Bento 指标卡）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            XPeachWell(modifier = Modifier.weight(1f), contentPadding = PaddingValues(10.dp)) {
                Text("直线距离", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = plan.formattedDistance,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            XPeachWell(modifier = Modifier.weight(1f), contentPadding = PaddingValues(10.dp)) {
                Text("行进方位", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "${plan.compassDirection} (${plan.bearingDegrees.roundToInt()}°)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            XPeachWell(modifier = Modifier.weight(1f), contentPadding = PaddingValues(10.dp)) {
                Text("预计步行", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "约 ${plan.estimatedWalkMinutes} 分钟",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 分步导航指引
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            plan.turnByTurnSteps.forEachIndexed { idx, stepText ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                RoundedCornerShape(5.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${idx + 1}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stepText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
