package cn.edu.haut.campus.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CampusClubCategory
import cn.edu.haut.campus.core.model.CampusClubItem
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.PresetQqChannelItem
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZonedDateTime

internal val LIANHUAJIE_STUDY_ROOMS_MAP: Map<String, List<String>> = linkedMapOf(
    "5号教学楼" to listOf("5101", "5103", "5105", "5201", "5202", "5204", "5301", "5303", "5402"),
    "6号教学楼" to listOf("6101", "6102", "6201", "6203", "6205", "6301", "6304", "6401"),
    "7号教学楼" to listOf("7102", "7104", "7201", "7203", "7302", "7305", "7401"),
    "8号教学楼" to listOf("8101", "8103", "8201", "8202", "8301", "8303", "8402"),
    "9号教学楼" to listOf("9101", "9102", "9201", "9204", "9301", "9303"),
    "11号教学楼" to listOf("11101", "11102", "11201", "11203", "11301", "11302")
)

internal data class CampusStudyPeriodSlot(
    val label: String,
    val startPeriod: Int,
    val endPeriod: Int
)

internal val CAMPUS_STUDY_PERIOD_SLOTS = listOf(
    CampusStudyPeriodSlot("1-2节", 1, 2),
    CampusStudyPeriodSlot("3-4节", 3, 4),
    CampusStudyPeriodSlot("5-6节", 5, 6),
    CampusStudyPeriodSlot("7-8节", 7, 8),
    CampusStudyPeriodSlot("9-10节", 9, 10)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmptyClassroomScreen(
    repository: CampusRepository,
    onNavigateToMapWithRoom: (String) -> Unit,
    onBack: () -> Unit
) {
    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val calendarOverrides by repository.calendarOverridesFlow.collectAsState(initial = emptyList())

    val today = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
    val currentTeachingWeek = remember(today, currentTerm, calendarOverrides) {
        currentTerm?.let { TeachingWeekCalculator.resolveDate(today, it, calendarOverrides).teachingWeek } ?: 1
    }

    var selectedDayOfWeek by remember { mutableStateOf(today.dayOfWeek) }
    var selectedSlotIndex by remember {
        val nowHour = ZonedDateTime.now(HAUT_ZONE_ID).hour
        val defaultIdx = when {
            nowHour < 10 -> 0
            nowHour < 12 -> 1
            nowHour < 16 -> 2
            nowHour < 18 -> 3
            else -> 4
        }
        mutableIntStateOf(defaultIdx)
    }
    val buildingKeys = remember { LIANHUAJIE_STUDY_ROOMS_MAP.keys.toList() }
    var selectedBuildingFilter by remember { mutableStateOf<String?>(null) }

    val activeSlot = CAMPUS_STUDY_PERIOD_SLOTS[selectedSlotIndex]
    val busyRoomsInSlot = remember(rules, currentTerm?.id, currentTeachingWeek, selectedDayOfWeek, activeSlot) {
        rules.filter { rule ->
            (currentTerm == null || rule.termId == currentTerm?.id) &&
                rule.weekday == selectedDayOfWeek &&
                (rule.weeks.isEmpty() || currentTeachingWeek in rule.weeks) &&
                rule.startPeriod <= activeSlot.endPeriod &&
                rule.endPeriod >= activeSlot.startPeriod
        }.map { it.rawRoom }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "空教室查询",
            subtitle = "莲花街校区 · 第 $currentTeachingWeek 周 · 点击教室可直接导航",
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "选择星期与节次",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (day in DayOfWeek.entries) {
                        XPeachFilterPill(
                            label = if (day == today.dayOfWeek) "${day.ChineseLabel}(今)" else day.ChineseLabel,
                            selected = selectedDayOfWeek == day,
                            onClick = { selectedDayOfWeek = day }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CAMPUS_STUDY_PERIOD_SLOTS.forEachIndexed { idx, slot ->
                        XPeachFilterPill(
                            label = slot.label,
                            selected = selectedSlotIndex == idx,
                            onClick = { selectedSlotIndex = idx }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    XPeachFilterPill(
                        label = "全部教学楼",
                        selected = selectedBuildingFilter == null,
                        onClick = { selectedBuildingFilter = null }
                    )
                    for (bName in buildingKeys) {
                        XPeachFilterPill(
                            label = bName.removeSuffix("教学楼"),
                            selected = selectedBuildingFilter == bName,
                            onClick = { selectedBuildingFilter = bName }
                        )
                    }
                }
            }

            val buildingsToShow = if (selectedBuildingFilter != null) {
                listOf(selectedBuildingFilter!!)
            } else {
                buildingKeys
            }

            for (buildingName in buildingsToShow) {
                val candidateRooms = LIANHUAJIE_STUDY_ROOMS_MAP[buildingName].orEmpty()
                val freeRooms = candidateRooms.filter { code ->
                    busyRoomsInSlot.none { raw -> raw.contains(code) }
                }
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildingName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        XPeachStatusDotText(
                            text = "${freeRooms.size}/${candidateRooms.size} 空闲",
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (roomCode in freeRooms) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                                    .border(
                                        width = 0.8.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        onNavigateToMapWithRoom("${buildingName.removeSuffix("教学楼")}-$roomCode")
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = roomCode,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = "导航去自习",
                                    tint = XPeachTheme.readablePrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun CampusCommunityScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val catalog = prefs.campusCommunityCatalog

    var selectedTab by remember { mutableIntStateOf(0) } // 0: 学生社团, 1: 校园频道
    var selectedClubCategory by remember { mutableStateOf<CampusClubCategory?>(null) }
    var clubSearchQuery by remember { mutableStateOf("") }
    var feedbackText by remember { mutableStateOf<String?>(null) }
    var editingChannelItem by remember { mutableStateOf<PresetQqChannelItem?>(null) }
    var editingClubItem by remember { mutableStateOf<CampusClubItem?>(null) }
    var isRefreshingCloud by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        isRefreshingCloud = true
        runCatching { repository.refreshCampusCommunityContent() }
        isRefreshingCloud = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "社团与校园频道",
            subtitle = "河南工业大学 · 学生社团与校园频道",
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            },
            actions = {
                XPeachFilterPill(
                    label = if (isRefreshingCloud) "刷新中…" else "刷新",
                    selected = false,
                    onClick = {
                        if (!isRefreshingCloud) {
                            scope.launch {
                                isRefreshingCloud = true
                                repository.refreshCampusCommunityContent()
                                    .onSuccess { updated ->
                                        feedbackText = "已更新校园频道（${updated.channels.size} 个）与学生社团（${updated.clubs.size} 个）"
                                    }
                                    .onFailure {
                                        feedbackText = "当前展示本地缓存目录，稍后可重试刷新"
                                    }
                                isRefreshingCloud = false
                            }
                        }
                    }
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (catalog.bulletin.enabled && catalog.bulletin.title.isNotBlank()) {
                XPeachCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = XPeachTheme.secondaryAccentCardColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📢 ${catalog.bulletin.title}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (catalog.bulletin.tag.isNotBlank()) {
                            XPeachStatusDotText(
                                text = catalog.bulletin.tag,
                                dotColor = XPeachTheme.readablePrimary
                            )
                        }
                    }
                    if (catalog.bulletin.content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = catalog.bulletin.content,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            XPeachSegmentedTabs(
                items = listOf(
                    "学生社团 (${catalog.clubs.size})",
                    "校园频道 (${catalog.channels.size})"
                ),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            if (feedbackText != null) {
                XPeachCard(
                    containerColor = XPeachTheme.primaryAccentCardColor
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = feedbackText!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { feedbackText = null }) {
                            Text("知道了", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        XPeachFilterPill(
                            label = "全部 (${catalog.clubs.size})",
                            selected = selectedClubCategory == null,
                            compact = true,
                            onClick = { selectedClubCategory = null }
                        )
                        for (cat in CampusClubCategory.entries) {
                            XPeachFilterPill(
                                label = cat.label,
                                selected = selectedClubCategory == cat,
                                compact = true,
                                onClick = { selectedClubCategory = cat }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = clubSearchQuery,
                        onValueChange = { clubSearchQuery = it },
                        label = { Text("搜索社团名称、场地或群号") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val filteredClubs = remember(
                    catalog.clubs,
                    selectedClubCategory,
                    clubSearchQuery,
                    prefs.customClubGroupNumbers
                ) {
                    val q = clubSearchQuery.trim().lowercase()
                    catalog.clubs.filter { club ->
                        val catOk = selectedClubCategory == null || club.category == selectedClubCategory
                        val groupNo = prefs.customClubGroupNumbers[club.id] ?: club.qqGroupNumber
                        val queryOk = q.isEmpty() ||
                            club.name.lowercase().contains(q) ||
                            club.summary.lowercase().contains(q) ||
                            club.affiliatedUnit.lowercase().contains(q) ||
                            club.activityVenue.lowercase().contains(q) ||
                            groupNo.contains(q) ||
                            club.highlights.any { it.lowercase().contains(q) }
                        catOk && queryOk
                    }
                }

                for (club in filteredClubs) {
                    val effectiveGroupNo = prefs.customClubGroupNumbers[club.id]?.takeIf { it.isNotBlank() }
                        ?: club.qqGroupNumber

                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = club.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (club.pinned) {
                                    XPeachStatusBadge(
                                        text = "置顶",
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                XPeachStatusDotText(
                                    text = club.category.label,
                                    dotColor = XPeachTheme.readablePrimary
                                )
                            }
                            Text(
                                text = club.affiliatedUnit,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = club.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (club.highlights.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (tag in club.highlights) {
                                    Text(
                                        text = "#$tag",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = XPeachTheme.readablePrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "场地：${club.activityVenue}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "QQ 群：$effectiveGroupNo",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = XPeachTheme.readablePrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                XPeachFilterPill(
                                    label = "改群号",
                                    selected = false,
                                    compact = true,
                                    onClick = { editingClubItem = club }
                                )
                                XPeachFilterPill(
                                    label = "复制",
                                    selected = false,
                                    compact = true,
                                    onClick = {
                                        copyCommunityText(context, "社团QQ群号", effectiveGroupNo)
                                        feedbackText = "已复制「${club.name}」群号：$effectiveGroupNo"
                                    }
                                )
                                XPeachFilterPill(
                                    label = "加群",
                                    selected = true,
                                    compact = true,
                                    onClick = {
                                        feedbackText = openOrCopyClubQqGroup(
                                            context = context,
                                            clubName = club.name,
                                            groupNumber = effectiveGroupNo
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                for (channel in catalog.channels) {
                    val effectiveUrl = prefs.customChannelUrls[channel.id]?.takeIf { it.isNotBlank() }
                        ?: channel.defaultInviteUrl
                    val isCustomized = !prefs.customChannelUrls[channel.id].isNullOrBlank()

                    XPeachCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            feedbackText = openPresetQqChannel(
                                context = context,
                                channelTitle = channel.title,
                                inviteUrl = effectiveUrl
                            )
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
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = channel.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (channel.pinned) {
                                        XPeachStatusBadge(
                                            text = "置顶",
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    XPeachStatusDotText(
                                        text = if (isCustomized) "已修改" else channel.tagLabel,
                                        dotColor = XPeachTheme.readablePrimary
                                    )
                                }
                                Text(
                                    text = channel.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = effectiveUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = XPeachTheme.readablePrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                XPeachFilterPill(
                                    label = "改链接",
                                    selected = false,
                                    compact = true,
                                    onClick = { editingChannelItem = channel }
                                )
                                XPeachFilterPill(
                                    label = "打开",
                                    selected = true,
                                    compact = true,
                                    onClick = {
                                        feedbackText = openPresetQqChannel(
                                            context = context,
                                            channelTitle = channel.title,
                                            inviteUrl = effectiveUrl
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    val channelToEdit = editingChannelItem
    if (channelToEdit != null) {
        var channelUrlInput by remember(channelToEdit, prefs.customChannelUrls) {
            mutableStateOf(
                prefs.customChannelUrls[channelToEdit.id]?.takeIf { it.isNotBlank() }
                    ?: channelToEdit.defaultInviteUrl
            )
        }

        XPeachAlertDialog(
            onDismissRequest = { editingChannelItem = null },
            title = { Text("修改「${channelToEdit.title}」链接") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "填入 QQ 频道邀请链接（如 https://pd.qq.com/s/xxxx），保存后点击即可打开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = channelUrlInput,
                        onValueChange = { channelUrlInput = it },
                        label = { Text("QQ 频道链接") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.saveCustomChannelUrl(channelToEdit.id, channelUrlInput.trim())
                            editingChannelItem = null
                            feedbackText = "已更新「${channelToEdit.title}」链接。"
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingChannelItem = null }) {
                    Text("取消")
                }
            }
        )
    }

    val clubToEdit = editingClubItem
    if (clubToEdit != null) {
        var groupNoInput by remember(clubToEdit, prefs.customClubGroupNumbers) {
            mutableStateOf(
                prefs.customClubGroupNumbers[clubToEdit.id]?.takeIf { it.isNotBlank() }
                    ?: clubToEdit.qqGroupNumber
            )
        }

        XPeachAlertDialog(
            onDismissRequest = { editingClubItem = null },
            title = { Text("修改「${clubToEdit.name}」群号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "输入最新社团 QQ 群号，保存后点击加群即可跳转。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = groupNoInput,
                        onValueChange = { groupNoInput = it },
                        label = { Text("QQ 群号") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.saveCustomClubGroupNumber(clubToEdit.id, groupNoInput.trim())
                            editingClubItem = null
                            feedbackText = "已更新「${clubToEdit.name}」群号为 ${groupNoInput.trim()}。"
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingClubItem = null }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun copyCommunityText(context: Context, label: String, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

private fun openOrCopyClubQqGroup(
    context: Context,
    clubName: String,
    groupNumber: String
): String {
    val cleanNo = groupNumber.trim()
    copyCommunityText(context, "$clubName QQ群号", cleanNo)
    val qqCardUri = Uri.parse(
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$cleanNo&card_type=group&source=qrcode"
    )
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, qqCardUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "已复制群号 $cleanNo，正在打开手机 QQ..."
    }.getOrElse {
        "已复制群号 $cleanNo 到剪贴板"
    }
}

private fun openPresetQqChannel(
    context: Context,
    channelTitle: String,
    inviteUrl: String
): String {
    val cleanUrl = inviteUrl.trim().ifBlank { "https://pd.qq.com" }
    copyCommunityText(context, channelTitle, cleanUrl)
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "正在打开「$channelTitle」..."
    }.getOrElse {
        "已复制「$channelTitle」链接：$cleanUrl"
    }
}
