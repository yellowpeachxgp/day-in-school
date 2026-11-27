package cn.edu.haut.campus.feature.settings

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
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
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.database.isPendingPickup
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
import cn.edu.haut.campus.core.designsystem.XPeachSwitch
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
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.HomeCardModule
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PresetLocalProxyClient
import cn.edu.haut.campus.core.model.PresetMirrorNode
import cn.edu.haut.campus.core.model.ProxyMode
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.core.model.ThemeVisualDefaults
import cn.edu.haut.campus.core.model.todayFocusMinutes
import cn.edu.haut.campus.core.model.totalFocusMinutes
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.core.reminder.ReminderPermissions
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import cn.edu.haut.campus.feature.settings.hub.ui.CampusHospitalPharmacyDialog
import cn.edu.haut.campus.feature.settings.root.ui.NetworkProxyAndCloudConfigSection
import cn.edu.haut.campus.feature.settings.root.ui.ProfileDisclaimerSection
import cn.edu.haut.campus.feature.settings.root.ui.ProfileMetricItem
import cn.edu.haut.campus.feature.settings.root.ui.StudentDigitalIdHeader
import cn.edu.haut.campus.feature.settings.root.ui.ThemePaletteAndCardLayoutSection
import cn.edu.haut.campus.feature.settings.root.update.SettingsUpdateRouteSection
import cn.edu.haut.campus.feature.settings.root.home.SettingsHomeRouteSection
import cn.edu.haut.campus.feature.settings.root.academic.SettingsAcademicRouteSection
import cn.edu.haut.campus.feature.settings.root.theme.SettingsThemeRouteSection
import cn.edu.haut.campus.feature.settings.root.network.SettingsNetworkRouteSection
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
import kotlin.math.abs

private val AUDIT_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

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
    onNavigateToCloudSeatBooking: () -> Unit = onNavigateToLibrarySeats,
    onNavigateToFocus: () -> Unit = {},
    onNavigateToEmptyClassrooms: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToCompetitions: () -> Unit = onNavigateToCommunity,
    onNavigateToFeedbackChannels: () -> Unit = onNavigateToCommunity,
    onNavigateToMoments: () -> Unit = onNavigateToCommunity,
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
    val libraryBookingPlans by repository.libraryBookingPlansFlow.collectAsState(initial = emptyList())
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
    var expandPeriodTable by remember { mutableStateOf(false) }

    // GitHub Release 自动版本检测与在线拉取直装状态
    val currentInstalledVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.18.0" }
            .getOrDefault("0.18.0")
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
                SettingsHomeRouteSection(
                    repository = repository,
                    activeSchool = activeSchool,
                    onNavigateToImporter = onNavigateToImporter,
                    onNavigateToElectricity = onNavigateToElectricity,
                    onNavigateToLibraryCatalog = onNavigateToLibraryCatalog,
                    onNavigateToLibrarySeats = onNavigateToLibrarySeats,
                    onNavigateToCloudSeatBooking = onNavigateToCloudSeatBooking,
                    onNavigateToFocus = onNavigateToFocus,
                    onNavigateToEmptyClassrooms = onNavigateToEmptyClassrooms,
                    onNavigateToCommunity = onNavigateToCommunity,
                    onNavigateToCompetitions = onNavigateToCompetitions,
                    onNavigateToFeedbackChannels = onNavigateToFeedbackChannels,
                    onNavigateToMoments = onNavigateToMoments,
                    onNavigateToSchoolCenter = onNavigateToSchoolCenter,
                    onNavigateToParcelHub = onNavigateToParcelHub,
                    onNavigateToXpeachCloud = onNavigateToXpeachCloud,
                    onFeedback = { feedbackText = it },
                    onRefreshWidgets = { WidgetRefreshCoordinator.requestImmediateRefreshAll(context) }
)
            } else {
                // =========================================================================
                // 二级页面：「设置」—— 提醒、调休、作息、小组件、隐私安全与版本更新
                // =========================================================================

                SettingsAcademicRouteSection(
                    repository = repository,
                    onNavigateToImporter = onNavigateToImporter,
                    onFeedback = { feedbackText = it },
                    onRefreshWidgets = { WidgetRefreshCoordinator.requestImmediateRefreshAll(context) }
                )

            SettingsThemeRouteSection(
                repository = repository,
                onFeedback = { feedbackText = it },
                onRefreshWidgets = { WidgetRefreshCoordinator.requestImmediateRefreshAll(context) }
            )

            // 8. 外观、隐私安全与数据管理
            var showHospitalDialogInSettings by remember { mutableStateOf(false) }
            var animeLauncherAliasEnabled by remember(context) {
                mutableStateOf(
                    runCatching {
                        cn.edu.haut.campus.core.designsystem.LauncherIconAliasReconciler.reconcile(context)
                    }.getOrDefault(false)
                )
            }
            if (showHospitalDialogInSettings) {
                CampusHospitalPharmacyDialog(
                    onDismiss = { showHospitalDialogInSettings = false },
                    onOpenUrl = { _, url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }
                )
            }
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("外观、隐私与数据", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("切换二次元猫娘桌面图标", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "默认使用工大深蓝钟楼自适应图标，开启后切换为二次元猫娘桌面图标（互斥单图标，不影响桌面小组件点击）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachSwitch(
                        checked = animeLauncherAliasEnabled,
                        onCheckedChange = { enabled ->
                            runCatching {
                                cn.edu.haut.campus.core.designsystem.LauncherIconAliasReconciler.applyUserIconSelection(
                                    context = context,
                                    enableAnimeIcon = enabled
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                animeLauncherAliasEnabled = enabled
                                feedbackText = if (enabled) {
                                    "已切换为二次元猫娘桌面图标（返回桌面稍等片刻即可刷新）"
                                } else {
                                    "已恢复默认工大深蓝钟楼桌面图标"
                                }
                            }.onFailure {
                                feedbackText = "当前系统桌面不支持动态切换应用图标别名"
                            }
                        }
                    )
                }
                if (isHautCampus) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("校医院药品价格公示与医保参考", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "查询 ${cn.edu.haut.campus.core.domain.CampusHospitalPharmacyCatalog.ITEMS.size} 项西药/中成药公示单价与医保报销注意事项",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        XPeachFilterPill(
                            label = "查看目录",
                            selected = false,
                            compact = true,
                            onClick = { showHospitalDialogInSettings = true }
                        )
                    }
                }
                if (isHautCampus) Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("显示宿舍水电服务", style = MaterialTheme.typography.bodyMedium)
                    XPeachSwitch(
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
                    XPeachSwitch(
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
                    XPeachSwitch(
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
                    XPeachSwitch(
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
                    XPeachSwitch(
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
                    XPeachSwitch(
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
                        Text("隐藏成绩与学号", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "隐藏绩点和分数，并禁止在应用内截屏",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachSwitch(
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
            SettingsNetworkRouteSection(
                repository = repository,
                onFeedback = { feedbackText = it }
            )

            SettingsUpdateRouteSection(
                repository = repository,
                currentInstalledVersion = currentInstalledVersion
            )
            }
                }
            }

            // 预留底部悬浮胶囊导航栏高度
            Spacer(modifier = Modifier.height(96.dp))
        }
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
}

