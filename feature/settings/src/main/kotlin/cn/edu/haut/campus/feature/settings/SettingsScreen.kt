package cn.edu.haut.campus.feature.settings

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.academic.GitHubReleaseInfo
import cn.edu.haut.campus.core.academic.GitHubReleaseUpdater
import cn.edu.haut.campus.core.academic.HautSessionManager
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.DEFAULT_HOME_CARD_ORDER
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.designsystem.PresetThemePalette
import cn.edu.haut.campus.core.designsystem.UiDesignStyle
import cn.edu.haut.campus.core.designsystem.WallpaperThemeEngine
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.GpaCalculator
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.CampusClubCategory
import cn.edu.haut.campus.core.model.CampusClubItem
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.PresetLocalProxyClient
import cn.edu.haut.campus.core.model.PresetMirrorNode
import cn.edu.haut.campus.core.model.PresetQqChannelItem
import cn.edu.haut.campus.core.model.ProxyMode
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import cn.edu.haut.campus.core.model.WidgetThemeStyle
import cn.edu.haut.campus.core.model.WidgetType
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.core.reminder.ReminderPermissions
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

private val AUDIT_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

/**
 * 莲花街校区主要教学楼典型自习/公共教室池，结合用户本地已导入课表排课数据进行实时空闲教室反查
 */
private val LIANHUAJIE_STUDY_ROOMS_BY_BUILDING: Map<String, List<String>> = linkedMapOf(
    "5号教学楼" to listOf("5101", "5103", "5105", "5201", "5202", "5204", "5301", "5303", "5402"),
    "6号教学楼" to listOf("6101", "6102", "6201", "6203", "6205", "6301", "6304", "6401"),
    "7号教学楼" to listOf("7102", "7104", "7201", "7203", "7302", "7305", "7401"),
    "8号教学楼" to listOf("8101", "8103", "8201", "8202", "8301", "8303", "8402"),
    "9号教学楼" to listOf("9101", "9102", "9201", "9204", "9301", "9303"),
    "11号教学楼" to listOf("11101", "11102", "11201", "11203", "11301", "11302")
)

private data class StudyPeriodSlot(
    val label: String,
    val startPeriod: Int,
    val endPeriod: Int
)

private val STUDY_PERIOD_SLOTS = listOf(
    StudyPeriodSlot("1-2节", 1, 2),
    StudyPeriodSlot("3-4节", 3, 4),
    StudyPeriodSlot("5-6节", 5, 6),
    StudyPeriodSlot("7-8节", 7, 8),
    StudyPeriodSlot("9-10节", 9, 10)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repository: CampusRepository,
    onBack: (() -> Unit)? = null,
    onNavigateToImporter: () -> Unit = {},
    onNavigateToMapWithRoom: (String) -> Unit = {},
    onNavigateToElectricity: () -> Unit = {},
    onNavigateToLibraryCatalog: () -> Unit = {},
    onNavigateToLibrarySeats: () -> Unit = {},
    onNavigateToFocus: () -> Unit = {},
    onNavigateToEmptyClassrooms: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToSchoolCenter: () -> Unit = {},
    onNavigateToParcelHub: () -> Unit = {},
    onNavigateToXpeachCloud: () -> Unit = {},
    activeSchool: SchoolProfile = SchoolDirectory.hautProfile(),
    activeProfileLabel: String = "默认账号",
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isHautCampus = activeSchool.id == SchoolDirectory.HAUT_SCHOOL_ID
    val scrollState = rememberScrollState()
    var showSystemSettingsMenu by remember { mutableStateOf(onBack != null) }

    BackHandler(enabled = showSystemSettingsMenu && onBack == null) {
        showSystemSettingsMenu = false
    }

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
            showSystemSettingsMenu = false
            scrollState.animateScrollTo(0)
        }
    }

    val studentProfile by repository.studentProfileFlow.collectAsState(initial = null)
    val terms by repository.termsFlow.collectAsState(initial = emptyList())
    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val attempts by repository.gradeAttemptsFlow.collectAsState(initial = emptyList())
    val periodTimes by repository.periodTimesFlow.collectAsState(initial = emptyList())
    val calendarOverrides by repository.calendarOverridesFlow.collectAsState(initial = emptyList())
    val occurrenceOverrides by repository.occurrenceOverridesFlow.collectAsState(initial = emptyList())
    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())
    val importAudits by repository.importAuditsFlow.collectAsState(initial = emptyList())
    val examSchedules by repository.examSchedulesFlow.collectAsState(initial = emptyList())
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val activeReminderTasks by repository.activeReminderTasksFlow.collectAsState(initial = emptyList())
    val activeFocusSession by repository.activeFocusSessionFlow.collectAsState(initial = null)
    val completedFocusSessions by repository.completedFocusSessionsFlow.collectAsState(initial = emptyList())
    val onlineRooms by repository.utilityOnlineRoomsFlow.collectAsState(initial = emptyList())
    val utilityMeters by repository.utilityMetersFlow.collectAsState(initial = emptyList())
    val utilityReadings by repository.utilityReadingsFlow.collectAsState(initial = emptyList())
    val reminderCoordinator = remember(context) { ReminderCoordinator.getInstance(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var reminderPermSnapshot by remember(context) {
        mutableStateOf(ReminderPermissions.inspect(context))
    }
    var expandReminderDiagnostics by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        reminderPermSnapshot = ReminderPermissions.inspect(context)
        scope.launch {
            if (granted) {
                reminderCoordinator.reconcileNow("NOTIFICATION_PERMISSION_GRANTED", forceRescheduleAlarms = true)
            }
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reminderPermSnapshot = ReminderPermissions.inspect(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var isDynamicSyncing by remember { mutableStateOf(false) }

    val today = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
    val resolvedWeekInfo = remember(today, currentTerm, calendarOverrides) {
        currentTerm?.let { TeachingWeekCalculator.resolveDate(today, it, calendarOverrides) }
    }
    val currentTeachingWeek = resolvedWeekInfo?.teachingWeek ?: 1

    // 本周总课时计算
    val thisWeekClassCount = remember(
        currentTeachingWeek,
        currentTerm,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        buildings
    ) {
        val activeTerm = currentTerm ?: return@remember 0
        val map = ScheduleEngine.resolveOccurrencesForTeachingWeek(
            teachingWeek = currentTeachingWeek,
            term = activeTerm,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            buildings = buildings
        )
        map.values.sumOf { it.size }
    }

    // 学业 GPA 报告
    val officialSnapshot = remember(prefs.officialGpaValuePlain, prefs.officialGpaNote) {
        prefs.officialGpaValuePlain?.toBigDecimalOrNull()?.let { valBd ->
            OfficialGpaSnapshot(
                cumulativeGpa = valBd,
                fetchedAt = Instant.now(),
                fieldDefinitionNote = prefs.officialGpaNote ?: "教务系统平均绩点"
            )
        }
    }
    val latestTermId = remember(attempts) { attempts.firstOrNull()?.termId }
    val latestAcademicYear = remember(attempts) { attempts.firstOrNull()?.academicYear }
    val gpaReport = remember(attempts, latestTermId, latestAcademicYear, officialSnapshot) {
        GpaCalculator.calculateReport(
            attempts = attempts,
            currentTermId = latestTermId,
            currentAcademicYear = latestAcademicYear,
            officialSnapshot = officialSnapshot
        )
    }

    // 各类弹窗与折叠状态
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showImportHistoryDialog by remember { mutableStateOf(false) }
    var showAddCalendarOverrideDialog by remember { mutableStateOf(false) }
    var editingPeriod by remember { mutableStateOf<PeriodTime?>(null) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf<String?>(null) }
    var widgetPrivacyDefault by remember { mutableStateOf(false) }
    var widgetCompactDefault by remember { mutableStateOf(false) }
    var expandPeriodTable by remember { mutableStateOf(false) }
    var expandStudyFinder by remember { mutableStateOf(true) }
    var expandClubsSection by remember { mutableStateOf(true) }
    var selectedClubCategory by remember { mutableStateOf<CampusClubCategory?>(null) }
    var clubSearchQuery by remember { mutableStateOf("") }
    var editingChannelItem by remember { mutableStateOf<PresetQqChannelItem?>(null) }
    var editingClubItem by remember { mutableStateOf<CampusClubItem?>(null) }

    // GitHub Release 自动版本检测与在线拉取直装状态
    val currentInstalledVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.9.0" }
            .getOrDefault("0.9.0")
    }
    var isCheckingRelease by remember { mutableStateOf(false) }
    var releaseInfoState by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var isDownloadingApk by remember { mutableStateOf(false) }
    var apkDownloadProgress by remember { mutableStateOf(0f) }
    var releaseStatusMessage by remember { mutableStateOf<String?>(null) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(Unit) {
        isCheckingRelease = true
        val checkRes = GitHubReleaseUpdater.checkLatestRelease(currentInstalledVersion)
        isCheckingRelease = false
        checkRes.onSuccess { info ->
            releaseInfoState = info
            releaseStatusMessage = if (info.hasNewerVersion) {
                "发现新版本 ${info.tagName}"
            } else {
                "当前已是最新版本（v$currentInstalledVersion）"
            }
        }
    }

    // 空教室速查过滤状态
    val buildingKeys = remember { LIANHUAJIE_STUDY_ROOMS_BY_BUILDING.keys.toList() }
    var selectedStudyBuilding by remember { mutableStateOf(buildingKeys.first()) }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = if (showSystemSettingsMenu) "设置" else "我的",
            subtitle = if (showSystemSettingsMenu) {
                "通用设置与偏好"
            } else {
                "${activeSchool.displayName} · $activeProfileLabel"
            },
            badgeText = if (showSystemSettingsMenu) {
                "v$currentInstalledVersion"
            } else {
                resolvedWeekInfo?.teachingWeek?.let { "第 $it 周" }
            },
            navigationIcon = if (showSystemSettingsMenu || onBack != null) {
                {
                    XPeachIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = {
                            if (onBack != null) {
                                onBack()
                            } else {
                                showSystemSettingsMenu = false
                                scope.launch { scrollState.animateScrollTo(0) }
                            }
                        }
                    )
                }
            } else null,
            actions = {
                if (showSystemSettingsMenu) {
                    XPeachIconButton(
                        icon = Icons.Default.History,
                        contentDescription = "同步记录",
                        onClick = { showImportHistoryDialog = true }
                    )
                    XPeachIconButton(
                        icon = Icons.Default.CloudSync,
                        contentDescription = "教务同步",
                        onClick = onNavigateToImporter
                    )
                } else {
                    XPeachIconButton(
                        icon = Icons.Default.CloudSync,
                        contentDescription = "教务同步",
                        onClick = onNavigateToImporter
                    )
                    XPeachIconButton(
                        icon = Icons.Default.Settings,
                        contentDescription = "设置",
                        onClick = {
                            showSystemSettingsMenu = true
                            scope.launch { scrollState.animateScrollTo(0) }
                        }
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AnimatedVisibility(
                visible = feedbackText != null,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
            ) {
                feedbackText?.let { msg ->
                    XPeachCard(
                        containerColor = XPeachTheme.primaryAccentCardColor,
                        borderColor = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = msg,
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
            }

            AnimatedContent(
                targetState = showSystemSettingsMenu,
                transitionSpec = {
                    val direction = if (targetState) {
                        AnimatedContentTransitionScope.SlideDirection.Start
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.End
                    }
                    (slideIntoContainer(
                        towards = direction,
                        animationSpec = tween(250, easing = FastOutSlowInEasing)
                    ) { it / 8 } + fadeIn(tween(200))) togetherWith
                        (slideOutOfContainer(
                            towards = direction,
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) { it / 10 } + fadeOut(tween(140)))
                },
                label = "profileSettingsMenuTransition",
                modifier = Modifier.fillMaxWidth()
            ) { inSystemSettings ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
            if (!inSystemSettings) {
                // =========================================================================
                // 一级页面：「我的」—— 融合浓缩学籍学业总览、常用服务内测中心与底部免责条款
                // =========================================================================
                val cloudBinding by repository.cloudIdentityBindingFlow.collectAsState(initial = null)
                val parcelItems by repository.parcelItemsFlow.collectAsState(initial = emptyList())
                val extensionPlugins by repository.installedExtensionPluginsFlow.collectAsState(initial = emptyList())
                val latestAudit = importAudits.firstOrNull()

                // 1. 融合数字学籍、核心学业指标、学分构成与本学期校历进度主卡
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    StudentDigitalIdHeader(
                        profile = studentProfile,
                        schoolLabel = activeSchool.displayName,
                        privacyMasked = prefs.gpaPrivacyShieldEnabled,
                        lastAudit = latestAudit,
                        onSwitchSchool = onNavigateToSchoolCenter,
                        onEditProfile = { showEditProfileDialog = true },
                        onSyncNow = {
                            if (!isHautCampus) {
                                onNavigateToImporter()
                            } else if (prefs.hasSavedCredentials && !isDynamicSyncing) {
                                scope.launch {
                                    isDynamicSyncing = true
                                    feedbackText = "正在同步课表、考试和成绩..."
                                    val res = repository.performDynamicAcademicSync(force = true)
                                    isDynamicSyncing = false
                                    if (res == null) {
                                        onNavigateToImporter()
                                    } else {
                                        res.onSuccess { syncData ->
                                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                            feedbackText = "已同步 ${syncData.timetableCandidates.size} 门课程、${syncData.examItems.size} 场考试、${syncData.gradeAttempts.size} 条成绩"
                                        }.onFailure { err ->
                                            feedbackText = "同步失败：${err.message ?: "请检查网络"}"
                                        }
                                    }
                                }
                            } else {
                                onNavigateToImporter()
                            }
                        }
                    )

                    XPeachInsetDivider(verticalPadding = 10.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfileMetricItem(
                            label = "本周课时",
                            value = "${thisWeekClassCount}节",
                            modifier = Modifier.weight(1f)
                        )
                        ProfileMetricItem(
                            label = "考试安排",
                            value = "${examSchedules.size}场",
                            modifier = Modifier.weight(1f)
                        )
                        ProfileMetricItem(
                            label = "已获学分",
                            value = if (prefs.gpaPrivacyShieldEnabled) "**.*" else gpaReport.totalEarnedCredits.stripTrailingZeros().toPlainString(),
                            modifier = Modifier.weight(1f)
                        )
                        ProfileMetricItem(
                            label = "平均绩点",
                            value = if (prefs.gpaPrivacyShieldEnabled) "*.**" else (gpaReport.cumulativeGpa?.toPlainString() ?: "--"),
                            highlight = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (attempts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val categoryCredits = remember(attempts) {
                            val passed = attempts.filter { it.passed }.distinctBy { it.courseKey }
                            CourseCategory.entries.associateWith { cat ->
                                passed.filter { it.category == cat }
                                    .fold(BigDecimal.ZERO) { acc, item -> acc.add(item.credits) }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (cat in CourseCategory.entries) {
                                val cred = categoryCredits[cat] ?: BigDecimal.ZERO
                                val credText = if (prefs.gpaPrivacyShieldEnabled) "**" else cred.stripTrailingZeros().toPlainString()
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(vertical = 6.dp, horizontal = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${cat.label} $credText",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 10.dp)

                    val totalWeeks = (currentTerm?.weekCount ?: 20).coerceAtLeast(1)
                    val weekProgress = (currentTeachingWeek.toFloat() / totalWeeks.toFloat()).coerceIn(0.05f, 1f)
                    val examStartDate = currentTerm?.startDate?.plusWeeks(18)
                    val daysToExam = examStartDate?.let { ChronoUnit.DAYS.between(today, it) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildString {
                                append(currentTerm?.displayName ?: "当前学期")
                                append(" · 第 $currentTeachingWeek/$totalWeeks 周")
                                if (daysToExam != null && daysToExam > 0) {
                                    append(" · 距期末约 ${daysToExam} 天")
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        XPeachStatusDotText(
                            text = "进度 ${(weekProgress * 100).toInt()}%",
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { weekProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = XPeachTheme.readablePrimary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    if (terms.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (t in terms) {
                                val count = rules.count { it.termId == t.id }
                                XPeachFilterPill(
                                    label = "${t.displayName} ($count)",
                                    selected = t.id == currentTerm?.id,
                                    compact = true,
                                    onClick = {
                                        scope.launch {
                                            repository.selectCurrentTerm(t.id)
                                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                            feedbackText = "已切换至 ${t.displayName}（共 $count 门排课）"
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // 2. 融合个人校园服务与内测扩展中心卡
                val primaryOnlineRoom = onlineRooms.firstOrNull()
                val onlineDevices = remember(primaryOnlineRoom) {
                    primaryOnlineRoom?.let { decodeOnlineDevicesJson(it.devicesJson) }.orEmpty()
                }
                val primaryMeter = utilityMeters.firstOrNull()
                val latestReading = remember(primaryMeter, utilityReadings) {
                    primaryMeter?.let { m ->
                        utilityReadings
                            .filter { it.meterId == m.id }
                            .maxByOrNull { it.recordedAtEpochMs }
                    }
                }
                val roomSummaryTitle = when {
                    primaryOnlineRoom != null -> primaryOnlineRoom.roomName
                    primaryMeter != null -> "${primaryMeter.roomName} · ${primaryMeter.name}"
                    else -> "未绑定宿舍"
                }
                val roomSummaryValue = when {
                    onlineDevices.isNotEmpty() -> {
                        onlineDevices.joinToString(" · ") { it.balanceRaw.ifBlank { "在线水电" } }
                    }
                    latestReading != null && primaryMeter != null -> "剩余 ${latestReading.value}${primaryMeter.unit}"
                    else -> "点击绑定寝室水电"
                }
                val todayFocusMinutes = remember(completedFocusSessions, today) {
                    completedFocusSessions.sumOf { s ->
                        val doneMs = s.completedAtEpochMs ?: s.expectedEndAtEpochMs
                        val d = Instant.ofEpochMilli(doneMs).atZone(HAUT_ZONE_ID).toLocalDate()
                        if (d == today) s.durationMinutes else 0
                    }
                }
                val totalFocusMinutes = remember(completedFocusSessions) {
                    completedFocusSessions.sumOf { it.durationMinutes }
                }
                val waitingCount = parcelItems.count { it.status == "READY_FOR_PICKUP" }
                val peachFormatted = cloudBinding?.let {
                    "%.2f".format(it.peachBalanceMicropeach.toDouble() / 10_000.0)
                }
                val enabledPluginCount = extensionPlugins.count { it.status == "ENABLED" }

                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToParcelHub)
                            .padding(vertical = 2.dp),
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
                                text = "我的快递",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "自动同步物流与驿站取件码",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (waitingCount > 0) {
                            XPeachStatusBadge(
                                text = "待取 $waitingCount 件",
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            XPeachStatusDotText(
                                text = "共 ${parcelItems.size} 件",
                                dotColor = XPeachTheme.readablePrimary
                            )
                        }
                    }

                    if (isHautCampus) {
                        XPeachInsetDivider(verticalPadding = 8.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onNavigateToElectricity)
                                .padding(vertical = 2.dp),
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
                                    text = "宿舍水电 · $roomSummaryTitle",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = roomSummaryValue,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "水电详情 ›",
                                style = MaterialTheme.typography.labelMedium,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToFocus)
                            .padding(vertical = 2.dp),
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
                                text = if (activeFocusSession != null) {
                                    "自习专注 · 进行中（${activeFocusSession?.durationMinutes}分钟）"
                                } else {
                                    "自习专注"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "今日已专注 ${todayFocusMinutes} 分钟 · 累计 ${totalFocusMinutes} 分钟（${completedFocusSessions.size} 次）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "进入计时 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToXpeachCloud)
                            .padding(vertical = 2.dp),
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
                                text = "云账号与抢座服务 (测试版)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (cloudBinding != null) {
                                    "已登录 ${cloudBinding?.displayName} · 积分仅作内测防恶意滥用 · 扩展 $enabledPluginCount/${extensionPlugins.size}"
                                } else {
                                    "代抢座位服务（测试版） · 积分仅用于内部测试防止恶意利用"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (peachFormatted != null) {
                            XPeachStatusBadge(
                                text = "内测 ${peachFormatted} 🍑",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = XPeachTheme.readablePrimary
                            )
                        } else {
                            XPeachStatusBadge(
                                text = "测试版",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { showImportHistoryDialog = true }
                            .padding(vertical = 2.dp),
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
                                text = "教务同步与多身份管理",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (latestAudit != null) {
                                    "身份：$activeProfileLabel · 最近同步 ${AUDIT_TIME_FMT.format(latestAudit.importedAt)}（${courses.size} 门课 / ${attempts.size} 条成绩）"
                                } else {
                                    "当前身份：$activeProfileLabel · 尚未同步教务数据"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "记录 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }
                }

                // 3. 个人主页底部免责条款与内测说明
                ProfileDisclaimerSection()
            } else {
                // =========================================================================
                // 二级页面：「设置」—— 提醒、调休、作息、小组件、隐私安全与版本更新
                // =========================================================================

                // 1. 学期、调休与停课管理 + 课表文件互通
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
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
                                text = "学期与调休",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "切换学期、节假日调休与课表导入导出",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (prefs.timetableInterchangeEnabled) {
                            XPeachFilterPill(
                                label = "导入/导出",
                                selected = false,
                                onClick = onNavigateToImporter
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "当前学期",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                        for (t in terms) {
                            val count = rules.count { it.termId == t.id }
                            XPeachFilterPill(
                                label = "${t.displayName} ($count)",
                                selected = t.id == currentTerm?.id,
                                compact = true,
                                onClick = {
                                    scope.launch {
                                        repository.selectCurrentTerm(t.id)
                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                        feedbackText = "已切换至 ${t.displayName}（共 $count 门排课）"
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "调休与停课 (${calendarOverrides.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        XPeachFilterPill(
                            label = "+ 添加",
                            selected = false,
                            compact = true,
                            onClick = { showAddCalendarOverrideDialog = true }
                        )
                    }

                    if (calendarOverrides.isNotEmpty()) {
                        XPeachInsetDivider(verticalPadding = 8.dp)
                        val sortedOverrides = calendarOverrides.sortedBy { it.date }
                        Column {
                            sortedOverrides.forEachIndexed { index, ov ->
                                if (index > 0) {
                                    XPeachInsetDivider(verticalPadding = 6.dp)
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${ov.date} · ${ov.title}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = when (ov.overrideType) {
                                                CalendarOverrideType.HOLIDAY_NO_CLASS -> "全天停课放假"
                                                CalendarOverrideType.MAKEUP_FOLLOW_DATE -> "调休补课（按 ${ov.followDate ?: "指定日"} 课表）"
                                                CalendarOverrideType.CUSTOM_TEACHING_WEEK -> "自定义教学周"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                repository.deleteCalendarOverride(ov.id)
                                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "删除调休",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. 桌面小组件中心（含 Bento 视觉预览卡片与一键添加）
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("桌面小组件画廊", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "全新 Bento 视觉风格 · 点击预览卡片可一键添加至桌面",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                feedbackText = "已刷新全部桌面小组件。"
                            }
                        ) {
                            Text("刷新全部", maxLines = 1, softWrap = false)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    fun requestPin(label: String, providerClass: String) {
                        val mgr = AppWidgetManager.getInstance(context)
                        val provider = ComponentName(context.packageName, providerClass)
                        if (mgr.isRequestPinAppWidgetSupported) {
                            val ok = mgr.requestPinAppWidget(provider, null, null)
                            feedbackText = if (ok) {
                                "请在系统弹窗中确认添加「$label」"
                            } else {
                                "请长按手机桌面空白处添加小组件"
                            }
                        } else {
                            feedbackText = "请长按手机桌面空白处添加小组件"
                        }
                    }

                    // 横向 Bento 小组件高保真预览画廊
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 1) 2x2 下一节课预览卡
                        Column(
                            modifier = Modifier
                                .width(156.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .border(0.7.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                .clickable {
                                    requestPin("2×2 下一节", "cn.edu.haut.campus.widget.nextclass.NextClassAppWidgetProvider")
                                }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                XPeachStatusBadge(
                                    text = "下一节课",
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = "2×2",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "数据结构与算法",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "10:10–11:50 · 3-4节",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "5教 5204",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = "添加 ›",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = XPeachTheme.readablePrimary
                                )
                            }
                        }

                        // 2) 4x2 今日双栏预览卡
                        Column(
                            modifier = Modifier
                                .width(214.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .border(0.7.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                .clickable {
                                    requestPin("4×2 今日课程", "cn.edu.haut.campus.widget.today.TodayAppWidgetProvider")
                                }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "今日课表双栏",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "4×2 · 添加 ›",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = XPeachTheme.readablePrimary
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1.1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                                        .padding(horizontal = 7.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "操作系统原理",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "6教 6201 · 3-4节",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = XPeachTheme.readablePrimary,
                                        maxLines = 1
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(0.9f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "14:30 计网 @8教",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "19:00 选修 @钟楼",
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }

                        // 3) 4x2 快递取件码预览卡
                        Column(
                            modifier = Modifier
                                .width(204.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                                .border(0.7.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                .clickable {
                                    requestPin("4×2 快递取件码", "cn.edu.haut.campus.widget.today.ParcelAppWidgetProvider")
                                }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "驿站取件码",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "4×2 · 添加 ›",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = XPeachTheme.readablePrimary
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "3-2-1048",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = "北苑菜鸟驿站 · 顺丰速运",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                requestPin("4×4 时间轴", "cn.edu.haut.campus.widget.timeline.TimelineAppWidgetProvider")
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("添加 4×4 时间轴", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = {
                                requestPin("4×4 周课表", "cn.edu.haut.campus.widget.week.WeekAppWidgetProvider")
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("添加 4×4 周课表", maxLines = 1, softWrap = false)
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("小组件紧凑布局", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = widgetCompactDefault,
                            onCheckedChange = { checked ->
                                widgetCompactDefault = checked
                                scope.launch {
                                    for (id in 0..20) {
                                        val existing = repository.getWidgetConfigOrDefault(id, WidgetType.NEXT_CLASS_2X2)
                                        repository.saveWidgetConfig(
                                            existing.copy(
                                                compact = checked,
                                                privacyMode = widgetPrivacyDefault
                                            )
                                        )
                                    }
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    feedbackText = "已更新小组件紧凑布局。"
                                }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("小组件隐藏课程名与教室", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = widgetPrivacyDefault,
                            onCheckedChange = { checked ->
                                widgetPrivacyDefault = checked
                                scope.launch {
                                    for (id in 0..20) {
                                        val existing = repository.getWidgetConfigOrDefault(id, WidgetType.NEXT_CLASS_2X2)
                                        repository.saveWidgetConfig(
                                            existing.copy(
                                                compact = widgetCompactDefault,
                                                privacyMode = checked
                                            )
                                        )
                                    }
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    feedbackText = "已更新小组件隐私保护。"
                                }
                            }
                        )
                    }
                }

            // 7. 作息节次表（默认折叠）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("作息时间", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "08:00–21:25 · 共 ${periodTimes.size} 节 · 点击时间可修改",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachFilterPill(
                        label = if (expandPeriodTable) "收起" else "展开",
                        selected = expandPeriodTable,
                        onClick = { expandPeriodTable = !expandPeriodTable }
                    )
                }

                AnimatedVisibility(visible = expandPeriodTable) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        for (pt in periodTimes) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "第 ${pt.periodNo} 节 (${pt.sectionLabel})",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TextButton(
                                    onClick = { editingPeriod = pt },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(
                                        text = "${pt.startLocalTime}–${pt.endLocalTime}",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 7.5 上课与考试提醒（含「稍后提醒」Snooze）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                val rCfg = prefs.reminderConfig
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("上课与考试提醒", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "课前与考前自动通知",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachStatusDotText(
                        text = when {
                            !rCfg.enabled -> "已关闭"
                            !reminderPermSnapshot.canPostNotifications -> "无通知权限"
                            reminderPermSnapshot.exactAlarmAllowed && rCfg.preciseReminderPreferred -> "精确闹钟"
                            else -> "普通提醒"
                        },
                        dotColor = if (rCfg.enabled && reminderPermSnapshot.canPostNotifications) {
                            XPeachTheme.readablePrimary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("开启上课与考试提醒", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "课前和考前自动发送提醒通知",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = rCfg.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !reminderPermSnapshot.notificationPermissionGranted
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            scope.launch {
                                repository.updateReminderGlobalConfig { it.copy(enabled = enabled) }
                                reminderPermSnapshot = ReminderPermissions.inspect(context)
                            }
                        }
                    )
                }

                // 权限状态提示与引导
                if (rCfg.enabled && (!reminderPermSnapshot.canPostNotifications || (!reminderPermSnapshot.exactAlarmAllowed && rCfg.preciseReminderPreferred))) {
                    XPeachInsetDivider(verticalPadding = 6.dp)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (!reminderPermSnapshot.canPostNotifications) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "未开启通知权限，无法弹出提醒",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                ReminderPermissions.buildNotificationSettingsIntent(context)
                                            )
                                        }
                                    }
                                ) {
                                    Text("去开启", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                        if (!reminderPermSnapshot.exactAlarmAllowed && rCfg.preciseReminderPreferred) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "未开启精确闹钟权限，通知可能延迟几分钟",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                ReminderPermissions.buildExactAlarmSettingsIntent(context)
                                            )
                                        }
                                    }
                                ) {
                                    Text("去开启", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "课前提前时间（当前：提前 ${rCfg.classLeadMinutes} 分钟）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (lead in listOf(5, 10, 15, 20, 30, 45, 60)) {
                        XPeachFilterPill(
                            label = "${lead}分钟",
                            selected = rCfg.classLeadMinutes == lead,
                            compact = true,
                            onClick = {
                                scope.launch {
                                    repository.updateReminderGlobalConfig {
                                        it.copy(classLeadMinutes = lead)
                                    }
                                    feedbackText = "已设置课前提前 $lead 分钟提醒"
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "单次提醒最多延后次数",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (maxCount in listOf(1, 2, 3)) {
                            XPeachFilterPill(
                                label = "${maxCount}次",
                                selected = rCfg.classSnoozeMaxCount == maxCount,
                                compact = true,
                                onClick = {
                                    scope.launch {
                                        repository.updateReminderGlobalConfig {
                                            it.copy(
                                                classSnoozeMaxCount = maxCount,
                                                examSnoozeMaxCount = maxCount
                                            )
                                        }
                                    }
                                }
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
                        Text("考试提前 1 天提醒", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "开考前一天提醒考试时间与考场",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = rCfg.examDayBeforeEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.updateReminderGlobalConfig { it.copy(examDayBeforeEnabled = enabled) }
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("考试提前 1 小时提醒", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "开考前 1 小时提醒考场与座位号",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = rCfg.examHourBeforeEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                repository.updateReminderGlobalConfig { it.copy(examHourBeforeEnabled = enabled) }
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("使用精确闹钟", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "避免系统省电策略延迟上课提醒",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = rCfg.preciseReminderPreferred,
                        onCheckedChange = { preferred ->
                            scope.launch {
                                repository.updateReminderGlobalConfig { it.copy(preciseReminderPreferred = preferred) }
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("锁屏隐藏课程与教室名称", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "锁屏时只提示有课，解锁后显示详情",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = rCfg.lockscreenPrivate,
                        onCheckedChange = { priv ->
                            scope.launch {
                                repository.updateReminderGlobalConfig { it.copy(lockscreenPrivate = priv) }
                            }
                        }
                    )
                }

                XPeachInsetDivider(verticalPadding = 8.dp)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = prefs.lastReminderReconcileSummary
                            ?: "待触发提醒：${activeReminderTasks.size} 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = XPeachTheme.readablePrimary,
                        fontWeight = FontWeight.Medium
                    )
                    if (activeReminderTasks.isNotEmpty()) {
                        val nextTask = activeReminderTasks.first()
                        val nextTimeStr = Instant.ofEpochMilli(nextTask.scheduledAtEpochMs)
                            .atZone(HAUT_ZONE_ID)
                            .format(AUDIT_TIME_FMT)
                        Text(
                            text = "下次提醒：$nextTimeStr（已延后 ${nextTask.snoozeCount}/${nextTask.maxSnoozes} 次）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val report = reminderCoordinator.reconcileNow(
                                    reason = "USER_MANUAL_REBUILD",
                                    forceRescheduleAlarms = true
                                )
                                feedbackText = "已刷新提醒：共安排 ${report.activeScheduledCount} 项"
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("刷新提醒")
                    }

                    FilledTonalButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !reminderPermSnapshot.notificationPermissionGranted
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                scope.launch {
                                    val sent = reminderCoordinator.triggerTestReminder()
                                    feedbackText = if (sent) {
                                        "已发送测试通知"
                                    } else {
                                        "发送失败，请先开启通知权限"
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("测试通知")
                    }
                }

                if (prefs.reminderDiagnosticLogs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "提醒记录 (${prefs.reminderDiagnosticLogs.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { expandReminderDiagnostics = !expandReminderDiagnostics }) {
                            Text(if (expandReminderDiagnostics) "收起" else "展开")
                        }
                    }
                    AnimatedVisibility(visible = expandReminderDiagnostics) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            XPeachInsetDivider(verticalPadding = 4.dp)
                            for (logLine in prefs.reminderDiagnosticLogs.take(8)) {
                                Text(
                                    text = logLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 7.5 主题、透明液态毛玻璃 UI、系统桌面背景同步、调色盘与模块化卡片布局
            val runDesktopWallpaperSync: () -> Unit = {
                scope.launch {
                    feedbackText = "正在同步系统桌面背景与主题色..."
                    WallpaperThemeEngine.syncSystemDesktopWallpaper(context)
                        .onSuccess { result ->
                            repository.saveCustomWallpaperTheme(
                                localPath = result.localFilePath,
                                primarySeedArgb = result.primarySeedArgb,
                                extractedSwatches = result.extractedSwatches,
                                enableLiquidGlass = true
                            )
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            val hexStr = String.format("#%06X", 0xFFFFFF and result.primarySeedArgb.toInt())
                            feedbackText = "${result.statusMessage} ($hexStr)"
                        }
                        .onFailure { err ->
                            feedbackText = "获取桌面背景失败：${err.message ?: "请稍后重试"}"
                        }
                }
            }

            val desktopWallpaperPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                runDesktopWallpaperSync()
            }

            val wallpaperPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    scope.launch {
                        feedbackText = "正在应用背景图片..."
                        WallpaperThemeEngine.importWallpaperAndExtractPalette(context, uri)
                            .onSuccess { result ->
                                repository.saveCustomWallpaperTheme(
                                    localPath = result.localFilePath,
                                    primarySeedArgb = result.primarySeedArgb,
                                    extractedSwatches = result.extractedSwatches
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                val hexStr = String.format("#%06X", 0xFFFFFF and result.primarySeedArgb.toInt())
                                feedbackText = "已应用自定义背景图 ($hexStr)"
                            }
                            .onFailure { err ->
                                feedbackText = "导入背景图失败：${err.message ?: "请换一张图片重试"}"
                            }
                    }
                }
            }

            ThemePaletteAndCardLayoutSection(
                prefs = prefs,
                onSelectThemeMode = { mode ->
                    scope.launch { repository.setThemeMode(mode) }
                },
                onSelectUiDesignStyle = { styleKey ->
                    scope.launch {
                        repository.setUiDesignStyle(styleKey)
                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        feedbackText = if (styleKey == UiDesignStyle.LIQUID_GLASS.key) {
                            "已切换为透明液态毛玻璃 UI"
                        } else {
                            "已切换为 Flutter 经典质感 UI"
                        }
                    }
                },
                onToggleDynamicColor = { enabled ->
                    scope.launch { repository.setUseDynamicColor(enabled) }
                },
                onSelectPalette = { key, seedArgb ->
                    scope.launch {
                        repository.setThemePalette(key, seedArgb)
                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        feedbackText = if (key == PresetThemePalette.LIQUID_GLASS.key) {
                            "已启用透明毛玻璃主题"
                        } else {
                            "已更新主题配色"
                        }
                    }
                },
                onSyncDesktopWallpaper = {
                    if (WallpaperThemeEngine.hasSystemWallpaperPermission(context)) {
                        runDesktopWallpaperSync()
                    } else {
                        desktopWallpaperPermLauncher.launch(
                            WallpaperThemeEngine.requiredWallpaperPermissionName()
                        )
                    }
                },
                onExtractDesktopColors = {
                    scope.launch {
                        WallpaperThemeEngine.extractSystemDesktopColorsOnly(context)
                            .onSuccess { swatches ->
                                val primary = swatches.firstOrNull() ?: 0xFF0EA5E9L
                                repository.saveExtractedDesktopSwatches(
                                    primarySeedArgb = primary,
                                    extractedSwatches = swatches
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                val hexStr = String.format("#%06X", 0xFFFFFF and primary.toInt())
                                feedbackText = "已提取并应用系统桌面主题色 ($hexStr)"
                            }
                            .onFailure { err ->
                                feedbackText = "提取桌面主题色失败：${err.message ?: "请重试"}"
                            }
                    }
                },
                onPickWallpaperImage = {
                    wallpaperPickerLauncher.launch("image/*")
                },
                onUpdateWallpaperParams = { alpha, blurDp, cardAlpha ->
                    scope.launch {
                        repository.updateWallpaperVisualParams(
                            alpha = alpha,
                            blurDp = blurDp,
                            cardAlpha = cardAlpha
                        )
                    }
                },
                onResetWallpaperParams = {
                    scope.launch {
                        repository.resetWallpaperVisualParams()
                        feedbackText = "已恢复默认毛玻璃透明度与模糊度"
                    }
                },
                onClearWallpaper = {
                    scope.launch {
                        repository.clearCustomWallpaperTheme()
                        feedbackText = "已移除自定义背景图片"
                    }
                },
                onUpdateCardLayout = { order, hidden, fullWidth ->
                    scope.launch {
                        repository.updateHomeCardLayout(order, hidden, fullWidth)
                        feedbackText = "已更新首页卡片布局"
                    }
                },
                onResetCardLayout = {
                    scope.launch {
                        repository.resetHomeCardLayout()
                        feedbackText = "已恢复默认首页卡片布局"
                    }
                }
            )

            // 8. 外观、隐私安全与数据管理
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("外观、隐私与数据", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                if (isHautCampus) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示宿舍水电服务", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = prefs.utilitiesEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setCampusServiceFeatureFlag(utilitiesEnabled = enabled) }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示图书馆馆藏查询", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = prefs.libraryNativeEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setCampusServiceFeatureFlag(libraryNativeEnabled = enabled) }
                        }
                    )
                }
                if (isHautCampus) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示图书馆座位预约", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = prefs.librarySeatEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setCampusServiceFeatureFlag(librarySeatEnabled = enabled) }
                        }
                    )
                }
                if (isHautCampus) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示自习专注计时", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = prefs.focusEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setCampusServiceFeatureFlag(focusEnabled = enabled) }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("在「今天」页显示自习专注快捷栏", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = prefs.showTodayFocusQuickPill,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setCampusServiceFeatureFlag(showTodayFocusQuickPill = enabled) }
                        }
                    )
                }
                XPeachInsetDivider(verticalPadding = 6.dp)
                if (isHautCampus) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启动时自动更新课表与考试", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "打开应用时自动从教务系统获取最新课表、考试和成绩",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.autoSyncEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setAutoSyncEnabled(enabled) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("跟随系统壁纸颜色", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "开启后使用系统壁纸配色，关闭则使用默认蓝色",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.useDynamicColor,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setUseDynamicColor(enabled) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("隐藏成绩与学号", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "隐藏绩点和分数，并禁止在应用内截屏",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.gpaPrivacyShieldEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setGpaPrivacyShield(enabled) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                repository.resetOnboardingForGuide()
                                onBack?.invoke()
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重新进入引导页")
                    }

                    Button(
                        onClick = { showWipeConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清除个人数据")
                    }
                }
            }

            // 8.5 网络代理与云服务加速配置
            NetworkProxyAndCloudConfigSection(
                proxyConfig = prefs.networkProxyConfig,
                onSaveProxyConfig = { updatedConfig ->
                    scope.launch {
                        repository.updateNetworkProxyConfig(updatedConfig)
                        feedbackText = "已更新代理与云服务配置：${updatedConfig.statusSummaryLabel()}"
                    }
                },
                onResetProxyConfig = {
                    scope.launch {
                        repository.resetNetworkProxyConfig()
                        feedbackText = "已恢复默认加速镜像配置"
                    }
                }
            )

            // 9. 版本更新
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
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
                            text = "版本更新",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "检查新版本并下载安装包 · 当前线路：${prefs.networkProxyConfig.statusSummaryLabel()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (releaseInfoState?.hasNewerVersion == true) {
                        XPeachStatusBadge(
                            text = "有新版 ${releaseInfoState?.tagName}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    } else {
                        XPeachStatusDotText(
                            text = "v$currentInstalledVersion",
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启动时自动检查更新", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "有新版本时弹窗提示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.autoCheckUpdateEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setAutoCheckUpdateEnabled(enabled) }
                        }
                    )
                }

                XPeachInsetDivider(verticalPadding = 8.dp)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = releaseStatusMessage
                            ?: if (isCheckingRelease) "正在通过「${prefs.networkProxyConfig.statusSummaryLabel()}」检查新版本..."
                            else "当前版本：v$currentInstalledVersion · Android ${Build.VERSION.RELEASE}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (releaseInfoState?.hasNewerVersion == true) {
                            XPeachTheme.readablePrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    releaseInfoState?.let { info ->
                        Text(
                            text = "最新版本：${info.releaseTitle} (${info.tagName}) · 通道：${info.resolvedViaRoute}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (info.changelog.isNotBlank()) {
                            Text(
                                text = info.changelog.take(240),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (isDownloadingApk) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { apkDownloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = XPeachTheme.readablePrimary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = !isCheckingRelease && !isDownloadingApk,
                        onClick = {
                            scope.launch {
                                isCheckingRelease = true
                                releaseStatusMessage = "正在通过「${prefs.networkProxyConfig.statusSummaryLabel()}」检查更新..."
                                val res = GitHubReleaseUpdater.checkLatestRelease(
                                    currentVersionName = currentInstalledVersion,
                                    proxyConfig = prefs.networkProxyConfig
                                )
                                isCheckingRelease = false
                                res.onSuccess { info ->
                                    releaseInfoState = info
                                    releaseStatusMessage = if (info.hasNewerVersion) {
                                        "发现新版本 ${info.tagName}（${info.resolvedViaRoute}），点击右侧按钮下载安装。"
                                    } else {
                                        "当前 v$currentInstalledVersion 已是最新版本（${info.resolvedViaRoute}）。"
                                    }
                                }.onFailure { err ->
                                    releaseStatusMessage = "检查失败：${err.message ?: "请尝试在上方切换加速镜像或代理"}"
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isCheckingRelease) "正在检查..." else "检查更新"
                        )
                    }

                    Button(
                        enabled = !isDownloadingApk && !isCheckingRelease,
                        onClick = {
                            val readyFile = downloadedApkFile
                            if (readyFile != null && readyFile.exists()) {
                                GitHubReleaseUpdater.triggerInstallApk(context, readyFile)
                                    .onSuccess { installed ->
                                        releaseStatusMessage = if (installed) {
                                            "已打开安装界面，请确认安装。"
                                        } else {
                                            "请先允许「安装未知应用」后再点击安装。"
                                        }
                                    }
                                    .onFailure { err ->
                                        releaseStatusMessage = "无法打开安装器：${err.message}"
                                    }
                            } else {
                                scope.launch {
                                    isDownloadingApk = true
                                    apkDownloadProgress = 0f
                                    releaseStatusMessage = "正在通过「${prefs.networkProxyConfig.statusSummaryLabel()}」获取下载地址..."
                                    val targetInfo = releaseInfoState
                                        ?: GitHubReleaseUpdater.checkLatestRelease(
                                            currentVersionName = currentInstalledVersion,
                                            proxyConfig = prefs.networkProxyConfig
                                        ).getOrNull()
                                    if (targetInfo == null) {
                                        isDownloadingApk = false
                                        releaseStatusMessage = "获取版本信息失败，请在上方切换代理线路后重试。"
                                        return@launch
                                    }
                                    releaseInfoState = targetInfo
                                    val dlRes = GitHubReleaseUpdater.downloadReleaseApk(
                                        context = context,
                                        releaseInfo = targetInfo,
                                        proxyConfig = prefs.networkProxyConfig,
                                        onProgress = { dl, total ->
                                            if (total > 0L) {
                                                apkDownloadProgress = dl.toFloat() / total.toFloat()
                                                val dlMb = String.format("%.1f", dl / (1024.0 * 1024.0))
                                                val totalMb = String.format("%.1f", total / (1024.0 * 1024.0))
                                                releaseStatusMessage =
                                                    "正在加速下载 ${targetInfo.tagName}：${(apkDownloadProgress * 100).toInt()}% ($dlMb / $totalMb MB)"
                                            }
                                        }
                                    )
                                    isDownloadingApk = false
                                    dlRes.onSuccess { apkFile ->
                                        downloadedApkFile = apkFile
                                        GitHubReleaseUpdater.triggerInstallApk(context, apkFile)
                                            .onSuccess { installed ->
                                                releaseStatusMessage = if (installed) {
                                                    "下载完成，正在打开安装界面。"
                                                } else {
                                                    "下载完成，请允许「安装未知应用」后点击安装。"
                                                }
                                            }
                                            .onFailure { err ->
                                                releaseStatusMessage = "打开安装失败：${err.message}"
                                            }
                                    }.onFailure { err ->
                                        releaseStatusMessage = "下载失败：${err.message ?: "请检查网络或切换代理线路"}"
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = when {
                                isDownloadingApk -> "正在下载..."
                                downloadedApkFile?.exists() == true -> "安装更新"
                                releaseInfoState?.hasNewerVersion == true -> "下载新版本"
                                else -> "下载安装包"
                            }
                        )
                    }
                }
            }
            }
                }
            }

            // 预留底部悬浮胶囊导航栏高度
            Spacer(modifier = Modifier.height(96.dp))
        }
    }

    // 弹窗 1：编辑个人信息
    if (showEditProfileDialog) {
        var nameInput by remember(studentProfile) { mutableStateOf(studentProfile?.displayName ?: "") }
        var schoolIdInput by remember(studentProfile) { mutableStateOf(studentProfile?.schoolId ?: "") }
        var majorInput by remember(studentProfile) { mutableStateOf(studentProfile?.major ?: "") }
        var yearInput by remember(studentProfile) { mutableStateOf(studentProfile?.enrollmentYear?.toString() ?: "2023") }

        XPeachAlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("编辑个人信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("姓名 / 昵称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = schoolIdInput,
                        onValueChange = { schoolIdInput = it },
                        label = { Text("学号") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = majorInput,
                        onValueChange = { majorInput = it },
                        label = { Text("学院 / 专业") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = yearInput,
                        onValueChange = { yearInput = it },
                        label = { Text("入学年份 (如 2023)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.saveStudentProfile(
                                StudentProfile(
                                    id = studentProfile?.id ?: "stu_local",
                                    schoolId = schoolIdInput.trim().ifBlank { "未填写" },
                                    displayName = nameInput.trim().ifBlank { null },
                                    major = majorInput.trim().ifBlank { null },
                                    enrollmentYear = yearInput.trim().toIntOrNull()
                                )
                            )
                            showEditProfileDialog = false
                            feedbackText = "已保存个人信息。"
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 弹窗 2：新增校历调休 / 停课安排
    if (showAddCalendarOverrideDialog) {
        var dateIso by remember { mutableStateOf(today.toString()) }
        var titleInput by remember { mutableStateOf("") }
        var isMakeup by remember { mutableStateOf(false) }
        var followDateIso by remember { mutableStateOf(today.minusDays(2).toString()) }

        XPeachAlertDialog(
            onDismissRequest = { showAddCalendarOverrideDialog = false },
            title = { Text("新增调休或停课日期") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateIso,
                        onValueChange = { dateIso = it },
                        label = { Text("目标日期 (YYYY-MM-DD)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("事由名称 (如 校运会停课 / 国庆调休)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        XPeachFilterPill(
                            label = "全天停课",
                            selected = !isMakeup,
                            onClick = { isMakeup = false }
                        )
                        XPeachFilterPill(
                            label = "调休补课",
                            selected = isMakeup,
                            onClick = { isMakeup = true }
                        )
                    }
                    if (isMakeup) {
                        OutlinedTextField(
                            value = followDateIso,
                            onValueChange = { followDateIso = it },
                            label = { Text("按哪一天的课表上课 (YYYY-MM-DD)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedDate = runCatching { LocalDate.parse(dateIso.trim()) }.getOrNull()
                        val parsedFollow = runCatching { LocalDate.parse(followDateIso.trim()) }.getOrNull()
                        if (parsedDate != null && titleInput.isNotBlank()) {
                            scope.launch {
                                repository.saveCalendarOverride(
                                    CalendarOverride(
                                        id = "cal_${UUID.randomUUID().toString().take(8)}",
                                        termId = currentTerm?.id ?: "2025-2026-2",
                                        date = parsedDate,
                                        overrideType = if (isMakeup) {
                                            CalendarOverrideType.MAKEUP_FOLLOW_DATE
                                        } else {
                                            CalendarOverrideType.HOLIDAY_NO_CLASS
                                        },
                                        followDate = if (isMakeup) parsedFollow else null,
                                        title = titleInput.trim()
                                    )
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                showAddCalendarOverrideDialog = false
                                feedbackText = "已添加校历调休/停课项。"
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCalendarOverrideDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 弹窗 3：历史同步审计记录
    if (showImportHistoryDialog) {
        XPeachAlertDialog(
            onDismissRequest = { showImportHistoryDialog = false },
            title = { Text("教务与课表同步记录") },
            text = {
                if (importAudits.isEmpty()) {
                    Text(
                        text = "暂无同步记录。点击右上角同步图标可从正方教务系统导入课表与成绩。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val audits = importAudits.take(8)
                    Column {
                        audits.forEachIndexed { index, audit ->
                            if (index > 0) {
                                XPeachInsetDivider(verticalPadding = 6.dp)
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "${AUDIT_TIME_FMT.format(audit.importedAt)} · ${audit.termId}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = audit.outcome,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportHistoryDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // 弹窗 4：修改单节作息时间
    val ptToEdit = editingPeriod
    if (ptToEdit != null) {
        var startIso by remember(ptToEdit) { mutableStateOf(ptToEdit.startLocalTime.toString()) }
        var endIso by remember(ptToEdit) { mutableStateOf(ptToEdit.endLocalTime.toString()) }

        XPeachAlertDialog(
            onDismissRequest = { editingPeriod = null },
            title = { Text("第 ${ptToEdit.periodNo} 节时间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startIso,
                        onValueChange = { startIso = it },
                        label = { Text("开始 (HH:mm)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = endIso,
                        onValueChange = { endIso = it },
                        label = { Text("结束 (HH:mm)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedStart = runCatching { LocalTime.parse(startIso.trim()) }.getOrNull()
                        val parsedEnd = runCatching { LocalTime.parse(endIso.trim()) }.getOrNull()
                        if (parsedStart != null && parsedEnd != null && parsedStart.isBefore(parsedEnd)) {
                            scope.launch {
                                repository.updatePeriodTime(
                                    ptToEdit.copy(
                                        startLocalTime = parsedStart,
                                        endLocalTime = parsedEnd
                                    )
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                feedbackText = "已更新第 ${ptToEdit.periodNo} 节时间。"
                            }
                        }
                        editingPeriod = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPeriod = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 弹窗 5：清除个人数据确认
    if (showWipeConfirmDialog) {
        XPeachAlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("清除个人数据") },
            text = {
                Text("将清空本地课表、成绩、个人信息、登录状态与小组件配置。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            HautSessionManager.clearAuthorizedSessionCookies()
                            repository.wipeAllPersonalData()
                            repository.resetOnboardingForGuide()
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            showWipeConfirmDialog = false
                            onBack?.invoke()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("确认清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 弹窗 6：修改 QQ 频道邀请链接
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

    // 弹窗 7：修改社团 QQ 群号
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


private fun copyTextToClipboard(context: Context, label: String, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}


/**
 * 唤醒手机 QQ 群资料卡或复制社团群号：
 * 使用官方 `mqqapi://card/show_pslcard?src_type=internal&version=1&uin=<群号>&card_type=group&source=qrcode` 协议直达加群。
 */
private fun joinOrCopyClubQqGroup(
    context: Context,
    clubName: String,
    groupNumber: String
): String {
    val cleanNo = groupNumber.trim()
    copyTextToClipboard(context, "$clubName QQ群号", cleanNo)
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


/**
 * 跳转加入 QQ 频道：
 * 同时将频道邀请链接复制到剪贴板，并通过系统 ACTION_VIEW 直接调起 QQ 频道或浏览器落地页。
 */
private fun jumpToPresetQqChannel(
    context: Context,
    channelTitle: String,
    inviteUrl: String
): String {
    val cleanUrl = inviteUrl.trim().ifBlank { "https://pd.qq.com" }
    copyTextToClipboard(context, channelTitle, cleanUrl)
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


@Composable
private fun StudentDigitalIdHeader(
    profile: StudentProfile?,
    schoolLabel: String,
    privacyMasked: Boolean,
    lastAudit: cn.edu.haut.campus.core.model.ImportAudit?,
    onSwitchSchool: () -> Unit,
    onEditProfile: () -> Unit,
    onSyncNow: () -> Unit
) {
    val displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: "未设置姓名"
    val rawSchoolId = profile?.schoolId?.takeIf { it.isNotBlank() } ?: "未绑定学号"
    val displaySchoolId = if (privacyMasked && rawSchoolId.length >= 8) {
        rawSchoolId.take(4) + "****" + rawSchoolId.takeLast(3)
    } else {
        rawSchoolId
    }
    val majorText = buildString {
        append(profile?.major?.takeIf { it.isNotBlank() } ?: schoolLabel)
        profile?.enrollmentYear?.let { append(" · ${it}级") }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(
                        width = 1.dp,
                        color = XPeachTheme.readablePrimary.copy(alpha = 0.25f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayName.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = XPeachTheme.readablePrimary
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    XPeachStatusDotText(
                        text = if (profile != null) "已绑定" else "未同步",
                        dotColor = if (profile != null) {
                            XPeachTheme.readablePrimary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
                    Text(
                        text = "$schoolLabel ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = XPeachTheme.readablePrimary,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onSwitchSchool)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = "$displaySchoolId · $majorText",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (lastAudit != null) {
                    Text(
                        text = "上次同步：${AUDIT_TIME_FMT.format(lastAudit.importedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            XPeachIconButton(
                icon = Icons.Default.Edit,
                contentDescription = "编辑资料",
                onClick = onEditProfile
            )
            XPeachFilterPill(
                label = "同步教务",
                selected = true,
                compact = true,
                onClick = onSyncNow
            )
        }
    }
}

@Composable
private fun ProfileDisclaimerSection() {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "免责条款与服务说明",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            XPeachStatusBadge(
                text = "内测声明",
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "1. 非官方工具声明：本应用为独立开发的非营利校园效率辅助工具，与高校官方教务、图书馆及后勤系统无任何隶属或委托关系。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "2. 代抢座位服务（测试版）：图书馆抢座辅助与云端代抢目前处于内部测试版（Beta）阶段，受网络波动及场馆策略影响不承诺成功率；请遵守场馆管理规定按时签到，未签到产生的违约记录由使用者自行承担。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "3. 内测防滥用积分（🍑）：账户内的 🍑 积分仅用于内部测试期间防止恶意高频请求与算力滥用，不具备任何货币价值，不提供任何商业充值、付费购买或提现变现服务。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "4. 隐私与数据准则：教务与场馆凭据仅加密存储于本机（主动开启测试版云端代抢任务除外）。课表、成绩、绩点、水电及快递数据仅供参考，请以学校官方系统为准。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun ProfileMetricItem(
    label: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = if (highlight) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}

private val SETTINGS_HOME_CARD_LABELS: List<Pair<String, String>> = listOf(
    "EXAM_SUMMARY" to "近期考试与学业",
    "STUDY_FOCUS" to "自习专注",
    "DORM_ELECTRICITY" to "宿舍水电",
    "LIBRARY_SEAT" to "座位预约",
    "PARCEL_HUB" to "驿站快递",
    "LIBRARY_CATALOG" to "馆藏检索",
    "EMPTY_CLASSROOM" to "空教室查询",
    "CAMPUS_COMMUNITY" to "社团与频道"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePaletteAndCardLayoutSection(
    prefs: AppPreferencesState,
    onSelectThemeMode: (String) -> Unit,
    onSelectUiDesignStyle: (String) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onSelectPalette: (String, Long?) -> Unit,
    onSyncDesktopWallpaper: () -> Unit,
    onExtractDesktopColors: () -> Unit,
    onPickWallpaperImage: () -> Unit,
    onUpdateWallpaperParams: (alpha: Float?, blurDp: Float?, cardAlpha: Float?) -> Unit,
    onResetWallpaperParams: () -> Unit,
    onClearWallpaper: () -> Unit,
    onUpdateCardLayout: (List<String>, Set<String>, Set<String>) -> Unit,
    onResetCardLayout: () -> Unit
) {
    var expandCustomPalette by remember { mutableStateOf(prefs.themePaletteKey == "CUSTOM") }
    var expandCardManager by remember { mutableStateOf(false) }
    var localWallpaperAlpha by remember(prefs.wallpaperAlpha) { mutableFloatStateOf(prefs.wallpaperAlpha) }
    var localWallpaperBlur by remember(prefs.wallpaperBlurDp) { mutableFloatStateOf(prefs.wallpaperBlurDp) }
    var localCardAlpha by remember(prefs.wallpaperCardAlpha) { mutableFloatStateOf(prefs.wallpaperCardAlpha) }

    val initialHsv = remember(prefs.customThemeSeedArgb) {
        val arr = FloatArray(3)
        android.graphics.Color.colorToHSV(prefs.customThemeSeedArgb.toInt(), arr)
        arr
    }
    var hue by remember(prefs.customThemeSeedArgb) { mutableFloatStateOf(initialHsv[0].coerceIn(0f, 360f)) }
    var saturation by remember(prefs.customThemeSeedArgb) { mutableFloatStateOf(initialHsv[1].coerceIn(0.05f, 1f)) }
    var brightness by remember(prefs.customThemeSeedArgb) { mutableFloatStateOf(initialHsv[2].coerceIn(0.20f, 1f)) }

    val customColorInt = remember(hue, saturation, brightness) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
    }
    val customHexText = remember(customColorInt) {
        String.format("#%06X", 0xFFFFFF and customColorInt)
    }
    var hexInput by remember(prefs.customThemeSeedArgb) { mutableStateOf(customHexText) }

    val isLiquidGlassActive = prefs.uiDesignStyle == UiDesignStyle.LIQUID_GLASS.key ||
        prefs.themePaletteKey == PresetThemePalette.LIQUID_GLASS.key
    val hasCustomWallpaper = !prefs.customWallpaperPath.isNullOrBlank()
    val showGlassControls = isLiquidGlassActive || hasCustomWallpaper

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "主题与视觉风格",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "切换 Flutter 经典质感或透明液态毛玻璃 UI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val currentPaletteLabel = when {
                isLiquidGlassActive && hasCustomWallpaper -> "液态毛玻璃 · 桌面背景"
                isLiquidGlassActive -> "透明液态毛玻璃"
                hasCustomWallpaper -> "自定义壁纸"
                prefs.useDynamicColor -> "系统取色"
                prefs.themePaletteKey == "CUSTOM" -> customHexText
                else -> PresetThemePalette.fromKey(prefs.themePaletteKey)?.label ?: "工大蓝"
            }
            XPeachStatusDotText(
                text = currentPaletteLabel,
                dotColor = XPeachTheme.readablePrimary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 0. 产品设计语言切换（Flutter 经典质感 vs 透明液态毛玻璃）
        val styleOptions = remember { UiDesignStyle.entries }
        val currentStyle = if (isLiquidGlassActive) UiDesignStyle.LIQUID_GLASS else UiDesignStyle.FLUTTER_M3
        XPeachSegmentedTabs(
            options = styleOptions,
            selectedOption = currentStyle,
            labelProvider = { it.label },
            onSelect = { chosen ->
                if (chosen == UiDesignStyle.LIQUID_GLASS) {
                    onSelectUiDesignStyle(UiDesignStyle.LIQUID_GLASS.key)
                } else {
                    onSelectUiDesignStyle(UiDesignStyle.FLUTTER_M3.key)
                }
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 0.5 透明毛玻璃主题与桌面壁纸一键同步工作台
        XPeachWell(
            modifier = Modifier.fillMaxWidth(),
            containerColor = if (isLiquidGlassActive) {
                XPeachTheme.primaryAccentCardColor
            } else {
                MaterialTheme.colorScheme.surfaceVariant
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
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (isLiquidGlassActive) "透明液态毛玻璃已启用" else "透明毛玻璃主题",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (hasCustomWallpaper) {
                            "已加载壁纸背景，可调节毛玻璃透光度与模糊度"
                        } else {
                            "支持获取系统桌面背景、桌面主题色或上传相册壁纸"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                XPeachStatusBadge(
                    text = if (isLiquidGlassActive) "液态毛玻璃 UI" else "Flutter UI",
                    containerColor = if (isLiquidGlassActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (isLiquidGlassActive) {
                        XPeachTheme.readablePrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                XPeachFilterPill(
                    label = "获取桌面背景",
                    selected = hasCustomWallpaper && isLiquidGlassActive,
                    compact = true,
                    onClick = onSyncDesktopWallpaper
                )
                XPeachFilterPill(
                    label = "提取桌面主题色",
                    selected = false,
                    compact = true,
                    onClick = onExtractDesktopColors
                )
                XPeachFilterPill(
                    label = if (hasCustomWallpaper) "更换相册图" else "相册背景图",
                    selected = hasCustomWallpaper && !isLiquidGlassActive,
                    compact = true,
                    onClick = onPickWallpaperImage
                )
                if (hasCustomWallpaper) {
                    XPeachFilterPill(
                        label = "移除背景图",
                        selected = false,
                        compact = true,
                        onClick = onClearWallpaper
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. 深浅色外观模式与系统动态取色
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "深浅外观",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((modeKey, modeLabel) in listOf(
                    "SYSTEM" to "跟随系统",
                    "LIGHT" to "浅色",
                    "DARK" to "深色"
                )) {
                    XPeachFilterPill(
                        label = modeLabel,
                        selected = prefs.themeMode == modeKey,
                        compact = true,
                        onClick = { onSelectThemeMode(modeKey) }
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Android 系统动态色彩",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "跟随系统 Material You 壁纸色板（兼容毛玻璃）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = prefs.useDynamicColor,
                    onCheckedChange = onToggleDynamicColor
                )
            }
        }

        // 1.5 壁纸提取色板与毛玻璃透明度/模糊度控制面板
        if (prefs.extractedWallpaperColors.isNotEmpty() || showGlassControls) {
            XPeachInsetDivider(verticalPadding = 10.dp)
            Column(modifier = Modifier.fillMaxWidth()) {
                if (prefs.extractedWallpaperColors.isNotEmpty()) {
                    Text(
                        text = "桌面 / 壁纸提取主题色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (swatchArgb in prefs.extractedWallpaperColors) {
                            val isSelectedSwatch = !prefs.useDynamicColor &&
                                prefs.themePaletteKey == "CUSTOM" &&
                                (prefs.customThemeSeedArgb and 0xFFFFFFL) == (swatchArgb and 0xFFFFFFL)
                            val swatchHex = String.format("#%06X", 0xFFFFFF and swatchArgb.toInt())
                            val rawSwatchColor = Color(swatchArgb)
                            val readableSwatch = XPeachTheme.readableAccent(rawSwatchColor)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (isSelectedSwatch) XPeachTheme.courseContainer(swatchArgb, active = true)
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                                    )
                                    .border(
                                        width = if (isSelectedSwatch) 1.5.dp else 0.7.dp,
                                        color = if (isSelectedSwatch) readableSwatch else MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .clickable { onSelectPalette("CUSTOM", swatchArgb) }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(rawSwatchColor)
                                        .border(
                                            0.6.dp,
                                            if (XPeachTheme.isDark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.12f),
                                            CircleShape
                                        )
                                )
                                Text(
                                    text = swatchHex,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelectedSwatch) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelectedSwatch) readableSwatch else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (showGlassControls) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "液态毛玻璃光学参数",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            XPeachFilterPill(
                                label = "极清 36%",
                                selected = abs(localCardAlpha - 0.36f) < 0.04f,
                                compact = true,
                                onClick = {
                                    localCardAlpha = 0.36f
                                    localWallpaperBlur = 14f
                                    localWallpaperAlpha = 0.70f
                                    onUpdateWallpaperParams(0.70f, 14f, 0.36f)
                                }
                            )
                            XPeachFilterPill(
                                label = "标准 68%",
                                selected = abs(localCardAlpha - 0.68f) < 0.04f,
                                compact = true,
                                onClick = {
                                    localCardAlpha = 0.68f
                                    localWallpaperBlur = 10f
                                    localWallpaperAlpha = 0.55f
                                    onUpdateWallpaperParams(0.55f, 10f, 0.68f)
                                }
                            )
                            XPeachFilterPill(
                                label = "重置",
                                selected = false,
                                compact = true,
                                onClick = {
                                    localCardAlpha = 0.68f
                                    localWallpaperBlur = 10f
                                    localWallpaperAlpha = 0.55f
                                    onResetWallpaperParams()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "毛玻璃卡片不透明度 (${(localCardAlpha * 100).toInt()}% · 越低越通透)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = localCardAlpha.coerceIn(0.18f, 0.98f),
                        onValueChange = {
                            localCardAlpha = it
                            onUpdateWallpaperParams(null, null, it)
                        },
                        valueRange = 0.18f..0.98f
                    )

                    Text(
                        text = "背景毛玻璃模糊度 (${localWallpaperBlur.toInt()} dp)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = localWallpaperBlur.coerceIn(0f, 35f),
                        onValueChange = {
                            localWallpaperBlur = it
                            onUpdateWallpaperParams(null, it, null)
                        },
                        valueRange = 0f..35f
                    )

                    Text(
                        text = "背景显色透光度 (${(localWallpaperAlpha * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = localWallpaperAlpha.coerceIn(0.05f, 1.0f),
                        onValueChange = {
                            localWallpaperAlpha = it
                            onUpdateWallpaperParams(it, null, null)
                        },
                        valueRange = 0.05f..1.0f
                    )
                }
            }
        }

        XPeachInsetDivider(verticalPadding = 10.dp)

        // 2. 9款精选预设主题色卡矩阵（首项为“透明毛玻璃”）
        Text(
            text = "精选主题配色（点击「透明毛玻璃」即刻切换液态毛玻璃 UI）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowPalettes in PresetThemePalette.entries.chunked(3)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (palette in rowPalettes) {
                        val isSelected = !prefs.useDynamicColor && prefs.themePaletteKey == palette.key
                        val swatchColor = Color(palette.seedArgb)
                        val readableSwatch = XPeachTheme.readableAccent(swatchColor)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) {
                                        XPeachTheme.courseContainer(palette.seedArgb, active = true)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                                    }
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.7.dp,
                                    color = if (isSelected) {
                                        readableSwatch
                                    } else if (XPeachTheme.isDark || XPeachTheme.isLiquidGlass) {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onSelectPalette(palette.key, null) }
                                .padding(vertical = 8.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (palette.activatesLiquidGlass) {
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    Color(0xFF38BDF8),
                                                    Color(0xFF818CF8),
                                                    Color(0xFF2DD4BF)
                                                )
                                            )
                                        } else {
                                            Brush.linearGradient(listOf(swatchColor, swatchColor))
                                        }
                                    )
                                    .border(
                                        0.8.dp,
                                        if (XPeachTheme.isDark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.10f),
                                        CircleShape
                                    )
                            )
                            Text(
                                text = palette.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) readableSwatch else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. 自由调色盘展开/收起
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(customColorInt))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                )
                Column {
                    Text(
                        text = "自定义调色盘 ($customHexText)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "调节滑块或输入色号自定义配色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            XPeachFilterPill(
                label = if (expandCustomPalette) "收起" else "调色盘",
                selected = prefs.themePaletteKey == "CUSTOM" && !prefs.useDynamicColor,
                compact = true,
                onClick = { expandCustomPalette = !expandCustomPalette }
            )
        }

        AnimatedVisibility(visible = expandCustomPalette) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                XPeachInsetDivider(verticalPadding = 6.dp)
                // 360° 彩虹色相渐变条预览
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF0000),
                                    Color(0xFFFFFF00),
                                    Color(0xFF00FF00),
                                    Color(0xFF00FFFF),
                                    Color(0xFF0000FF),
                                    Color(0xFFFF00FF),
                                    Color(0xFFFF0000)
                                )
                            )
                        )
                )
                Text(
                    text = "色相 (${hue.toInt()}°)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Slider(
                    value = hue.coerceIn(0f, 360f),
                    onValueChange = {
                        hue = it
                        val nextColor = android.graphics.Color.HSVToColor(floatArrayOf(it, saturation, brightness))
                        hexInput = String.format("#%06X", 0xFFFFFF and nextColor)
                    },
                    valueRange = 0f..360f
                )

                Text(
                    text = "饱和度 (${(saturation * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = saturation.coerceIn(0.05f, 1f),
                    onValueChange = {
                        saturation = it
                        val nextColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, it, brightness))
                        hexInput = String.format("#%06X", 0xFFFFFF and nextColor)
                    },
                    valueRange = 0.05f..1f
                )

                Text(
                    text = "明度 (${(brightness * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = brightness.coerceIn(0.20f, 1f),
                    onValueChange = {
                        brightness = it
                        val nextColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, it))
                        hexInput = String.format("#%06X", 0xFFFFFF and nextColor)
                    },
                    valueRange = 0.20f..1f
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { raw ->
                            hexInput = raw
                            val cleaned = raw.trim().removePrefix("#")
                            if (cleaned.length == 6) {
                                cleaned.toLongOrNull(16)?.let { rgb ->
                                    val argb = (0xFF000000L or rgb).toInt()
                                    val arr = FloatArray(3)
                                    android.graphics.Color.colorToHSV(argb, arr)
                                    hue = arr[0].coerceIn(0f, 360f)
                                    saturation = arr[1].coerceIn(0.05f, 1f)
                                    brightness = arr[2].coerceIn(0.20f, 1f)
                                }
                            }
                        },
                        label = { Text("色号 (#RRGGBB)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    val customBtnBg = Color(customColorInt)
                    val customBtnFg = if (customBtnBg.luminance() > 0.45f) Color(0xFF0F172A) else Color.White
                    Button(
                        onClick = {
                            val seedLong = 0xFF000000L or (customColorInt.toLong() and 0xFFFFFFL)
                            onSelectPalette("CUSTOM", seedLong)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = customBtnBg,
                            contentColor = customBtnFg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("应用配色", color = customBtnFg, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        XPeachInsetDivider(verticalPadding = 10.dp)

        // 4. 首页模块化卡片排序与显隐管理
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
                Text(
                    text = "首页卡片管理",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val visibleCount = (DEFAULT_HOME_CARD_ORDER.size - prefs.hiddenHomeCards.size).coerceAtLeast(0)
                Text(
                    text = "已显示 $visibleCount / ${DEFAULT_HOME_CARD_ORDER.size} 个卡片 · 可调整顺序与宽度",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            XPeachFilterPill(
                label = if (expandCardManager) "收起" else "调整卡片",
                selected = expandCardManager,
                compact = true,
                onClick = { expandCardManager = !expandCardManager }
            )
        }

        AnimatedVisibility(visible = expandCardManager) {
            val labelMap = remember { SETTINGS_HOME_CARD_LABELS.toMap() }
            val currentOrder = remember(prefs.homeCardOrder) {
                (prefs.homeCardOrder.filter { it in labelMap.keys } + DEFAULT_HOME_CARD_ORDER).distinct()
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                XPeachInsetDivider(verticalPadding = 4.dp)
                currentOrder.forEachIndexed { index, cardId ->
                    if (index > 0) {
                        XPeachInsetDivider(verticalPadding = 6.dp)
                    }
                    val title = labelMap[cardId] ?: cardId
                    val isVisible = cardId !in prefs.hiddenHomeCards
                    val isFull = cardId in prefs.fullWidthHomeCards

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}. $title",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isVisible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 6.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            XPeachFilterPill(
                                label = if (isFull) "大卡" else "小卡",
                                selected = isFull,
                                compact = true,
                                onClick = {
                                    val nextFull = if (isFull) prefs.fullWidthHomeCards - cardId else prefs.fullWidthHomeCards + cardId
                                    onUpdateCardLayout(currentOrder, prefs.hiddenHomeCards, nextFull)
                                }
                            )
                            XPeachFilterPill(
                                label = if (isVisible) "显示" else "隐藏",
                                selected = isVisible,
                                compact = true,
                                onClick = {
                                    val nextHidden = if (isVisible) prefs.hiddenHomeCards + cardId else prefs.hiddenHomeCards - cardId
                                    onUpdateCardLayout(currentOrder, nextHidden, prefs.fullWidthHomeCards)
                                }
                            )
                            XPeachFilterPill(
                                label = "↑",
                                selected = false,
                                compact = true,
                                onClick = {
                                    if (index > 0) {
                                        val mut = currentOrder.toMutableList()
                                        val tmp = mut[index - 1]
                                        mut[index - 1] = mut[index]
                                        mut[index] = tmp
                                        onUpdateCardLayout(mut, prefs.hiddenHomeCards, prefs.fullWidthHomeCards)
                                    }
                                }
                            )
                            XPeachFilterPill(
                                label = "↓",
                                selected = false,
                                compact = true,
                                onClick = {
                                    if (index < currentOrder.lastIndex) {
                                        val mut = currentOrder.toMutableList()
                                        val tmp = mut[index + 1]
                                        mut[index + 1] = mut[index]
                                        mut[index] = tmp
                                        onUpdateCardLayout(mut, prefs.hiddenHomeCards, prefs.fullWidthHomeCards)
                                    }
                                }
                            )
                        }
                    }
                }
                TextButton(
                    onClick = onResetCardLayout,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("恢复默认")
                }
            }
        }
    }
}

@Composable
private fun NetworkProxyAndCloudConfigSection(
    proxyConfig: NetworkProxyConfig,
    onSaveProxyConfig: (NetworkProxyConfig) -> Unit,
    onResetProxyConfig: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var customPrefixInput by remember(proxyConfig.customReverseProxyPrefix) {
        mutableStateOf(proxyConfig.customReverseProxyPrefix)
    }
    var hostInput by remember(proxyConfig.proxyHost) {
        mutableStateOf(proxyConfig.proxyHost)
    }
    var portInput by remember(proxyConfig.proxyPort) {
        mutableStateOf(proxyConfig.proxyPort.toString())
    }
    var cloudEndpointInput by remember(proxyConfig.customCloudApiBaseUrl) {
        mutableStateOf(proxyConfig.customCloudApiBaseUrl)
    }
    var expandCloudEndpoint by remember { mutableStateOf(false) }
    var isTestingConnectivity by remember { mutableStateOf(false) }
    var connectivityMessage by remember { mutableStateOf<String?>(null) }
    var connectivityOk by remember { mutableStateOf<Boolean?>(null) }

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "网络与更新线路",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "设置更新下载线路与网络代理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            XPeachStatusDotText(
                text = proxyConfig.statusSummaryLabel(),
                dotColor = XPeachTheme.readablePrimary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. 代理类型横向胶囊切换
        Text(
            text = "代理类型",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (mode in ProxyMode.entries) {
                XPeachFilterPill(
                    label = mode.label,
                    selected = proxyConfig.mode == mode,
                    compact = true,
                    onClick = {
                        connectivityMessage = null
                        onSaveProxyConfig(proxyConfig.copy(mode = mode))
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = proxyConfig.mode.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        XPeachInsetDivider(verticalPadding = 10.dp)

        // 2. 根据代理类型展示对应配置面板
        when (proxyConfig.mode) {
            ProxyMode.PRESET_MIRROR -> {
                Text(
                    text = "预置加速镜像线路",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (rowNodes in PresetMirrorNode.entries.chunked(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (node in rowNodes) {
                                val selected = proxyConfig.presetMirrorKey == node.key
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) XPeachTheme.readablePrimary.copy(alpha = 0.14f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                        )
                                        .border(
                                            width = if (selected) 1.5.dp else 0.6.dp,
                                            color = if (selected) XPeachTheme.readablePrimary else Color.Transparent,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            connectivityMessage = null
                                            onSaveProxyConfig(proxyConfig.copy(presetMirrorKey = node.key))
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = node.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = node.prefixUrl.removePrefix("https://").trimEnd('/'),
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

            ProxyMode.CUSTOM_REVERSE_PROXY -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "自定义镜像地址前缀",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customPrefixInput,
                            onValueChange = { customPrefixInput = it },
                            label = { Text("镜像前缀 (如 https://gh.example.com/)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val normalized = NetworkProxyConfig.normalizePrefixUrl(customPrefixInput)
                                customPrefixInput = normalized
                                connectivityMessage = null
                                onSaveProxyConfig(proxyConfig.copy(customReverseProxyPrefix = normalized))
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("保存", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            ProxyMode.HTTP_PROXY, ProxyMode.SOCKS_PROXY -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "常用代理客户端快捷填入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (preset in PresetLocalProxyClient.entries) {
                            val isCurrent = proxyConfig.mode == preset.mode &&
                                proxyConfig.proxyHost == preset.host &&
                                proxyConfig.proxyPort == preset.port
                            XPeachFilterPill(
                                label = preset.label,
                                selected = isCurrent,
                                compact = true,
                                onClick = {
                                    hostInput = preset.host
                                    portInput = preset.port.toString()
                                    connectivityMessage = null
                                    onSaveProxyConfig(
                                        proxyConfig.copy(
                                            mode = preset.mode,
                                            proxyHost = preset.host,
                                            proxyPort = preset.port
                                        )
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text("服务器地址") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.4f)
                        )
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("端口") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.9f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val validPort = (portInput.toIntOrNull() ?: 7890).coerceIn(1, 65535)
                            val cleanHost = hostInput.trim().ifBlank { "127.0.0.1" }
                            hostInput = cleanHost
                            portInput = validPort.toString()
                            connectivityMessage = null
                            onSaveProxyConfig(
                                proxyConfig.copy(
                                    proxyHost = cleanHost,
                                    proxyPort = validPort
                                )
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("应用代理配置", maxLines = 1, softWrap = false)
                    }
                }
            }

            ProxyMode.DIRECT -> {
                Text(
                    text = "直接连接服务器，建议开启下方「自动切换备用线路」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        XPeachInsetDivider(verticalPadding = 8.dp)

        // 3. 自动回退镜像开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自动切换备用线路", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "当前线路连接失败时自动尝试其他镜像",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = proxyConfig.autoFallbackMirror,
                onCheckedChange = { enabled ->
                    onSaveProxyConfig(proxyConfig.copy(autoFallbackMirror = enabled))
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 4. 云服务 API 网关端点配置（支持后续云同步与自建云服务）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("云服务地址", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = proxyConfig.customCloudApiBaseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            XPeachFilterPill(
                label = if (expandCloudEndpoint) "收起" else "修改",
                selected = expandCloudEndpoint,
                compact = true,
                onClick = { expandCloudEndpoint = !expandCloudEndpoint }
            )
        }

        AnimatedVisibility(visible = expandCloudEndpoint) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = cloudEndpointInput,
                    onValueChange = { cloudEndpointInput = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val cleaned = cloudEndpointInput.trim().ifBlank { NetworkProxyConfig.DEFAULT_CLOUD_API_BASE_URL }
                        cloudEndpointInput = cleaned
                        onSaveProxyConfig(proxyConfig.copy(customCloudApiBaseUrl = cleaned))
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("保存", maxLines = 1, softWrap = false)
                }
            }
        }

        if (connectivityMessage != null) {
            XPeachInsetDivider(verticalPadding = 8.dp)
            Text(
                text = connectivityMessage.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = if (connectivityOk == true) {
                    XPeachTheme.readablePrimary
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5. 连通性测速与恢复默认按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                enabled = !isTestingConnectivity,
                onClick = {
                    scope.launch {
                        isTestingConnectivity = true
                        connectivityMessage = "正在测试「${proxyConfig.statusSummaryLabel()}」连接..."
                        connectivityOk = true
                        val report = GitHubReleaseUpdater.testProxyConnectivity(proxyConfig)
                        isTestingConnectivity = false
                        connectivityOk = report.reachable
                        connectivityMessage = report.detailMessage
                    }
                },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (isTestingConnectivity) "正在测试..." else "测试连接"
                )
            }

            OutlinedButton(
                onClick = {
                    connectivityMessage = null
                    onResetProxyConfig()
                },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("恢复默认")
            }
        }
    }
}

