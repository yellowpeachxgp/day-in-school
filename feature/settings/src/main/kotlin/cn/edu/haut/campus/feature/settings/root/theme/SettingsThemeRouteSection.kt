package cn.edu.haut.campus.feature.settings.root.theme

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
import cn.edu.haut.campus.feature.settings.root.network.SettingsNetworkRouteSection
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.haut.campus.feature.settings.root.theme.SettingsThemeViewModel


/** 设置「主题与外观」分区 Route：平台壁纸权限/图片选择在此装配，UI 保持纯渲染。 */
@Composable
fun SettingsThemeRouteSection(
    repository: CampusRepository,
    onFeedback: (String) -> Unit,
    onRefreshWidgets: () -> Unit,
    viewModelStoreOwner: ViewModelStoreOwner
) {
    val viewModel: SettingsThemeViewModel = viewModel(
        viewModelStoreOwner = viewModelStoreOwner,
        key = "settings-theme-" + repository.profileId + "-" + repository.sessionGeneration,
        factory = SettingsThemeViewModel.factory(repository)
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.prefs
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

            // 7.5 主题、透明液态毛玻璃 UI、系统桌面背景同步、调色盘与模块化卡片布局
            val runDesktopWallpaperSync: () -> Unit = {
                scope.launch {
                    onFeedback("正在同步系统桌面背景与主题色...")
                    WallpaperThemeEngine.syncSystemDesktopWallpaper(context)
                        .onSuccess { result ->
                            viewModel.saveCustomWallpaperTheme(
                                localPath = result.localFilePath,
                                primarySeedArgb = result.primarySeedArgb,
                                extractedSwatches = result.extractedSwatches,
                                enableLiquidGlass = true
                            )
                            onRefreshWidgets()
                            val hexStr = String.format("#%06X", 0xFFFFFF and result.primarySeedArgb.toInt())
                            onFeedback("${result.statusMessage} ($hexStr)")
                        }
                        .onFailure { err ->
                            onFeedback("获取桌面背景失败：${err.message ?: "请稍后重试"}")
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
                        onFeedback("正在应用背景图片...")
                        WallpaperThemeEngine.importWallpaperAndExtractPalette(context, uri)
                            .onSuccess { result ->
                                viewModel.saveCustomWallpaperTheme(
                                    localPath = result.localFilePath,
                                    primarySeedArgb = result.primarySeedArgb,
                                    extractedSwatches = result.extractedSwatches
                                )
                                onRefreshWidgets()
                                val hexStr = String.format("#%06X", 0xFFFFFF and result.primarySeedArgb.toInt())
                                onFeedback("已应用自定义背景图 ($hexStr)")
                            }
                            .onFailure { err ->
                                onFeedback("导入背景图失败：${err.message ?: "请换一张图片重试"}")
                            }
                    }
                }
            }

            ThemePaletteAndCardLayoutSection(
                prefs = prefs,
                onSelectThemeMode = { mode ->
                    scope.launch { viewModel.setThemeMode(mode) }
                },
                onSelectUiDesignStyle = { styleKey ->
                    scope.launch {
                        viewModel.setUiDesignStyle(styleKey)
                        onRefreshWidgets()
                        val chosenStyle = UiDesignStyle.fromKey(styleKey)
                        onFeedback("已切换为「${chosenStyle.label}」视觉风格")
                    }
                },
                onApplyCuratedPreset = { styleKey, paletteKey, title ->
                    scope.launch {
                        viewModel.applyCuratedThemePreset(styleKey, paletteKey)
                        onRefreshWidgets()
                        onFeedback("已应用「$title」清新套装")
                    }
                },
                onToggleDynamicColor = { enabled ->
                    scope.launch { viewModel.setUseDynamicColor(enabled) }
                },
                onSelectPalette = { key, seedArgb ->
                    scope.launch {
                        viewModel.setThemePalette(key, seedArgb)
                        onRefreshWidgets()
                        onFeedback(
                            when (key) {
                                PresetThemePalette.LIQUID_GLASS.key -> "已启用 Liquid Glass 主题"
                                PresetThemePalette.HAUT_BLUE.key -> "已切换为工大校徽深蓝 (#053968)"
                                else -> "已更新主题配色"
                            }
                        )
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
                                viewModel.saveExtractedDesktopSwatches(
                                    primarySeedArgb = primary,
                                    extractedSwatches = swatches
                                )
                                onRefreshWidgets()
                                val hexStr = String.format("#%06X", 0xFFFFFF and primary.toInt())
                                onFeedback("已提取并应用系统桌面主题色 ($hexStr)")
                            }
                            .onFailure { err ->
                                onFeedback("提取桌面主题色失败：${err.message ?: "请重试"}")
                            }
                    }
                },
                onPickWallpaperImage = {
                    wallpaperPickerLauncher.launch("image/*")
                },
                onUpdateWallpaperParams = { alpha, blurDp, cardAlpha ->
                    scope.launch {
                        viewModel.updateWallpaperVisualParams(
                            alpha = alpha,
                            blurDp = blurDp,
                            cardAlpha = cardAlpha
                        )
                    }
                },
                onResetWallpaperParams = {
                    scope.launch {
                        viewModel.resetWallpaperVisualParams()
                        onFeedback("已恢复默认 Liquid Glass 参数")
                    }
                },
                onClearWallpaper = {
                    scope.launch {
                        viewModel.clearCustomWallpaperTheme()
                        onFeedback("已移除自定义背景图片")
                    }
                },
                onUpdateCardLayout = { order, hidden, fullWidth ->
                    scope.launch {
                        viewModel.updateHomeCardLayout(order, hidden, fullWidth)
                        onFeedback("已更新首页卡片布局")
                    }
                },
                onResetCardLayout = {
                    scope.launch {
                        viewModel.resetHomeCardLayout()
                        onFeedback("已恢复默认首页卡片布局")
                    }
                }
            )

}
