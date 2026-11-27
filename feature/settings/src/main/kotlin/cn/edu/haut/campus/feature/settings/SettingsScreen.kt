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
    var isCheckingRelease by remember { mutableStateOf(false) }
    var releaseInfoState by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var isDownloadingApk by remember { mutableStateOf(false) }
    var apkDownloadProgress by remember { mutableStateOf(0f) }
    var releaseStatusMessage by remember { mutableStateOf<String?>(null) }
    var downloadedApkFile by remember { mutableStateOf<File?>(null) }
    var showReleaseNotesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(prefs.networkProxyConfig.mode, prefs.networkProxyConfig.presetMirrorKey) {
        isCheckingRelease = true
        val checkRes = GitHubReleaseUpdater.checkLatestRelease(
            currentVersionName = currentInstalledVersion,
            proxyConfig = prefs.networkProxyConfig
        )
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

                    val activeExamTermId = currentTerm?.id?.takeIf { it.isNotBlank() }
                        ?: cn.edu.haut.campus.core.model.AcademicTermResolver.defaultTermId()
                    val activeTermExamCount = remember(examSchedules, activeExamTermId) {
                        examSchedules.count { it.termId == activeExamTermId }
                    }
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
                            value = "${activeTermExamCount}场",
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
                    else -> "绑定寝室水电"
                }
                val todayFocusMinutes = remember(completedFocusSessions, today) {
                    completedFocusSessions.todayFocusMinutes(today, HAUT_ZONE_ID)
                }
                val totalFocusMinutes = remember(completedFocusSessions) {
                    completedFocusSessions.totalFocusMinutes()
                }
                val waitingCount = parcelItems.count { it.isPendingPickup() }
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
                                text = "取件码与物流进度",
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
                                text = "今日 ${todayFocusMinutes} 分钟 · 累计 ${totalFocusMinutes} 分钟",
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
                            .clickable(onClick = onNavigateToMoments)
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
                                text = "失物招领与二手教材",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "寻物招领 (${prefs.campusMomentsSnapshot.posts.size}) · 二手课本 (${prefs.campusMomentsSnapshot.bookListings.size}) · 防丢码",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "互助广场 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToCommunity)
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
                                text = "合作社团与交流群",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "共 ${prefs.campusCommunityCatalog.clubs.size} 个社团 · 活动介绍与交流群",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "社团名录 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToFeedbackChannels)
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
                                text = "用户交流群、QQ频道与公众号",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "问题反馈、防冒充官方声明与校内权威公众号检索",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "反馈与频道 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToCompetitions)
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
                                text = "学科竞赛指南",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "共 ${prefs.campusCommunityCatalog.competitions.size} 项赛事 · 专业筛选与赛事官网",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "竞赛指南 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    val activeCloudOrLocalPlans = libraryBookingPlans.count {
                        it.status !in setOf("CANCELLED", "CONFIRMED", "FAILED", "EXPIRED", "SCHOOL_CANCELLED")
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToCloudSeatBooking)
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
                                text = "图书馆选座与云端抢座",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (activeCloudOrLocalPlans > 0) {
                                    "当前有 $activeCloudOrLocalPlans 项待执行抢座计划 · 支持关机离线准点抢座"
                                } else {
                                    "07:00 云端离线准点自动抢座、连续多日连抢与网页选座"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (activeCloudOrLocalPlans > 0) {
                            XPeachStatusBadge(
                                text = "待执行 $activeCloudOrLocalPlans",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = XPeachTheme.readablePrimary
                            )
                        } else {
                            Text(
                                text = "云端抢座 ›",
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
                                text = "云账号与扩展",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (cloudBinding != null) {
                                    "已登录 ${cloudBinding?.displayName} · 已启用 $enabledPluginCount 个扩展"
                                } else {
                                    "登录 XPeach 云账号开启云端抢座与扩展功能"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (peachFormatted != null) {
                            XPeachStatusBadge(
                                text = "🍑 ${peachFormatted}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = XPeachTheme.readablePrimary
                            )
                        } else {
                            Text(
                                text = "去登录 ›",
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
                                text = "教务同步记录",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (latestAudit != null) {
                                    "最近同步 ${AUDIT_TIME_FMT.format(latestAudit.importedAt)} · ${courses.size} 门课"
                                } else {
                                    "尚未同步教务数据"
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
                            Text("桌面小组件", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "点击卡片添加至桌面",
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
                        val ok = WidgetRefreshCoordinator.requestPinAppWidget(context, providerClass)
                        feedbackText = if (ok) {
                            "请在系统弹窗中确认添加「$label」"
                        } else {
                            "请长按手机桌面空白处添加小组件"
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
                                    requestPin("2×2 下一节", WidgetRefreshCoordinator.PROVIDER_NEXT_CLASS_2X2)
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
                                    requestPin("4×2 今日课程", WidgetRefreshCoordinator.PROVIDER_TODAY_4X2)
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
                                    requestPin("4×2 快递取件码", WidgetRefreshCoordinator.PROVIDER_PARCEL_4X2)
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
                                requestPin("4×4 时间轴", WidgetRefreshCoordinator.PROVIDER_TIMELINE_4X4)
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("添加 4×4 时间轴", maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = {
                                requestPin("4×4 周课表", WidgetRefreshCoordinator.PROVIDER_WEEK_4X4)
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
                        XPeachSwitch(
                            checked = prefs.widgetDefaultCompact,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateAllWidgetDefaults(compact = checked)
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
                        XPeachSwitch(
                            checked = prefs.widgetDefaultPrivacyMode,
                            onCheckedChange = { checked ->
                                scope.launch {
                                    repository.updateAllWidgetDefaults(privacyMode = checked)
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
                    XPeachSwitch(
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
                    XPeachSwitch(
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
                    XPeachSwitch(
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
                    XPeachSwitch(
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
                    XPeachSwitch(
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
                        val chosenStyle = UiDesignStyle.fromKey(styleKey)
                        feedbackText = "已切换为「${chosenStyle.label}」视觉风格"
                    }
                },
                onApplyCuratedPreset = { styleKey, paletteKey, title ->
                    scope.launch {
                        repository.applyCuratedThemePreset(styleKey, paletteKey)
                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        feedbackText = "已应用「$title」清新套装"
                    }
                },
                onToggleDynamicColor = { enabled ->
                    scope.launch { repository.setUseDynamicColor(enabled) }
                },
                onSelectPalette = { key, seedArgb ->
                    scope.launch {
                        repository.setThemePalette(key, seedArgb)
                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        feedbackText = when (key) {
                            PresetThemePalette.LIQUID_GLASS.key -> "已启用 Liquid Glass 主题"
                            PresetThemePalette.HAUT_BLUE.key -> "已切换为工大校徽深蓝 (#053968)"
                            else -> "已更新主题配色"
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
                        feedbackText = "已恢复默认 Liquid Glass 参数"
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
                    XPeachSwitch(
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最新版本：${info.releaseTitle} (${info.tagName}) · 通道：${info.resolvedViaRoute}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (info.changelog.isNotBlank()) {
                                TextButton(
                                    onClick = { showReleaseNotesDialog = true },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("查看更新日志", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (info.changelog.isNotBlank()) {
                            XPeachWell(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showReleaseNotesDialog = true }
                            ) {
                                Text(
                                    text = "更新内容（${info.tagName}）：",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = info.changelog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                                    showReleaseNotesDialog = true
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

    val dialogReleaseInfo = releaseInfoState
    if (showReleaseNotesDialog && dialogReleaseInfo != null) {
        XPeachAlertDialog(
            onDismissRequest = { showReleaseNotesDialog = false },
            title = {
                Text(
                    text = if (dialogReleaseInfo.hasNewerVersion) {
                        "发现新版本 ${dialogReleaseInfo.tagName}"
                    } else {
                        "版本更新日志 ${dialogReleaseInfo.tagName}"
                    },
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (dialogReleaseInfo.hasNewerVersion) {
                            "当前版本 v$currentInstalledVersion → 最新版本 ${dialogReleaseInfo.tagName}（${dialogReleaseInfo.resolvedViaRoute}）"
                        } else {
                            "当前版本 v$currentInstalledVersion 已是最新（${dialogReleaseInfo.resolvedViaRoute}）"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = XPeachTheme.readablePrimary
                    )
                    XPeachWell(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = dialogReleaseInfo.changelog.ifBlank { "常规性能优化与体验改进。" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showReleaseNotesDialog = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (dialogReleaseInfo.hasNewerVersion) "去下载更新" else "知道了")
                }
            }
        )
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
}

