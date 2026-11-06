package cn.edu.haut.campus

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.academic.HautLiveAcademicClient
import cn.edu.haut.campus.core.academic.syncSummaryText
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.PresetThemePalette
import cn.edu.haut.campus.core.designsystem.UiDesignStyle
import cn.edu.haut.campus.core.designsystem.WallpaperThemeEngine
import cn.edu.haut.campus.core.designsystem.XPeachBentoTile
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackBanner
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackTone
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachGlassSurface
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachSwitchRow
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTokens
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.core.model.ThemeVisualDefaults
import cn.edu.haut.campus.core.reminder.ReminderPermissions
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 新用户四步交互式引导流程阶段枚举。
 */
enum class OnboardingWizardStep(
    val stepNumber: Int,
    val shortTitle: String,
    val headline: String,
    val subtitle: String,
) {
    WELCOME_FEATURES(
        stepNumber = 1,
        shortTitle = "核心速览",
        headline = "欢迎来到日在校园",
        subtitle = "课表时间轴、校区室内外导航、图书馆抢座与驿站取件一站就绪",
    ),
    VISUAL_THEME_STUDIO(
        stepNumber = 2,
        shortTitle = "视觉主题",
        headline = "定制你的专属视觉风格",
        subtitle = "自由切换 Flutter 经典质感或透明液态毛玻璃，支持提取桌面壁纸与主题色",
    ),
    SCHOOL_AND_PERMISSIONS(
        stepNumber = 3,
        shortTitle = "校区与提醒",
        headline = "选择校区并开启智能提醒",
        subtitle = "配置双校区地图识别与课前通知权限，不错过每一节重要课程",
    ),
    ACADEMIC_DATA_SETUP(
        stepNumber = 4,
        shortTitle = "课表建档",
        headline = "同步课表与学业数据",
        subtitle = "支持统一身份认证一键同步、网页/CSV 多源导入或体验演示课表",
    );
}

/**
 * 生产级多步交互式新用户引导流（支持首次启动引导及从「我的 -> 重新进入引导页」随时唤起）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingFlowScreen(
    repository: CampusRepository,
    activeSchool: SchoolProfile = SchoolDirectory.hautProfile(),
    onOpenSchoolCenter: () -> Unit = {},
    onOpenAdvancedImporter: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val isHautSchool = activeSchool.id == SchoolDirectory.HAUT_SCHOOL_ID

    var currentStepIndex by rememberSaveable { mutableIntStateOf(0) }
    val steps = OnboardingWizardStep.entries
    val activeStep = steps[currentStepIndex.coerceIn(0, steps.lastIndex)]

    var bannerMessage by remember { mutableStateOf<String?>(null) }
    var bannerTone by remember { mutableStateOf(XPeachFeedbackTone.INFO) }

    // 权限状态实时探测
    var hasLocationPermission by remember {
        mutableStateOf(CampusMapNavigator.hasLocationPermission(context))
    }
    var reminderPermSnapshot by remember {
        mutableStateOf(ReminderPermissions.inspect(context))
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission = result.values.any { it } || CampusMapNavigator.hasLocationPermission(context)
        if (hasLocationPermission) {
            bannerTone = XPeachFeedbackTone.SUCCESS
            bannerMessage = "位置权限已开启，校园地图可实时定位并规划步行路线"
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        reminderPermSnapshot = ReminderPermissions.inspect(context)
        if (reminderPermSnapshot.canPostNotifications) {
            bannerTone = XPeachFeedbackTone.SUCCESS
            bannerMessage = "通知权限已开启，课前与考试提醒将准时推送"
        }
    }

    val wallpaperPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                WallpaperThemeEngine.importWallpaperAndExtractPalette(context, uri)
                    .onSuccess { imported ->
                        repository.saveCustomWallpaperTheme(
                            localPath = imported.localFilePath,
                            primarySeedArgb = imported.primarySeedArgb,
                            extractedSwatches = imported.extractedSwatches,
                            enableLiquidGlass = true,
                        )
                        bannerTone = XPeachFeedbackTone.SUCCESS
                        bannerMessage = imported.statusMessage
                    }
                    .onFailure {
                        bannerTone = XPeachFeedbackTone.WARNING
                        bannerMessage = "读取相册图片失败，请换一张图片重试"
                    }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasLocationPermission = CampusMapNavigator.hasLocationPermission(context)
                reminderPermSnapshot = ReminderPermissions.inspect(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = currentStepIndex > 0) {
        currentStepIndex = (currentStepIndex - 1).coerceAtLeast(0)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (currentStepIndex + 1).toFloat() / steps.size.toFloat(),
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "onboardingStepProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // 顶部步骤进度导航栏
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = XPeachTokens.Spacing.PageHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    XPeachStatusBadge(
                        text = "步骤 ${activeStep.stepNumber}/${steps.size}",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = XPeachTheme.readablePrimary,
                    )
                    Text(
                        text = activeStep.shortTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                TextButton(
                    onClick = {
                        scope.launch {
                            repository.completeOnboarding()
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "跳过引导",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape),
                color = XPeachTheme.readablePrimary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            // 可点击步骤胶囊条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                steps.forEachIndexed { idx, step ->
                    XPeachFilterPill(
                        label = "${step.stepNumber}. ${step.shortTitle}",
                        selected = idx == currentStepIndex,
                        compact = true,
                        onClick = { currentStepIndex = idx },
                    )
                }
            }

            XPeachFeedbackBanner(
                message = bannerMessage,
                tone = bannerTone,
                onDismiss = { bannerMessage = null },
            )
        }

        // 主体步骤内容区
        AnimatedContent(
            targetState = activeStep,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                val slideDir = if (forward) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                (slideIntoContainer(
                    towards = slideDir,
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                ) { it / 8 } + fadeIn(tween(200))) togetherWith (slideOutOfContainer(
                    towards = slideDir,
                    animationSpec = tween(200, easing = FastOutSlowInEasing),
                ) { it / 10 } + fadeOut(tween(150)))
            },
            label = "onboardingStepContent",
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) { step ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = XPeachTokens.Spacing.PageHorizontal, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 步骤标题头
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = step.headline,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = step.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when (step) {
                    OnboardingWizardStep.WELCOME_FEATURES -> {
                        OnboardingWelcomeFeaturesStep(
                            activeSchool = activeSchool,
                            onJumpToStep = { target -> currentStepIndex = target.ordinal },
                            onPinSampleWidget = {
                                val ok = WidgetRefreshCoordinator.requestPinAppWidget(
                                    context,
                                    WidgetRefreshCoordinator.PROVIDER_NEXT_CLASS_2X2,
                                )
                                bannerTone = if (ok) XPeachFeedbackTone.SUCCESS else XPeachFeedbackTone.INFO
                                bannerMessage = if (ok) {
                                    "请在系统弹窗中确认添加「2×2 下一节课」小组件"
                                } else {
                                    "也可以在进入首页后长按手机桌面空白处添加小组件"
                                }
                            },
                        )
                    }

                    OnboardingWizardStep.VISUAL_THEME_STUDIO -> {
                        OnboardingVisualThemeStudioStep(
                            prefs = prefs,
                            onSelectDesignStyle = { style ->
                                scope.launch {
                                    repository.setUiDesignStyle(style.key)
                                    bannerTone = XPeachFeedbackTone.SUCCESS
                                    bannerMessage = "已切换为「${style.label}」设计语言"
                                }
                            },
                            onSyncDesktopWallpaper = {
                                scope.launch {
                                    WallpaperThemeEngine.syncSystemDesktopWallpaper(context)
                                        .onSuccess { res ->
                                            repository.saveCustomWallpaperTheme(
                                                localPath = res.localFilePath,
                                                primarySeedArgb = res.primarySeedArgb,
                                                extractedSwatches = res.extractedSwatches,
                                                enableLiquidGlass = true,
                                            )
                                            bannerTone = XPeachFeedbackTone.SUCCESS
                                            bannerMessage = res.statusMessage
                                        }
                                        .onFailure {
                                            bannerTone = XPeachFeedbackTone.WARNING
                                            bannerMessage = "未能直接读取系统壁纸，你可以点击「从相册选图」上传背景"
                                        }
                                }
                            },
                            onExtractDesktopColors = {
                                scope.launch {
                                    WallpaperThemeEngine.extractSystemDesktopColorsOnly(context)
                                        .onSuccess { swatches ->
                                            val primarySeed = swatches.firstOrNull() ?: 0xFF0EA5E9L
                                            repository.saveExtractedDesktopSwatches(
                                                primarySeedArgb = primarySeed,
                                                extractedSwatches = swatches,
                                            )
                                            bannerTone = XPeachFeedbackTone.SUCCESS
                                            bannerMessage = "已提取系统桌面主题色并应用到全局控件"
                                        }
                                        .onFailure {
                                            bannerTone = XPeachFeedbackTone.WARNING
                                            bannerMessage = "暂未能读取系统桌面主题色，你可以选择下方预设色板"
                                        }
                                }
                            },
                            onPickGalleryWallpaper = { wallpaperPickerLauncher.launch("image/*") },
                            onClearWallpaper = {
                                scope.launch {
                                    repository.clearCustomWallpaperTheme()
                                    bannerTone = XPeachFeedbackTone.INFO
                                    bannerMessage = "已移除自定义壁纸背景"
                                }
                            },
                            onSelectThemeMode = { mode ->
                                scope.launch { repository.setThemeMode(mode) }
                            },
                            onToggleDynamicColor = { enabled ->
                                scope.launch { repository.setUseDynamicColor(enabled) }
                            },
                            onSelectPalette = { key, customSeed ->
                                scope.launch { repository.setThemePalette(key, customSeed) }
                            },
                            onUpdateGlassParams = { alpha, blurDp, cardAlpha ->
                                scope.launch {
                                    repository.updateWallpaperVisualParams(
                                        alpha = alpha,
                                        blurDp = blurDp,
                                        cardAlpha = cardAlpha,
                                    )
                                }
                            },
                        )
                    }

                    OnboardingWizardStep.SCHOOL_AND_PERMISSIONS -> {
                        OnboardingSchoolAndPermissionsStep(
                            activeSchool = activeSchool,
                            isHautSchool = isHautSchool,
                            prefs = prefs,
                            hasLocationPermission = hasLocationPermission,
                            canPostNotifications = reminderPermSnapshot.canPostNotifications,
                            canScheduleExactAlarms = reminderPermSnapshot.exactAlarmAllowed,
                            onOpenSchoolCenter = onOpenSchoolCenter,
                            onSelectCampusId = { campusId ->
                                scope.launch {
                                    repository.setSelectedCampusId(campusId)
                                    bannerTone = XPeachFeedbackTone.SUCCESS
                                    bannerMessage = when (campusId) {
                                        "lianhuajie" -> "已固定为莲花街校区地图与作息"
                                        "songshanlu" -> "已固定为嵩山路校区地图与作息"
                                        else -> "已设为根据 GPS 位置自动识别校区"
                                    }
                                }
                            },
                            onRequestLocationPermission = {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    )
                                )
                            },
                            onRequestNotificationPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !reminderPermSnapshot.notificationPermissionGranted
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    runCatching {
                                        context.startActivity(
                                            ReminderPermissions.buildNotificationSettingsIntent(context)
                                        )
                                    }
                                }
                            },
                            onOpenExactAlarmSettings = {
                                runCatching {
                                    context.startActivity(
                                        ReminderPermissions.buildExactAlarmSettingsIntent(context)
                                    )
                                }
                            },
                            onToggleRemindersEnabled = { enabled ->
                                scope.launch {
                                    repository.updateReminderGlobalConfig { it.copy(enabled = enabled) }
                                }
                            },
                            onSelectClassLeadMinutes = { minutes ->
                                scope.launch {
                                    repository.updateReminderGlobalConfig { it.copy(classLeadMinutes = minutes) }
                                }
                            },
                        )
                    }

                    OnboardingWizardStep.ACADEMIC_DATA_SETUP -> {
                        OnboardingAcademicDataSetupStep(
                            repository = repository,
                            activeSchool = activeSchool,
                            isHautSchool = isHautSchool,
                            initialXnm = prefs.syncXnm,
                            initialXqm = prefs.syncXqm,
                            onOpenAdvancedImporter = onOpenAdvancedImporter,
                            onCompleted = {
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // 底部固定操作栏
        XPeachGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = XPeachTokens.Spacing.PageHorizontal, vertical = 10.dp),
            shape = RoundedCornerShape(20.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (currentStepIndex > 0) {
                    OutlinedButton(
                        onClick = { currentStepIndex = (currentStepIndex - 1).coerceAtLeast(0) },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("上一步", maxLines = 1, softWrap = false)
                    }
                } else {
                    Text(
                        text = "${activeSchool.displayName} · 欢迎向导",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (currentStepIndex < steps.lastIndex) {
                    Button(
                        onClick = { currentStepIndex = (currentStepIndex + 1).coerceAtMost(steps.lastIndex) },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = "下一步：${steps[currentStepIndex + 1].shortTitle}",
                            maxLines = 1,
                            softWrap = false,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                repository.completeOnboarding()
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("进入日在校园", maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingWelcomeFeaturesStep(
    activeSchool: SchoolProfile,
    onJumpToStep: (OnboardingWizardStep) -> Unit,
    onPinSampleWidget: () -> Unit,
) {
    // 顶部品牌欢迎卡
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.primaryAccentCardColor,
        borderColor = XPeachTheme.readablePrimary.copy(alpha = 0.28f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "日在校园",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    XPeachStatusBadge(
                        text = activeSchool.displayName,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = XPeachTheme.readablePrimary,
                    )
                }
                Text(
                    text = "专为高校同学打造的课表、地图、图书馆与校园服务全能工作台。仅需 1 分钟即可完成个性化配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 2x2 Bento 四大能力矩阵
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            XPeachBentoTile(
                title = "智慧课表",
                badgeText = "1-10节",
                subtitle = "连堂小课间拆解、重课冲突分栏、调休停课自动换算",
                icon = Icons.Rounded.CalendarMonth,
                accentColor = XPeachTheme.readablePrimary,
                onClick = { onJumpToStep(OnboardingWizardStep.ACADEMIC_DATA_SETUP) },
                modifier = Modifier.weight(1f),
            )
            XPeachBentoTile(
                title = "校园与室内地图",
                badgeText = "双校区",
                subtitle = "莲花街与嵩山路教学楼检索、逐层室内教室平面图与步行路线",
                icon = Icons.Rounded.Explore,
                accentColor = Color(0xFF0D9488),
                onClick = { onJumpToStep(OnboardingWizardStep.SCHOOL_AND_PERMISSIONS) },
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            XPeachBentoTile(
                title = "图书馆与快递",
                badgeText = "一站聚合",
                subtitle = "馆藏图书查询、座位预约、菜鸟驿站取件码与寝室电费监控",
                icon = Icons.Rounded.LocalLibrary,
                accentColor = Color(0xFF7C3AED),
                onClick = { onJumpToStep(OnboardingWizardStep.ACADEMIC_DATA_SETUP) },
                modifier = Modifier.weight(1f),
            )
            XPeachBentoTile(
                title = "液态毛玻璃主题",
                badgeText = "新视觉",
                subtitle = "一键同步手机桌面壁纸与主题色，自由调节磨砂玻璃透光度",
                icon = Icons.Rounded.Palette,
                accentColor = Color(0xFF0284C7),
                onClick = { onJumpToStep(OnboardingWizardStep.VISUAL_THEME_STUDIO) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    // 桌面小组件快捷添加卡片
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Widgets,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Bento 桌面小组件套装",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "支持 2×2 下一节、4×2 今日双栏、4×2 驿站取件码与 4×4 周课表",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilledTonalButton(
                onClick = onPinSampleWidget,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("添加 2×2", maxLines = 1, softWrap = false)
            }
        }
    }

    // 本地优先与隐私安全承诺
    XPeachWell(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = XPeachTheme.onSuccessContainer,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "本地优先存储 · 硬件级凭据加密",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "你的学号与教务密码仅使用 Android Keystore 硬件密钥加密保存在本机，课表与成绩支持完全离线查看。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OnboardingVisualThemeStudioStep(
    prefs: AppPreferencesState,
    onSelectDesignStyle: (UiDesignStyle) -> Unit,
    onSyncDesktopWallpaper: () -> Unit,
    onExtractDesktopColors: () -> Unit,
    onPickGalleryWallpaper: () -> Unit,
    onClearWallpaper: () -> Unit,
    onSelectThemeMode: (String) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onSelectPalette: (String, Long?) -> Unit,
    onUpdateGlassParams: (Float?, Float?, Float?) -> Unit,
) {
    val isLiquidGlass = XPeachTheme.isLiquidGlass
    val hasCustomWallpaper = !prefs.customWallpaperPath.isNullOrBlank()
    var cardAlphaDraft by remember(prefs.wallpaperCardAlpha) {
        mutableFloatStateOf(prefs.wallpaperCardAlpha)
    }
    var blurDraft by remember(prefs.wallpaperBlurDp) {
        mutableFloatStateOf(prefs.wallpaperBlurDp)
    }

    // 1. 双设计语言并排选择卡
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "选择全局设计语言",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            XPeachStatusDotText(
                text = if (isLiquidGlass) "透明液态毛玻璃" else "Flutter 经典质感",
                dotColor = XPeachTheme.readablePrimary,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UiDesignStyle.entries.forEach { style ->
                val selected = (style == UiDesignStyle.LIQUID_GLASS && isLiquidGlass) ||
                    (style == UiDesignStyle.FLUTTER_M3 && !isLiquidGlass)
                val cardShape = RoundedCornerShape(16.dp)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(cardShape)
                        .background(
                            if (selected) {
                                XPeachTheme.primaryAccentCardColor
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            }
                        )
                        .border(
                            width = if (selected) 1.5.dp else 0.75.dp,
                            color = if (selected) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.outlineVariant,
                            shape = cardShape,
                        )
                        .clickable { onSelectDesignStyle(style) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (style == UiDesignStyle.LIQUID_GLASS) {
                                Icons.Rounded.AutoAwesome
                            } else {
                                Icons.Rounded.Palette
                            },
                            contentDescription = null,
                            tint = if (selected) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        if (selected) {
                            XPeachStatusBadge(
                                text = "当前使用",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = XPeachTheme.readablePrimary,
                            )
                        }
                    }
                    Text(
                        text = style.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = style.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 桌面壁纸与毛玻璃调节工作台
        XPeachWell(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "桌面背景与毛玻璃透光调节",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                XPeachFilterPill(
                    label = "获取手机桌面背景",
                    selected = hasCustomWallpaper && isLiquidGlass,
                    compact = true,
                    onClick = onSyncDesktopWallpaper,
                )
                XPeachFilterPill(
                    label = "仅提取桌面主题色",
                    selected = false,
                    compact = true,
                    onClick = onExtractDesktopColors,
                )
                XPeachFilterPill(
                    label = if (hasCustomWallpaper) "更换相册背景" else "从相册选图",
                    selected = hasCustomWallpaper && !isLiquidGlass,
                    compact = true,
                    onClick = onPickGalleryWallpaper,
                )
                if (hasCustomWallpaper) {
                    XPeachFilterPill(
                        label = "清除背景图",
                        selected = false,
                        compact = true,
                        onClick = onClearWallpaper,
                    )
                }
            }

            if (isLiquidGlass || hasCustomWallpaper) {
                XPeachInsetDivider(verticalPadding = 8.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "毛玻璃卡片不透明度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${(cardAlphaDraft * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary,
                    )
                }
                Slider(
                    value = cardAlphaDraft,
                    onValueChange = {
                        cardAlphaDraft = it
                        onUpdateGlassParams(null, null, it)
                    },
                    valueRange = ThemeVisualDefaults.WALLPAPER_CARD_ALPHA_RANGE,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "背景磨砂模糊半径",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${blurDraft.roundToInt()} dp",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary,
                    )
                }
                Slider(
                    value = blurDraft,
                    onValueChange = {
                        blurDraft = it
                        onUpdateGlassParams(null, it, null)
                    },
                    valueRange = ThemeVisualDefaults.WALLPAPER_BLUR_DP_RANGE,
                )
            }
        }
    }

    // 2. 深浅模式与预设色板
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "深浅外观与主题色板",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        val modePairs = remember {
            listOf(
                "SYSTEM" to "跟随系统",
                "LIGHT" to "浅色模式",
                "DARK" to "深色模式",
            )
        }
        val activeModePair = modePairs.firstOrNull { it.first == prefs.themeMode } ?: modePairs.first()
        XPeachSegmentedTabs(
            options = modePairs,
            selectedOption = activeModePair,
            labelProvider = { it.second },
            onSelect = { onSelectThemeMode(it.first) },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Spacer(modifier = Modifier.height(6.dp))
            XPeachSwitchRow(
                title = "跟随系统动态取色 (Material You)",
                subtitle = "自动同步 Android 12+ 系统壁纸主色调",
                checked = prefs.useDynamicColor,
                onCheckedChange = onToggleDynamicColor,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PresetThemePalette.entries.forEach { palette ->
                val isSelected = !prefs.useDynamicColor && prefs.themePaletteKey == palette.key
                val swatchColor = Color(palette.seedArgb)
                XPeachFilterPill(
                    label = palette.label,
                    selected = isSelected,
                    compact = true,
                    onClick = { onSelectPalette(palette.key, null) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(
                                    if (palette.activatesLiquidGlass) {
                                        Brush.linearGradient(
                                            listOf(Color(0xFF38BDF8), Color(0xFF818CF8))
                                        )
                                    } else {
                                        Brush.linearGradient(listOf(swatchColor, swatchColor))
                                    }
                                )
                        )
                    },
                )
            }
        }
    }

    // 3. 实时课程卡片视觉预览
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "实时视觉预览 · 算法设计与分析",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "08:30–10:05 · 莲花街校区 5号教学楼 5204",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            XPeachStatusBadge(
                text = "第 1-2 节",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = XPeachTheme.readablePrimary,
            )
        }
    }
}

@Composable
private fun OnboardingSchoolAndPermissionsStep(
    activeSchool: SchoolProfile,
    isHautSchool: Boolean,
    prefs: AppPreferencesState,
    hasLocationPermission: Boolean,
    canPostNotifications: Boolean,
    canScheduleExactAlarms: Boolean,
    onOpenSchoolCenter: () -> Unit,
    onSelectCampusId: (String) -> Unit,
    onRequestLocationPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onToggleRemindersEnabled: (Boolean) -> Unit,
    onSelectClassLeadMinutes: (Int) -> Unit,
) {
    // 1. 学校与校区选择
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.School,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "当前学校：${activeSchool.displayName}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "支持多校区地图切换、其它高校导入或纯离线课表模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(
                onClick = onOpenSchoolCenter,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("切换学校", maxLines = 1, softWrap = false)
            }
        }

        if (isHautSchool) {
            XPeachInsetDivider(verticalPadding = 10.dp)
            Text(
                text = "默认活动校区",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            val campusOptions = remember {
                listOf(
                    "auto" to "GPS 自动识别",
                    "lianhuajie" to "莲花街校区",
                    "songshanlu" to "嵩山路校区",
                )
            }
            val selectedPair = campusOptions.firstOrNull { it.first == prefs.selectedCampusId }
                ?: campusOptions.first()
            XPeachSegmentedTabs(
                options = campusOptions,
                selectedOption = selectedPair,
                labelProvider = { it.second },
                onSelect = { onSelectCampusId(it.first) },
            )
        }
    }

    // 2. 地图定位与通知提醒权限一站式配置
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "权限与课前提醒设置",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // 位置权限行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "校区定位权限",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "用于在校园地图中显示实时位置、最近教学楼与步行路线",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hasLocationPermission) {
                XPeachStatusDotText(
                    text = "已开启",
                    dotColor = MaterialTheme.colorScheme.tertiary,
                    textColor = XPeachTheme.onSuccessContainer,
                )
            } else {
                FilledTonalButton(
                    onClick = onRequestLocationPermission,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("开启定位", maxLines = 1, softWrap = false)
                }
            }
        }

        XPeachInsetDivider(verticalPadding = 8.dp)

        // 通知权限行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NotificationsActive,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "上课与考试通知权限",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "课前自动发送教室位置提醒与实况倒计时通知",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canPostNotifications) {
                XPeachStatusDotText(
                    text = "已开启",
                    dotColor = MaterialTheme.colorScheme.tertiary,
                    textColor = XPeachTheme.onSuccessContainer,
                )
            } else {
                FilledTonalButton(
                    onClick = onRequestNotificationPermission,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("开启通知", maxLines = 1, softWrap = false)
                }
            }
        }

        if (!canScheduleExactAlarms) {
            XPeachInsetDivider(verticalPadding = 8.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "精确闹钟唤醒权限",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "确保手机锁屏休眠时课前提醒与番茄钟准点响起",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onOpenExactAlarmSettings,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text("前往授权", maxLines = 1, softWrap = false)
                }
            }
        }

        XPeachInsetDivider(verticalPadding = 8.dp)

        XPeachSwitchRow(
            title = "启用课前与考试自动提醒",
            subtitle = "默认在每节课上课前提前发送教室与节次提醒",
            checked = prefs.reminderConfig.enabled,
            onCheckedChange = onToggleRemindersEnabled,
        )

        if (prefs.reminderConfig.enabled) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "课前提前提醒时间",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (lead in listOf(15, 20, 30)) {
                        XPeachFilterPill(
                            label = "${lead}分钟",
                            selected = prefs.reminderConfig.classLeadMinutes == lead,
                            compact = true,
                            onClick = { onSelectClassLeadMinutes(lead) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingAcademicDataSetupStep(
    repository: CampusRepository,
    activeSchool: SchoolProfile,
    isHautSchool: Boolean,
    initialXnm: String,
    initialXqm: String,
    onOpenAdvancedImporter: () -> Unit,
    onCompleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var studentIdInput by rememberSaveable { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    val candidateYears = remember { AcademicTermResolver.candidateAcademicYearCodes() }
    var selectedXnm by rememberSaveable(initialXnm) {
        mutableStateOf(initialXnm.ifBlank { AcademicTermResolver.defaultAcademicYearCode() })
    }
    var selectedXqm by rememberSaveable(initialXqm) {
        mutableStateOf(initialXqm.ifBlank { AcademicTermResolver.defaultSemesterCode() })
    }

    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusMessage by remember { mutableStateOf<String?>(null) }
    var isErrorStatus by remember { mutableStateOf(false) }

    // 1. 教务在线同步卡片
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudSync,
                    contentDescription = null,
                    tint = XPeachTheme.readablePrimary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = if (isHautSchool) "方式一：统一身份认证一键同步" else "课表导入与建档",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            XPeachStatusDotText(
                text = if (isHautSchool) "官方直连" else activeSchool.displayName
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isHautSchool) {
            val triggerLoginAndSync: () -> Unit = {
                if (!isSyncing && studentIdInput.isNotBlank() && passwordInput.isNotBlank()) {
                    focusManager.clearFocus()
                    scope.launch {
                        val plainPwd = passwordInput
                        isSyncing = true
                        isErrorStatus = false
                        syncStatusMessage = "正在同步课表、考试安排与成绩记录..."
                        val result = HautLiveAcademicClient.fetchLiveAcademicData(
                            studentId = studentIdInput.trim(),
                            passwordPlain = plainPwd,
                            xnm = selectedXnm,
                            xqm = selectedXqm,
                        )
                        passwordInput = ""
                        isSyncing = false

                        result.onSuccess { syncData ->
                            val applied = runCatching {
                                repository.applyLiveSyncData(syncData, overwriteUserConflicts = false)
                            }
                            if (applied.isFailure) {
                                isErrorStatus = true
                                syncStatusMessage = "保存同步数据失败，请重试（已有数据不受影响）"
                                return@onSuccess
                            }
                            val saved = runCatching {
                                repository.saveAuthorizedSyncCredentials(
                                    studentIdInput.trim(),
                                    plainPwd,
                                    selectedXnm,
                                    selectedXqm,
                                )
                            }
                            repository.setAutoSyncEnabled(saved.isSuccess)
                            syncStatusMessage = syncData.syncSummaryText() +
                                if (saved.isFailure) "\n凭据保存失败，已关闭后台自动更新" else ""
                            repository.completeOnboarding()
                            onCompleted()
                        }.onFailure { err ->
                            isErrorStatus = true
                            syncStatusMessage = err.message ?: "登录或同步失败，请检查学号密码"
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
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = passwordInput,
                onValueChange = { passwordInput = it },
                label = { Text("统一身份认证密码") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { triggerLoginAndSync() }),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("学年", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                for (yr in candidateYears) {
                    val startInt = yr.toIntOrNull() ?: 2025
                    XPeachFilterPill(
                        label = "$startInt-${startInt + 1}",
                        selected = selectedXnm == yr,
                        compact = true,
                        onClick = { selectedXnm = yr },
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("学期", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                XPeachFilterPill(
                    label = "第 1 学期 (秋)",
                    selected = selectedXqm == AcademicTermResolver.AUTUMN_SEMESTER_CODE,
                    compact = true,
                    onClick = { selectedXqm = AcademicTermResolver.AUTUMN_SEMESTER_CODE },
                )
                XPeachFilterPill(
                    label = "第 2 学期 (春)",
                    selected = selectedXqm == AcademicTermResolver.SPRING_SEMESTER_CODE,
                    compact = true,
                    onClick = { selectedXqm = AcademicTermResolver.SPRING_SEMESTER_CODE },
                )
            }

            XPeachFeedbackBanner(
                message = syncStatusMessage,
                tone = if (isErrorStatus) XPeachFeedbackTone.DANGER else XPeachFeedbackTone.INFO,
                modifier = Modifier.padding(top = 8.dp),
                onDismiss = { syncStatusMessage = null },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                enabled = !isSyncing && studentIdInput.isNotBlank() && passwordInput.isNotBlank(),
                onClick = triggerLoginAndSync,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在同步并建档...", maxLines = 1, softWrap = false)
                } else {
                    Text(
                        text = "登录并同步 (${AcademicTermResolver.formatTermLabel(selectedXnm, selectedXqm)})",
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        } else {
            Text(
                text = "当前所选学校支持通过教务网页抓包、CSV/ICS 标准课表文件导入或手动录入课程。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 2. 多源导入与演示/空白体验入口卡片
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "更多建档与体验方式",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(
                onClick = onOpenAdvancedImporter,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("网页 / CSV 导入", maxLines = 1, softWrap = false)
            }

            if (isHautSchool) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { repository.resetToDemoSeedData() }
                            repository.completeOnboarding()
                            onCompleted()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("加载演示课表", maxLines = 1, softWrap = false)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        TextButton(
            onClick = {
                scope.launch {
                    repository.completeOnboarding()
                    onCompleted()
                }
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (isHautSchool) "暂不登录，直接进入空白主页 ›" else "进入离线课表主页 ›",
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
