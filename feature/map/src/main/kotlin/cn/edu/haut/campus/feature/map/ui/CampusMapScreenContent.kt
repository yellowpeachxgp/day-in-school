package cn.edu.haut.campus.feature.map.ui

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
import cn.edu.haut.campus.feature.map.state.MapUiAction
import cn.edu.haut.campus.feature.map.state.MapUiState
import cn.edu.haut.campus.feature.map.ui.indoor.BuildingIndoorMapDialog
import cn.edu.haut.campus.feature.map.ui.map.RealCampusTileMap
import cn.edu.haut.campus.feature.map.ui.components.FloatingRouteBottomSheet
import cn.edu.haut.campus.feature.map.ui.components.MapHospitalPharmacyDialog
import cn.edu.haut.campus.feature.map.ui.components.OfficialGisWebViewDialog
import kotlin.reflect.KProperty


private class MapActionDelegate<T>(
    initialValue: T,
    private val write: (T) -> Unit
) {
    private var currentValue: T = initialValue

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T = currentValue
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        currentValue = value
        write(value)
    }
}

private fun <T> mapActionState(value: T, write: (T) -> Unit): MapActionDelegate<T> =
    MapActionDelegate(value, write)

/**
 * 校园地图纯渲染层：只消费不可变 [MapUiState] 并发送 [MapUiAction]。
 */
@Composable
internal fun CampusMapScreenContent(
    state: MapUiState,
    onAction: (MapUiAction) -> Unit
) {
    val preferences = state.preferences
    val activeSchool = state.activeSchool
    val mapProvider: Any? = if (state.mapProviderAvailable) Unit else null
    val customZones = state.customZones
    val detectedCampus = state.detectedCampus
    val isLianhuajieActive = state.isLianhuajieActive
    val activeCampusPolygon = state.activeCampusPolygon
    val buildings = state.allBuildings
    val campusBuildings = state.campusBuildings
    val filteredBuildings = state.filteredBuildings
    val targetBuilding = state.targetBuilding
    val resolution = state.resolution
    val activeBuildingFootprint = state.activeBuildingFootprint
    val navigationPlan = state.navigationPlan
    val userLocation = state.userLocation
    val effectiveHeadingDegrees = state.effectiveHeadingDegrees
    val gisDataset = state.gisDataset
    val activeIndoorFloorPlan = state.activeIndoorFloorPlan
    val isLoadingIndoorFloorPlan = state.isLoadingIndoorFloorPlan
    val selectedIndoorFloorIndex = state.selectedIndoorFloorIndex
    val officialSearchResults = state.officialSearchResults
    val isOfficialRouteActive = state.isOfficialRouteActive
    val defaultCenterGcj = state.defaultCenterLatGcj to state.defaultCenterLonGcj
    val elecBadge = state.electricityBadge
    val elecSub = state.electricitySubtitle

    var isFullScreenMap by mapActionState(state.isFullScreenMap) { onAction(MapUiAction.SetFullScreenMap(it)) }
    var showOfficialWebGisDialog by mapActionState(state.showOfficialWebGisDialog) { onAction(MapUiAction.SetOfficialWebGisDialogVisible(it)) }
    var showMapHospitalPharmacyDialog by mapActionState(state.showMapHospitalPharmacyDialog) { onAction(MapUiAction.SetHospitalPharmacyDialogVisible(it)) }
    var searchQuery by mapActionState(state.searchQuery) { onAction(MapUiAction.UpdateSearchQuery(it)) }
    var selectedCategory by mapActionState(state.selectedCategory) { onAction(MapUiAction.SelectCategory(it)) }
    var selectedBuilding by mapActionState(state.selectedBuilding) { onAction(MapUiAction.SetSelectedBuilding(it)) }
    var manualOriginBuilding by mapActionState(state.manualOriginBuilding) { onAction(MapUiAction.SetManualOriginBuilding(it)) }
    var inAppNavigationActive by mapActionState(state.inAppNavigationActive) { onAction(MapUiAction.SetInAppNavigationActive(it)) }
    var expandRouteSteps by mapActionState(state.expandRouteSteps) { onAction(MapUiAction.SetExpandRouteSteps(it)) }
    var gisStyleMode by mapActionState(state.gisStyleMode) { onAction(MapUiAction.SelectGisStyleMode(it)) }
    var selectedIndoorRoom by mapActionState(state.selectedIndoorRoom) { onAction(MapUiAction.SelectIndoorRoom(it)) }
    var showIndoorSecondaryMap by mapActionState(state.showIndoorSecondaryMap) { onAction(MapUiAction.SetIndoorSecondaryMapVisible(it)) }
    var isDrawingZoneMode by mapActionState(state.isDrawingZoneMode) { onAction(MapUiAction.SetDrawingZoneMode(it)) }
    var drawingZoneVertices by mapActionState(state.drawingZoneVertices) { onAction(MapUiAction.UpdateDrawingZoneVertices(it)) }
    var drawingZoneBuildings by mapActionState(state.drawingZoneBuildings) { onAction(MapUiAction.UpdateDrawingZoneBuildings(it)) }
    var drawingZoneName by mapActionState(state.drawingZoneName) { onAction(MapUiAction.UpdateDrawingZoneName(it)) }
    var showZoneImportExportDialog by mapActionState(state.showZoneImportExportDialog) { onAction(MapUiAction.SetZoneImportExportVisible(it)) }
    var zoneAdaptationJsonInput by mapActionState(state.zoneAdaptationJsonInput) { onAction(MapUiAction.UpdateZoneAdaptationJsonInput(it)) }
    var zoneAdaptationFeedback by mapActionState(state.zoneAdaptationFeedback) { onAction(MapUiAction.SetZoneAdaptationFeedback(it)) }
    var pendingPinPoint by mapActionState(state.pendingPinPoint) { onAction(MapUiAction.SetPendingPinPoint(it)) }
    var pendingPinName by mapActionState(state.pendingPinName) { onAction(MapUiAction.UpdatePendingPinName(it)) }
    var hasLocationPermission by mapActionState(state.hasLocationPermission) { onAction(MapUiAction.SetHasLocationPermission(it)) }
    var isFollowingUser by mapActionState(state.isFollowingUser) { onAction(MapUiAction.SetFollowingUser(it)) }
    var mapCenterLatGcj by mapActionState(state.mapCenterLatGcj) { onAction(MapUiAction.UpdateMapCenterLatGcj(it)) }
    var mapCenterLonGcj by mapActionState(state.mapCenterLonGcj) { onAction(MapUiAction.UpdateMapCenterLonGcj(it)) }
    var mapZoom by mapActionState(state.mapZoom) { onAction(MapUiAction.UpdateMapZoom(it)) }

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
                onAction(MapUiAction.SelectIndoorFloor(floorIdx))
            },
            onSelectRoom = { room ->
                selectedIndoorRoom = room
            },
            onConfirmRoomForOutdoorRoute = { room ->
                onAction(MapUiAction.ConfirmIndoorRoomForOutdoorRoute(room))
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
                onAction(MapUiAction.OpenOfficialGisExternalBrowser)
            }
        )
    }

    if (showMapHospitalPharmacyDialog) {
        MapHospitalPharmacyDialog(
            onDismiss = { showMapHospitalPharmacyDialog = false },
            onOpenUrl = { url ->
                onAction(MapUiAction.OpenUrl(url))
            }
        )
    }

    if (showZoneImportExportDialog) {
        val currentExportJson = state.zoneAdaptationExportJson
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
                                    onAction(MapUiAction.CopyZoneAdaptationJson)
                                },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("复制配置")
                        }
                        if (customZones.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    onAction(MapUiAction.ClearCustomZones)
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
                        onAction(MapUiAction.ImportZoneAdaptationJson)
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
                        text = formatMapPinCoordinate(pt),
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
                        onAction(MapUiAction.ConfirmPendingPin)
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
                                            onAction(MapUiAction.SelectGisSearchResult(item, item.isIndoorRoom))
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
                                                    onAction(MapUiAction.SelectGisSearchResult(item, true))
                                                }
                                            )
                                        }
                                        XPeachFilterPill(
                                            label = if (item.isIndoorRoom) "室外路线" else "查看位置",
                                            selected = !item.isIndoorRoom,
                                            compact = true,
                                            onClick = {
                                                onAction(MapUiAction.SelectGisSearchResult(item, false))
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
                            onClick = { onAction(MapUiAction.SelectCampus("auto")) }
                        )
                        XPeachFilterPill(
                            label = "莲花街校区",
                            selected = preferences.selectedCampusId == SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value,
                            onClick = {
                                onAction(MapUiAction.SelectCampus(SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value))
                            }
                        )
                        XPeachFilterPill(
                            label = "嵩山路校区",
                            selected = preferences.selectedCampusId == SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value,
                            onClick = {
                                onAction(MapUiAction.SelectCampus(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value))
                            }
                        )
                        for (zone in customZones) {
                            XPeachFilterPill(
                                label = zone.campusName,
                                selected = preferences.selectedCampusId == zone.campusId,
                                onClick = {
                                    onAction(MapUiAction.SelectCampus(zone.campusId))
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
                            onClick = { onAction(MapUiAction.NavigateToSchoolCenter) },
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
                                text = formatMapNavigationSummary(
                                    targetBuildingName = targetBuilding?.name,
                                    navigationPlan = navigationPlan,
                                    selectedIndoorRoom = selectedIndoorRoom,
                                    resolution = resolution
                                ),
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
                                        onAction(MapUiAction.SelectGisStyleMode(mode))
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
                                onAction(MapUiAction.ViewportChanged(newLat, newLon, newZoom, fromFullScreenMap = false))
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
                        onClick = { onAction(MapUiAction.NavigateToElectricity) }
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
                        onClick = { onAction(MapUiAction.NavigateToEmptyClassrooms) }
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
                        onClick = { onAction(MapUiAction.NavigateToCommunity) }
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
                        onClick = { onAction(MapUiAction.NavigateToCompetitions) }
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
                onAction(MapUiAction.AddDrawingZoneVertex(GeoPoint(wgsLat, wgsLon)))
            },
            onViewportChange = { newLat, newLon, newZoom ->
                onAction(MapUiAction.ViewportChanged(newLat, newLon, newZoom, fromFullScreenMap = true))
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
                        onAction(MapUiAction.FollowUser)
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
                                        onAction(MapUiAction.SelectGisStyleMode(mode))
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
                        onAction(MapUiAction.SelectCampus(SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value))
                    }
                )
                XPeachFilterPill(
                    label = "嵩山路",
                    selected = detectedCampus.campusId == SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value,
                    compact = true,
                    onClick = {
                        onAction(MapUiAction.SelectCampus(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value))
                    }
                )
                for (zone in customZones) {
                    XPeachFilterPill(
                        label = zone.campusName,
                        selected = detectedCampus.campusId == zone.campusId,
                        compact = true,
                        onClick = {
                            onAction(MapUiAction.SelectCampus(zone.campusId))
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
                                    onAction(MapUiAction.SelectGisSearchResult(item, item.isIndoorRoom))
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
                                onAction(MapUiAction.AddPendingPinAtCenter)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("添加建筑")
                        }
                        OutlinedButton(
                            enabled = drawingZoneVertices.isNotEmpty(),
                            onClick = {
                                onAction(MapUiAction.UndoDrawingZoneVertex)
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("撤销")
                        }
                        Button(
                            enabled = drawingZoneVertices.size >= 3,
                            shape = RoundedCornerShape(12.dp),
                            onClick = {
                                onAction(MapUiAction.SaveCustomZone)
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
                                        onAction(MapUiAction.ConfirmBuildingCandidate(candidate))
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
                    onAction(MapUiAction.LocateUser)
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
                    onToggleExpandSteps = { onAction(MapUiAction.ToggleRouteSteps) },
                    onOpenIndoorSecondaryMap = {
                        showIndoorSecondaryMap = true
                    },
                    onSelectOriginBuilding = { origin ->
                        onAction(MapUiAction.SelectOriginBuilding(origin))
                    },
                    onToggleInAppNavigation = {
                        onAction(MapUiAction.ToggleInAppNavigation)
                    },
                    onOpenOfficialWebGis = {
                        showOfficialWebGisDialog = true
                    },
                    onOpenExternalMap = {
                        onAction(MapUiAction.OpenExternalMap)
                    },
                    onOpenHospitalPharmacy = { onAction(MapUiAction.NavigateToHospitalPharmacy) }
                )
            }
        }
    }
}
