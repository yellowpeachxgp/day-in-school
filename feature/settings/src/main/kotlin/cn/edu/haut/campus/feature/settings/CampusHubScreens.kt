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
import cn.edu.haut.campus.core.domain.CampusHospitalPharmacyCatalog
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.HospitalMedicineCategory
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CampusClubCategory
import cn.edu.haut.campus.core.model.CampusClubItem
import cn.edu.haut.campus.core.model.CampusCompetitionCategory
import cn.edu.haut.campus.core.model.CampusCompetitionItem
import cn.edu.haut.campus.core.model.CampusMajorTrack
import cn.edu.haut.campus.core.model.CompetitionCareerGoal
import cn.edu.haut.campus.core.model.CompetitionCompassEngine
import cn.edu.haut.campus.core.model.CompetitionGradeStage
import cn.edu.haut.campus.core.model.CompetitionSeasonWindow
import cn.edu.haut.campus.core.model.CompetitionSkillPreference
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
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())

    val today = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
    val currentTeachingWeek = remember(today, currentTerm, calendarOverrides) {
        currentTerm?.let { TeachingWeekCalculator.resolveDate(today, it, calendarOverrides).teachingWeek } ?: 1
    }

    var selectedCampusKey by remember(prefs.selectedCampusId, rules.size) {
        mutableStateOf(
            if (prefs.selectedCampusId.contains("songshan", ignoreCase = true) ||
                rules.any { it.rawRoom.contains("嵩山") || it.rawRoom.contains("齐礼阎") }
            ) "songshanlu" else "lianhuajie"
        )
    }
    val campusDisplayName = if (selectedCampusKey == "songshanlu") "嵩山路校区" else "莲花街校区"

    var selectedDayOfWeek by remember { mutableStateOf(today.dayOfWeek) }
    var selectedSlotIndex by remember {
        mutableIntStateOf(CampusStudyRoomCatalog.resolveActiveSlotIndex(ZonedDateTime.now(HAUT_ZONE_ID).hour))
    }
    val campusRoomsMap = remember(selectedCampusKey) {
        CampusStudyRoomCatalog.studyRoomsMapForCampus(selectedCampusKey)
    }
    val buildingKeys = remember(campusRoomsMap) { campusRoomsMap.keys.toList() }
    var selectedBuildingFilter by remember(selectedCampusKey) { mutableStateOf<String?>(null) }

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
            title = "自习教室与无课参考",
            subtitle = "$campusDisplayName · 第 $currentTeachingWeek 周 · 个人课表无冲突参考",
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
            XPeachWell(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "数据来源说明：当前展示为校区公共教学楼自习教室名录 + 个人课表无冲突参考（已自动排除您本人在所选节次有课的教室），非全校教务实时排课占用数据，现场请以教室实际使用情况为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "选择校区、星期与节次",
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
                    XPeachFilterPill(
                        label = "莲花街校区",
                        selected = selectedCampusKey == "lianhuajie",
                        onClick = { selectedCampusKey = "lianhuajie" }
                    )
                    XPeachFilterPill(
                        label = "嵩山路校区",
                        selected = selectedCampusKey == "songshanlu",
                        onClick = { selectedCampusKey = "songshanlu" }
                    )
                }
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
                val candidateRooms = campusRoomsMap[buildingName].orEmpty()
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
                            text = "个人无冲突 ${freeRooms.size}/${candidateRooms.size} 间",
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
                                        val navTarget = if (roomCode.contains("嵩山")) {
                                            roomCode
                                        } else {
                                            "${buildingName.removeSuffix("教学楼")}-$roomCode"
                                        }
                                        onNavigateToMapWithRoom(navTarget)
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
    onOpenMoments: () -> Unit = {},
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
    var selectedSeasonWindow by remember { mutableStateOf<CompetitionSeasonWindow?>(null) }
    var onlyBeginnerFriendly by remember { mutableStateOf(false) }
    var compSearchQuery by remember { mutableStateOf("") }
    var isCompassExpanded by remember { mutableStateOf(false) }
    var compassGradeStage by remember { mutableStateOf(CompetitionGradeStage.FRESHMAN) }
    var compassCareerGoal by remember { mutableStateOf(CompetitionCareerGoal.POSTGRAD_BONUS) }
    var compassSkillPref by remember(inferredMajorTrack) {
        mutableStateOf(
            when (inferredMajorTrack) {
                CampusMajorTrack.CS_AI_SOFTWARE -> CompetitionSkillPreference.CODE_ALGO_AI
                CampusMajorTrack.EE_AUTOMATION_IOT,
                CampusMajorTrack.MECH_VEHICLE_ROBO -> CompetitionSkillPreference.CIRCUIT_MCU_ROBO
                CampusMajorTrack.MATH_PHYSICS_CIVIL -> CompetitionSkillPreference.MATH_DATA
                CampusMajorTrack.ECON_MGMT_FINANCE,
                CampusMajorTrack.FOOD_BIO_CHEM_MAT -> CompetitionSkillPreference.PRODUCT_BP_PITCH
                CampusMajorTrack.DESIGN_MEDIA_FOREIGN -> CompetitionSkillPreference.DESIGN_VIDEO_ACG
                else -> CompetitionSkillPreference.CODE_ALGO_AI
            }
        )
    }
    var selectedCompetitionDetail by remember { mutableStateOf<CampusCompetitionItem?>(null) }
    var showHospitalPharmacyDialog by remember { mutableStateOf(false) }
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
            title = "社团与竞赛",
            subtitle = "河南工业大学 · 社团、竞赛与交流群",
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
                                        feedbackText = "已更新目录：社团 ${updated.clubs.size} 个、竞赛 ${updated.competitions.size} 项"
                                    }
                                    .onFailure {
                                        feedbackText = "刷新失败，当前显示本地数据"
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

            XPeachCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenMoments)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "失物招领与二手教材",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "寻物招领 (${prefs.campusMomentsSnapshot.posts.size}) · 二手课本 (${prefs.campusMomentsSnapshot.bookListings.size}) · 随身防丢码",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachStatusBadge(
                        text = "进入广场 ›",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = XPeachTheme.readablePrimary
                    )
                }
            }

            XPeachCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showHospitalPharmacyDialog = true }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "校医院药品价格公示与医保参考",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            XPeachStatusDotText(text = "${CampusHospitalPharmacyCatalog.ITEMS.size} 项")
                        }
                        Text(
                            text = "西药/中成药公示单价检索 · 大学生医保报销规则说明 · 致谢 @${CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachStatusBadge(
                        text = "查药价 ›",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = XPeachTheme.readablePrimary
                    )
                }
            }

            XPeachSegmentedTabs(
                items = listOf(
                    "合作社团 (${catalog.clubs.size})",
                    "竞赛指南 (${catalog.competitions.size})",
                    "交流与反馈"
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
                        placeholder = "搜索社团、场地或群号"
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
                // 1. 30秒专业与兴趣发展自测罗盘向导
                val compassResult = remember(
                    catalog.competitions,
                    catalog.clubs,
                    selectedMajorTrack,
                    inferredMajorTrack,
                    compassGradeStage,
                    compassCareerGoal,
                    compassSkillPref
                ) {
                    CompetitionCompassEngine.evaluate(
                        competitions = catalog.competitions,
                        clubs = catalog.clubs,
                        majorTrack = selectedMajorTrack ?: inferredMajorTrack,
                        gradeStage = compassGradeStage,
                        careerGoal = compassCareerGoal,
                        skillPref = compassSkillPref
                    )
                }

                MajorInterestCompassCard(
                    isExpanded = isCompassExpanded,
                    onToggleExpand = { isCompassExpanded = !isCompassExpanded },
                    gradeStage = compassGradeStage,
                    onSelectGradeStage = { compassGradeStage = it },
                    careerGoal = compassCareerGoal,
                    onSelectCareerGoal = { compassCareerGoal = it },
                    skillPref = compassSkillPref,
                    onSelectSkillPref = { compassSkillPref = it },
                    compassResult = compassResult,
                    onInspectCompetition = { selectedCompetitionDetail = it },
                    onJoinClub = { club ->
                        val effectiveNo = prefs.customClubGroupNumbers[club.id]?.takeIf { it.isNotBlank() }
                            ?: club.qqGroupNumber
                        feedbackText = openOrCopyClubQqGroup(context, club.name, effectiveNo)
                    },
                    onApplyToFilter = {
                        selectedCompCategory = compassSkillPref.matchedCategories.firstOrNull()
                        onlyBeginnerFriendly = compassGradeStage == CompetitionGradeStage.FRESHMAN
                        feedbackText = "已按所选条件筛选竞赛"
                    }
                )

                // 2. 竞赛指南与筛选器
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
                                        text = "当前专业：$majorName",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "已优先显示 ${inferredMajorTrack.label} 相关赛事",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                XPeachFilterPill(
                                    label = if (selectedMajorTrack == inferredMajorTrack) "当前专业" else "只看本专业",
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
                        placeholder = "搜索竞赛、专业或社团"
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "专业方向",
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
                        text = "竞赛类别",
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
                            label = "全部类别",
                            selected = selectedCompCategory == null && !onlyBeginnerFriendly,
                            compact = true,
                            onClick = {
                                selectedCompCategory = null
                                onlyBeginnerFriendly = false
                            }
                        )
                        XPeachFilterPill(
                            label = "入门友好",
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

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "报名时段",
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
                            label = "全年时段",
                            selected = selectedSeasonWindow == null,
                            compact = true,
                            onClick = { selectedSeasonWindow = null }
                        )
                        for (season in CompetitionSeasonWindow.entries) {
                            XPeachFilterPill(
                                label = season.shortLabel,
                                selected = selectedSeasonWindow == season,
                                compact = true,
                                onClick = {
                                    selectedSeasonWindow = if (selectedSeasonWindow == season) null else season
                                }
                            )
                        }
                    }
                    if (selectedSeasonWindow != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = selectedSeasonWindow!!.periodSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary
                        )
                    }
                }

                val filteredCompetitions = remember(
                    catalog.competitions,
                    selectedMajorTrack,
                    selectedCompCategory,
                    selectedSeasonWindow,
                    onlyBeginnerFriendly,
                    compSearchQuery
                ) {
                    val q = compSearchQuery.trim().lowercase()
                    catalog.competitions
                        .filter { comp ->
                            val majorOk = comp.matchesMajorTrack(selectedMajorTrack)
                            val catOk = selectedCompCategory == null || comp.category == selectedCompCategory
                            val seasonOk = comp.matchesSeason(selectedSeasonWindow)
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
                            majorOk && catOk && seasonOk && begOk && queryOk
                        }
                        .sortedWith(
                            compareByDescending<CampusCompetitionItem> {
                                it.isCoreForMajorTrack(selectedMajorTrack)
                            }.thenByDescending { it.pinned }.thenBy { it.sortOrder }
                        )
                }

                if (filteredCompetitions.isEmpty()) {
                    XPeachEmptyState(
                        title = "未找到匹配的竞赛",
                        subtitle = "请尝试调整筛选条件或搜索关键词",
                        icon = Icons.Rounded.EmojiEvents,
                        actionLabel = "重置筛选",
                        onAction = {
                            selectedMajorTrack = null
                            selectedCompCategory = null
                            selectedSeasonWindow = null
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
                        onOpenDetail = { selectedCompetitionDetail = comp },
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
                // 1. App 用户交流群、QQ 频道与问题反馈专卡（与校内社团招新群严格区分）
                val appUserQqGroup = prefs.customClubGroupNumbers["__app_user_feedback_qq_group__"]?.takeIf { it.isNotBlank() }
                    ?: catalog.officialChannels.userFeedbackQqGroup.takeIf { it.isNotBlank() }
                val appUserChannelUrl = prefs.customChannelUrls["__app_user_feedback_channel__"]?.takeIf { it.isNotBlank() }
                    ?: catalog.officialChannels.userFeedbackChannelUrl.takeIf {
                        it.isNotBlank() && !it.contains("github.com", ignoreCase = true)
                    }
                var showEditAppFeedbackGroupDialog by remember { mutableStateOf(false) }

                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "Day in School 用户交流与问题反馈",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "用于软件使用交流、课表/地图勘误与新功能建议（独立于校内社团招新群）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        XPeachStatusBadge(
                            text = "官方反馈",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    XPeachWell(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (appUserQqGroup != null) {
                                    "用户交流 QQ 群：$appUserQqGroup"
                                } else {
                                    "用户交流 QQ 群：待云端同步或点击下方绑定群号"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (appUserChannelUrl != null) {
                                    "QQ 频道链接：$appUserChannelUrl"
                                } else {
                                    "GitHub Issue 反馈：https://github.com/yellowpeachxgp/day-in-school/issues"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (appUserQqGroup != null) {
                            XPeachFilterPill(
                                label = "复制交流群号",
                                selected = false,
                                compact = true,
                                onClick = {
                                    copyCommunityText(context, "用户交流QQ群", appUserQqGroup)
                                    feedbackText = "已复制用户交流群号：$appUserQqGroup"
                                }
                            )
                            XPeachFilterPill(
                                label = "打开QQ加群",
                                selected = true,
                                compact = true,
                                onClick = {
                                    feedbackText = openOrCopyClubQqGroup(context, "Day in School 用户交流群", appUserQqGroup)
                                }
                            )
                        }
                        if (appUserChannelUrl != null) {
                            XPeachFilterPill(
                                label = "打开QQ频道",
                                selected = true,
                                compact = true,
                                onClick = {
                                    feedbackText = openPresetQqChannel(
                                        context = context,
                                        channelTitle = "Day in School 官方QQ频道",
                                        inviteUrl = appUserChannelUrl
                                    )
                                }
                            )
                            XPeachFilterPill(
                                label = "复制频道链接",
                                selected = false,
                                compact = true,
                                onClick = {
                                    copyCommunityText(context, "用户交流QQ频道", appUserChannelUrl)
                                    feedbackText = "已复制 QQ 频道链接：$appUserChannelUrl"
                                }
                            )
                        }
                        XPeachFilterPill(
                            label = if (appUserQqGroup != null || appUserChannelUrl != null) "修改群号/频道" else "配置交流群/频道",
                            selected = false,
                            compact = true,
                            onClick = { showEditAppFeedbackGroupDialog = true }
                        )
                        XPeachFilterPill(
                            label = "提交 GitHub Issue",
                            selected = appUserQqGroup == null,
                            compact = true,
                            onClick = {
                                feedbackText = openCompetitionWebsite(
                                    context = context,
                                    title = "GitHub Issues 反馈",
                                    url = "https://github.com/yellowpeachxgp/day-in-school/issues"
                                )
                            }
                        )
                    }
                }

                if (showEditAppFeedbackGroupDialog) {
                    var qqInput by remember(appUserQqGroup) { mutableStateOf(appUserQqGroup.orEmpty()) }
                    var chInput by remember(appUserChannelUrl) { mutableStateOf(appUserChannelUrl.orEmpty()) }
                    XPeachAlertDialog(
                        onDismissRequest = { showEditAppFeedbackGroupDialog = false },
                        title = { Text("配置用户交流 QQ 群与频道") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "可填入已建立的 Day in School 用户交流 QQ 群号或 QQ 频道邀请链接，保存后支持一键复制与呼起客户端。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedTextField(
                                    value = qqInput,
                                    onValueChange = { qqInput = it },
                                    label = { Text("用户交流 QQ 群号（选填）") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = chInput,
                                    onValueChange = { chInput = it },
                                    label = { Text("QQ 频道邀请链接（选填）") },
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
                                        repository.saveCustomClubGroupNumber("__app_user_feedback_qq_group__", qqInput.trim())
                                        repository.saveCustomChannelUrl("__app_user_feedback_channel__", chInput.trim())
                                        showEditAppFeedbackGroupDialog = false
                                        feedbackText = "已保存用户交流群与频道配置"
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("保存")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEditAppFeedbackGroupDialog = false }) {
                                Text("取消")
                            }
                        }
                    )
                }

                // 2. 微信公众号与学校权威公共号导航（诚实标注 App 自有号状态，同时提供校内公开认证号检索）
                val cloudWechatName = catalog.officialChannels.wechatOfficialAccountName.trim()
                val isAppWechatActive = catalog.officialChannels.wechatOfficialAccountStatus.equals("ACTIVE", ignoreCase = true) &&
                    cloudWechatName.isNotEmpty()
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "微信公众号与通知服务",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "App 通知服务状态说明与河工大校内权威公众号检索",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        XPeachStatusBadge(
                            text = if (isAppWechatActive) "已开通" else "待开通",
                            containerColor = if (isAppWechatActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isAppWechatActive) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    XPeachWell(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (isAppWechatActive) {
                                    "Day in School 官方公众号：$cloudWechatName（可在微信搜索关注；官方绝不会索要您的教务密码）"
                                } else {
                                    "状态说明：Day in School 官方微信公众号目前处于筹备与资质审核阶段，暂未开放关注或账号绑定。正式上线后将通过云端配置在本栏公布认证名称，切勿向非官方公众号提交教务密码。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isAppWechatActive) {
                                XPeachFilterPill(
                                    label = "复制公众号名称",
                                    selected = true,
                                    compact = true,
                                    onClick = {
                                        copyCommunityText(context, "官方微信公众号", cloudWechatName)
                                        feedbackText = "已复制公众号名称：$cloudWechatName"
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "校内常用公开公众号（点击复制名称前往微信搜索）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (wx in cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog.verifiedSchoolWechatAccounts) {
                            XPeachWell(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        copyCommunityText(context, "学校公众号", wx.name)
                                        feedbackText = "已复制「${wx.name}」，可前往微信搜索关注"
                                    },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = "${wx.name} · ${wx.unit}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = wx.summary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Text(
                                        text = "复制 ›",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = XPeachTheme.readablePrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
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

    val detailComp = selectedCompetitionDetail
    if (detailComp != null) {
        val effectivePartnerQq = if (detailComp.partnerClubId.isNotBlank()) {
            prefs.customClubGroupNumbers[detailComp.partnerClubId]?.takeIf { it.isNotBlank() }
                ?: detailComp.partnerClubQq
        } else {
            detailComp.partnerClubQq
        }
        CompetitionDetailDialog(
            comp = detailComp,
            effectivePartnerQq = effectivePartnerQq,
            onDismiss = { selectedCompetitionDetail = null },
            onCopyWebsite = {
                copyCommunityText(context, "${detailComp.shortTitle}官网", detailComp.officialWebsiteUrl)
                feedbackText = "已复制「${detailComp.shortTitle}」官网链接：${detailComp.officialWebsiteUrl}"
            },
            onOpenWebsite = {
                feedbackText = openCompetitionWebsite(
                    context = context,
                    title = detailComp.shortTitle,
                    url = detailComp.officialWebsiteUrl
                )
            },
            onJoinPartnerClub = {
                if (effectivePartnerQq.isNotBlank()) {
                    feedbackText = openOrCopyClubQqGroup(
                        context = context,
                        clubName = detailComp.partnerClubName.ifBlank { detailComp.shortTitle },
                        groupNumber = effectivePartnerQq
                    )
                }
            }
        )
    }

    if (showHospitalPharmacyDialog) {
        CampusHospitalPharmacyDialog(
            onDismiss = { showHospitalPharmacyDialog = false },
            onOpenUrl = { title, url ->
                feedbackText = openCompetitionWebsite(context, title, url)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampusHospitalPharmacyDialog(
    onDismiss: () -> Unit,
    onOpenUrl: (String, String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<HospitalMedicineCategory?>(null) }
    var showPolicyRules by remember { mutableStateOf(false) }

    val filteredItems = remember(searchQuery, selectedCategory) {
        CampusHospitalPharmacyCatalog.search(
            query = searchQuery,
            categoryFilter = selectedCategory
        )
    }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "校医院药品价格公示目录",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "共收录 ${CampusHospitalPharmacyCatalog.ITEMS.size} 项西药/中成药 · 采集于 ${CampusHospitalPharmacyCatalog.CAPTURED_DATE_ISO}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "重要说明：本表为校医院现场价格公示册离线转录（医保报销前基准单价），不代表当前实时在售或库存，也不提供在线买药或处方指导；并非所有药品均可走医保，实际报销金额以校医院收费窗口实时医保结算为准。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "资料贡献者：@${CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = XPeachTheme.readablePrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        onOpenUrl(
                                            "@${CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE} GitHub",
                                            CampusHospitalPharmacyCatalog.CONTRIBUTOR_GITHUB_URL
                                        )
                                    }
                                    .padding(vertical = 2.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                XPeachFilterPill(
                                    label = "贡献者主页",
                                    selected = false,
                                    compact = true,
                                    onClick = {
                                        onOpenUrl(
                                            "@${CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE} GitHub",
                                            CampusHospitalPharmacyCatalog.CONTRIBUTOR_GITHUB_URL
                                        )
                                    }
                                )
                                XPeachFilterPill(
                                    label = if (showPolicyRules) "收起医保说明" else "医保规则说明",
                                    selected = showPolicyRules,
                                    compact = true,
                                    onClick = { showPolicyRules = !showPolicyRules }
                                )
                            }
                        }
                    }
                }

                if (showPolicyRules) {
                    XPeachWell(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((title, desc) in CampusHospitalPharmacyCatalog.MEDICAL_INSURANCE_NOTES) {
                                Text(
                                    text = "• $title：$desc",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            XPeachFilterPill(
                                label = "查看学校医保政策原文",
                                selected = true,
                                compact = true,
                                onClick = {
                                    onOpenUrl("大学生医保政策说明", CampusHospitalPharmacyCatalog.POLICY_WECHAT_URL)
                                }
                            )
                        }
                    }
                }

                XPeachSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "搜索药名、规格、剂型、厂家或准字号"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    XPeachFilterPill(
                        label = "全部 (${CampusHospitalPharmacyCatalog.ITEMS.size})",
                        selected = selectedCategory == null,
                        compact = true,
                        onClick = { selectedCategory = null }
                    )
                    for (cat in HospitalMedicineCategory.entries) {
                        XPeachFilterPill(
                            label = cat.label,
                            selected = selectedCategory == cat,
                            compact = true,
                            onClick = {
                                selectedCategory = if (selectedCategory == cat) null else cat
                            }
                        )
                    }
                }

                Text(
                    text = "匹配结果：共 ${filteredItems.size} 项（可输入关键词按药名、规格、厂家或准字号检索）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                for (item in filteredItems) {
                    XPeachWell(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
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
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    XPeachStatusDotText(text = item.category.label)
                                    XPeachStatusDotText(
                                        text = item.provenanceBadgeText,
                                        dotColor = if (item.hasRegistryDiscrepancy) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    )
                                }
                                Text(
                                    text = "规格：${item.specification} · 剂型：${item.dosageForm}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${item.manufacturer} · ${item.approvalNumber} · 公示第${item.sourcePage}页",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                item.verificationNote?.let { vNote ->
                                    Text(
                                        text = vNote,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "¥${item.disclosedPriceYuan}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = "每${item.unit} · 医保前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MajorInterestCompassCard(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    gradeStage: CompetitionGradeStage,
    onSelectGradeStage: (CompetitionGradeStage) -> Unit,
    careerGoal: CompetitionCareerGoal,
    onSelectCareerGoal: (CompetitionCareerGoal) -> Unit,
    skillPref: CompetitionSkillPreference,
    onSelectSkillPref: (CompetitionSkillPreference) -> Unit,
    compassResult: cn.edu.haut.campus.core.model.CompetitionCompassResult,
    onInspectCompetition: (CampusCompetitionItem) -> Unit,
    onJoinClub: (CampusClubItem) -> Unit,
    onApplyToFilter: () -> Unit
) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.primaryAccentCardColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "参赛方向筛选",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "按年级、目标和兴趣推荐适合的竞赛",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            XPeachFilterPill(
                label = if (isExpanded) "收起" else "展开",
                selected = isExpanded,
                compact = true,
                onClick = onToggleExpand
            )
        }

        if (isExpanded) {
            Spacer(modifier = Modifier.height(12.dp))
            XPeachInsetDivider(verticalPadding = 2.dp)
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "1. 年级阶段",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (st in CompetitionGradeStage.entries) {
                    XPeachFilterPill(
                        label = st.shortLabel,
                        selected = gradeStage == st,
                        compact = true,
                        onClick = { onSelectGradeStage(st) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "2. 参赛目标",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (goal in CompetitionCareerGoal.entries) {
                    XPeachFilterPill(
                        label = goal.shortLabel,
                        selected = careerGoal == goal,
                        compact = true,
                        onClick = { onSelectCareerGoal(goal) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "3. 兴趣方向",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (pref in CompetitionSkillPreference.entries) {
                    XPeachFilterPill(
                        label = pref.shortLabel,
                        selected = skillPref == pref,
                        compact = true,
                        onClick = { onSelectSkillPref(pref) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            XPeachWell(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "推荐竞赛",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary
                    )
                    Text(
                        text = compassResult.stageSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    for ((stepTitle, item) in compassResult.entryStepOrder) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                                .clickable { onInspectCompetition(item) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = stepTitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = XPeachTheme.readablePrimary
                            )
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${item.tierBadge} · ${item.registrationWindow}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (compassResult.recommendedClubs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "相关社团",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (club in compassResult.recommendedClubs) {
                                XPeachFilterPill(
                                    label = "加群 · ${club.name} (${club.qqGroupNumber})",
                                    selected = false,
                                    compact = true,
                                    onClick = { onJoinClub(club) }
                                )
                            }
                            XPeachFilterPill(
                                label = "应用到列表筛选",
                                selected = true,
                                compact = true,
                                onClick = onApplyToFilter
                            )
                        }
                    }
                }
            }
        }
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
                        text = "${clubsCount} 个社团 · ${competitionsCount} 项竞赛",
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
                            text = "合作社团",
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
                        text = "活动场地：${club.activityVenue}",
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
                        text = "QQ 群：$effectiveGroupNo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isGroupCustomized) {
                        XPeachStatusDotText(
                            text = "已修改",
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
                label = "修改群号",
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
                    label = "加入群聊",
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
    onOpenDetail: () -> Unit,
    onCopyWebsite: () -> Unit,
    onOpenWebsite: () -> Unit,
    onJoinPartnerClub: () -> Unit
) {
    val visual = resolveCompetitionCategoryVisual(comp.category)

    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenDetail
    ) {
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
                            text = "本专业推荐",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    } else if (comp.pinned) {
                        XPeachStatusBadge(
                            text = "推荐",
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
                            text = "备赛建议：${comp.skillRoadmap}",
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text(
                    text = "赛程与赛制：${comp.registrationWindow} · ${comp.teamMode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "承办与主办：${comp.hautHostCollege}（${comp.organizer}）",
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
                            text = "关联社团：${comp.partnerClubName}（QQ 群：$effectivePartnerQq）",
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                XPeachFilterPill(
                    label = "赛事详情",
                    selected = false,
                    compact = true,
                    onClick = onOpenDetail
                )
                XPeachFilterPill(
                    label = "复制链接",
                    selected = false,
                    compact = true,
                    onClick = onCopyWebsite
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (comp.partnerClubName.isNotBlank() && effectivePartnerQq.isNotBlank()) {
                    XPeachFilterPill(
                        label = "加入社团群",
                        selected = false,
                        compact = true,
                        onClick = onJoinPartnerClub
                    )
                }
                XPeachFilterPill(
                    label = "打开官网",
                    selected = true,
                    compact = false,
                    onClick = onOpenWebsite
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompetitionDetailDialog(
    comp: CampusCompetitionItem,
    effectivePartnerQq: String,
    onDismiss: () -> Unit,
    onCopyWebsite: () -> Unit,
    onOpenWebsite: () -> Unit,
    onJoinPartnerClub: () -> Unit
) {
    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = comp.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    XPeachStatusBadge(
                        text = comp.tierBadge,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = XPeachTheme.readablePrimary
                    )
                    XPeachStatusBadge(
                        text = comp.difficultyLabel,
                        containerColor = if (comp.beginnerFriendly) {
                            XPeachTheme.successContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (comp.beginnerFriendly) {
                            XPeachTheme.onSuccessContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "赛事级别与认可度",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = XPeachTheme.readablePrimary
                        )
                        Text(
                            text = comp.recognitionValueText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "适合专业与对象",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = comp.targetPersonaText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "分阶段备赛建议",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        for ((stageTitle, stageDesc) in comp.stagedProgressionRoadmap()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stageTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = stageDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "组织单位与官网",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "校内承办：${comp.hautHostCollege}\n主办单位：${comp.organizer}\n赛程窗口：${comp.registrationWindow}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (comp.partnerClubName.isNotBlank() && effectivePartnerQq.isNotBlank()) {
                            Text(
                                text = "关联社团：${comp.partnerClubName}（QQ 群：$effectivePartnerQq）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                        Text(
                            text = "官方网站：${comp.officialWebsiteUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = XPeachTheme.readablePrimary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenWebsite,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("打开官网")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (comp.partnerClubName.isNotBlank() && effectivePartnerQq.isNotBlank()) {
                    TextButton(onClick = onJoinPartnerClub) {
                        Text("加社团群")
                    }
                } else {
                    TextButton(onClick = onCopyWebsite) {
                        Text("复制链接")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
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
        "已复制链接，正在打开「$title」官网…"
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
