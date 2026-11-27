package cn.edu.haut.campus.feature.map.ui.components

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
import cn.edu.haut.campus.feature.map.ui.indoor.BuildingIndoorMapDialog
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
internal fun FloatingRouteBottomSheet(
    building: Building,
    plan: InAppNavigationPlan,
    isOfficialGisRoute: Boolean,
    activeFootprint: GisBuildingFootprint?,
    selectedIndoorRoom: GisIndoorRoom?,
    allBuildings: List<Building>,
    hasLiveGps: Boolean,
    deviceHeadingDegrees: Float?,
    manualOriginBuilding: Building?,
    inAppNavigationActive: Boolean,
    expandRouteSteps: Boolean,
    onToggleExpandSteps: () -> Unit,
    onOpenIndoorSecondaryMap: () -> Unit,
    onSelectOriginBuilding: (Building?) -> Unit,
    onToggleInAppNavigation: () -> Unit,
    onOpenOfficialWebGis: () -> Unit,
    onOpenExternalMap: () -> Unit,
    onOpenHospitalPharmacy: () -> Unit = {}
) {
    val headingHint = remember(deviceHeadingDegrees, plan.bearingDegrees) {
        if (deviceHeadingDegrees == null) {
            "方向：${plan.compassDirectionLabel}"
        } else {
            var diff = (plan.bearingDegrees - deviceHeadingDegrees.roundToInt()) % 360
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            when {
                abs(diff) <= 22 -> "正对方向"
                diff > 22 -> "右转约 ${abs(diff)}°"
                else -> "左转约 ${abs(diff)}°"
            }
        }
    }

    val hasIndoorMap = activeFootprint?.hasIndoorFloors == true

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
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    XPeachStatusDotText(
                        text = "${plan.estimatedWalkDistanceMeters}m · 约${plan.estimatedWalkMinutes}分钟",
                        dotColor = XPeachTheme.readablePrimary
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append("${plan.originLabel} → ${building.shortName}")
                        if (selectedIndoorRoom != null) {
                            append(" (${selectedIndoorRoom.floorLabel} · ${selectedIndoorRoom.name})")
                        }
                        append(" · $headingHint")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = XPeachTheme.readablePrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            XPeachFilterPill(
                label = if (expandRouteSteps) "收起" else "详情",
                selected = expandRouteSteps,
                compact = true,
                onClick = onToggleExpandSteps
            )
        }

        // 若当前楼宇支持室内平面图，提供二级室内地图入口条
        if (hasIndoorMap) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = XPeachTheme.primaryAccentCardColor,
                border = androidx.compose.foundation.BorderStroke(
                    0.8.dp,
                    XPeachTheme.readablePrimary.copy(alpha = 0.36f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenIndoorSecondaryMap)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = XPeachTheme.readablePrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (selectedIndoorRoom != null) {
                                "已选 ${selectedIndoorRoom.floorLabel} ${selectedIndoorRoom.name} · 查看室内平面图"
                            } else {
                                "查看 ${building.shortName} 室内平面图（1F–${activeFootprint!!.maxLevelIndex + 1}F）"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = XPeachTheme.readablePrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "查看 ›",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary
                    )
                }
            }
        }

        // 若当前选中校医院，提供 202 项药品价格公示与大学生医保说明快捷入口
        if (building.id == "b_hospital" || building.name.contains("校医院")) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = XPeachTheme.primaryAccentCardColor,
                border = androidx.compose.foundation.BorderStroke(
                    0.8.dp,
                    XPeachTheme.readablePrimary.copy(alpha = 0.36f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenHospitalPharmacy)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "校医院药品价格公示目录与大学生医保说明",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = XPeachTheme.readablePrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "查看目录 ›",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary
                    )
                }
            }
        }

        if (expandRouteSteps) {
            Spacer(modifier = Modifier.height(10.dp))

            val officialDesc = activeFootprint?.description?.takeIf { it.isNotBlank() } ?: building.description
            if (officialDesc.isNotBlank()) {
                Text(
                    text = officialDesc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

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
                    label = if (hasLiveGps) "我的位置" else "南门",
                    selected = manualOriginBuilding == null,
                    compact = true,
                    onClick = { onSelectOriginBuilding(null) }
                )
                for (b in allBuildings.filter { it.id != building.id }) {
                    XPeachFilterPill(
                        label = b.shortName,
                        selected = manualOriginBuilding?.id == b.id,
                        compact = true,
                        onClick = { onSelectOriginBuilding(b) }
                    )
                }
            }

            XPeachInsetDivider(verticalPadding = 6.dp)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
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
                                .background(XPeachTheme.primaryAccentCardColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${step.stepIndex}",
                                style = MaterialTheme.typography.labelSmall,
                                color = XPeachTheme.readablePrimary,
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
                            color = XPeachTheme.readablePrimary,
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onToggleInAppNavigation,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (inAppNavigationActive) "隐藏路线" else "显示路线",
                    maxLines = 1,
                    softWrap = false
                )
            }

            if (hasIndoorMap) {
                FilledTonalButton(
                    onClick = onOpenIndoorSecondaryMap,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("室内平面", maxLines = 1, softWrap = false)
                }
            } else {
                FilledTonalButton(
                    onClick = onOpenOfficialWebGis,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("网页地图", maxLines = 1, softWrap = false)
                }
            }

            FilledTonalButton(
                onClick = onOpenExternalMap,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("高德步行", maxLines = 1, softWrap = false)
            }
        }
    }
}
