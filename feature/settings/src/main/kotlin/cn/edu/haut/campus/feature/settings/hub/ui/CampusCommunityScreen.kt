package cn.edu.haut.campus.feature.settings.hub.ui

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import cn.edu.haut.campus.feature.settings.hub.state.CampusCommunityUiAction
import cn.edu.haut.campus.feature.settings.hub.state.CampusCommunityUiState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampusCommunityScreenContent(
    state: CampusCommunityUiState,
    actions: CampusCommunityUiAction,
    initialTab: Int = 0,
    onOpenMoments: () -> Unit = {},
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = state.prefs
    val studentProfile = state.studentProfile
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

    LaunchedEffect(actions) {
        isRefreshingCloud = true
        runCatching { actions.refreshCampusCommunityContent() }
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
                                actions.refreshCampusCommunityContent()
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
                                        actions.saveCustomClubGroupNumber("__app_user_feedback_qq_group__", qqInput.trim())
                                        actions.saveCustomChannelUrl("__app_user_feedback_channel__", chInput.trim())
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
                                text = "微信公众号、小程序与防冒充声明",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "官方渠道说明、商业广告澄清与河工大校内权威公众号检索",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        XPeachStatusBadge(
                            text = if (isAppWechatActive) "已开通" else "未开通",
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
                                    "Day in School 官方公众号：$cloudWechatName（完全免费无商业广告；官方绝不会索要您的教务密码）"
                                } else {
                                    "官方防冒充声明：Day in School（工大课表）完全免费、无任何商业推广广告。官方微信公众号仍在筹备中，且目前未上线任何推送商业引流广告的同名微信公众号或微信小程序。任何带有商业营销广告或索要教务密码的同名/近似公众号与小程序（如用户反馈的「邓州市少宇电子店」等第三方主体）均与本项目无关，切勿向非官方渠道提交统一认证或教务密码。"
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
                            actions.saveCustomChannelUrl(channelToEdit.id, channelUrlInput.trim())
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
                            actions.saveCustomClubGroupNumber(clubToEdit.id, groupNoInput.trim())
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
