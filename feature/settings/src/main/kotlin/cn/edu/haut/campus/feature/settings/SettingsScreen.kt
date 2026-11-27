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
import cn.edu.haut.campus.feature.settings.root.ui.SyncHistoryDialog
import cn.edu.haut.campus.feature.settings.root.update.SettingsUpdateRouteSection
import cn.edu.haut.campus.feature.settings.root.home.SettingsHomeRouteSection
import cn.edu.haut.campus.feature.settings.root.academic.SettingsAcademicRouteSection
import cn.edu.haut.campus.feature.settings.root.theme.SettingsThemeRouteSection
import cn.edu.haut.campus.feature.settings.root.data.SettingsDataRouteSection
import cn.edu.haut.campus.feature.settings.root.network.SettingsNetworkRouteSection
import cn.edu.haut.campus.feature.settings.state.SettingsTransientResetRegistry
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
    val scrollState = rememberScrollState()
    val transientResetRegistry = remember { SettingsTransientResetRegistry() }
    // 拆分前同步/资料/更新等 remember 都挂在根 SettingsScreen；底栏切换导致根页退出时才统一重置。
    DisposableEffect(repository, transientResetRegistry) {
        onDispose { transientResetRegistry.resetAll() }
    }
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

    val importAudits by repository.importAuditsFlow.collectAsState(initial = emptyList())
    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val calendarOverrides by repository.calendarOverridesFlow.collectAsState(initial = emptyList())

    val today = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
    val resolvedWeekInfo = remember(today, currentTerm, calendarOverrides) {
        currentTerm?.let { TeachingWeekCalculator.resolveDate(today, it, calendarOverrides) }
    }
    val currentTeachingWeek = resolvedWeekInfo?.teachingWeek ?: 1

    // 各类弹窗与折叠状态
    var showImportHistoryDialog by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf<String?>(null) }

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
                    onRefreshWidgets = { WidgetRefreshCoordinator.requestImmediateRefreshAll(context) },
                    onShowImportHistory = { showImportHistoryDialog = true },
                    transientResetRegistry = transientResetRegistry
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

            SettingsDataRouteSection(
                repository = repository,
                onBack = onBack,
                isHautCampus = activeSchool.id == SchoolDirectory.HAUT_SCHOOL_ID,
                onFeedback = { feedbackText = it },
                onRefreshWidgets = { WidgetRefreshCoordinator.requestImmediateRefreshAll(context) }
            )

            // 8.5 网络代理与云服务加速配置
            SettingsNetworkRouteSection(
                repository = repository,
                onFeedback = { feedbackText = it }
            )

            SettingsUpdateRouteSection(
                repository = repository,
                currentInstalledVersion = currentInstalledVersion,
                transientResetRegistry = transientResetRegistry
            )
            }
                }
            }

            // 预留底部悬浮胶囊导航栏高度
            Spacer(modifier = Modifier.height(96.dp))
        }
    }

    if (showImportHistoryDialog) {
        SyncHistoryDialog(
            importAudits = importAudits,
            onDismiss = { showImportHistoryDialog = false }
        )
    }

}
