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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VolunteerActivism
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachEmptyState
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackBanner
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackTone
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSearchField
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CampusClubCategory
import cn.edu.haut.campus.core.model.CampusClubItem
import cn.edu.haut.campus.core.model.CampusCompetitionCategory
import cn.edu.haut.campus.core.model.CampusCompetitionItem
import cn.edu.haut.campus.core.model.CampusMajorTrack
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.PresetQqChannelItem
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZonedDateTime

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
        mutableIntStateOf(CampusStudyRoomCatalog.resolveActiveSlotIndex(ZonedDateTime.now(HAUT_ZONE_ID).hour))
    }
    val buildingKeys = remember { CampusStudyRoomCatalog.LIANHUAJIE_STUDY_ROOMS_MAP.keys.toList() }
    var selectedBuildingFilter by remember { mutableStateOf<String?>(null) }

    val activeSlot = CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS[selectedSlotIndex]
    val busyRoomsInSlot = remember(rules, currentTerm?.id, currentTeachingWeek, selectedDayOfWeek, activeSlot) {
        CampusStudyRoomCatalog.findBusyRawRooms(
            rules = rules,
            termId = currentTerm?.id,
            teachingWeek = currentTeachingWeek,
            weekday = selectedDayOfWeek,
            slot = activeSlot
        )
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
                    CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS.forEachIndexed { idx, slot ->
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
                val candidateRooms = CampusStudyRoomCatalog.LIANHUAJIE_STUDY_ROOMS_MAP[buildingName].orEmpty()
                val freeRooms = candidateRooms.filter { code ->
                    CampusStudyRoomCatalog.isRoomFree(code, busyRoomsInSlot)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampusCommunityScreen(
    repository: CampusRepository,
    initialTab: Int = 0,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val studentProfile by repository.studentProfileFlow.collectAsState(initial = null)
    val catalog = prefs.campusCommunityCatalog
    val inferredMajorTrack = remember(studentProfile?.major) {
        CampusMajorTrack.inferFromMajorText(studentProfile?.major)
    }

    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) } // 0: 合作社团, 1: 竞赛指南, 2: 校园频道
    var selectedClubCategory by remember { mutableStateOf<CampusClubCategory?>(null) }
    var clubSearchQuery by remember { mutableStateOf("") }
    var selectedMajorTrack by remember(inferredMajorTrack) { mutableStateOf<CampusMajorTrack?>(inferredMajorTrack) }
    var selectedCompCategory by remember { mutableStateOf<CampusCompetitionCategory?>(null) }
    var onlyBeginnerFriendly by remember { mutableStateOf(false) }
    var compSearchQuery by remember { mutableStateOf("") }
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
            title = "社团、竞赛与频道",
            subtitle = "河南工业大学 · 官方合作社团、学科竞赛指南与交流群",
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            },
            actions = {
                XPeachFilterPill(
                    label = if (isRefreshingCloud) "刷新中…" else "同步云端",
                    selected = false,
                    onClick = {
                        if (!isRefreshingCloud) {
                            scope.launch {
                                isRefreshingCloud = true
                                repository.refreshCampusCommunityContent()
                                    .onSuccess { updated ->
                                        feedbackText = "已同步云端最新目录：合作社团 ${updated.clubs.size} 个 · 竞赛指南 ${updated.competitions.size} 项 · 校园频道 ${updated.channels.size} 个"
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
                CommunityBulletinHeroCard(
                    title = catalog.bulletin.title,
                    content = catalog.bulletin.content,
                    tag = catalog.bulletin.tag,
                    version = catalog.version,
                    clubsCount = catalog.clubs.size,
                    competitionsCount = catalog.competitions.size
                )
            }

            XPeachSegmentedTabs(
                items = listOf(
                    "合作社团 (${catalog.clubs.size})",
                    "竞赛指南 (${catalog.competitions.size})",
                    "校园频道 (${catalog.channels.size})"
                ),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            XPeachFeedbackBanner(
                message = feedbackText,
                tone = XPeachFeedbackTone.INFO,
                onDismiss = { feedbackText = null }
            )

            if (selectedTab == 0) {
                val categoryCounts = remember(catalog.clubs) {
                    catalog.clubs.groupingBy { it.category }.eachCount()
                }
                val activeCategories = remember(categoryCounts) {
                    val nonEmpty = CampusClubCategory.entries.filter { (categoryCounts[it] ?: 0) > 0 }
                    nonEmpty.ifEmpty { CampusClubCategory.entries }
                }

                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    XPeachSearchField(
                        value = clubSearchQuery,
                        onValueChange = { clubSearchQuery = it },
                        placeholder = "搜索社团名称、活动方向、场地或 QQ 群号"
                    )

                    Spacer(modifier = Modifier.height(10.dp))

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
                        for (cat in activeCategories) {
                            val count = categoryCounts[cat] ?: 0
                            XPeachFilterPill(
                                label = if (count > 0) "${cat.label} ($count)" else cat.label,
                                selected = selectedClubCategory == cat,
                                compact = true,
                                onClick = { selectedClubCategory = cat }
                            )
                        }
                    }
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

                if (filteredClubs.isEmpty()) {
                    XPeachEmptyState(
                        title = "未找到匹配的社团",
                        subtitle = "请尝试更换搜索关键词或切换全部分类查看",
                        icon = Icons.Rounded.Groups,
                        actionLabel = "重置筛选条件",
                        onAction = {
                            selectedClubCategory = null
                            clubSearchQuery = ""
                        }
                    )
                }

                for (club in filteredClubs) {
                    val effectiveGroupNo = prefs.customClubGroupNumbers[club.id]?.takeIf { it.isNotBlank() }
                        ?: club.qqGroupNumber
                    val isGroupCustomized = !prefs.customClubGroupNumbers[club.id].isNullOrBlank()

                    CampusClubCard(
                        club = club,
                        effectiveGroupNo = effectiveGroupNo,
                        isGroupCustomized = isGroupCustomized,
                        onEditGroupNo = { editingClubItem = club },
                        onCopyGroupNo = {
                            copyCommunityText(context, "社团QQ群号", effectiveGroupNo)
                            feedbackText = "已复制「${club.name}」群号：$effectiveGroupNo"
                        },
                        onJoinGroup = {
                            feedbackText = openOrCopyClubQqGroup(
                                context = context,
                                clubName = club.name,
                                groupNumber = effectiveGroupNo
                            )
                        }
                    )
                }
            } else if (selectedTab == 1) {
                // 竞赛指南与专业/兴趣发展匹配器
                val majorName = studentProfile?.major?.takeIf { it.isNotBlank() }
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    if (majorName != null && inferredMajorTrack != null) {
                        XPeachWell(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
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
                                        text = "🎓 已识别您的专业：$majorName",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "推荐赛道：${inferredMajorTrack.label}（优先置顶本专业核心白名单赛事）",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                XPeachFilterPill(
                                    label = if (selectedMajorTrack == inferredMajorTrack) "已匹配本专业" else "匹配本专业",
                                    selected = selectedMajorTrack == inferredMajorTrack,
                                    compact = true,
                                    onClick = {
                                        selectedMajorTrack = if (selectedMajorTrack == inferredMajorTrack) null else inferredMajorTrack
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    XPeachSearchField(
                        value = compSearchQuery,
                        onValueChange = { compSearchQuery = it },
                        placeholder = "搜索竞赛名称、适合专业、技能路线或合作社团…"
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "按专业赛道匹配适合你的比赛：",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        XPeachFilterPill(
                            label = "全部专业 (${catalog.competitions.size})",
                            selected = selectedMajorTrack == null,
                            compact = true,
                            onClick = { selectedMajorTrack = null }
                        )
                        for (track in CampusMajorTrack.entries.filter { it != CampusMajorTrack.ALL_MAJORS }) {
                            XPeachFilterPill(
                                label = track.shortLabel,
                                selected = selectedMajorTrack == track,
                                compact = true,
                                onClick = {
                                    selectedMajorTrack = if (selectedMajorTrack == track) null else track
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "按兴趣发展方向与入门难度筛选：",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        XPeachFilterPill(
                            label = "全部方向",
                            selected = selectedCompCategory == null && !onlyBeginnerFriendly,
                            compact = true,
                            onClick = {
                                selectedCompCategory = null
                                onlyBeginnerFriendly = false
                            }
                        )
                        XPeachFilterPill(
                            label = "✨ 大一/零基础友好",
                            selected = onlyBeginnerFriendly,
                            compact = true,
                            onClick = { onlyBeginnerFriendly = !onlyBeginnerFriendly }
                        )
                        for (cat in CampusCompetitionCategory.entries) {
                            XPeachFilterPill(
                                label = cat.shortLabel,
                                selected = selectedCompCategory == cat,
                                compact = true,
                                onClick = {
                                    selectedCompCategory = if (selectedCompCategory == cat) null else cat
                                }
                            )
                        }
                    }
                }

                val filteredCompetitions = remember(
                    catalog.competitions,
                    selectedMajorTrack,
                    selectedCompCategory,
                    onlyBeginnerFriendly,
                    compSearchQuery
                ) {
                    val q = compSearchQuery.trim().lowercase()
                    catalog.competitions
                        .filter { comp ->
                            val majorOk = comp.matchesMajorTrack(selectedMajorTrack)
                            val catOk = selectedCompCategory == null || comp.category == selectedCompCategory
                            val begOk = !onlyBeginnerFriendly || comp.beginnerFriendly
                            val queryOk = q.isEmpty() ||
                                comp.title.lowercase().contains(q) ||
                                comp.shortTitle.lowercase().contains(q) ||
                                comp.tierBadge.lowercase().contains(q) ||
                                comp.organizer.lowercase().contains(q) ||
                                comp.hautHostCollege.lowercase().contains(q) ||
                                comp.summary.lowercase().contains(q) ||
                                comp.skillRoadmap.lowercase().contains(q) ||
                                comp.partnerClubName.lowercase().contains(q) ||
                                comp.interestTags.any { it.lowercase().contains(q) } ||
                                comp.recommendedMajorTracks.any {
                                    it.label.lowercase().contains(q) || it.shortLabel.lowercase().contains(q)
                                }
                            majorOk && catOk && begOk && queryOk
                        }
                        .sortedWith(
                            compareByDescending<CampusCompetitionItem> {
                                it.isCoreForMajorTrack(selectedMajorTrack)
                            }.thenByDescending { it.pinned }.thenBy { it.sortOrder }
                        )
                }

                if (filteredCompetitions.isEmpty()) {
                    XPeachEmptyState(
                        title = "未找到匹配的学科竞赛",
                        subtitle = "可尝试切换至「全部专业」或清除搜索词查看完整白名单赛事名录",
                        icon = Icons.Rounded.EmojiEvents,
                        actionLabel = "重置全部筛选",
                        onAction = {
                            selectedMajorTrack = null
                            selectedCompCategory = null
                            onlyBeginnerFriendly = false
                            compSearchQuery = ""
                        }
                    )
                }

                for (comp in filteredCompetitions) {
                    val effectivePartnerQq = if (comp.partnerClubId.isNotBlank()) {
                        prefs.customClubGroupNumbers[comp.partnerClubId]?.takeIf { it.isNotBlank() }
                            ?: comp.partnerClubQq
                    } else {
                        comp.partnerClubQq
                    }

                    CampusCompetitionCard(
                        comp = comp,
                        isCoreForSelectedMajor = comp.isCoreForMajorTrack(selectedMajorTrack ?: inferredMajorTrack),
                        effectivePartnerQq = effectivePartnerQq,
                        onCopyWebsite = {
                            copyCommunityText(context, "${comp.shortTitle}官网", comp.officialWebsiteUrl)
                            feedbackText = "已复制「${comp.shortTitle}」官网链接：${comp.officialWebsiteUrl}"
                        },
                        onOpenWebsite = {
                            feedbackText = openCompetitionWebsite(
                                context = context,
                                title = comp.shortTitle,
                                url = comp.officialWebsiteUrl
                            )
                        },
                        onJoinPartnerClub = {
                            if (effectivePartnerQq.isNotBlank()) {
                                feedbackText = openOrCopyClubQqGroup(
                                    context = context,
                                    clubName = comp.partnerClubName.ifBlank { comp.shortTitle },
                                    groupNumber = effectivePartnerQq
                                )
                            }
                        }
                    )
                }
            } else {
                if (catalog.channels.isEmpty()) {
                    XPeachEmptyState(
                        title = "校园频道暂时置空",
                        subtitle = "当前仅开放官方合作学生社团与学科竞赛指南，校园频道后续将通过云端实时下发开放。",
                        icon = Icons.Rounded.Forum,
                        actionLabel = "浏览学科竞赛指南 (${catalog.competitions.size})",
                        onAction = { selectedTab = 1 }
                    )
                }
                for (channel in catalog.channels) {
                    val effectiveUrl = prefs.customChannelUrls[channel.id]?.takeIf { it.isNotBlank() }
                        ?: channel.defaultInviteUrl
                    val isCustomized = !prefs.customChannelUrls[channel.id].isNullOrBlank()

                    CampusChannelCard(
                        channel = channel,
                        effectiveUrl = effectiveUrl,
                        isCustomized = isCustomized,
                        onEditUrl = { editingChannelItem = channel },
                        onOpenChannel = {
                            feedbackText = openPresetQqChannel(
                                context = context,
                                channelTitle = channel.title,
                                inviteUrl = effectiveUrl
                            )
                        }
                    )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommunityBulletinHeroCard(
    title: String,
    content: String,
    tag: String,
    version: Long,
    clubsCount: Int,
    competitionsCount: Int
) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.secondaryAccentCardColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Campaign,
                    contentDescription = null,
                    tint = XPeachTheme.readablePrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (tag.isNotBlank()) {
                        XPeachStatusDotText(
                            text = tag,
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }
                    XPeachStatusBadge(
                        text = "云控 v$version · ${clubsCount}社团 · ${competitionsCount}竞赛",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = XPeachTheme.readablePrimary
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (content.isNotBlank()) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private data class ClubCategoryVisualMeta(
    val icon: ImageVector,
    val accentColor: Color
)

@Composable
private fun resolveClubCategoryVisual(category: CampusClubCategory): ClubCategoryVisualMeta {
    val isDark = XPeachTheme.isDark
    return when (category) {
        CampusClubCategory.TECH_INNOVATION -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Memory,
            accentColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
        )
        CampusClubCategory.ART_PERFORMANCE -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Palette,
            accentColor = if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777)
        )
        CampusClubCategory.ACADEMIC_CULTURE -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.AutoAwesome,
            accentColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
        )
        CampusClubCategory.SPORTS_FITNESS -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.EmojiEvents,
            accentColor = if (isDark) Color(0xFF34D399) else Color(0xFF059669)
        )
        CampusClubCategory.VOLUNTEER_ORG -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.VolunteerActivism,
            accentColor = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampusClubCard(
    club: CampusClubItem,
    effectiveGroupNo: String,
    isGroupCustomized: Boolean,
    onEditGroupNo: () -> Unit,
    onCopyGroupNo: () -> Unit,
    onJoinGroup: () -> Unit
) {
    val visual = resolveClubCategoryVisual(club.category)

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        // 1. 顶部身份区：左侧分类徽章图标 + 右侧独占一行的完整社团名称 + 下方置顶/类别/挂靠学院标签流
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(visual.accentColor.copy(alpha = if (XPeachTheme.isDark) 0.20f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = club.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (club.pinned) {
                        XPeachStatusBadge(
                            text = "官方合作 · 置顶",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    }
                    XPeachStatusBadge(
                        text = club.category.label,
                        containerColor = visual.accentColor.copy(alpha = if (XPeachTheme.isDark) 0.22f else 0.12f),
                        contentColor = visual.accentColor
                    )
                    if (club.affiliatedUnit.isNotBlank()) {
                        XPeachStatusBadge(
                            text = club.affiliatedUnit,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 2. 社团简介（独占完整宽度，不与任何徽章或标题挤压）
        if (club.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = club.summary,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 3. 特色亮点标签流
        if (club.highlights.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (tag in club.highlights) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (XPeachTheme.isDark) 0.26f else 0.45f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. 场地与官方 QQ 群信息凹槽
        XPeachWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "常规活动场地：${club.activityVenue}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "招新/交流 QQ 群：$effectiveGroupNo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isGroupCustomized) {
                        XPeachStatusDotText(
                            text = "已自定义",
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5. 独立底部操作栏（不挤压左侧群号与场地文字）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            XPeachFilterPill(
                label = "自定义群号",
                selected = false,
                compact = true,
                onClick = onEditGroupNo
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                XPeachFilterPill(
                    label = "复制群号",
                    selected = false,
                    compact = true,
                    onClick = onCopyGroupNo
                )
                XPeachFilterPill(
                    label = "一键加群",
                    selected = true,
                    compact = false,
                    onClick = onJoinGroup
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampusChannelCard(
    channel: PresetQqChannelItem,
    effectiveUrl: String,
    isCustomized: Boolean,
    onEditUrl: () -> Unit,
    onOpenChannel: () -> Unit
) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenChannel
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Forum,
                    contentDescription = null,
                    tint = XPeachTheme.readablePrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (channel.pinned) {
                        XPeachStatusBadge(
                            text = "置顶频道",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    }
                    XPeachStatusBadge(
                        text = if (isCustomized) "已自定义" else channel.tagLabel,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        if (channel.subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = channel.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        XPeachWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = effectiveUrl,
                style = MaterialTheme.typography.labelSmall,
                color = XPeachTheme.readablePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XPeachFilterPill(
                    label = "修改链接",
                    selected = false,
                    compact = true,
                    onClick = onEditUrl
                )
                XPeachFilterPill(
                    label = "进入频道",
                    selected = true,
                    compact = false,
                    onClick = onOpenChannel
                )
            }
        }
    }
}

@Composable
private fun resolveCompetitionCategoryVisual(category: CampusCompetitionCategory): ClubCategoryVisualMeta {
    val isDark = XPeachTheme.isDark
    return when (category) {
        CampusCompetitionCategory.ALGO_PROGRAMMING -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Code,
            accentColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
        )
        CampusCompetitionCategory.ELECTRONICS_ROBO -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Memory,
            accentColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
        )
        CampusCompetitionCategory.MATH_MODELING -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Calculate,
            accentColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
        )
        CampusCompetitionCategory.INNOVATION_ENTREPRENEURSHIP -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.EmojiEvents,
            accentColor = if (isDark) Color(0xFFFB923C) else Color(0xFFEA580C)
        )
        CampusCompetitionCategory.CYBER_SECURITY_AI -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Security,
            accentColor = if (isDark) Color(0xFF34D399) else Color(0xFF059669)
        )
        CampusCompetitionCategory.DIGITAL_MEDIA_ART -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Palette,
            accentColor = if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampusCompetitionCard(
    comp: CampusCompetitionItem,
    isCoreForSelectedMajor: Boolean,
    effectivePartnerQq: String,
    onCopyWebsite: () -> Unit,
    onOpenWebsite: () -> Unit,
    onJoinPartnerClub: () -> Unit
) {
    val visual = resolveCompetitionCategoryVisual(comp.category)

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        // 1. 顶部身份区：44dp 赛事方向图标 + 独占首行完整赛事名称 + 自动换行徽标流
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(visual.accentColor.copy(alpha = if (XPeachTheme.isDark) 0.20f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = comp.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isCoreForSelectedMajor) {
                        XPeachStatusBadge(
                            text = "★ 本专业核心推荐",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    } else if (comp.pinned) {
                        XPeachStatusBadge(
                            text = "重点推荐",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    }
                    XPeachStatusBadge(
                        text = comp.tierBadge,
                        containerColor = visual.accentColor.copy(alpha = if (XPeachTheme.isDark) 0.22f else 0.12f),
                        contentColor = visual.accentColor
                    )
                    XPeachStatusBadge(
                        text = comp.difficultyLabel,
                        containerColor = if (comp.beginnerFriendly) {
                            XPeachTheme.successContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                        },
                        contentColor = if (comp.beginnerFriendly) {
                            XPeachTheme.onSuccessContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        // 2. 赛事核心简介（独占整宽）
        if (comp.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = comp.summary,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 3. 能力与兴趣标签流
        if (comp.interestTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (tag in comp.interestTags) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (XPeachTheme.isDark) 0.26f else 0.45f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. 专业适配、备赛路线与合作社团组队凹槽
        XPeachWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.School,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Text(
                        text = "适合专业：${comp.recommendedMajorTracks.joinToString(" · ") { it.label }}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (comp.skillRoadmap.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = visual.accentColor,
                            modifier = Modifier.size(15.dp).padding(top = 2.dp)
                        )
                        Text(
                            text = "备赛成长路线：${comp.skillRoadmap}",
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text(
                    text = "🗓️ 赛程与赛制：${comp.registrationWindow} · ${comp.teamMode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "🏛️ 校内组织：${comp.hautHostCollege}（${comp.organizer}）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (comp.partnerClubName.isNotBlank() && effectivePartnerQq.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Groups,
                            contentDescription = null,
                            tint = XPeachTheme.readablePrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "组队合作社团：${comp.partnerClubName}（QQ 群：$effectivePartnerQq）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = XPeachTheme.readablePrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "官方网站：${comp.officialWebsiteUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = XPeachTheme.readablePrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5. 独立底部操作栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            XPeachFilterPill(
                label = "复制官网",
                selected = false,
                compact = true,
                onClick = onCopyWebsite
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (comp.partnerClubName.isNotBlank() && effectivePartnerQq.isNotBlank()) {
                    XPeachFilterPill(
                        label = "加社团群组队",
                        selected = false,
                        compact = true,
                        onClick = onJoinPartnerClub
                    )
                }
                XPeachFilterPill(
                    label = "打开竞赛官网",
                    selected = true,
                    compact = false,
                    onClick = onOpenWebsite
                )
            }
        }
    }
}

private fun openCompetitionWebsite(
    context: Context,
    title: String,
    url: String
): String {
    val cleanUrl = url.trim()
    copyCommunityText(context, "$title 官网", cleanUrl)
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "已复制官网链接，正在为您打开「$title」官方网站…"
    }.getOrElse {
        "已复制「$title」官网链接到剪贴板：$cleanUrl"
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
