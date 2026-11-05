package cn.edu.haut.campus.feature.map

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.location.GisBuildingFootprint
import cn.edu.haut.campus.core.location.GisIndoorFloorPlan
import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisIndoorRoomCategory
import cn.edu.haut.campus.core.location.HautOfficialGisClient
import cn.edu.haut.campus.core.model.Building
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 二级独立室内平面地图页（Dedicated Secondary Indoor Floor Plan Viewer）。
 *
 * 将楼宇室内分层平面图（1F ~ 11F）从室外大地图中彻底解耦出来：
 * - 采用以单栋建筑几何包络线为基准的独立米制正交投影画布，默认自动撑满 80% 视口，彻底消除在室外地图上缩成一团的拥挤感；
 * - 支持按房间类别（教室 / 实验室 / 楼梯电梯 / 卫生间 / 办公室）高亮筛选与本栋楼内房间号实时搜索（支持跨楼层自动跳转）；
 * - 选中任意房间后自动绘制从本层最近楼梯/电梯口到目标教室的室内虚线引导路径，并带有防重叠门牌号渲染。
 */
@Composable
internal fun BuildingIndoorMapDialog(
    building: Building,
    footprint: GisBuildingFootprint,
    floorPlan: GisIndoorFloorPlan?,
    isLoadingFloorPlan: Boolean,
    selectedFloorIndex: Int,
    selectedRoom: GisIndoorRoom?,
    onSelectFloor: (Int) -> Unit,
    onSelectRoom: (GisIndoorRoom?) -> Unit,
    onConfirmRoomForOutdoorRoute: (GisIndoorRoom) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    var indoorSearchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf<GisIndoorRoomCategory?>(null) }
    var planZoom by remember { mutableFloatStateOf(1.0f) }
    var planPanOffset by remember { mutableStateOf(Offset.Zero) }

    // 切换楼层时自动重置视口到居中最佳比例
    LaunchedEffect(selectedFloorIndex, footprint.featureId) {
        planZoom = 1.0f
        planPanOffset = Offset.Zero
    }

    // 当在室内搜索框输入跨楼层房间号（例如在 1F 输入 32402）时，自动推断并切换至目标楼层
    LaunchedEffect(indoorSearchQuery) {
        val q = indoorSearchQuery.trim()
        if (q.length >= 3) {
            val inferredFloor = HautOfficialGisClient.inferFloorIndexFromRoomQuery(
                rawQuery = q,
                maxLevelIndex = footprint.maxLevelIndex
            )
            if (inferredFloor != null && inferredFloor != selectedFloorIndex) {
                onSelectFloor(inferredFloor)
            }
        }
    }

    // 当平面图加载完毕且存在室内搜索词时，自动匹配对应房间
    LaunchedEffect(floorPlan, indoorSearchQuery) {
        val q = indoorSearchQuery.trim()
        if (q.isNotBlank() && floorPlan != null) {
            val clean = HautOfficialGisClient.extractCleanRoomCode(q) ?: q
            val hit = floorPlan.rooms.firstOrNull {
                it.name.equals(clean, ignoreCase = true) ||
                    it.name.equals(q, ignoreCase = true) ||
                    it.name.contains(clean, ignoreCase = true)
            }
            if (hit != null && selectedRoom?.id != hit.id) {
                onSelectRoom(hit)
            }
        }
    }

    val currentFloorRooms = remember(floorPlan, selectedCategoryFilter, indoorSearchQuery) {
        val all = floorPlan?.rooms.orEmpty()
        all.filter { room ->
            if (room.name.isBlank()) return@filter false
            val catOk = if (selectedCategoryFilter == null) {
                room.category != GisIndoorRoomCategory.CORRIDOR
            } else {
                room.category == selectedCategoryFilter
            }
            val q = indoorSearchQuery.trim()
            val textOk = q.isBlank() ||
                room.name.contains(q, ignoreCase = true) ||
                room.category.label.contains(q, ignoreCase = true)
            catOk && textOk
        }.sortedBy { it.name }
    }

    // 计算当前选中房间距离本层最近楼梯/电梯口的直线引导距离
    val nearestStairOrElevator = remember(floorPlan, selectedRoom) {
        val target = selectedRoom ?: return@remember null
        floorPlan?.rooms
            ?.filter { it.category == GisIndoorRoomCategory.STAIRS_ELEVATOR }
            ?.minByOrNull {
                CampusMapNavigator.calculateDistanceMeters(
                    target.centerLat,
                    target.centerLon,
                    it.centerLat,
                    it.centerLon
                )
            }
    }

    val indoorWalkMeters = remember(selectedRoom, nearestStairOrElevator) {
        if (selectedRoom != null && nearestStairOrElevator != null) {
            CampusMapNavigator.calculateDistanceMeters(
                selectedRoom.centerLat,
                selectedRoom.centerLon,
                nearestStairOrElevator.centerLat,
                nearestStairOrElevator.centerLon
            ).roundToInt().coerceAtLeast(3)
        } else null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF8FAFC))
        ) {
            // 1. 底层全屏独立建筑平面正交投影画布
            IndoorFloorPlanCanvas(
                footprint = footprint,
                floorPlan = floorPlan,
                selectedRoom = selectedRoom,
                nearestStairOrElevator = nearestStairOrElevator,
                highlightCategory = selectedCategoryFilter,
                zoom = planZoom,
                panOffset = planPanOffset,
                onTransform = { newZoom, newPan ->
                    planZoom = newZoom
                    planPanOffset = newPan
                },
                onSelectRoom = { room ->
                    onSelectRoom(room)
                }
            )

            if (isLoadingFloorPlan) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shadowElevation = 4.dp,
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.4.dp
                        )
                        Text(
                            text = "正在加载 ${selectedFloorIndex + 1}F 室内平面图…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // 2. 顶部二级室内地图导航头（返回室外地图 + 楼名与当前楼层 + 本楼房间搜索与图例筛选）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    shadowElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier.clickable(onClick = onDismiss)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回室外地图",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "室外地图",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = building.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    XPeachStatusBadge(
                                        text = "${selectedFloorIndex + 1}F 室内平面",
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append("独立二级室内图")
                                        if (floorPlan != null) {
                                            val count = floorPlan.rooms.count { it.name.isNotBlank() }
                                            append(" · 本层已标注 $count 处房间/设施")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // 本栋楼内房间搜索框
                        OutlinedTextField(
                            value = indoorSearchQuery,
                            onValueChange = { indoorSearchQuery = it },
                            placeholder = {
                                Text(
                                    text = "在本楼内搜房间号（如 201、305，支持跨层自动跳转）",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                            },
                            trailingIcon = {
                                if (indoorSearchQuery.isNotBlank()) {
                                    IconButton(onClick = { indoorSearchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "清空",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                unfocusedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 房间功能图例与分类高亮筛选条
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IndoorLegendFilterChip(
                                label = "全部房间",
                                dotColor = Color(0xFF3B82F6),
                                selected = selectedCategoryFilter == null,
                                onClick = { selectedCategoryFilter = null }
                            )
                            IndoorLegendFilterChip(
                                label = "教室",
                                dotColor = Color(0xFF2563EB),
                                selected = selectedCategoryFilter == GisIndoorRoomCategory.CLASSROOM,
                                onClick = {
                                    selectedCategoryFilter =
                                        if (selectedCategoryFilter == GisIndoorRoomCategory.CLASSROOM) null
                                        else GisIndoorRoomCategory.CLASSROOM
                                }
                            )
                            IndoorLegendFilterChip(
                                label = "实验室",
                                dotColor = Color(0xFF059669),
                                selected = selectedCategoryFilter == GisIndoorRoomCategory.LABORATORY,
                                onClick = {
                                    selectedCategoryFilter =
                                        if (selectedCategoryFilter == GisIndoorRoomCategory.LABORATORY) null
                                        else GisIndoorRoomCategory.LABORATORY
                                }
                            )
                            IndoorLegendFilterChip(
                                label = "电梯/楼梯",
                                dotColor = Color(0xFF7C3AED),
                                selected = selectedCategoryFilter == GisIndoorRoomCategory.STAIRS_ELEVATOR,
                                onClick = {
                                    selectedCategoryFilter =
                                        if (selectedCategoryFilter == GisIndoorRoomCategory.STAIRS_ELEVATOR) null
                                        else GisIndoorRoomCategory.STAIRS_ELEVATOR
                                }
                            )
                            IndoorLegendFilterChip(
                                label = "卫生间",
                                dotColor = Color(0xFF0891B2),
                                selected = selectedCategoryFilter == GisIndoorRoomCategory.RESTROOM,
                                onClick = {
                                    selectedCategoryFilter =
                                        if (selectedCategoryFilter == GisIndoorRoomCategory.RESTROOM) null
                                        else GisIndoorRoomCategory.RESTROOM
                                }
                            )
                            IndoorLegendFilterChip(
                                label = "办公/教研",
                                dotColor = Color(0xFFD97706),
                                selected = selectedCategoryFilter == GisIndoorRoomCategory.OFFICE,
                                onClick = {
                                    selectedCategoryFilter =
                                        if (selectedCategoryFilter == GisIndoorRoomCategory.OFFICE) null
                                        else GisIndoorRoomCategory.OFFICE
                                }
                            )
                        }
                    }
                }
            }

            // 3. 右侧楼层切换塔（1F ~ 6F/11F）与缩放/复位按钮
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp, bottom = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shadowElevation = 3.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(vertical = 8.dp, horizontal = 6.dp)
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = "楼层切换",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .size(16.dp)
                        )
                        for ((floorIdx, floorLabel) in footprint.floorOptions.reversed()) {
                            val isSelected = selectedFloorIndex == floorIdx
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier
                                    .width(42.dp)
                                    .clickable { onSelectFloor(floorIdx) }
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = floorLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    XPeachIconButton(
                        icon = Icons.Default.Add,
                        contentDescription = "放大平面图",
                        onClick = { planZoom = (planZoom * 1.28f).coerceIn(0.75f, 6.0f) }
                    )
                    XPeachIconButton(
                        icon = Icons.Default.Remove,
                        contentDescription = "缩小平面图",
                        onClick = { planZoom = (planZoom / 1.28f).coerceIn(0.75f, 6.0f) }
                    )
                    XPeachIconButton(
                        icon = Icons.Default.ZoomOutMap,
                        contentDescription = "自适应居中",
                        onClick = {
                            planZoom = 1.0f
                            planPanOffset = Offset.Zero
                        }
                    )
                }
            }

            // 4. 底部房间详情与本层教室/实验室点选抽屉
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                    contentPadding = PaddingValues(14.dp)
                ) {
                    if (selectedRoom != null) {
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = selectedRoom.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    XPeachStatusBadge(
                                        text = selectedRoom.floorLabel,
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                    XPeachStatusBadge(
                                        text = selectedRoom.category.label
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append("${building.name} · ${selectedRoom.floorLabel}")
                                        if (indoorWalkMeters != null) {
                                            append(" · 距本层最近楼梯/电梯约 ${indoorWalkMeters}m")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Button(
                                onClick = { onConfirmRoomForOutdoorRoute(selectedRoom) },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("导航到此房间", maxLines = 1, softWrap = false)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "点击平面图中的房间或下方门牌号即可高亮定位",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("返回室外路线", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // 本层房间门牌快速点选条
                    if (currentFloorRooms.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${selectedFloorIndex + 1}F 门牌：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                            for (room in currentFloorRooms) {
                                XPeachFilterPill(
                                    label = room.name,
                                    selected = selectedRoom?.id == room.id,
                                    onClick = { onSelectRoom(room) }
                                )
                            }
                        }
                    } else if (!isLoadingFloorPlan) {
                        Text(
                            text = "当前楼层暂无匹配的标注房间，可切换右侧楼层查看",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IndoorLegendFilterChip(
    label: String,
    dotColor: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * 独立建筑平面正交米制投影画布（无街景底图干扰、高对比度建筑制图风格、严格防重叠文字标注）。
 */
@Composable
private fun IndoorFloorPlanCanvas(
    footprint: GisBuildingFootprint,
    floorPlan: GisIndoorFloorPlan?,
    selectedRoom: GisIndoorRoom?,
    nearestStairOrElevator: GisIndoorRoom?,
    highlightCategory: GisIndoorRoomCategory?,
    zoom: Float,
    panOffset: Offset,
    onTransform: (Float, Offset) -> Unit,
    onSelectRoom: (GisIndoorRoom) -> Unit
) {
    val currentZoom by rememberUpdatedState(zoom)
    val currentPan by rememberUpdatedState(panOffset)
    val currentFloorPlan by rememberUpdatedState(floorPlan)
    val currentOnTransform by rememberUpdatedState(onTransform)
    val currentOnSelectRoom by rememberUpdatedState(onSelectRoom)

    // 计算当前楼宇平面图的地理包围盒与中心点，保证单栋楼宇在 1.0x 下自动饱满居中显示
    val boundsInfo = remember(footprint, floorPlan) {
        val allPts = buildList {
            floorPlan?.rooms?.forEach { addAll(it.outerRing) }
            floorPlan?.outlineRings?.forEach { addAll(it) }
            addAll(footprint.outerRing)
        }
        if (allPts.isEmpty()) {
            IndoorBounds(
                midLat = footprint.centerLat,
                midLon = footprint.centerLon,
                cosLat = cos(Math.toRadians(footprint.centerLat)).coerceAtLeast(0.2),
                spanXMeters = 60.0,
                spanYMeters = 60.0
            )
        } else {
            val minLat = allPts.minOf { it.latitude }
            val maxLat = allPts.maxOf { it.latitude }
            val minLon = allPts.minOf { it.longitude }
            val maxLon = allPts.maxOf { it.longitude }
            val midLat = (minLat + maxLat) / 2.0
            val midLon = (minLon + maxLon) / 2.0
            val cosLat = cos(Math.toRadians(midLat)).coerceAtLeast(0.2)
            val spanX = ((maxLon - minLon) * 111320.0 * cosLat).coerceAtLeast(22.0)
            val spanY = ((maxLat - minLat) * 110540.0).coerceAtLeast(22.0)
            IndoorBounds(
                midLat = midLat,
                midLon = midLon,
                cosLat = cosLat,
                spanXMeters = spanX,
                spanYMeters = spanY
            )
        }
    }

    val currentBounds by rememberUpdatedState(boundsInfo)
    var canvasWidthPx by remember { mutableFloatStateOf(1080f) }
    var canvasHeightPx by remember { mutableFloatStateOf(1920f) }

    fun wgsToPlanScreenDynamic(lat: Double, lon: Double): Offset {
        val b = currentBounds
        val baseScale = minOf(
            (canvasWidthPx * 0.82f) / b.spanXMeters.toFloat(),
            (canvasHeightPx * 0.52f) / b.spanYMeters.toFloat()
        ).coerceAtLeast(1.5f)
        val effectiveScale = baseScale * currentZoom
        val xm = ((lon - b.midLon) * 111320.0 * b.cosLat).toFloat()
        val ym = (-(lat - b.midLat) * 110540.0).toFloat()
        val cx = canvasWidthPx * 0.46f + currentPan.x
        val cy = canvasHeightPx * 0.48f + currentPan.y
        return Offset(cx + xm * effectiveScale, cy + ym * effectiveScale)
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val plan = currentFloorPlan ?: return@detectTapGestures
                    val hitRoom = plan.rooms.firstOrNull { room ->
                        val ring = room.outerRing.map { wgsToPlanScreenDynamic(it.latitude, it.longitude) }
                        isPointInsidePolygon(tapOffset, ring)
                    }
                    if (hitRoom != null) {
                        currentOnSelectRoom(hitRoom)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val newZoom = (currentZoom * gestureZoom).coerceIn(0.75f, 6.0f)
                    val newPan = Offset(
                        x = (currentPan.x + pan.x).coerceIn(-canvasWidthPx * 1.6f, canvasWidthPx * 1.6f),
                        y = (currentPan.y + pan.y).coerceIn(-canvasHeightPx * 1.6f, canvasHeightPx * 1.6f)
                    )
                    currentOnTransform(newZoom, newPan)
                }
            }
    ) {
        canvasWidthPx = size.width
        canvasHeightPx = size.height

        val baseScale = minOf(
            (size.width * 0.82f) / boundsInfo.spanXMeters.toFloat(),
            (size.height * 0.52f) / boundsInfo.spanYMeters.toFloat()
        ).coerceAtLeast(1.5f)
        val effectiveScale = baseScale * zoom
        val originCenter = Offset(
            x = size.width * 0.46f + panOffset.x,
            y = size.height * 0.48f + panOffset.y
        )

        fun wgsToScreen(lat: Double, lon: Double): Offset {
            val xm = ((lon - boundsInfo.midLon) * 111320.0 * boundsInfo.cosLat).toFloat()
            val ym = (-(lat - boundsInfo.midLat) * 110540.0).toFloat()
            return Offset(
                x = originCenter.x + xm * effectiveScale,
                y = originCenter.y + ym * effectiveScale
            )
        }

        // 1. 绘制建筑工程制图浅色网格背景（无杂乱街景瓦片干扰）
        drawRect(color = Color(0xFFF8FAFC))
        val gridStepMeters = when {
            effectiveScale >= 18f -> 5f
            effectiveScale >= 9f -> 10f
            else -> 20f
        }
        val gridStepPx = (gridStepMeters * effectiveScale).coerceAtLeast(28f)
        val startX = (originCenter.x % gridStepPx + gridStepPx) % gridStepPx
        val startY = (originCenter.y % gridStepPx + gridStepPx) % gridStepPx
        var gx = startX
        while (gx < size.width) {
            drawLine(
                color = Color(0xFFE2E8F0),
                start = Offset(gx, 0f),
                end = Offset(gx, size.height),
                strokeWidth = 1f
            )
            gx += gridStepPx
        }
        var gy = startY
        while (gy < size.height) {
            drawLine(
                color = Color(0xFFE2E8F0),
                start = Offset(0f, gy),
                end = Offset(size.width, gy),
                strokeWidth = 1f
            )
            gy += gridStepPx
        }

        // 2. 绘制建筑外轮廓结构底板与阴影
        val shellRings = if (!floorPlan?.outlineRings.isNullOrEmpty()) {
            floorPlan!!.outlineRings
        } else if (footprint.outerRing.size >= 3) {
            listOf(footprint.outerRing)
        } else {
            emptyList()
        }

        for (ring in shellRings) {
            val pts = ring.map { wgsToScreen(it.latitude, it.longitude) }
            if (pts.size < 3) continue
            val shadowPath = Path().apply {
                pts.forEachIndexed { idx, p ->
                    if (idx == 0) moveTo(p.x + 6f, p.y + 8f) else lineTo(p.x + 6f, p.y + 8f)
                }
                close()
            }
            val shellPath = Path().apply {
                pts.forEachIndexed { idx, p ->
                    if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(path = shadowPath, color = Color(0x180F172A))
            drawPath(path = shellPath, color = Color(0xFFF1F5F9))
            drawPath(
                path = shellPath,
                color = Color(0xFF475569),
                style = Stroke(width = 4.5f, join = StrokeJoin.Round)
            )
        }

        // 3. 绘制当前楼层所有房间多边形（按教室/实验室/楼梯/卫生间着色，无重叠清晰边界）
        val rooms = floorPlan?.rooms.orEmpty()
        for (room in rooms) {
            val pts = room.outerRing.map { wgsToScreen(it.latitude, it.longitude) }
            if (pts.size < 3) continue
            val isSelected = selectedRoom?.id == room.id
            val isDimmed = highlightCategory != null && room.category != highlightCategory && !isSelected
            val alphaMul = if (isDimmed) 0.28f else 1.0f

            val roomPath = Path().apply {
                pts.forEachIndexed { idx, p ->
                    if (idx == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }

            val baseFill = when {
                isSelected -> Color(0xFFF97316)
                room.category == GisIndoorRoomCategory.CLASSROOM -> Color(0xFFDBEAFE)
                room.category == GisIndoorRoomCategory.LABORATORY -> Color(0xFFD1FAE5)
                room.category == GisIndoorRoomCategory.OFFICE -> Color(0xFFFEF3C7)
                room.category == GisIndoorRoomCategory.STAIRS_ELEVATOR -> Color(0xFFEDE9FE)
                room.category == GisIndoorRoomCategory.RESTROOM -> Color(0xFFCFFAFE)
                room.category == GisIndoorRoomCategory.CORRIDOR -> Color(0xFFF8FAFC)
                else -> Color(0xFFE2E8F0)
            }
            val baseBorder = when {
                isSelected -> Color(0xFFC2410C)
                room.category == GisIndoorRoomCategory.CLASSROOM -> Color(0xFF3B82F6)
                room.category == GisIndoorRoomCategory.LABORATORY -> Color(0xFF10B981)
                room.category == GisIndoorRoomCategory.OFFICE -> Color(0xFFF59E0B)
                room.category == GisIndoorRoomCategory.STAIRS_ELEVATOR -> Color(0xFF7C3AED)
                room.category == GisIndoorRoomCategory.RESTROOM -> Color(0xFF0891B2)
                else -> Color(0xFF94A3B8)
            }

            drawPath(
                path = roomPath,
                color = baseFill.copy(alpha = (if (isSelected) 0.90f else 0.88f) * alphaMul)
            )
            drawPath(
                path = roomPath,
                color = baseBorder.copy(alpha = alphaMul),
                style = Stroke(
                    width = if (isSelected) 4.5f else 2.0f,
                    join = StrokeJoin.Round
                )
            )
        }

        // 4. 若选中了目标房间，自动绘制从本层最近楼梯/电梯口到目标房间的室内步行引导虚线
        if (selectedRoom != null && nearestStairOrElevator != null && selectedRoom.id != nearestStairOrElevator.id) {
            val startPt = wgsToScreen(nearestStairOrElevator.centerLat, nearestStairOrElevator.centerLon)
            val endPt = wgsToScreen(selectedRoom.centerLat, selectedRoom.centerLon)
            drawLine(
                color = Color.White,
                start = startPt,
                end = endPt,
                strokeWidth = 9f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color(0xFF0284C7),
                start = startPt,
                end = endPt,
                strokeWidth = 5f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
            )
            val segLen = hypot((endPt.x - startPt.x).toDouble(), (endPt.y - startPt.y).toDouble()).toFloat()
            if (segLen > 45f) {
                val angle = atan2((endPt.y - startPt.y).toDouble(), (endPt.x - startPt.x).toDouble())
                val midX = (startPt.x + endPt.x) / 2f
                val midY = (startPt.y + endPt.y) / 2f
                val wing = 9f
                drawLine(
                    color = Color(0xFF0284C7),
                    start = Offset(
                        midX - (wing * cos(angle - PI / 4)).toFloat(),
                        midY - (wing * sin(angle - PI / 4)).toFloat()
                    ),
                    end = Offset(midX, midY),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color(0xFF0284C7),
                    start = Offset(
                        midX - (wing * cos(angle + PI / 4)).toFloat(),
                        midY - (wing * sin(angle + PI / 4)).toFloat()
                    ),
                    end = Offset(midX, midY),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
            drawCircle(color = Color.White, radius = 9f, center = startPt)
            drawCircle(color = Color(0xFF7C3AED), radius = 6f, center = startPt)
        }

        // 5. 严格防碰撞的房间门牌号标注（仅当文字能放入房间多边形内部且不与已有标签重叠时才绘制）
        val placedRects = mutableListOf<RectF>()
        val roomLabelPaint = Paint().apply {
            isAntiAlias = true
            textSize = when {
                zoom >= 2.2f -> 12.sp.toPx()
                zoom >= 1.4f -> 10.5.sp.toPx()
                else -> 9.5.sp.toPx()
            }
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = android.graphics.Color.rgb(30, 41, 59)
        }

        for (room in rooms) {
            if (room.name.isBlank() || room.id == selectedRoom?.id) continue
            val isDimmed = highlightCategory != null && room.category != highlightCategory
            if (isDimmed) continue

            val pts = room.outerRing.map { wgsToScreen(it.latitude, it.longitude) }
            if (pts.size < 3) continue
            val minX = pts.minOf { it.x }
            val maxX = pts.maxOf { it.x }
            val minY = pts.minOf { it.y }
            val maxY = pts.maxOf { it.y }
            val polyW = maxX - minX
            val polyH = maxY - minY

            val tw = roomLabelPaint.measureText(room.name)
            val th = roomLabelPaint.textSize
            // 如果房间在当前缩放级别下太窄放不下文字，则自动隐藏文字，放大后再显现，绝不糊成一团
            if (polyW < tw * 0.88f || polyH < th * 1.15f) continue

            val center = wgsToScreen(room.centerLat, room.centerLon)
            val candidateRect = RectF(
                center.x - tw / 2f - 4f,
                center.y - th / 2f - 3f,
                center.x + tw / 2f + 4f,
                center.y + th / 2f + 3f
            )
            if (placedRects.any { RectF.intersects(it, candidateRect) }) continue
            placedRects.add(candidateRect)

            drawContext.canvas.nativeCanvas.drawText(
                room.name,
                center.x - tw / 2f,
                center.y + th * 0.34f,
                roomLabelPaint
            )
        }

        // 6. 最高优先级：绘制当前选中房间的醒目悬浮气泡与定位锚点
        if (selectedRoom != null) {
            val center = wgsToScreen(selectedRoom.centerLat, selectedRoom.centerLon)
            drawCircle(
                color = Color(0x44F97316),
                radius = 28f,
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 9f,
                center = center
            )
            drawCircle(
                color = Color(0xFFEA580C),
                radius = 6f,
                center = center
            )

            val calloutText = "★ ${selectedRoom.name} (${selectedRoom.floorLabel})"
            val calloutTextPaint = Paint().apply {
                isAntiAlias = true
                textSize = 11.5.sp.toPx()
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                color = android.graphics.Color.WHITE
            }
            val calloutBgPaint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.rgb(234, 88, 12)
                style = Paint.Style.FILL
            }
            val tw = calloutTextPaint.measureText(calloutText)
            val rectLeft = center.x - tw / 2f - 12f
            val rectTop = center.y - 44f
            val rectRight = center.x + tw / 2f + 12f
            val rectBottom = center.y - 14f
            drawContext.canvas.nativeCanvas.drawRoundRect(
                rectLeft,
                rectTop,
                rectRight,
                rectBottom,
                14f,
                14f,
                calloutBgPaint
            )
            drawContext.canvas.nativeCanvas.drawText(
                calloutText,
                center.x - tw / 2f,
                center.y - 24f,
                calloutTextPaint
            )
        }

        // 7. 左下角绘制真实米制比例尺与指北标识
        val scaleBarTextPaint = Paint().apply {
            isAntiAlias = true
            textSize = 10.sp.toPx()
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            color = android.graphics.Color.rgb(71, 85, 105)
        }
        val barLeft = 42f
        val barY = size.height - 460f
        drawLine(
            color = Color(0xFF475569),
            start = Offset(barLeft, barY),
            end = Offset(barLeft + gridStepPx, barY),
            strokeWidth = 3f
        )
        drawLine(
            color = Color(0xFF475569),
            start = Offset(barLeft, barY - 6f),
            end = Offset(barLeft, barY + 6f),
            strokeWidth = 3f
        )
        drawLine(
            color = Color(0xFF475569),
            start = Offset(barLeft + gridStepPx, barY - 6f),
            end = Offset(barLeft + gridStepPx, barY + 6f),
            strokeWidth = 3f
        )
        drawContext.canvas.nativeCanvas.drawText(
            "${gridStepMeters.roundToInt()}米 · 上北下南",
            barLeft,
            barY - 10f,
            scaleBarTextPaint
        )
    }
}

private data class IndoorBounds(
    val midLat: Double,
    val midLon: Double,
    val cosLat: Double,
    val spanXMeters: Double,
    val spanYMeters: Double
)

private fun isPointInsidePolygon(pt: Offset, ring: List<Offset>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.lastIndex
    for (i in ring.indices) {
        val xi = ring[i].x
        val yi = ring[i].y
        val xj = ring[j].x
        val yj = ring[j].y
        val intersect = ((yi > pt.y) != (yj > pt.y)) &&
            (pt.x < (xj - xi) * (pt.y - yi) / ((yj - yi).takeIf { abs(it) > 1e-5f } ?: 1e-5f) + xi)
        if (intersect) inside = !inside
        j = i
    }
    return inside
}
