package cn.edu.haut.campus

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.edu.haut.campus.core.academic.GitHubReleaseInfo
import cn.edu.haut.campus.core.academic.GitHubReleaseUpdater
import cn.edu.haut.campus.core.academic.syncSummaryText
import cn.edu.haut.campus.core.academic.HautLiveAcademicClient
import java.io.File
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.core.reminder.ReminderNotificationFactory
import cn.edu.haut.campus.core.reminder.AccountBackgroundScope
import cn.edu.haut.campus.core.reminder.focus.FocusCoordinator
import cn.edu.haut.campus.core.reminder.focus.FocusDeliveryTracker
import cn.edu.haut.campus.core.reminder.focus.FocusNotificationFactory
import cn.edu.haut.campus.feature.grades.GradesScreen
import cn.edu.haut.campus.feature.importer.ImporterScreen
import cn.edu.haut.campus.feature.map.CampusMapScreen
import cn.edu.haut.campus.feature.settings.CampusCommunityScreen
import cn.edu.haut.campus.feature.settings.DormElectricityScreen
import cn.edu.haut.campus.feature.settings.EmptyClassroomScreen
import cn.edu.haut.campus.feature.settings.LibraryCatalogScreen
import cn.edu.haut.campus.feature.settings.LibrarySeatBookingScreen
import cn.edu.haut.campus.feature.settings.ParcelHubScreen
import cn.edu.haut.campus.feature.settings.SettingsScreen
import cn.edu.haut.campus.feature.settings.SchoolCenterScreen
import cn.edu.haut.campus.feature.settings.StudyFocusScreen
import cn.edu.haut.campus.feature.settings.XPeachCloudAccountScreen
import cn.edu.haut.campus.feature.timetable.TimetableScreen
import cn.edu.haut.campus.feature.today.TodayScreen
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PrimaryBottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    TODAY("today", "今天", Icons.Default.Schedule),
    TIMETABLE("timetable", "课表", Icons.Default.CalendarViewWeek),
    GRADES("grades", "学业", Icons.Default.Assessment),
    CAMPUS("map", "校园", Icons.Default.Map),
    PROFILE("profile", "我的", Icons.Default.Person)
}

class MainActivity : ComponentActivity() {

    private var deepLinkTab by mutableStateOf<String?>(null)
    private var deepLinkRoomQuery by mutableStateOf<String?>(null)
    private var deepLinkOverlayRoute by mutableStateOf<String?>(null)
    private var pendingAccountIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingAccountIntent = intent?.let(::Intent)
        val sessions = AccountSessionCoordinator.getInstance(applicationContext)

        setContent {
            val sessionState by sessions.states.collectAsState()
            LaunchedEffect(sessions) {
                runCatching { sessions.initialize() }
            }
            when (val state = sessionState) {
                is AccountSessionState.Ready -> key(state.active.profileId, state.active.generation) {
                    val active = state.active
                    val prefs by active.repository.preferencesFlow.collectAsState(initial = null)
                    val readyPrefs = prefs
                    SideEffect {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                    LaunchedEffect(active.profileId, active.generation) {
                        pendingAccountIntent?.let { pending ->
                            extractWidgetIntent(pending, active)
                            pendingAccountIntent = null
                        }
                        FocusCoordinator.getInstance(applicationContext).restoreOrCompleteActiveSessionAsync()
                    }
                    if (readyPrefs == null) {
                        HautCampusTheme { AccountSessionStatusScreen("正在读取当前身份的数据") }
                    } else {
                        HautCampusTheme(
                            themeMode = readyPrefs.themeMode,
                            dynamicColor = readyPrefs.useDynamicColor,
                            themePaletteKey = readyPrefs.themePaletteKey,
                            customSeedArgb = readyPrefs.customThemeSeedArgb,
                            customWallpaperPath = readyPrefs.customWallpaperPath,
                            wallpaperAlpha = readyPrefs.wallpaperAlpha,
                            wallpaperBlurDp = readyPrefs.wallpaperBlurDp,
                            wallpaperCardAlpha = readyPrefs.wallpaperCardAlpha
                        ) {
                            HautCampusRootApp(
                                repository = active.repository,
                                prefs = readyPrefs,
                                activeSession = active,
                                sessions = sessions,
                                onSwitchProfile = { profileId ->
                                    pendingAccountIntent = null
                                    deepLinkTab = null
                                    deepLinkRoomQuery = null
                                    deepLinkOverlayRoute = null
                                    lifecycleScope.launch { runCatching { sessions.switchProfile(profileId) } }
                                },
                                externalTabRequest = deepLinkTab,
                                externalRoomQuery = deepLinkRoomQuery,
                                externalOverlayRequest = deepLinkOverlayRoute,
                                onConsumeExternalRequest = {
                                    deepLinkTab = null
                                    deepLinkRoomQuery = null
                                    deepLinkOverlayRoute = null
                                }
                            )
                        }
                    }
                }
                else -> {
                    SideEffect {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        if (state is AccountSessionState.Switching) {
                            deepLinkTab = null
                            deepLinkRoomQuery = null
                            deepLinkOverlayRoute = null
                        }
                    }
                    HautCampusTheme {
                        AccountSessionStatusScreen(
                            message = when (state) {
                                is AccountSessionState.Failure -> state.message
                                is AccountSessionState.Switching -> "正在安全切换身份"
                                else -> "正在准备本地数据"
                            },
                            onRetry = if (state is AccountSessionState.Failure) {
                                { lifecycleScope.launch { runCatching { sessions.initialize() } }; Unit }
                            } else null
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FocusDeliveryTracker.isAppInForeground = true
        if (AccountSessionCoordinator.getInstance(applicationContext).currentSnapshot() != null) {
            FocusCoordinator.getInstance(applicationContext).restoreOrCompleteActiveSessionAsync()
        }
    }

    override fun onPause() {
        FocusDeliveryTracker.isAppInForeground = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val active = AccountSessionCoordinator.getInstance(applicationContext).currentSnapshot()
        if (active == null) pendingAccountIntent = Intent(intent) else extractWidgetIntent(intent, active)
    }

    private fun extractWidgetIntent(intent: Intent?, active: ActiveSession) {
        if (intent == null) return
        val hasAccountScopedAction = intent.hasExtra(WidgetRefreshCoordinator.EXTRA_PROFILE_ID) ||
            intent.hasExtra(ReminderNotificationFactory.EXTRA_REMINDER_NAV_ACTION) ||
            intent.hasExtra(FocusNotificationFactory.EXTRA_OVERLAY_ROUTE)
        if (!hasAccountScopedAction) {
            pendingAccountIntent = null
            return
        }
        val accountScope = AccountBackgroundScope.read(intent)
        val widgetProfile = intent.getStringExtra(WidgetRefreshCoordinator.EXTRA_PROFILE_ID)
        val widgetGeneration = intent.getLongExtra(WidgetRefreshCoordinator.EXTRA_GENERATION, -1L)
        val widgetScopeValid = widgetProfile != null &&
            widgetProfile == active.profileId && widgetGeneration == active.generation
        val accountScopeValid = accountScope?.matches(active) == true
        val hasWidgetScope = widgetProfile != null
        val requiresAccountScope = intent.hasExtra(ReminderNotificationFactory.EXTRA_REMINDER_NAV_ACTION) ||
            intent.hasExtra(FocusNotificationFactory.EXTRA_OVERLAY_ROUTE)
        if ((hasWidgetScope && !widgetScopeValid) || (requiresAccountScope && !accountScopeValid)) {
            pendingAccountIntent = null
            return
        }

        val overlayRoute = intent?.getStringExtra(FocusNotificationFactory.EXTRA_OVERLAY_ROUTE)
        if (!overlayRoute.isNullOrBlank()) {
            deepLinkOverlayRoute = overlayRoute
        }
        val tab = intent?.getStringExtra(WidgetRefreshCoordinator.EXTRA_NAVIGATE_TAB)
        val room = intent?.getStringExtra(WidgetRefreshCoordinator.EXTRA_RAW_ROOM)
        if (!tab.isNullOrBlank()) {
            if (tab in setOf(
                    "parcel_hub",
                    "importer",
                    "library_seats",
                    "electricity",
                    "library_catalog",
                    "focus",
                    "empty_classrooms",
                    "community",
                    "xpeach_cloud",
                    "school_center"
                )
            ) {
                deepLinkOverlayRoute = tab
            } else {
                deepLinkTab = tab
            }
        }
        if (!room.isNullOrBlank()) {
            deepLinkRoomQuery = room
        }

        val reminderNavAction = intent?.getStringExtra(ReminderNotificationFactory.EXTRA_REMINDER_NAV_ACTION)
        if (!reminderNavAction.isNullOrBlank()) {
            val reminderRoom = intent.getStringExtra(ReminderNotificationFactory.EXTRA_REMINDER_ROOM_TEXT)
                ?.takeIf { it.isNotBlank() }
                ?: intent.getStringExtra(ReminderNotificationFactory.EXTRA_REMINDER_BUILDING_ID)
            when (reminderNavAction) {
                ReminderNotificationFactory.NAV_ACTION_OPEN_MAP -> {
                    deepLinkTab = PrimaryBottomTab.CAMPUS.route
                    if (!reminderRoom.isNullOrBlank()) {
                        deepLinkRoomQuery = reminderRoom
                    }
                }
                ReminderNotificationFactory.NAV_ACTION_OPEN_EXAMS -> {
                    deepLinkTab = PrimaryBottomTab.GRADES.route
                }
                ReminderNotificationFactory.NAV_ACTION_OPEN_TODAY -> {
                    deepLinkTab = PrimaryBottomTab.TODAY.route
                }
            }
            val reminderTaskId = intent.getStringExtra(ReminderNotificationFactory.EXTRA_REMINDER_TASK_ID)
            if (!reminderTaskId.isNullOrBlank() &&
                accountScopeValid
            ) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    ReminderCoordinator.getInstance(applicationContext).dismissTask(
                        taskId = reminderTaskId,
                        expectedGeneration = null
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountSessionStatusScreen(message: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (onRetry == null) CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.titleMedium)
        if (onRetry != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("重试读取本地数据") }
        }
    }
}

@Composable
fun HautCampusRootApp(
    repository: CampusRepository,
    prefs: AppPreferencesState,
    activeSession: ActiveSession,
    sessions: AccountSessionCoordinator,
    onSwitchProfile: (String) -> Unit,
    externalTabRequest: String?,
    externalRoomQuery: String?,
    externalOverlayRequest: String? = null,
    onConsumeExternalRequest: () -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf(PrimaryBottomTab.TODAY) }
    var overlayScreen by rememberSaveable { mutableStateOf<String?>(null) }
    var mapRoomQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var tabResetCounter by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    val isHautCampus = activeSession.school.id == SchoolDirectory.HAUT_SCHOOL_ID

    // 启动自动检测 GitHub Release 最新版本状态
    var detectedUpdateRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var hasCheckedStartupRelease by rememberSaveable { mutableStateOf(false) }
    var isDownloadingStartupApk by remember { mutableStateOf(false) }
    var startupDownloadProgress by remember { mutableStateOf(0f) }
    var startupDownloadStatusText by remember { mutableStateOf<String?>(null) }
    var downloadedStartupApkFile by remember { mutableStateOf<File?>(null) }

    // 打开应用时若已绑定教务凭据且开启了自动同步，后台自动静默更新最新课表、期初/期中/期末考试与成绩
    LaunchedEffect(prefs.hasCompletedOnboarding, prefs.autoSyncEnabled, prefs.hasSavedCredentials) {
        if (isHautCampus && prefs.hasCompletedOnboarding && prefs.autoSyncEnabled && prefs.hasSavedCredentials) {
            val res = repository.performDynamicAcademicSync(force = false)
            res?.onSuccess {
                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
            }
        }
    }

    // 打开应用时静默双向同步 XPeach 云端快照（🍑 余额、云端抢座、跨端包裹）及已绑定的小米快递取件码
    LaunchedEffect(repository, prefs.hasCompletedOnboarding) {
        if (prefs.hasCompletedOnboarding) {
            runCatching {
                val xiaomiAccounts = repository.getXiaomiAccounts()
                if (xiaomiAccounts.any { it.enabled && it.phones.isNotEmpty() }) {
                    repository.syncXiaomiExpressPackages()
                }
            }
            runCatching {
                repository.syncWithCampusCloud()
            }
            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
        }
    }

    // 打开应用时自动从 GitHub Release API 检测是否有新版本可直装更新
    LaunchedEffect(prefs.hasCompletedOnboarding, prefs.autoCheckUpdateEnabled) {
        if (prefs.hasCompletedOnboarding && prefs.autoCheckUpdateEnabled && !hasCheckedStartupRelease) {
            hasCheckedStartupRelease = true
            val currentVer = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.9.0"
            }.getOrDefault("0.9.0")
            GitHubReleaseUpdater.checkLatestRelease(
                currentVersionName = currentVer,
                proxyConfig = prefs.networkProxyConfig
            ).onSuccess { release ->
                if (release.hasNewerVersion) {
                    detectedUpdateRelease = release
                }
            }
        }
    }

    BackHandler(enabled = overlayScreen != null || currentTab != PrimaryBottomTab.TODAY) {
        if (overlayScreen != null) {
            overlayScreen = null
        } else {
            currentTab = PrimaryBottomTab.TODAY
        }
    }

    LaunchedEffect(externalTabRequest, externalRoomQuery, externalOverlayRequest) {
        if (!externalOverlayRequest.isNullOrBlank()) {
            overlayScreen = externalOverlayRequest
            onConsumeExternalRequest()
        } else if (externalTabRequest != null) {
            overlayScreen = null
            currentTab = PrimaryBottomTab.entries.firstOrNull { it.route == externalTabRequest }
                ?: PrimaryBottomTab.TODAY
            if (!externalRoomQuery.isNullOrBlank()) {
                mapRoomQuery = externalRoomQuery
            }
            onConsumeExternalRequest()
        }
    }

    val activeRouteKey = when {
        !isHautCampus && overlayScreen in setOf("electricity", "library_catalog", "library_seats", "empty_classrooms", "community") -> "school_center"
        overlayScreen != null -> overlayScreen!!
        !prefs.hasCompletedOnboarding -> "onboarding"
        else -> "main_tabs"
    }

    AnimatedContent(
        targetState = activeRouteKey,
        transitionSpec = {
            val isOpeningOverlay = targetState != "main_tabs" && targetState != "onboarding"
            val isClosingOverlay = initialState != "main_tabs" && initialState != "onboarding" &&
                (targetState == "main_tabs" || targetState == "onboarding")
            when {
                isOpeningOverlay -> {
                    (slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    ) { it / 6 } + fadeIn(tween(220)) + scaleIn(
                        initialScale = 0.96f,
                        animationSpec = tween(280, easing = FastOutSlowInEasing)
                    )) togetherWith (slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                        animationSpec = tween(240, easing = FastOutSlowInEasing)
                    ) { it / 10 } + fadeOut(tween(170)) + scaleOut(
                        targetScale = 0.96f,
                        animationSpec = tween(240, easing = FastOutSlowInEasing)
                    ))
                }
                isClosingOverlay -> {
                    (slideIntoContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(260, easing = FastOutSlowInEasing)
                    ) { it / 10 } + fadeIn(tween(210)) + scaleIn(
                        initialScale = 0.96f,
                        animationSpec = tween(260, easing = FastOutSlowInEasing)
                    )) togetherWith (slideOutOfContainer(
                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                        animationSpec = tween(240, easing = FastOutSlowInEasing)
                    ) { it / 6 } + fadeOut(tween(170)) + scaleOut(
                        targetScale = 0.96f,
                        animationSpec = tween(240, easing = FastOutSlowInEasing)
                    ))
                }
                else -> {
                    (fadeIn(tween(240)) + scaleIn(
                        initialScale = 0.97f,
                        animationSpec = tween(240, easing = FastOutSlowInEasing)
                    )) togetherWith fadeOut(tween(180))
                }
            }.using(SizeTransform(clip = false))
        },
        label = "rootScreenTransition",
        modifier = Modifier.fillMaxSize()
    ) { routeKey ->
        when (routeKey) {
            "school_center" -> {
                SchoolCenterScreen(
                    onBack = { overlayScreen = null },
                    onOpenOfflineImporter = { overlayScreen = "importer" },
                    sessions = sessions,
                    activeSession = activeSession,
                    onSwitchProfile = onSwitchProfile
                )
            }
            "importer" -> {
                ImporterScreen(
                    repository = repository,
                    onBack = { overlayScreen = null }
                )
            }
            "electricity" -> {
                DormElectricityScreen(
                    repository = repository,
                    onBack = { overlayScreen = null }
                )
            }
            "library_catalog" -> {
                LibraryCatalogScreen(
                    onBack = { overlayScreen = null }
                )
            }
            "library_seats" -> {
                LibrarySeatBookingScreen(
                    repository = repository,
                    onBack = { overlayScreen = null }
                )
            }
            "focus" -> {
                StudyFocusScreen(
                    repository = repository,
                    onBack = { overlayScreen = null }
                )
            }
            "empty_classrooms" -> {
                EmptyClassroomScreen(
                    repository = repository,
                    onNavigateToMapWithRoom = { room ->
                        overlayScreen = null
                        mapRoomQuery = room
                        currentTab = PrimaryBottomTab.CAMPUS
                    },
                    onBack = { overlayScreen = null }
                )
            }
            "community" -> {
                CampusCommunityScreen(
                    repository = repository,
                    onBack = { overlayScreen = null }
                )
            }
            "parcel_hub" -> {
                ParcelHubScreen(
                    repository = repository,
                    onBack = { overlayScreen = null }
                )
            }
            "xpeach_cloud" -> {
                XPeachCloudAccountScreen(
                    repository = repository,
                    onBack = { overlayScreen = null }
                )
            }
            "onboarding" -> {
                OnboardingWelcomeScreen(
                    repository = repository,
                    activeSchool = activeSession.school,
                    onOpenSchoolCenter = { overlayScreen = "school_center" },
                    onOpenAdvancedImporter = { overlayScreen = "importer" }
                )
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AnimatedContent(
                        targetState = currentTab,
                        transitionSpec = {
                            val direction = if (targetState.ordinal >= initialState.ordinal) {
                                AnimatedContentTransitionScope.SlideDirection.Start
                            } else {
                                AnimatedContentTransitionScope.SlideDirection.End
                            }
                            (slideIntoContainer(
                                towards = direction,
                                animationSpec = tween(240, easing = FastOutSlowInEasing)
                            ) { it / 12 } + fadeIn(animationSpec = tween(200))) togetherWith
                                (slideOutOfContainer(
                                    towards = direction,
                                    animationSpec = tween(200, easing = FastOutSlowInEasing)
                                ) { it / 14 } + fadeOut(animationSpec = tween(140)))
                        },
                        label = "mainTabTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { targetTab ->
                        when (targetTab) {
                            PrimaryBottomTab.TODAY -> TodayScreen(
                                repository = repository,
                                onNavigateToMapWithRoom = { room ->
                                    mapRoomQuery = room
                                    currentTab = PrimaryBottomTab.CAMPUS
                                },
                                onNavigateToImporter = { overlayScreen = "importer" },
                                onNavigateToSettings = { currentTab = PrimaryBottomTab.PROFILE },
                                onNavigateToFocus = { overlayScreen = "focus" },
                                onNavigateToElectricity = { overlayScreen = "electricity" },
                                onNavigateToLibraryCatalog = { overlayScreen = "library_catalog" },
                                onNavigateToLibrarySeats = { overlayScreen = "library_seats" },
                                onNavigateToEmptyClassrooms = { overlayScreen = "empty_classrooms" },
                                onNavigateToCommunity = { overlayScreen = "community" },
                                onNavigateToParcelHub = { overlayScreen = "parcel_hub" },
                                onNavigateToGrades = { currentTab = PrimaryBottomTab.GRADES },
                                resetTrigger = if (currentTab == PrimaryBottomTab.TODAY) tabResetCounter else 0
                            )
                            PrimaryBottomTab.TIMETABLE -> TimetableScreen(
                                repository = repository,
                                onNavigateToImporter = { overlayScreen = "importer" },
                                onNavigateToMapWithRoom = { room ->
                                    mapRoomQuery = room
                                    currentTab = PrimaryBottomTab.CAMPUS
                                },
                                resetTrigger = if (currentTab == PrimaryBottomTab.TIMETABLE) tabResetCounter else 0
                            )
                            PrimaryBottomTab.GRADES -> GradesScreen(
                                repository = repository,
                                onNavigateToImporter = { overlayScreen = "importer" },
                                onNavigateToMapWithRoom = { room ->
                                    mapRoomQuery = room
                                    currentTab = PrimaryBottomTab.CAMPUS
                                },
                                onNavigateToLibrarySeats = { overlayScreen = "library_seats" },
                                onNavigateToLibraryCatalog = { overlayScreen = "library_catalog" },
                                onNavigateToFocus = { overlayScreen = "focus" },
                                onNavigateToEmptyClassrooms = { overlayScreen = "empty_classrooms" },
                                resetTrigger = if (currentTab == PrimaryBottomTab.GRADES) tabResetCounter else 0
                            )
                            PrimaryBottomTab.CAMPUS -> CampusMapScreen(
                                repository = repository,
                                initialClassroomQuery = mapRoomQuery,
                                onNavigateToElectricity = { overlayScreen = "electricity" },
                                onNavigateToEmptyClassrooms = { overlayScreen = "empty_classrooms" },
                                onNavigateToCommunity = { overlayScreen = "community" },
                                onNavigateToSchoolCenter = { overlayScreen = "school_center" },
                                activeSchool = activeSession.school,
                                resetTrigger = if (currentTab == PrimaryBottomTab.CAMPUS) tabResetCounter else 0
                            )
                            PrimaryBottomTab.PROFILE -> SettingsScreen(
                                repository = repository,
                                onBack = null,
                                onNavigateToImporter = { overlayScreen = "importer" },
                                onNavigateToMapWithRoom = { room ->
                                    mapRoomQuery = room
                                    currentTab = PrimaryBottomTab.CAMPUS
                                },
                                onNavigateToElectricity = { overlayScreen = "electricity" },
                                onNavigateToLibraryCatalog = { overlayScreen = "library_catalog" },
                                onNavigateToLibrarySeats = { overlayScreen = "library_seats" },
                                onNavigateToFocus = { overlayScreen = "focus" },
                                onNavigateToEmptyClassrooms = { overlayScreen = "empty_classrooms" },
                                onNavigateToCommunity = { overlayScreen = "community" },
                                onNavigateToSchoolCenter = { overlayScreen = "school_center" },
                                onNavigateToParcelHub = { overlayScreen = "parcel_hub" },
                                onNavigateToXpeachCloud = { overlayScreen = "xpeach_cloud" },
                                activeSchool = activeSession.school,
                                activeProfileLabel = activeSession.label,
                                resetTrigger = if (currentTab == PrimaryBottomTab.PROFILE) tabResetCounter else 0
                            )
                        }
                    }

                    // 软键盘弹出时自动收起底部悬浮椭圆导航栏，避免挤压输入区域
                    AnimatedVisibility(
                        visible = !isImeVisible,
                        enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it },
                        exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        XPeachBottomDock(
                            currentTab = currentTab,
                            onSelectTab = { clicked ->
                                if (currentTab == clicked) {
                                    tabResetCounter++
                                } else {
                                    currentTab = clicked
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    val releaseToPrompt = detectedUpdateRelease
    if (releaseToPrompt != null) {
        AlertDialog(
            onDismissRequest = {
                if (!isDownloadingStartupApk) {
                    detectedUpdateRelease = null
                }
            },
            title = {
                Text("发现新版本 ${releaseToPrompt.tagName}")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "当前版本 v${releaseToPrompt.currentVersionNormalized}，可更新至 ${releaseToPrompt.tagName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = releaseToPrompt.changelog.take(360),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isDownloadingStartupApk || startupDownloadStatusText != null) {
                        if (isDownloadingStartupApk) {
                            LinearProgressIndicator(
                                progress = { startupDownloadProgress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                            )
                        }
                        startupDownloadStatusText?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDownloadingStartupApk,
                    onClick = {
                        val readyFile = downloadedStartupApkFile
                        if (readyFile != null && readyFile.exists()) {
                            GitHubReleaseUpdater.triggerInstallApk(context, readyFile)
                                .onSuccess { installed ->
                                    startupDownloadStatusText = if (installed) {
                                        "请在系统弹窗中确认安装"
                                    } else {
                                        "请先在设置中允许安装未知应用，再点击安装"
                                    }
                                }
                                .onFailure { err ->
                                    startupDownloadStatusText = "打开安装器失败：${err.message}"
                                }
                        } else {
                            scope.launch {
                                isDownloadingStartupApk = true
                                startupDownloadProgress = 0f
                                startupDownloadStatusText = "正在下载安装包..."
                                val dlRes = GitHubReleaseUpdater.downloadReleaseApk(
                                    context = context,
                                    releaseInfo = releaseToPrompt,
                                    proxyConfig = prefs.networkProxyConfig,
                                    onProgress = { dl, total ->
                                        if (total > 0L) {
                                            startupDownloadProgress = dl.toFloat() / total.toFloat()
                                            val dlMb = String.format("%.1f", dl / (1024.0 * 1024.0))
                                            val totalMb = String.format("%.1f", total / (1024.0 * 1024.0))
                                            startupDownloadStatusText =
                                                "正在下载 ${(startupDownloadProgress * 100).toInt()}% ($dlMb / $totalMb MB)"
                                        }
                                    }
                                )
                                isDownloadingStartupApk = false
                                dlRes.onSuccess { apkFile ->
                                    downloadedStartupApkFile = apkFile
                                    GitHubReleaseUpdater.triggerInstallApk(context, apkFile)
                                        .onSuccess { installed ->
                                            startupDownloadStatusText = if (installed) {
                                                "下载完成，请确认安装"
                                            } else {
                                                "下载完成，开启安装权限后即可安装"
                                            }
                                        }
                                        .onFailure { err ->
                                            startupDownloadStatusText = "打开安装器失败：${err.message}"
                                        }
                                }.onFailure { err ->
                                    startupDownloadStatusText = "下载失败：${err.message ?: "请检查网络"}"
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = when {
                            isDownloadingStartupApk -> "正在下载..."
                            downloadedStartupApkFile?.exists() == true -> "安装更新"
                            else -> "立即更新"
                        },
                        maxLines = 1,
                        softWrap = false
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isDownloadingStartupApk,
                    onClick = { detectedUpdateRelease = null }
                ) {
                    Text("稍后")
                }
            }
        )
    }
}

@Composable
private fun OnboardingWelcomeScreen(
    repository: CampusRepository,
    activeSchool: cn.edu.haut.campus.core.model.SchoolProfile = SchoolDirectory.hautProfile(),
    onOpenSchoolCenter: () -> Unit = {},
    onOpenAdvancedImporter: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val isHautSchool = activeSchool.id == SchoolDirectory.HAUT_SCHOOL_ID

    var hasLocationPermission by remember {
        mutableStateOf(CampusMapNavigator.hasLocationPermission(context))
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission = result.values.any { it } || CampusMapNavigator.hasLocationPermission(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationPermission = CampusMapNavigator.hasLocationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var studentIdInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var selectedXnm by rememberSaveable { mutableStateOf("2025") }
    var selectedXqm by rememberSaveable { mutableStateOf("12") }
    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusMessage by remember { mutableStateOf<String?>(null) }
    var isErrorStatus by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "日在校园",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "${activeSchool.displayName} · ${activeSchool.primaryCampus?.name ?: "离线模式"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 0. 学校与身份选择（支持多校目录浏览与离线建档）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp)
                    ) {
                        Text(
                            text = "当前学校：${activeSchool.displayName}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "可切换学校、查看各校能力核验边界，或创建非工大离线身份",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenSchoolCenter,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("切换学校", maxLines = 1, softWrap = false)
                    }
                }
            }

            // 1. 定位权限
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 10.dp)
                    ) {
                        Text(
                            text = "位置权限",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "在地图中显示当前位置和步行路线",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (hasLocationPermission) {
                        XPeachStatusBadge(
                            text = "已授权",
                            containerColor = Color(0xFFDCFCE7),
                            contentColor = Color(0xFF15803D)
                        )
                    } else {
                        Button(
                            onClick = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("授权", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // 2. 教务同步 / 离线建档
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isHautSchool) "同步课表与成绩" else "导入或手工录入课表",
                        style = MaterialTheme.typography.titleMedium
                    )
                    XPeachStatusBadge(text = if (isHautSchool) "统一身份认证" else "离线模式")
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (isHautSchool) {
                    val focusManager = LocalFocusManager.current
                    val triggerLoginAndSync: () -> Unit = {
                        if (!isSyncing && studentIdInput.isNotBlank() && passwordInput.isNotBlank()) {
                            focusManager.clearFocus()
                            scope.launch {
                                val plainPwd = passwordInput
                                isSyncing = true
                                isErrorStatus = false
                                syncStatusMessage = "正在获取课表、考试和成绩..."
                                val result = HautLiveAcademicClient.fetchLiveAcademicData(
                                    studentId = studentIdInput,
                                    passwordPlain = plainPwd,
                                    xnm = selectedXnm,
                                    xqm = selectedXqm
                                )
                                passwordInput = ""
                                isSyncing = false

                                result.onSuccess { syncData ->
                                    val applied = runCatching {
                                        repository.applyLiveSyncData(syncData, overwriteUserConflicts = false)
                                    }
                                    if (applied.isFailure) {
                                        isErrorStatus = true
                                        syncStatusMessage = "同步数据保存失败，请重试；原有数据未主动清除"
                                        return@onSuccess
                                    }
                                    val saved = runCatching {
                                        repository.saveAuthorizedSyncCredentials(
                                            studentIdInput, plainPwd, selectedXnm, selectedXqm
                                        )
                                    }
                                    repository.setAutoSyncEnabled(saved.isSuccess)
                                    syncStatusMessage = syncData.syncSummaryText() +
                                        if (saved.isFailure) "\n安全凭据保存失败，自动同步已关闭；已导入数据仍可离线使用" else ""
                                    repository.completeOnboarding()
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                }.onFailure { err ->
                                    isErrorStatus = true
                                    syncStatusMessage = err.message ?: "登录或同步失败"
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = studentIdInput,
                        onValueChange = { studentIdInput = it },
                        label = { Text("学号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("密码") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { triggerLoginAndSync() }
                        ),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                                )
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("学年", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                        for (yr in listOf("2026", "2025", "2024", "2023")) {
                            XPeachFilterPill(
                                label = "$yr-${yr.toInt() + 1}",
                                selected = selectedXnm == yr,
                                onClick = { selectedXnm = yr }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("学期", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                        XPeachFilterPill(
                            label = "第 1 学期",
                            selected = selectedXqm == "3",
                            onClick = { selectedXqm = "3" }
                        )
                        XPeachFilterPill(
                            label = "第 2 学期",
                            selected = selectedXqm == "12",
                            onClick = { selectedXqm = "12" }
                        )
                    }

                    if (syncStatusMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = syncStatusMessage!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isErrorStatus) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        enabled = !isSyncing && studentIdInput.isNotBlank() && passwordInput.isNotBlank(),
                        onClick = triggerLoginAndSync,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在同步...", maxLines = 1, softWrap = false)
                        } else {
                            Text("登录并同步", maxLines = 1, softWrap = false)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    Text(
                        text = "当前学校尚未启用线上教务直连核验。可先使用网页/CSV 导入或直接进入离线课表手工录入，数据保存在当前身份的独立本地空间。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenAdvancedImporter,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("网页 / CSV 导入", maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                repository.completeOnboarding()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isHautSchool) "先去看看" else "进入离线课表", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * Apple Liquid Glass 风格悬浮椭圆胶囊导航栏：
 * - 支持手指按住连续左右拖拽滑动选框（Pan Scrubbing）与单击切换
 * - 跨 Tab 位移时根据速度动态产生水平液态拉伸与垂直回弹形变（Velocity Stretch & Squish）
 * - 线性马达触觉反馈（Haptics）+ 斜向高光折射边框 + 底部柔和渐隐遮罩（Bottom Scrim）
 */
@Composable
private fun XPeachBottomDock(
    currentTab: PrimaryBottomTab,
    onSelectTab: (PrimaryBottomTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = PrimaryBottomTab.entries
    val selectedIndex = tabs.indexOf(currentTab).coerceAtLeast(0)
    val haptic = LocalHapticFeedback.current
    val latestOnSelectTab by rememberUpdatedState(onSelectTab)
    val latestCurrentTab by rememberUpdatedState(currentTab)

    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var isPressing by remember { mutableStateOf(false) }

    val targetFraction = dragFraction ?: selectedIndex.toFloat()
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(
            dampingRatio = 0.72f,
            stiffness = 400f
        ),
        label = "liquidOvalFraction"
    )

    // 根据目标位置与当前动画位置的瞬时差值计算液态弹性拉伸系数
    val travelDelta = abs(targetFraction - animatedFraction)
    val pillScaleX by animateFloatAsState(
        targetValue = 1f + (travelDelta * 0.28f).coerceAtMost(0.24f) + (if (isPressing) 0.05f else 0f),
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 450f),
        label = "pillScaleX"
    )
    val pillScaleY by animateFloatAsState(
        targetValue = 1f - (travelDelta * 0.09f).coerceAtMost(0.09f) + (if (isPressing) 0.03f else 0f),
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 450f),
        label = "pillScaleY"
    )

    val bgColor = MaterialTheme.colorScheme.background
    val specularRimBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.75f),
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        ),
        start = Offset(0f, 0f),
        end = Offset(800f, 220f)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (bgColor.alpha >= 0.05f) {
                    Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                bgColor.copy(alpha = 0.58f),
                                bgColor.copy(alpha = 0.88f)
                            )
                        )
                    )
                } else {
                    Modifier
                }
            )
            .navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 368.dp)
                .fillMaxWidth()
                .height(62.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 4.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(width = 1.dp, brush = specularRimBrush)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 5.dp, vertical = 5.dp)
                    .pointerInput(tabs.size) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            isPressing = true
                            val totalWidthPx = size.width.toFloat().coerceAtLeast(1f)
                            val slotWidthPx = totalWidthPx / tabs.size

                            var lastTriggeredIndex = (down.position.x / slotWidthPx)
                                .toInt()
                                .coerceIn(0, tabs.lastIndex)
                            var hasDragged = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val moveDx = abs(change.position.x - down.position.x)
                                if (moveDx > viewConfiguration.touchSlop * 0.45f) {
                                    hasDragged = true
                                }
                                if (hasDragged) {
                                    change.consume()
                                    val rawFraction = ((change.position.x / slotWidthPx) - 0.5f)
                                        .coerceIn(0f, (tabs.size - 1).toFloat())
                                    dragFraction = rawFraction
                                    val hoveredIndex = rawFraction.roundToInt().coerceIn(0, tabs.lastIndex)
                                    if (hoveredIndex != lastTriggeredIndex) {
                                        lastTriggeredIndex = hoveredIndex
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        latestOnSelectTab(tabs[hoveredIndex])
                                    }
                                }
                            }

                            isPressing = false
                            if (hasDragged) {
                                val finalIndex = (dragFraction ?: selectedIndex.toFloat())
                                    .roundToInt()
                                    .coerceIn(0, tabs.lastIndex)
                                dragFraction = null
                                if (tabs[finalIndex] != latestCurrentTab) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    latestOnSelectTab(tabs[finalIndex])
                                }
                            } else {
                                dragFraction = null
                                val tappedIndex = (down.position.x / slotWidthPx)
                                    .toInt()
                                    .coerceIn(0, tabs.lastIndex)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                latestOnSelectTab(tabs[tappedIndex])
                            }
                        }
                    }
            ) {
                val tabWidth = maxWidth / tabs.size
                val indicatorOffset = tabWidth * animatedFraction

                // 动态液态椭圆选中框（支持跟手连续位移 + 速度弹性拉伸 + 高光内描边）
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = pillScaleX
                            scaleY = pillScaleY
                        }
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f),
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.80f)
                                )
                            )
                        )
                        .border(
                            width = 0.9.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.36f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                )
                            ),
                            shape = CircleShape
                        )
                )

                // 5 个导航项（今天 / 课表 / 学业 / 校园 / 我的）
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val distance = abs(animatedFraction - index)
                        val selected = distance < 0.5f
                        val contentColor by animateColorAsState(
                            targetValue = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            animationSpec = spring(stiffness = 500f),
                            label = "tabColor"
                        )
                        val itemScale by animateFloatAsState(
                            targetValue = if (selected) 1.06f else 0.95f,
                            animationSpec = spring(
                                dampingRatio = 0.65f,
                                stiffness = 420f
                            ),
                            label = "tabScale"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .graphicsLayer {
                                    scaleX = itemScale
                                    scaleY = itemScale
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.label,
                                tint = contentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = contentColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}
