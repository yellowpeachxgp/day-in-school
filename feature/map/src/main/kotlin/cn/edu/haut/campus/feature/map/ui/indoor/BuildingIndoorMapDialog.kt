package cn.edu.haut.campus.feature.map.ui.indoor

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
import cn.edu.haut.campus.core.designsystem.XPeachGlassSurface
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
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
import cn.edu.haut.campus.feature.map.state.rememberIndoorMapInteractionState

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

    val interaction = rememberIndoorMapInteractionState(
        selectedFloorIndex = selectedFloorIndex,
        footprint = footprint,
        floorPlan = floorPlan,
        selectedRoom = selectedRoom,
        onSelectFloor = onSelectFloor,
        onSelectRoom = onSelectRoom
    )

    val currentFloorRooms = remember(floorPlan, interaction.selectedCategoryFilter, interaction.searchQuery) {
        val all = floorPlan?.rooms.orEmpty()
        all.filter { room ->
            if (room.name.isBlank()) return@filter false
            val catOk = if (interaction.selectedCategoryFilter == null) {
                room.category != GisIndoorRoomCategory.CORRIDOR
            } else {
                room.category == interaction.selectedCategoryFilter
            }
            val q = interaction.searchQuery.trim()
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
            ).coerceAtLeast(3)
        } else null
    }

    val isDarkPlan = XPeachTheme.isDark
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
                .background(if (isDarkPlan) Color(0xFF0C121B) else Color(0xFFF8FAFC))
        ) {
            // 1. 底层全屏独立建筑平面正交投影画布
            IndoorFloorPlanCanvas(
                footprint = footprint,
                floorPlan = floorPlan,
                selectedRoom = selectedRoom,
                nearestStairOrElevator = nearestStairOrElevator,
                highlightCategory = interaction.selectedCategoryFilter,
                zoom = interaction.zoom,
                panOffset = interaction.panOffset,
                onTransform = { newZoom, newPan ->
                    interaction.zoom = newZoom
                    interaction.panOffset = newPan
                },
                onSelectRoom = { room ->
                    onSelectRoom(room)
                }
            )

            if (isLoadingFloorPlan) {
                XPeachGlassSurface(
                    shape = RoundedCornerShape(16.dp),
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
                            text = "正在加载 ${selectedFloorIndex + 1}F…",
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
                XPeachGlassSurface(
                    shape = RoundedCornerShape(18.dp),
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
                                    XPeachStatusDotText(
                                        text = "${selectedFloorIndex + 1}F",
                                        dotColor = XPeachTheme.readablePrimary
                                    )
                                }
                                Text(
                                    text = if (floorPlan != null) {
                                        val count = floorPlan.rooms.count { it.name.isNotBlank() }
                                        "本层共 $count 处房间与设施"
                                    } else {
                                        "室内平面图"
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
                            value = interaction.searchQuery,
                            onValueChange = { interaction.searchQuery = it },
                            placeholder = {
                                Text(
                                    text = "搜索房间号（如 201、305）",
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
                                if (interaction.searchQuery.isNotBlank()) {
                                    IconButton(onClick = { interaction.searchQuery = "" }) {
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
                                selected = interaction.selectedCategoryFilter == null,
                                onClick = { interaction.selectedCategoryFilter = null }
                            )
                            IndoorLegendFilterChip(
                                label = "教室",
                                dotColor = Color(0xFF2563EB),
                                selected = interaction.selectedCategoryFilter == GisIndoorRoomCategory.CLASSROOM,
                                onClick = {
                                    interaction.selectedCategoryFilter =
                                        if (interaction.selectedCategoryFilter == GisIndoorRoomCategory.CLASSROOM) null
                                        else GisIndoorRoomCategory.CLASSROOM
                                }
                            )
                            IndoorLegendFilterChip(
                                label = "实验室",
                                dotColor = Color(0xFF059669),
                                selected = interaction.selectedCategoryFilter == GisIndoorRoomCategory.LABORATORY,
                                onClick = {
                                    interaction.selectedCategoryFilter =
                                        if (interaction.selectedCategoryFilter == GisIndoorRoomCategory.LABORATORY) null
                                        else GisIndoorRoomCategory.LABORATORY
                                }
                            )
                            IndoorLegendFilterChip(
                                label = "电梯/楼梯",
                                dotColor = Color(0xFF7C3AED),
                                selected = interaction.selectedCategoryFilter == GisIndoorRoomCategory.STAIRS_ELEVATOR,
                                onClick = {
                                    interaction.selectedCategoryFilter =
                                        if (interaction.selectedCategoryFilter == GisIndoorRoomCategory.STAIRS_ELEVATOR) null
                                        else GisIndoorRoomCategory.STAIRS_ELEVATOR
                                }
                            )
                            IndoorLegendFilterChip(
                                label = "卫生间",
                                dotColor = Color(0xFF0891B2),
                                selected = interaction.selectedCategoryFilter == GisIndoorRoomCategory.RESTROOM,
                                onClick = {
                                    interaction.selectedCategoryFilter =
                                        if (interaction.selectedCategoryFilter == GisIndoorRoomCategory.RESTROOM) null
                                        else GisIndoorRoomCategory.RESTROOM
                                }
                            )
                            IndoorLegendFilterChip(
                                label = "办公/教研",
                                dotColor = Color(0xFFD97706),
                                selected = interaction.selectedCategoryFilter == GisIndoorRoomCategory.OFFICE,
                                onClick = {
                                    interaction.selectedCategoryFilter =
                                        if (interaction.selectedCategoryFilter == GisIndoorRoomCategory.OFFICE) null
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
                XPeachGlassSurface(
                    shape = RoundedCornerShape(18.dp),
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
                            tint = XPeachTheme.readablePrimary,
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
                        onClick = { interaction.zoom = (interaction.zoom * 1.28f).coerceIn(0.75f, 6.0f) }
                    )
                    XPeachIconButton(
                        icon = Icons.Default.Remove,
                        contentDescription = "缩小平面图",
                        onClick = { interaction.zoom = (interaction.zoom / 1.28f).coerceIn(0.75f, 6.0f) }
                    )
                    XPeachIconButton(
                        icon = Icons.Default.ZoomOutMap,
                        contentDescription = "自适应居中",
                        onClick = {
                            interaction.zoom = 1.0f
                            interaction.panOffset = Offset.Zero
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
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    XPeachStatusDotText(
                                        text = "${selectedRoom.floorLabel} · ${selectedRoom.category.label}",
                                        dotColor = XPeachTheme.readablePrimary
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append("${building.name} · ${selectedRoom.floorLabel}")
                                        if (indoorWalkMeters != null) {
                                            append(" · 距楼梯/电梯约 ${indoorWalkMeters}m")
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
                                Text("去这里", maxLines = 1, softWrap = false)
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
                                text = "点击房间或下方房号查看位置",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            OutlinedButton(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("返回地图", style = MaterialTheme.typography.labelSmall)
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
                                text = "${selectedFloorIndex + 1}F 房间：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                            for (room in currentFloorRooms) {
                                XPeachFilterPill(
                                    label = room.name,
                                    selected = selectedRoom?.id == room.id,
                                    compact = true,
                                    onClick = { onSelectRoom(room) }
                                )
                            }
                        }
                    } else if (!isLoadingFloorPlan) {
                        Text(
                            text = "当前楼层无匹配房间",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
