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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.academic.GitHubReleaseInfo
import cn.edu.haut.campus.core.academic.GitHubReleaseUpdater
import cn.edu.haut.campus.core.academic.HautSessionManager
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.GpaCalculator
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.CampusClubCategory
import cn.edu.haut.campus.core.model.CampusClubItem
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.PresetQqChannelItem
import cn.edu.haut.campus.core.model.StudentProfile
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
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
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
            title = if (onBack == null) "我的" else "设置",
            subtitle = "河南工业大学 · 莲花街校区",
            badgeText = resolvedWeekInfo?.teachingWeek?.let { "第 $it 周" },
            navigationIcon = if (onBack != null) {
                {
                    XPeachIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack
                    )
                }
            } else null,
            actions = {
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
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (feedbackText != null) {
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
                            text = feedbackText!!,
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

            // 1. 数字学生证 / 个人学籍名片
            StudentDigitalIdCard(
                profile = studentProfile,
                privacyMasked = prefs.gpaPrivacyShieldEnabled,
                lastAudit = importAudits.firstOrNull(),
                onEditProfile = { showEditProfileDialog = true },
                onSyncNow = {
                    if (prefs.hasSavedCredentials && !isDynamicSyncing) {
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

            // 2. 本学期学业与课表 4 维全景看板
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

            // 3. 校历学期进度环 & 调休/停课管理
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

            // 3.5 校园服务快捷入口（宿舍水电 / 馆藏查询 / 座位预约 / 自习专注 / 课表互通）
            if (prefs.utilitiesEnabled || prefs.libraryNativeEnabled || prefs.librarySeatEnabled || prefs.focusEnabled) {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "校园服务",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (prefs.timetableInterchangeEnabled) {
                            XPeachFilterPill(
                                label = "课表文件互通 (.md/.json)",
                                selected = false,
                                onClick = onNavigateToImporter
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val serviceEntries = buildList {
                        if (prefs.utilitiesEnabled) {
                            add(Triple("宿舍水电", "电费余量与每日趋势", onNavigateToElectricity))
                        }
                        if (prefs.libraryNativeEnabled) {
                            add(Triple("馆藏查询", "书目检索与在馆状态", onNavigateToLibraryCatalog))
                        }
                        if (prefs.librarySeatEnabled) {
                            add(Triple("座位预约", "图书馆选座与余位", onNavigateToLibrarySeats))
                        }
                        if (prefs.focusEnabled) {
                            add(Triple("自习专注", "倒计时与到点提醒", onNavigateToFocus))
                        }
                        add(Triple("空教室查询", "按节次反查空闲自习教室", onNavigateToEmptyClassrooms))
                        add(Triple("社团与频道", "32个学生社团与校园频道", onNavigateToCommunity))
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (rowItems in serviceEntries.chunked(2)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                for ((titleText, subText, action) in rowItems) {
                                    XPeachWell(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(onClick = action)
                                    ) {
                                        Text(
                                            text = titleText,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = subText,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (rowItems.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            // 5. 学分结构分类统计（当存在已导入成绩时展示）
            if (attempts.isNotEmpty()) {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Text("学分统计", style = MaterialTheme.typography.titleMedium)
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

            // 6. 学期切换与桌面小组件中心
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("学期与桌面小组件", style = MaterialTheme.typography.titleMedium)
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

            // 8. 外观、隐私安全与数据管理
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("外观、隐私与数据", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
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
                Row(
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
                Row(
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
                Row(
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
                            text = "检查新版本并下载安装包",
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
                            ?: if (isCheckingRelease) "正在检查新版本..."
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
                            text = "最新版本：${info.releaseTitle} (${info.tagName}) · ${info.publishedAt.ifBlank { "最新" }}",
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
                                releaseStatusMessage = "正在检查更新..."
                                val res = GitHubReleaseUpdater.checkLatestRelease(currentInstalledVersion)
                                isCheckingRelease = false
                                res.onSuccess { info ->
                                    releaseInfoState = info
                                    releaseStatusMessage = if (info.hasNewerVersion) {
                                        "发现新版本 ${info.tagName}，点击右侧按钮下载安装。"
                                    } else {
                                        "当前 v$currentInstalledVersion 已是最新版本。"
                                    }
                                }.onFailure { err ->
                                    releaseStatusMessage = "检查失败：${err.message ?: "请检查网络连接"}"
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
                                    releaseStatusMessage = "正在获取下载地址..."
                                    val targetInfo = releaseInfoState
                                        ?: GitHubReleaseUpdater.checkLatestRelease(currentInstalledVersion).getOrNull()
                                    if (targetInfo == null) {
                                        isDownloadingApk = false
                                        releaseStatusMessage = "获取版本信息失败，请检查网络后重试。"
                                        return@launch
                                    }
                                    releaseInfoState = targetInfo
                                    val dlRes = GitHubReleaseUpdater.downloadReleaseApk(
                                        context = context,
                                        releaseInfo = targetInfo,
                                        onProgress = { dl, total ->
                                            if (total > 0L) {
                                                apkDownloadProgress = dl.toFloat() / total.toFloat()
                                                val dlMb = String.format("%.1f", dl / (1024.0 * 1024.0))
                                                val totalMb = String.format("%.1f", total / (1024.0 * 1024.0))
                                                releaseStatusMessage =
                                                    "正在下载 ${targetInfo.tagName}：${(apkDownloadProgress * 100).toInt()}% ($dlMb / $totalMb MB)"
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
                                        releaseStatusMessage = "下载失败：${err.message ?: "请检查网络"}"
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
        append(profile?.major?.takeIf { it.isNotBlank() } ?: "河南工业大学 · 莲花街校区")
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
