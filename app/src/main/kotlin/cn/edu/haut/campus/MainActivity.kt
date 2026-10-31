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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
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

            // GPA 与成绩防截屏/最近任务缩略图隐私保护（PRD 4.2）
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
    var overlayScreen by rememberSaveable { mutableStateOf<String?>(null) } // "importer" or "settings"
    var mapRoomQuery by rememberSaveable { mutableStateOf<String?>(null) }

    // Android 14/15/16 预测式返回手势（Predictive Back Gesture）状态回退支持
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
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    XPeachBottomDock(
                        currentTab = currentTab,
                        onSelectTab = { currentTab = it }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
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
            }
        }
    }
}

/**
 * 首次启动引导页（XPeach Auth-Shell 扁平化风格）
 * - 绝不预填任何虚构演示课程或成绩
 * - 第一步请求设备定位权限以启用校园实时定位与步行导航
 * - 第二步支持河工大统一身份认证一键同步真实课表与成绩，或直接进入空白工作台
 */
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 顶部品牌标识（对标 xpeach-sso .brand-mark + .auth-header）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.School,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "日在校园",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        XPeachStatusBadge(text = "HAUT 莲花街")
                    }
                    Text(
                        text = "河南工业大学 · 本地优先课表、GPA 与校园导航工作台",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 步骤 1：设备定位权限请求（用于校园内实时定位与跨楼宇步行导航）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (hasLocationPermission) Icons.Default.CheckCircle else Icons.Default.MyLocation,
                            contentDescription = null,
                            tint = if (hasLocationPermission) Color(0xFF16A34A) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "步骤 1 · 设备定位与校园导航权限",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    XPeachStatusBadge(
                        text = if (hasLocationPermission) "✓ 已授权" else "待授权",
                        containerColor = if (hasLocationPermission) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (hasLocationPermission) Color(0xFF15803D) else MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "用于获取您在莲花街校区的实时 GPS 位置，计算您与下一节课教学楼的直线距离、方位角，并提供大课间换教室的 APP 内步行路线引导。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!hasLocationPermission) {
                    Spacer(modifier = Modifier.height(10.dp))
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("授权位置权限（开启校园定位与导航）")
                    }
                }
            }

            // 步骤 2：统一身份认证同步真实课表与成绩
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "步骤 2 · 同步教务课表与成绩",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    XPeachStatusBadge(text = "CAS 统一认证")
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "输入河南工业大学统一身份认证学号与密码，一键拉取本学期课表与历史成绩。密码仅用于当次内存加密握手，绝不落盘或上传第三方。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = studentIdInput,
                    onValueChange = { studentIdInput = it },
                    label = { Text("学号（如 231040600xxx）") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("统一身份认证密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("学年：", style = MaterialTheme.typography.labelMedium)
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
                    Text("学期：", style = MaterialTheme.typography.labelMedium)
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
                            syncStatusMessage = "正在通过河工大统一身份认证同步您的真实课表与成绩..."
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
                                syncStatusMessage = "同步失败：${err.message ?: "请检查学号密码或改用下方 WebView 网页授权导入"}"
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
                        Text("正在同步教务数据...")
                    } else {
                        Text("登录统一认证并进入「日在校园」")
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
                        Text("WebView / CSV 导入")
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
                        Text("进入空白工作台 →")
                    }
                }
            }

            // 底部隐私承诺与架构说明（xpeach-sso .auth-footer 风格）
            XPeachWell(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Explore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "本地优先承诺：所有课表、成绩与定位计算均在设备端本地完成，无任何云端中转服务器，随时可在设置中一键抹除个人数据。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 扁平化底部导航栏（严格对齐 xpeach-sso 的 `.mobile-glass-dock` + `.mobile-dock-item`）
 * - 1dp 顶部分割线，纯色平面背景，无悬浮阴影
 * - 选中态采用 8dp 圆角浅蓝胶囊 (`primaryContainer`) 与 `--brand` 主色图标文字
 */
@Composable
private fun XPeachBottomDock(
    currentTab: PrimaryBottomTab,
    onSelectTab: (PrimaryBottomTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (tab in PrimaryBottomTab.entries) {
                val selected = currentTab == tab
                val containerColor = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                }
                val contentColor = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(containerColor)
                        .then(
                            if (selected) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            } else Modifier
                        )
                        .clickable { onSelectTab(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = contentColor
                    )
                }
            }
        }
    }
}
