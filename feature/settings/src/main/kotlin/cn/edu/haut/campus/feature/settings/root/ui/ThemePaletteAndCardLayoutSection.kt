package cn.edu.haut.campus.feature.settings.root.ui

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
internal fun ThemePaletteAndCardLayoutSection(
    prefs: AppPreferencesState,
    onSelectThemeMode: (String) -> Unit,
    onSelectUiDesignStyle: (String) -> Unit,
    onApplyCuratedPreset: (String, String, String) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onSelectPalette: (String, Long?) -> Unit,
    onSyncDesktopWallpaper: () -> Unit,
    onExtractDesktopColors: () -> Unit,
    onPickWallpaperImage: () -> Unit,
    onUpdateWallpaperParams: (alpha: Float?, blurDp: Float?, cardAlpha: Float?) -> Unit,
    onResetWallpaperParams: () -> Unit,
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
    var hue by remember(prefs.customThemeSeedArgb) { mutableFloatStateOf(initialHsv[0].coerceIn(0f, 360f)) }
    var saturation by remember(prefs.customThemeSeedArgb) { mutableFloatStateOf(initialHsv[1].coerceIn(0.05f, 1f)) }
    var brightness by remember(prefs.customThemeSeedArgb) { mutableFloatStateOf(initialHsv[2].coerceIn(0.20f, 1f)) }

    val customColorInt = remember(hue, saturation, brightness) {
        android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness))
    }
    val customHexText = remember(customColorInt) {
        String.format("#%06X", 0xFFFFFF and customColorInt)
    }
    var hexInput by remember(prefs.customThemeSeedArgb) { mutableStateOf(customHexText) }

    val currentStyle = if (prefs.themePaletteKey == PresetThemePalette.LIQUID_GLASS.key) {
        UiDesignStyle.LIQUID_GLASS
    } else {
        UiDesignStyle.fromKey(prefs.uiDesignStyle)
    }
    val isLiquidGlassActive = currentStyle == UiDesignStyle.LIQUID_GLASS
    val hasCustomWallpaper = !prefs.customWallpaperPath.isNullOrBlank()
    val showGlassControls = isLiquidGlassActive || hasCustomWallpaper

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
                    text = "主题与外观",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "界面风格、主题配色与首页卡片",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val currentPaletteLabel = when {
                isLiquidGlassActive && hasCustomWallpaper -> "Liquid Glass · 桌面背景"
                isLiquidGlassActive -> "Liquid Glass"
                hasCustomWallpaper -> "${currentStyle.label} · 壁纸"
                prefs.useDynamicColor -> "${currentStyle.label} · 系统取色"
                prefs.themePaletteKey == "CUSTOM" -> "${currentStyle.label} · $customHexText"
                else -> "${currentStyle.label} · ${PresetThemePalette.fromKey(prefs.themePaletteKey)?.label ?: "薄荷晨风"}"
            }
            XPeachStatusDotText(
                text = currentPaletteLabel,
                dotColor = XPeachTheme.readablePrimary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 0. 界面风格选择
        val styleRows = remember { UiDesignStyle.entries.chunked(2) }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            styleRows.forEach { rowStyles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowStyles.forEach { style ->
                        val selected = currentStyle == style
                        val cardShape = RoundedCornerShape(14.dp)
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
                                    shape = cardShape
                                )
                                .clickable { onSelectUiDesignStyle(style.key) }
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = style.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selected) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                XPeachStatusBadge(
                                    text = if (selected) "使用中" else style.badgeText,
                                    containerColor = if (selected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                                    },
                                    contentColor = if (selected) {
                                        XPeachTheme.readablePrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            Text(
                                text = style.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 0.2 预设主题组合
        Text(
            text = "预设主题组合",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        val curatedSuites = remember {
            listOf(
                Triple("FRESH_BREEZE", "MINT_BREEZE", "薄荷晨风"),
                Triple("CREAM_PASTEL", "PEACH_OOLONG", "白桃乌龙"),
                Triple("FRESH_BREEZE", "SKY_SODA", "晴空苏打"),
                Triple("FRESH_BREEZE", "MATCHA_CREAM", "抹茶奶盖"),
                Triple("CREAM_PASTEL", "LAVENDER_MIST", "海盐香芋"),
                Triple("CREAM_PASTEL", "POMELO_SUN", "西柚暖阳"),
                Triple("LIQUID_GLASS", "LIQUID_GLASS", "Liquid Glass"),
                Triple("FLUTTER_M3", "HAUT_BLUE", "工大蓝")
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            curatedSuites.forEach { (sKey, pKey, title) ->
                val isSuiteSelected = !prefs.useDynamicColor &&
                    currentStyle.key == sKey &&
                    prefs.themePaletteKey == pKey
                XPeachFilterPill(
                    label = title,
                    selected = isSuiteSelected,
                    compact = true,
                    onClick = { onApplyCuratedPreset(sKey, pKey, title) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 0.5 背景图片与毛玻璃设置
        XPeachWell(
            modifier = Modifier.fillMaxWidth(),
            containerColor = if (isLiquidGlassActive) {
                XPeachTheme.primaryAccentCardColor
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ) {
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
                        text = if (isLiquidGlassActive) "Liquid Glass 已启用" else "背景图片与 Liquid Glass",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (hasCustomWallpaper) {
                            "已设置背景图，可在下方调节透明度与模糊度"
                        } else {
                            "使用系统壁纸或相册图片作为背景"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                XPeachStatusBadge(
                    text = if (isLiquidGlassActive) "已启用" else "标准",
                    containerColor = if (isLiquidGlassActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    contentColor = if (isLiquidGlassActive) {
                        XPeachTheme.readablePrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                XPeachFilterPill(
                    label = "使用系统壁纸",
                    selected = hasCustomWallpaper && isLiquidGlassActive,
                    compact = true,
                    onClick = onSyncDesktopWallpaper
                )
                XPeachFilterPill(
                    label = "提取壁纸颜色",
                    selected = false,
                    compact = true,
                    onClick = onExtractDesktopColors
                )
                XPeachFilterPill(
                    label = if (hasCustomWallpaper) "更换相册图" else "相册背景图",
                    selected = hasCustomWallpaper && !isLiquidGlassActive,
                    compact = true,
                    onClick = onPickWallpaperImage
                )
                if (hasCustomWallpaper) {
                    XPeachFilterPill(
                        label = "移除背景图",
                        selected = false,
                        compact = true,
                        onClick = onClearWallpaper
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. 深浅色外观模式与系统动态取色
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
                        compact = true,
                        onClick = { onSelectThemeMode(modeKey) }
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "系统动态取色",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "跟随系统壁纸自动调整主题色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                XPeachSwitch(
                    checked = prefs.useDynamicColor,
                    onCheckedChange = onToggleDynamicColor
                )
            }
        }

        // 1.5 壁纸提取色板与毛玻璃透明度/模糊度控制面板
        if (prefs.extractedWallpaperColors.isNotEmpty() || showGlassControls) {
            XPeachInsetDivider(verticalPadding = 10.dp)
            Column(modifier = Modifier.fillMaxWidth()) {
                if (prefs.extractedWallpaperColors.isNotEmpty()) {
                    Text(
                        text = "桌面 / 壁纸提取主题色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (swatchArgb in prefs.extractedWallpaperColors) {
                            val isSelectedSwatch = !prefs.useDynamicColor &&
                                prefs.themePaletteKey == "CUSTOM" &&
                                (prefs.customThemeSeedArgb and 0xFFFFFFL) == (swatchArgb and 0xFFFFFFL)
                            val swatchHex = String.format("#%06X", 0xFFFFFF and swatchArgb.toInt())
                            val rawSwatchColor = Color(swatchArgb)
                            val readableSwatch = XPeachTheme.readableAccent(rawSwatchColor)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (isSelectedSwatch) XPeachTheme.courseContainer(swatchArgb, active = true)
                                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
                                    )
                                    .border(
                                        width = if (isSelectedSwatch) 1.5.dp else 0.7.dp,
                                        color = if (isSelectedSwatch) readableSwatch else MaterialTheme.colorScheme.outlineVariant,
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
                                        .background(rawSwatchColor)
                                        .border(
                                            0.6.dp,
                                            if (XPeachTheme.isDark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.12f),
                                            CircleShape
                                        )
                                )
                                Text(
                                    text = swatchHex,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelectedSwatch) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelectedSwatch) readableSwatch else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                if (showGlassControls) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "背景与模糊度",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            XPeachFilterPill(
                                label = "通透 56%",
                                selected = abs(localCardAlpha - 0.56f) < 0.04f,
                                compact = true,
                                onClick = {
                                    localCardAlpha = 0.56f
                                    localWallpaperBlur = 18f
                                    localWallpaperAlpha = 0.62f
                                    onUpdateWallpaperParams(0.62f, 18f, 0.56f)
                                }
                            )
                            XPeachFilterPill(
                                label = "标准 76%",
                                selected = abs(localCardAlpha - 0.76f) < 0.04f,
                                compact = true,
                                onClick = {
                                    localCardAlpha = 0.76f
                                    localWallpaperBlur = 16f
                                    localWallpaperAlpha = 0.55f
                                    onUpdateWallpaperParams(0.55f, 16f, 0.76f)
                                }
                            )
                            XPeachFilterPill(
                                label = "重置",
                                selected = false,
                                compact = true,
                                onClick = {
                                    localCardAlpha = 0.76f
                                    localWallpaperBlur = 16f
                                    localWallpaperAlpha = 0.55f
                                    onResetWallpaperParams()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "卡片不透明度 (${(localCardAlpha * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = ThemeVisualDefaults.clampGlassOpacity(localCardAlpha),
                        onValueChange = {
                            localCardAlpha = it
                            onUpdateWallpaperParams(null, null, it)
                        },
                        valueRange = ThemeVisualDefaults.MIN_GLASS_OPACITY..ThemeVisualDefaults.MAX_GLASS_OPACITY
                    )

                    Text(
                        text = "背景模糊度 (${localWallpaperBlur.toInt()} dp)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = ThemeVisualDefaults.clampBlurRadiusDp(localWallpaperBlur),
                        onValueChange = {
                            localWallpaperBlur = it
                            onUpdateWallpaperParams(null, it, null)
                        },
                        valueRange = ThemeVisualDefaults.MIN_BLUR_RADIUS_DP..ThemeVisualDefaults.MAX_BLUR_RADIUS_DP
                    )

                    Text(
                        text = "背景不透明度 (${(localWallpaperAlpha * 100).toInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = localWallpaperAlpha.coerceIn(0.05f, 1.0f),
                        onValueChange = {
                            localWallpaperAlpha = it
                            onUpdateWallpaperParams(it, null, null)
                        },
                        valueRange = 0.05f..1.0f
                    )
                }
            }
        }

        XPeachInsetDivider(verticalPadding = 10.dp)

        // 2. 主题配色
        Text(
            text = "主题配色",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rowPalettes in PresetThemePalette.entries.chunked(3)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (palette in rowPalettes) {
                        val isSelected = !prefs.useDynamicColor && prefs.themePaletteKey == palette.key
                        val swatchColor = Color(palette.seedArgb)
                        val readableSwatch = XPeachTheme.readableAccent(swatchColor)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) {
                                        XPeachTheme.courseContainer(palette.seedArgb, active = true)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
                                    }
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.7.dp,
                                    color = if (isSelected) {
                                        readableSwatch
                                    } else if (XPeachTheme.isDark || XPeachTheme.isLiquidGlass) {
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                                    } else {
                                        Color.Transparent
                                    },
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
                                    .background(
                                        if (palette.activatesLiquidGlass) {
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    Color(0xFF38BDF8),
                                                    Color(0xFF818CF8),
                                                    Color(0xFF2DD4BF)
                                                )
                                            )
                                        } else {
                                            Brush.linearGradient(listOf(swatchColor, swatchColor))
                                        }
                                    )
                                    .border(
                                        0.8.dp,
                                        if (XPeachTheme.isDark) Color.White.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.10f),
                                        CircleShape
                                    )
                            )
                            Text(
                                text = palette.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) readableSwatch else MaterialTheme.colorScheme.onSurface,
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
                        text = "调节滑块或输入色号自定义配色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            XPeachFilterPill(
                label = if (expandCustomPalette) "收起" else "调色盘",
                selected = prefs.themePaletteKey == "CUSTOM" && !prefs.useDynamicColor,
                compact = true,
                onClick = { expandCustomPalette = !expandCustomPalette }
            )
        }

        AnimatedVisibility(visible = expandCustomPalette) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                XPeachInsetDivider(verticalPadding = 6.dp)
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
                    text = "色相 (${hue.toInt()}°)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Slider(
                    value = hue.coerceIn(0f, 360f),
                    onValueChange = {
                        hue = it
                        val nextColor = android.graphics.Color.HSVToColor(floatArrayOf(it, saturation, brightness))
                        hexInput = String.format("#%06X", 0xFFFFFF and nextColor)
                    },
                    valueRange = 0f..360f
                )

                Text(
                    text = "饱和度 (${(saturation * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = saturation.coerceIn(0.05f, 1f),
                    onValueChange = {
                        saturation = it
                        val nextColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, it, brightness))
                        hexInput = String.format("#%06X", 0xFFFFFF and nextColor)
                    },
                    valueRange = 0.05f..1f
                )

                Text(
                    text = "明度 (${(brightness * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = brightness.coerceIn(0.20f, 1f),
                    onValueChange = {
                        brightness = it
                        val nextColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, it))
                        hexInput = String.format("#%06X", 0xFFFFFF and nextColor)
                    },
                    valueRange = 0.20f..1f
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
                                    hue = arr[0].coerceIn(0f, 360f)
                                    saturation = arr[1].coerceIn(0.05f, 1f)
                                    brightness = arr[2].coerceIn(0.20f, 1f)
                                }
                            }
                        },
                        label = { Text("色号 (#RRGGBB)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )
                    val customBtnBg = Color(customColorInt)
                    val customBtnFg = if (customBtnBg.luminance() > 0.45f) Color(0xFF0F172A) else Color.White
                    Button(
                        onClick = {
                            val seedLong = 0xFF000000L or (customColorInt.toLong() and 0xFFFFFFL)
                            onSelectPalette("CUSTOM", seedLong)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = customBtnBg,
                            contentColor = customBtnFg
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("应用配色", color = customBtnFg, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        XPeachInsetDivider(verticalPadding = 10.dp)

        // 4. 首页模块化卡片排序与显隐管理
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                Text(
                    text = "首页卡片管理",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val totalCards = HomeCardModule.defaultOrderIds.size
                val visibleCount = (totalCards - prefs.hiddenHomeCards.size).coerceAtLeast(0)
                Text(
                    text = "已显示 $visibleCount / $totalCards 个卡片 · 可调整顺序与宽度",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            XPeachFilterPill(
                label = if (expandCardManager) "收起" else "调整卡片",
                selected = expandCardManager,
                compact = true,
                onClick = { expandCardManager = !expandCardManager }
            )
        }

        AnimatedVisibility(visible = expandCardManager) {
            val currentOrder = remember(prefs.homeCardOrder) {
                HomeCardModule.normalizeOrderIds(prefs.homeCardOrder)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                XPeachInsetDivider(verticalPadding = 4.dp)
                currentOrder.forEachIndexed { index, cardId ->
                    if (index > 0) {
                        XPeachInsetDivider(verticalPadding = 6.dp)
                    }
                    val title = HomeCardModule.fromId(cardId)?.title ?: cardId
                    val isVisible = cardId !in prefs.hiddenHomeCards
                    val isFull = cardId in prefs.fullWidthHomeCards

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
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
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp)
                            )
                            XPeachStatusDotText(
                                text = if (!isVisible) "已隐藏" else if (isFull) "大卡片" else "小卡片",
                                dotColor = if (isVisible) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                XPeachFilterPill(
                                    label = if (isFull) "切换小卡" else "切换大卡",
                                    selected = isFull,
                                    compact = true,
                                    onClick = {
                                        val nextFull = if (isFull) prefs.fullWidthHomeCards - cardId else prefs.fullWidthHomeCards + cardId
                                        onUpdateCardLayout(currentOrder, prefs.hiddenHomeCards, nextFull)
                                    }
                                )
                                XPeachFilterPill(
                                    label = if (isVisible) "显示中" else "已隐藏",
                                    selected = isVisible,
                                    compact = true,
                                    onClick = {
                                        val nextHidden = if (isVisible) prefs.hiddenHomeCards + cardId else prefs.hiddenHomeCards - cardId
                                        onUpdateCardLayout(currentOrder, nextHidden, prefs.fullWidthHomeCards)
                                    }
                                )
                                XPeachFilterPill(
                                    label = "↑",
                                    selected = false,
                                    compact = true,
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
                                    compact = true,
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
                    Text("恢复默认")
                }
            }
        }
    }
}

