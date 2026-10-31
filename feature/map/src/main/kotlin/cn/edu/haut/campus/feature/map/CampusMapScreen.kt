package cn.edu.haut.campus.feature.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
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

    LaunchedEffect(initialClassroomQuery) {
        if (!initialClassroomQuery.isNullOrBlank()) {
            searchQuery = initialClassroomQuery
        }
    }

    val classroomResolution = remember(searchQuery, buildings, aliases) {
        if (searchQuery.isBlank()) null
        else ClassroomBuildingResolver.resolve(searchQuery, buildings, aliases)
    }

    LaunchedEffect(classroomResolution) {
        if (classroomResolution?.building != null) {
            selectedBuilding = classroomResolution.building
        }
    }

    val filteredBuildings = remember(buildings, selectedCategory, searchQuery) {
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("莲花街校区地图与教室定位", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "室外楼级坐标核验 · 教室字符串别名解析",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("输入教务教室（如 1号教学楼1204、A座204、3206）或建筑名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // 教务教室解析结果与模糊匹配人工确认卡片（遵循 PRD 6：模糊匹配需给用户确认，不可装作已识别成功）
            if (classroomResolution != null && searchQuery.isNotBlank()) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = when (classroomResolution.status) {
                            BuildingMatchStatus.EXACT_VERIFIED -> MaterialTheme.colorScheme.secondaryContainer
                            BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION -> MaterialTheme.colorScheme.primaryContainer
                            BuildingMatchStatus.UNRESOLVED -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (classroomResolution.status) {
                            BuildingMatchStatus.EXACT_VERIFIED -> {
                                val b = classroomResolution.building!!
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "已精确识别教学楼：${b.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "原始教室「${classroomResolution.rawRoom}」${classroomResolution.roomNumberHint?.let { " · 房间号 $it" } ?: ""} · 推荐入口：${b.entrances.joinToString(" / ")}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Button(
                                    onClick = { CampusMapNavigator.launchWalkingNavigation(context, b) }
                                ) {
                                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("步行导航至 ${b.name}")
                                }
                            }

                            BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION -> {
                                val candidate = classroomResolution.building
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.HelpOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "模糊匹配待确认（置信度 ${(classroomResolution.confidence * 100).toInt()}%）",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = "教室字符串「${classroomResolution.rawRoom}」未经100%核验，推测可能位于「${candidate?.name ?: "未知楼宇"}」（依据：${classroomResolution.matchedAlias ?: "编号推断"}）。请确认是否正确：",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (candidate != null) {
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    repository.confirmBuildingAliasForRoom(
                                                        rawRoom = classroomResolution.rawRoom,
                                                        buildingId = candidate.id
                                                    )
                                                }
                                            }
                                        ) {
                                            Text("确认是 ${candidate.shortName}")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                CampusMapNavigator.launchWalkingNavigation(context, candidate)
                                            }
                                        ) {
                                            Text("仅本次导航")
                                        }
                                    }
                                }
                            }

                            BuildingMatchStatus.UNRESOLVED -> {
                                Text(
                                    text = "尚未能自动匹配「${classroomResolution.rawRoom}」所属楼栋，请从下方莲花街校区建筑列表中选择并绑定：",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // 莲花街校区方位示意雷达图
            LianhuajieCampusSchematicCard(
                buildings = buildings,
                highlightedBuilding = selectedBuilding ?: classroomResolution?.building,
                onSelectBuilding = { selectedBuilding = it }
            )

            // 类别筛选
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("全部地点") }
                )
                for (category in CampusCategory.entries) {
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category.label) }
                    )
                }
            }

            // POI 建筑卡片列表
            for (building in filteredBuildings) {
                val isSelected = selectedBuilding?.id == building.id
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedBuilding = building }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = building.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${building.category.label} · 核验于 ${building.verifiedAt}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = { CampusMapNavigator.launchWalkingNavigation(context, building) }
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("步行路线")
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = building.description,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "常用入口：${building.entrances.joinToString("、")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LianhuajieCampusSchematicCard(
    buildings: List<Building>,
    highlightedBuilding: Building?,
    onSelectBuilding: (Building) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val outlineColor = MaterialTheme.colorScheme.outlineVariant

    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("莲花街校区楼宇相对方位图（北↑）", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = highlightedBuilding?.shortName?.let { "当前定位: $it" } ?: "点击下方建筑高亮",
                    style = MaterialTheme.typography.labelMedium,
                    color = primaryColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                    if (buildings.isEmpty()) return@Canvas
                    val minLat = buildings.minOf { it.latitude }
                    val maxLat = buildings.maxOf { it.latitude }
                    val minLng = buildings.minOf { it.longitude }
                    val maxLng = buildings.maxOf { it.longitude }
                    val latSpan = (maxLat - minLat).coerceAtLeast(0.002)
                    val lngSpan = (maxLng - minLng).coerceAtLeast(0.002)

                    // 校园中轴参考网格
                    drawLine(
                        color = outlineColor,
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = outlineColor,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1.dp.toPx()
                    )

                    for (b in buildings) {
                        val xRatio = ((b.longitude - minLng) / lngSpan).toFloat().coerceIn(0.05f, 0.95f)
                        val yRatio = (1.0 - (b.latitude - minLat) / latSpan).toFloat().coerceIn(0.08f, 0.92f)
                        val pt = Offset(xRatio * size.width, yRatio * size.height)
                        val isHighlighted = highlightedBuilding?.id == b.id

                        if (isHighlighted) {
                            drawCircle(
                                color = primaryColor.copy(alpha = 0.25f),
                                radius = 16.dp.toPx(),
                                center = pt
                            )
                        }
                        drawCircle(
                            color = if (isHighlighted) primaryColor else secondaryColor,
                            radius = if (isHighlighted) 8.dp.toPx() else 5.dp.toPx(),
                            center = pt
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "注：室外 GPS 为楼级参考；楼内具体教室请结合楼层指示牌。支持快速调起系统地图步行导航。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
