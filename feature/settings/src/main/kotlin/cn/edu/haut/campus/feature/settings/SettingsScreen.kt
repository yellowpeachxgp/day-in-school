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
import cn.edu.haut.campus.core.designsystem.WallpaperThemeEngine
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
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
    activeProfileLabel: String = "原有身份",
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
                fieldDefinitionNote = prefs.officialGpaNote ?: "来自教务官方平均学分绩点"
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
                "提醒、作息、调休、小组件、隐私与版本更新"
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
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
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
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
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
                // 一级页面：「我的」—— 仅展示个人必要核心数据
                // =========================================================================

                // 1. 数字学生证 / 个人学籍名片
                val cloudBinding by repository.cloudIdentityBindingFlow.collectAsState(initial = null)
                val parcelItems by repository.parcelItemsFlow.collectAsState(initial = emptyList())
                val extensionPlugins by repository.installedExtensionPluginsFlow.collectAsState(initial = emptyList())

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToSchoolCenter
                    ) {
                        Text("切换学校", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${activeSchool.displayName} · 扩展功能(${extensionPlugins.size})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    XPeachCard(
                        modifier = Modifier.weight(1f),
                        onClick = onNavigateToXpeachCloud
                    ) {
                        val peachFormatted = cloudBinding?.let {
                            "%.2f".format(it.peachBalanceMicropeach.toDouble() / 10_000.0)
                        }
                        Text(
                            text = if (peachFormatted != null) "云账号 · ${peachFormatted}🍑" else "云账号与代抢",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (cloudBinding != null) {
                                "已登录 ${cloudBinding?.displayName}"
                            } else {
                                "关机自动代抢图书馆座位"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                XPeachCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNavigateToParcelHub
                ) {
                    val waitingCount = parcelItems.count { it.status == "READY_FOR_PICKUP" }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("我的快递与取件码", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "绑定手机号自动同步快递 · 驿站取件码集中查看",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        XPeachStatusBadge(
                            text = if (waitingCount > 0) "待取 $waitingCount 件" else "共 ${parcelItems.size} 件",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                StudentDigitalIdCard(
                    profile = studentProfile,
                    schoolLabel = activeSchool.displayName,
                    privacyMasked = prefs.gpaPrivacyShieldEnabled,
                    lastAudit = importAudits.firstOrNull(),
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

                // 2. 个人 4 维核心数据看板
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
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
                }

                // 3. 学分分类结构统计
                if (attempts.isNotEmpty()) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text("已修学分构成", style = MaterialTheme.typography.titleMedium)
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (cat in CourseCategory.entries) {
                                val cred = categoryCredits[cat] ?: BigDecimal.ZERO
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                        .padding(vertical = 8.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = if (prefs.gpaPrivacyShieldEnabled) "**" else cred.stripTrailingZeros().toPlainString(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = cat.label,
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

                // 4. 本学期校历进度卡（纯展示与快速切换学期，调休管理归入二级设置菜单）
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    val totalWeeks = (currentTerm?.weekCount ?: 20).coerceAtLeast(1)
                    val weekProgress = (currentTeachingWeek.toFloat() / totalWeeks.toFloat()).coerceIn(0.05f, 1f)
                    val examStartDate = currentTerm?.startDate?.plusWeeks(18)
                    val daysToExam = examStartDate?.let { ChronoUnit.DAYS.between(today, it) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = currentTerm?.displayName ?: "2025-2026 学年",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (daysToExam != null && daysToExam > 0) {
                                    "第 $currentTeachingWeek / $totalWeeks 周 · 距期末周约 $daysToExam 天"
                                } else {
                                    "第 $currentTeachingWeek / $totalWeeks 周 · 学期进行中"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        XPeachStatusBadge(
                            text = "${(weekProgress * 100).toInt()}%",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LinearProgressIndicator(
                        progress = { weekProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    if (terms.size > 1) {
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

                // 5. 个人绑定与累计数据摘要卡
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
                val latestAudit = importAudits.firstOrNull()

                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Text("个人状态摘要", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isHautCampus) XPeachWell(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onNavigateToElectricity)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "绑定宿舍 · $roomSummaryTitle",
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
                                    text = "水电详情",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        XPeachWell(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onNavigateToFocus)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (activeFocusSession != null) {
                                            "自习专注 · 进行中（${activeFocusSession?.durationMinutes}分钟）"
                                        } else {
                                            "自习专注记录"
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
                                    text = "进入计时",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        XPeachWell(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showImportHistoryDialog = true }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "教务数据同步状态",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (latestAudit != null) {
                                            "最近同步 ${AUDIT_TIME_FMT.format(latestAudit.importedAt)} · 已存 ${courses.size} 门课 / ${attempts.size} 条成绩"
                                        } else {
                                            "尚未同步教务数据 · 点击查看记录"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "同步日志",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
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
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "学期、调休与课表互通",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "切换当前学期、管理法定节假日调休及导入导出课表文件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (prefs.timetableInterchangeEnabled) {
                            XPeachFilterPill(
                                label = "课表互通 (.md/.json)",
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
                            onClick = { showAddCalendarOverrideDialog = true }
                        )
                    }

                    if (calendarOverrides.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (ov in calendarOverrides.sortedBy { it.date }) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
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
                                        modifier = Modifier.size(28.dp)
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

                // 6. 桌面小组件中心
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Text("桌面小组件", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (term in terms) {
                        XPeachFilterPill(
                            label = term.id,
                            selected = term.id == currentTerm?.id,
                            onClick = {
                                scope.launch {
                                    repository.selectCurrentTerm(term.id)
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    feedbackText = "已切换至 ${term.displayName}"
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(
                    thickness = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
                Spacer(modifier = Modifier.height(6.dp))

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
                                    repository.saveWidgetConfig(
                                        WidgetInstanceConfig(
                                            appWidgetId = id,
                                            widgetType = WidgetType.NEXT_CLASS_2X2,
                                            themeStyle = WidgetThemeStyle.DYNAMIC_SYSTEM,
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
                                    repository.saveWidgetConfig(
                                        WidgetInstanceConfig(
                                            appWidgetId = id,
                                            widgetType = WidgetType.NEXT_CLASS_2X2,
                                            themeStyle = WidgetThemeStyle.DYNAMIC_SYSTEM,
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

                Spacer(modifier = Modifier.height(6.dp))

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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            requestPin("2×2 下一节", "cn.edu.haut.campus.widget.nextclass.NextClassAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 2×2 下一节", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            requestPin("4×2 今日课程", "cn.edu.haut.campus.widget.today.TodayAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 4×2 今日", maxLines = 1, softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            requestPin("4×4 时间轴", "cn.edu.haut.campus.widget.timeline.TimelineAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 4×4 时间轴", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            requestPin("4×4 周课表", "cn.edu.haut.campus.widget.week.WeekAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 4×4 周课表", maxLines = 1, softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                FilledTonalButton(
                    onClick = {
                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        feedbackText = "已刷新全部桌面小组件。"
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("立即刷新全部小组件", maxLines = 1, softWrap = false)
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
                                TextButton(onClick = { editingPeriod = pt }) {
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
                            text = "课前提醒 · 稍后提醒 · 考试提醒",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachStatusBadge(
                        text = when {
                            !rCfg.enabled -> "已关闭"
                            !reminderPermSnapshot.canPostNotifications -> "无通知权限"
                            reminderPermSnapshot.exactAlarmAllowed && rCfg.preciseReminderPreferred -> "精确闹钟"
                            else -> "普通提醒"
                        },
                        containerColor = if (rCfg.enabled && reminderPermSnapshot.canPostNotifications) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (rCfg.enabled && reminderPermSnapshot.canPostNotifications) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
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
                            text = "按作息时间自动发送课前和考前通知，可在通知栏延后",
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
                    Spacer(modifier = Modifier.height(6.dp))
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
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

                Spacer(modifier = Modifier.height(8.dp))

                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = prefs.lastReminderReconcileSummary
                            ?: "待触发提醒：${activeReminderTasks.size} 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
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
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("刷新提醒", maxLines = 1, softWrap = false)
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
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("测试通知", maxLines = 1, softWrap = false)
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
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
            }

            // 7.5 主题、自定义背景图取色、调色盘与模块化卡片布局
            val wallpaperPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    scope.launch {
                        feedbackText = "正在解析背景图片并提取专属主题配色..."
                        WallpaperThemeEngine.importWallpaperAndExtractPalette(context, uri)
                            .onSuccess { result ->
                                repository.saveCustomWallpaperTheme(
                                    localPath = result.localFilePath,
                                    primarySeedArgb = result.primarySeedArgb,
                                    extractedSwatches = result.extractedSwatches
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                val hexStr = String.format("#%06X", 0xFFFFFF and result.primarySeedArgb.toInt())
                                feedbackText = "已应用自定义背景图并自动取色 ($hexStr)"
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
                onToggleDynamicColor = { enabled ->
                    scope.launch { repository.setUseDynamicColor(enabled) }
                },
                onSelectPalette = { key, seedArgb ->
                    scope.launch {
                        repository.setThemePalette(key, seedArgb)
                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        feedbackText = "已应用主题配色并同步刷新桌面小组件"
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
                onClearWallpaper = {
                    scope.launch {
                        repository.clearCustomWallpaperTheme()
                        feedbackText = "已移除自定义背景图片"
                    }
                },
                onUpdateCardLayout = { order, hidden, fullWidth ->
                    scope.launch {
                        repository.updateHomeCardLayout(order, hidden, fullWidth)
                        feedbackText = "已保存首页模块化卡片布局"
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
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    thickness = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
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
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重新进入引导页", maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = { showWipeConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清除个人数据", maxLines = 1, softWrap = false)
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
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
                    XPeachStatusBadge(
                        text = if (releaseInfoState?.hasNewerVersion == true) {
                            "有新版 ${releaseInfoState?.tagName}"
                        } else {
                            "v$currentInstalledVersion"
                        },
                        containerColor = if (releaseInfoState?.hasNewerVersion == true) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (releaseInfoState?.hasNewerVersion == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
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

                Spacer(modifier = Modifier.height(8.dp))

                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = releaseStatusMessage
                            ?: if (isCheckingRelease) "正在通过「${prefs.networkProxyConfig.statusSummaryLabel()}」检查新版本..."
                            else "当前版本：v$currentInstalledVersion · Android ${Build.VERSION.RELEASE}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (releaseInfoState?.hasNewerVersion == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    releaseInfoState?.let { info ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "最新版本：${info.releaseTitle} (${info.tagName}) · 通道：${info.resolvedViaRoute}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (info.changelog.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
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
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { apkDownloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
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
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isCheckingRelease) "正在检查..." else "检查更新",
                            maxLines = 1,
                            softWrap = false
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
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = when {
                                isDownloadingApk -> "正在下载..."
                                downloadedApkFile?.exists() == true -> "安装更新"
                                releaseInfoState?.hasNewerVersion == true -> "下载新版本"
                                else -> "下载安装包"
                            },
                            maxLines = 1,
                            softWrap = false
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

        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("编辑个人信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("姓名 / 昵称") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = schoolIdInput,
                        onValueChange = { schoolIdInput = it },
                        label = { Text("学号") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = majorInput,
                        onValueChange = { majorInput = it },
                        label = { Text("学院 / 专业") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = yearInput,
                        onValueChange = { yearInput = it },
                        label = { Text("入学年份 (如 2023)") },
                        singleLine = true
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
                    shape = RoundedCornerShape(8.dp)
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

        AlertDialog(
            onDismissRequest = { showAddCalendarOverrideDialog = false },
            title = { Text("新增调休或停课日期") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateIso,
                        onValueChange = { dateIso = it },
                        label = { Text("目标日期 (YYYY-MM-DD)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("事由名称 (如 校运会停课 / 国庆调休)") },
                        singleLine = true
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
                            singleLine = true
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
                    shape = RoundedCornerShape(8.dp)
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
        AlertDialog(
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (audit in importAudits.take(8)) {
                            XPeachWell(modifier = Modifier.fillMaxWidth()) {
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

        AlertDialog(
            onDismissRequest = { editingPeriod = null },
            title = { Text("第 ${ptToEdit.periodNo} 节时间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startIso,
                        onValueChange = { startIso = it },
                        label = { Text("开始 (HH:mm)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endIso,
                        onValueChange = { endIso = it },
                        label = { Text("结束 (HH:mm)") },
                        singleLine = true
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
                    shape = RoundedCornerShape(8.dp)
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
        AlertDialog(
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
                    shape = RoundedCornerShape(8.dp)
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

        AlertDialog(
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
                    shape = RoundedCornerShape(8.dp)
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

        AlertDialog(
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
                    shape = RoundedCornerShape(8.dp)
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
private fun StudentDigitalIdCard(
    profile: StudentProfile?,
    schoolLabel: String,
    privacyMasked: Boolean,
    lastAudit: cn.edu.haut.campus.core.model.ImportAudit?,
    onEditProfile: () -> Unit,
    onSyncNow: () -> Unit
) {
    val displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: "未设置姓名"
    val rawSchoolId = profile?.schoolId?.takeIf { it.isNotBlank() } ?: "同步教务后自动显示学号"
    val displaySchoolId = if (privacyMasked && rawSchoolId.length >= 8) {
        rawSchoolId.take(4) + "****" + rawSchoolId.takeLast(3)
    } else {
        rawSchoolId
    }
    val majorText = buildString {
        append(profile?.major?.takeIf { it.isNotBlank() } ?: schoolLabel)
        profile?.enrollmentYear?.let { append(" · ${it}级") }
    }

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
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
                // 简约圆角头像徽章
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayName.take(1),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        XPeachStatusBadge(
                            text = if (profile != null) "已绑定" else "未同步",
                            containerColor = if (profile != null) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (profile != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Text(
                        text = displaySchoolId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = majorText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            XPeachIconButton(
                icon = Icons.Default.Edit,
                contentDescription = "编辑资料",
                onClick = onEditProfile
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(
            thickness = 0.6.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (lastAudit != null) {
                    "上次同步：${AUDIT_TIME_FMT.format(lastAudit.importedAt)}"
                } else {
                    "尚未同步教务数据"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            XPeachFilterPill(
                label = "同步教务",
                selected = true,
                onClick = onSyncNow
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
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
    "LIBRARY_CATALOG" to "馆藏检索",
    "EMPTY_CLASSROOM" to "空教室查询",
    "CAMPUS_COMMUNITY" to "社团与频道"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePaletteAndCardLayoutSection(
    prefs: AppPreferencesState,
    onSelectThemeMode: (String) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onSelectPalette: (String, Long?) -> Unit,
    onPickWallpaperImage: () -> Unit,
    onUpdateWallpaperParams: (alpha: Float?, blurDp: Float?, cardAlpha: Float?) -> Unit,
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
    var hue by remember(prefs.customThemeSeedArgb) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(prefs.customThemeSeedArgb) { mutableFloatStateOf(initialHsv[1].coerceIn(0.2f, 1f)) }
    var brightness by remember(prefs.customThemeSeedArgb) { mutableFloatStateOf(initialHsv[2].coerceIn(0.3f, 1f)) }

    val customColorInt = remember(hue, saturation, brightness) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
    }
    val customHexText = remember(customColorInt) {
        String.format("#%06X", 0xFFFFFF and customColorInt)
    }
    var hexInput by remember(customHexText) { mutableStateOf(customHexText) }

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "主题、壁纸与调色盘",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "支持自定义背景图智能取色、模糊透明度调节与自由调色盘",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val currentPaletteLabel = when {
                !prefs.customWallpaperPath.isNullOrBlank() -> "自定义壁纸"
                prefs.useDynamicColor -> "系统取色"
                prefs.themePaletteKey == "CUSTOM" -> customHexText
                else -> PresetThemePalette.fromKey(prefs.themePaletteKey)?.label ?: "工大蓝"
            }
            XPeachStatusBadge(
                text = currentPaletteLabel,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. 深浅色外观模式切换
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
                        onClick = { onSelectThemeMode(modeKey) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1.5 自定义背景图片上传、自动取色与模糊度/透明度控制
        XPeachWell(modifier = Modifier.fillMaxWidth()) {
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
                        text = "自定义背景图片主题",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (prefs.customWallpaperPath.isNullOrBlank()) {
                            "上传相册图片作为全局背景，按钮、菜单与卡片模板自动提取主题色"
                        } else {
                            "已启用自定义背景图 · 按钮、菜单与卡片模板已同步壁纸配色"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!prefs.customWallpaperPath.isNullOrBlank()) {
                        XPeachFilterPill(
                            label = "移除",
                            selected = false,
                            onClick = onClearWallpaper
                        )
                    }
                    XPeachFilterPill(
                        label = if (prefs.customWallpaperPath.isNullOrBlank()) "上传背景图" else "更换图片",
                        selected = !prefs.customWallpaperPath.isNullOrBlank(),
                        onClick = onPickWallpaperImage
                    )
                }
            }

            if (!prefs.customWallpaperPath.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))

                if (prefs.extractedWallpaperColors.isNotEmpty()) {
                    Text(
                        text = "从背景图自动提取的配色（点击可切换按钮与菜单色调）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (swatchArgb in prefs.extractedWallpaperColors) {
                            val isSelectedSwatch = prefs.themePaletteKey == "CUSTOM" &&
                                (prefs.customThemeSeedArgb and 0xFFFFFFL) == (swatchArgb and 0xFFFFFFL)
                            val swatchHex = String.format("#%06X", 0xFFFFFF and swatchArgb.toInt())
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (isSelectedSwatch) Color(swatchArgb).copy(alpha = 0.18f)
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                    )
                                    .border(
                                        width = if (isSelectedSwatch) 1.5.dp else 0.6.dp,
                                        color = if (isSelectedSwatch) Color(swatchArgb) else MaterialTheme.colorScheme.outlineVariant,
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
                                        .background(Color(swatchArgb))
                                )
                                Text(
                                    text = swatchHex,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelectedSwatch) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelectedSwatch) Color(swatchArgb) else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = "背景模糊度 (${localWallpaperBlur.toInt()} dp)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = localWallpaperBlur,
                    onValueChange = {
                        localWallpaperBlur = it
                        onUpdateWallpaperParams(null, it, null)
                    },
                    valueRange = 0f..30f
                )

                Text(
                    text = "背景图片透明度 (${(localWallpaperAlpha * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = localWallpaperAlpha,
                    onValueChange = {
                        localWallpaperAlpha = it
                        onUpdateWallpaperParams(it, null, null)
                    },
                    valueRange = 0.10f..1.0f
                )

                Text(
                    text = "按钮菜单与卡片背景模板通透度 (${(localCardAlpha * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = localCardAlpha,
                    onValueChange = {
                        localCardAlpha = it
                        onUpdateWallpaperParams(null, null, it)
                    },
                    valueRange = 0.50f..0.98f
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. 8款精选预设主题色卡矩阵
        Text(
            text = "精选主题配色",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowPalettes in PresetThemePalette.entries.chunked(4)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (palette in rowPalettes) {
                        val isSelected = !prefs.useDynamicColor && prefs.themePaletteKey == palette.key
                        val swatchColor = Color(palette.seedArgb)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) swatchColor.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.6.dp,
                                    color = if (isSelected) swatchColor else Color.Transparent,
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
                                    .background(swatchColor)
                            )
                            Text(
                                text = palette.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) swatchColor else MaterialTheme.colorScheme.onSurface,
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
                        text = "拖动色相、饱和度与明度滑块或输入色号定制专属主题",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            XPeachFilterPill(
                label = if (expandCustomPalette) "收起" else "调色盘",
                selected = prefs.themePaletteKey == "CUSTOM" && !prefs.useDynamicColor,
                onClick = { expandCustomPalette = !expandCustomPalette }
            )
        }

        AnimatedVisibility(visible = expandCustomPalette) {
            XPeachWell(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
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
                    text = "色相 Hue (${hue.toInt()}°)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Slider(
                    value = hue,
                    onValueChange = { hue = it },
                    valueRange = 0f..360f
                )

                Text(
                    text = "饱和度 Saturation (${(saturation * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0.20f..1f
                )

                Text(
                    text = "明度 Brightness (${(brightness * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = 0.30f..0.95f
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
                                    hue = arr[0]
                                    saturation = arr[1].coerceIn(0.2f, 1f)
                                    brightness = arr[2].coerceIn(0.3f, 0.95f)
                                }
                            }
                        },
                        label = { Text("HEX 色号 (#RRGGBB)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val seedLong = 0xFF000000L or (customColorInt.toLong() and 0xFFFFFFL)
                            onSelectPalette("CUSTOM", seedLong)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(customColorInt)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("应用配色", color = Color.White, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(
            thickness = 0.6.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(10.dp))

        // 4. 首页模块化卡片排序与显隐管理
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "首页模块化卡片布局",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val visibleCount = (DEFAULT_HOME_CARD_ORDER.size - prefs.hiddenHomeCards.size).coerceAtLeast(0)
                Text(
                    text = "当前已启用 $visibleCount / ${DEFAULT_HOME_CARD_ORDER.size} 个板块 · 支持调整顺序与通栏/双列尺寸",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            XPeachFilterPill(
                label = if (expandCardManager) "收起" else "调整卡片",
                selected = expandCardManager,
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
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                currentOrder.forEachIndexed { index, cardId ->
                    val title = labelMap[cardId] ?: cardId
                    val isVisible = cardId !in prefs.hiddenHomeCards
                    val isFull = cardId in prefs.fullWidthHomeCards

                    XPeachWell(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}. $title",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isVisible) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                XPeachFilterPill(
                                    label = if (isFull) "通栏" else "双列",
                                    selected = isFull,
                                    onClick = {
                                        val nextFull = if (isFull) prefs.fullWidthHomeCards - cardId else prefs.fullWidthHomeCards + cardId
                                        onUpdateCardLayout(currentOrder, prefs.hiddenHomeCards, nextFull)
                                    }
                                )
                                XPeachFilterPill(
                                    label = if (isVisible) "显示" else "隐藏",
                                    selected = isVisible,
                                    onClick = {
                                        val nextHidden = if (isVisible) prefs.hiddenHomeCards + cardId else prefs.hiddenHomeCards - cardId
                                        onUpdateCardLayout(currentOrder, nextHidden, prefs.fullWidthHomeCards)
                                    }
                                )
                                XPeachFilterPill(
                                    label = "↑",
                                    selected = false,
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
                }
                TextButton(
                    onClick = onResetCardLayout,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("恢复默认卡片布局")
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
                    text = "网络代理与云服务加速",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "预置镜像加速、自定义反代、HTTP/SOCKS5 代理与云服务端点",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            XPeachStatusBadge(
                text = proxyConfig.statusSummaryLabel(),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary
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

        Spacer(modifier = Modifier.height(10.dp))

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
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                        )
                                        .border(
                                            width = if (selected) 1.5.dp else 0.6.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
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
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "自定义反向代理 URL 前缀（支持 Cloudflare Worker / 自建 Nginx 反代）",
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
                            label = { Text("反代前缀 (如 https://gh.example.com/)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val normalized = NetworkProxyConfig.normalizePrefixUrl(customPrefixInput)
                                customPrefixInput = normalized
                                connectivityMessage = null
                                onSaveProxyConfig(proxyConfig.copy(customReverseProxyPrefix = normalized))
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("保存", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            ProxyMode.HTTP_PROXY, ProxyMode.SOCKS_PROXY -> {
                XPeachWell(modifier = Modifier.fillMaxWidth()) {
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
                            label = { Text("主机 Host") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("端口 Port") },
                            singleLine = true,
                            modifier = Modifier.weight(0.9f)
                        )
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
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("应用", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            ProxyMode.DIRECT -> {
                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "当前使用直连模式访问 GitHub 与云服务；建议保持下方「主线路异常时自动切换备用镜像」开启以确保无代理环境下仍可正常下载更新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. 自动回退镜像开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("主线路异常时自动切换备用镜像", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "首选节点连接超时或失败时，自动轮询其余预置镜像线路",
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
                Text("云服务 API 网关地址", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = proxyConfig.customCloudApiBaseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            XPeachFilterPill(
                label = if (expandCloudEndpoint) "收起" else "自定义网关",
                selected = expandCloudEndpoint,
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
                    label = { Text("云服务 Base URL") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val cleaned = cloudEndpointInput.trim().ifBlank { NetworkProxyConfig.DEFAULT_CLOUD_API_BASE_URL }
                        cloudEndpointInput = cleaned
                        onSaveProxyConfig(proxyConfig.copy(customCloudApiBaseUrl = cleaned))
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("保存", maxLines = 1, softWrap = false)
                }
            }
        }

        if (connectivityMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            XPeachWell(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = connectivityMessage.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connectivityOk == true) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5. 连通性测速与恢复默认按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                enabled = !isTestingConnectivity,
                onClick = {
                    scope.launch {
                        isTestingConnectivity = true
                        connectivityMessage = "正在测试「${proxyConfig.statusSummaryLabel()}」连通性..."
                        connectivityOk = true
                        val report = GitHubReleaseUpdater.testProxyConnectivity(proxyConfig)
                        isTestingConnectivity = false
                        connectivityOk = report.reachable
                        connectivityMessage = report.detailMessage
                    }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (isTestingConnectivity) "正在测速..." else "测试代理连通性",
                    maxLines = 1,
                    softWrap = false
                )
            }

            TextButton(
                onClick = {
                    connectivityMessage = null
                    onResetProxyConfig()
                }
            ) {
                Text("恢复默认", maxLines = 1, softWrap = false)
            }
        }
    }
}

