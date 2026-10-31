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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.academic.HautLiveAcademicClient
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.feature.grades.GradesScreen
import cn.edu.haut.campus.feature.importer.ImporterScreen
import cn.edu.haut.campus.feature.map.CampusMapScreen
import cn.edu.haut.campus.feature.settings.SettingsScreen
import cn.edu.haut.campus.feature.timetable.TimetableScreen
import cn.edu.haut.campus.feature.today.TodayScreen
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch

enum class PrimaryBottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    TODAY("today", "今天", Icons.Default.Schedule),
    TIMETABLE("timetable", "课表", Icons.Default.CalendarViewWeek),
    GRADES("grades", "学业", Icons.Default.Assessment),
    CAMPUS("map", "校园", Icons.Default.Map)
}

class MainActivity : ComponentActivity() {

    private var deepLinkTab by mutableStateOf<String?>(null)
    private var deepLinkRoomQuery by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        extractWidgetIntent(intent)
        val repository = CampusRepository.getInstance(applicationContext)

        setContent {
            val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())

            LaunchedEffect(prefs.gpaPrivacyShieldEnabled) {
                if (prefs.gpaPrivacyShieldEnabled) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            HautCampusTheme(dynamicColor = prefs.useDynamicColor) {
                HautCampusRootApp(
                    repository = repository,
                    prefs = prefs,
                    externalTabRequest = deepLinkTab,
                    externalRoomQuery = deepLinkRoomQuery,
                    onConsumeExternalRequest = {
                        deepLinkTab = null
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractWidgetIntent(intent)
    }

    private fun extractWidgetIntent(intent: Intent?) {
        val tab = intent?.getStringExtra(WidgetRefreshCoordinator.EXTRA_NAVIGATE_TAB)
        val room = intent?.getStringExtra(WidgetRefreshCoordinator.EXTRA_RAW_ROOM)
        if (!tab.isNullOrBlank()) {
            deepLinkTab = tab
        }
        if (!room.isNullOrBlank()) {
            deepLinkRoomQuery = room
        }
    }
}

@Composable
fun HautCampusRootApp(
    repository: CampusRepository,
    prefs: AppPreferencesState,
    externalTabRequest: String?,
    externalRoomQuery: String?,
    onConsumeExternalRequest: () -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf(PrimaryBottomTab.TODAY) }
    var overlayScreen by rememberSaveable { mutableStateOf<String?>(null) }
    var mapRoomQuery by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = overlayScreen != null || currentTab != PrimaryBottomTab.TODAY) {
        if (overlayScreen != null) {
            overlayScreen = null
        } else {
            currentTab = PrimaryBottomTab.TODAY
        }
    }

    LaunchedEffect(externalTabRequest, externalRoomQuery) {
        if (externalTabRequest != null) {
            overlayScreen = null
            currentTab = PrimaryBottomTab.entries.firstOrNull { it.route == externalTabRequest }
                ?: PrimaryBottomTab.TODAY
            if (!externalRoomQuery.isNullOrBlank()) {
                mapRoomQuery = externalRoomQuery
            }
            onConsumeExternalRequest()
        }
    }

    when {
        overlayScreen == "importer" -> {
            ImporterScreen(
                repository = repository,
                onBack = { overlayScreen = null }
            )
        }
        overlayScreen == "settings" -> {
            SettingsScreen(
                repository = repository,
                onBack = { overlayScreen = null }
            )
        }
        !prefs.hasCompletedOnboarding -> {
            OnboardingWelcomeScreen(
                repository = repository,
                onOpenAdvancedImporter = { overlayScreen = "importer" }
            )
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (currentTab) {
                        PrimaryBottomTab.TODAY -> TodayScreen(
                            repository = repository,
                            onNavigateToMapWithRoom = { room ->
                                mapRoomQuery = room
                                currentTab = PrimaryBottomTab.CAMPUS
                            },
                            onNavigateToImporter = { overlayScreen = "importer" },
                            onNavigateToSettings = { overlayScreen = "settings" }
                        )
                        PrimaryBottomTab.TIMETABLE -> TimetableScreen(
                            repository = repository,
                            onNavigateToImporter = { overlayScreen = "importer" },
                            onNavigateToMapWithRoom = { room ->
                                mapRoomQuery = room
                                currentTab = PrimaryBottomTab.CAMPUS
                            }
                        )
                        PrimaryBottomTab.GRADES -> GradesScreen(
                            repository = repository,
                            onNavigateToImporter = { overlayScreen = "importer" }
                        )
                        PrimaryBottomTab.CAMPUS -> CampusMapScreen(
                            repository = repository,
                            initialClassroomQuery = mapRoomQuery
                        )
                    }
                }

                XPeachBottomDock(
                    currentTab = currentTab,
                    onSelectTab = { currentTab = it },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun OnboardingWelcomeScreen(
    repository: CampusRepository,
    onOpenAdvancedImporter: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)

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
    var passwordInput by rememberSaveable { mutableStateOf("") }
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
                    text = "河南工业大学 · 莲花街校区",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                            text = "用于校内实时定位与教学楼步行路线",
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

            // 2. 教务同步
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "同步课表与成绩",
                        style = MaterialTheme.typography.titleMedium
                    )
                    XPeachStatusBadge(text = "统一身份认证")
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = studentIdInput,
                    onValueChange = { studentIdInput = it },
                    label = { Text("学号") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
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
                    for (yr in listOf("2025", "2024", "2023")) {
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
                    onClick = {
                        scope.launch {
                            isSyncing = true
                            isErrorStatus = false
                            syncStatusMessage = "正在同步教务课表与成绩..."
                            val result = HautLiveAcademicClient.fetchLiveAcademicData(
                                studentId = studentIdInput,
                                passwordPlain = passwordInput,
                                xnm = selectedXnm,
                                xqm = selectedXqm
                            )
                            passwordInput = ""
                            isSyncing = false

                            result.onSuccess { syncData ->
                                syncData.studentProfile?.let { profile ->
                                    repository.saveStudentProfile(profile)
                                }
                                if (syncData.gradeAttempts.isNotEmpty()) {
                                    repository.saveImportedGrades(
                                        attempts = syncData.gradeAttempts,
                                        replaceExisting = true
                                    )
                                }
                                val targetTermId = currentTerm?.id ?: syncData.termId
                                val diff = repository.previewCourseImportDiff(
                                    termId = targetTermId,
                                    source = CourseSource.OFFICIAL_JW,
                                    candidates = syncData.timetableCandidates
                                )
                                repository.commitCourseImportDiff(
                                    preview = diff,
                                    overwriteUserConflicts = true
                                )
                                repository.completeOnboarding()
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            }.onFailure { err ->
                                isErrorStatus = true
                                syncStatusMessage = err.message ?: "登录或同步失败"
                            }
                        }
                    },
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
                        Text("暂不同步，直接进入", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * Apple 风格悬浮椭圆胶囊底栏 + 弹簧物理动画滑动椭圆选中框
 */
@Composable
private fun XPeachBottomDock(
    currentTab: PrimaryBottomTab,
    onSelectTab: (PrimaryBottomTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = PrimaryBottomTab.entries
    val selectedIndex = tabs.indexOf(currentTab).coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 3.dp,
            shadowElevation = 14.dp,
            border = BorderStroke(
                width = 0.8.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            )
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 5.dp, vertical = 5.dp)
            ) {
                val tabWidth = maxWidth / tabs.size
                val indicatorOffset by animateDpAsState(
                    targetValue = tabWidth * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = 0.75f,
                        stiffness = 380f
                    ),
                    label = "dynamicOvalOffset"
                )

                // 动态滑动椭圆选中胶囊（随 Tab 切换弹性平滑位移）
                Box(
                    modifier = Modifier
                        .offset(x = indicatorOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.88f))
                        .border(
                            width = 0.8.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            shape = CircleShape
                        )
                )

                // 4 个导航项
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (tab in tabs) {
                        val selected = currentTab == tab
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
                            targetValue = if (selected) 1.05f else 0.96f,
                            animationSpec = spring(
                                dampingRatio = 0.68f,
                                stiffness = 420f
                            ),
                            label = "tabScale"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onSelectTab(tab) }
                                )
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
