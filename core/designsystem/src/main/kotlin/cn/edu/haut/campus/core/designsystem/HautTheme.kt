package cn.edu.haut.campus.core.designsystem

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import cn.edu.haut.campus.core.model.ThemeVisualDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 全局产品设计语言枚举：
 * - [FRESH_BREEZE]: 清新森系 · 氧气呼吸（晨雾薄荷留白、22dp 温润大圆角、水彩低饱和课表与柔和微光边）
 * - [CREAM_PASTEL]: 奶油手账 · 温柔马卡龙（暖调燕麦纸感底色、24dp 云朵圆角、奶霜白卡片与手账马卡龙色块）
 * - [LIQUID_GLASS]: 透明液态毛玻璃设计语言（全局半透明磨砂折射、斜向流光高光边、桌面背景/流光网格透传）
 * - [FLUTTER_M3]: 经典高对比度现代扁平卡片体系（Flutter Material 3 & Cupertino 规范）
 */
enum class UiDesignStyle(
    val key: String,
    val label: String,
    val subtitle: String,
    val badgeText: String
) {
    FRESH_BREEZE(
        key = "FRESH_BREEZE",
        label = "清新森系",
        subtitle = "氧气感晨雾留白、温润大圆角与水彩课表",
        badgeText = "推荐 · 清新治愈"
    ),
    CREAM_PASTEL(
        key = "CREAM_PASTEL",
        label = "奶油手账",
        subtitle = "暖调燕麦纸感底色、柔和奶霜卡与马卡龙色",
        badgeText = "温柔马卡龙"
    ),
    LIQUID_GLASS(
        key = "LIQUID_GLASS",
        label = "透明液态毛玻璃",
        subtitle = "全局半透明磨砂折射、斜向流光边与景深透传",
        badgeText = "流光磨砂"
    ),
    FLUTTER_M3(
        key = "FLUTTER_M3",
        label = "Flutter 经典质感",
        subtitle = "高对比度现代扁平卡片与清晰利落边界",
        badgeText = "经典高效"
    );

    companion object {
        fun fromKey(key: String?): UiDesignStyle =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: FLUTTER_M3
    }
}

val LocalIsDarkTheme = staticCompositionLocalOf { false }
val LocalUiDesignStyle = staticCompositionLocalOf { UiDesignStyle.FLUTTER_M3 }
val LocalHasActiveBackdrop = staticCompositionLocalOf { false }
val LocalGlassCardAlpha = staticCompositionLocalOf { ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA }
val LocalGlassBlurDp = staticCompositionLocalOf { ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP }
val LocalBackdropAlpha = staticCompositionLocalOf { ThemeVisualDefaults.DEFAULT_WALLPAPER_ALPHA }

val XPeachBrandLight = Color(0xFF0066CC)
val XPeachBrandHoverLight = Color(0xFF0052A3)
val XPeachBrandSoftLight = Color(0xFFEAF3FF)
val XPeachBrandBorderLight = Color(0xFFC2DDFB)

val XPeachBrandDark = Color(0xFF479BFF)
val XPeachBrandSoftDark = Color(0xFF192D4B)
val XPeachBrandBorderDark = Color(0xFF2B4C7E)

val XPeachOk = Color(0xFF16A34A)
val XPeachOkSoft = Color(0xFFEAF7EE)
val XPeachOkDark = Color(0xFF34D399)
val XPeachOkSoftDark = Color(0xFF133324)

val XPeachWarn = Color(0xFFD97706)
val XPeachWarnSoft = Color(0xFFFEF3C7)
val XPeachWarnDark = Color(0xFFFBBF24)
val XPeachWarnSoftDark = Color(0xFF382812)

val XPeachDanger = Color(0xFFDC2626)
val XPeachDangerSoft = Color(0xFFFEE2E2)
val XPeachDangerDark = Color(0xFFF87171)
val XPeachDangerSoftDark = Color(0xFF3B1A1E)

val XPeachInfo = Color(0xFF0284C7)

// 轻盈柔和的浅色底色体系：以微灰蓝底色衬托纯白卡片，弱化硬边框
val XPeachBgLight = Color(0xFFF5F7FA)
val XPeachCardLight = Color(0xFFFFFFFF)
val XPeachWellLight = Color(0xFFF2F5F9)
val XPeachSidebarLight = Color(0xFFF8FAFC)
val XPeachBorderLight = Color(0xFFE8ECF2)
val XPeachBorderStrongLight = Color(0xFFD8E0EA)
val XPeachTextLight = Color(0xFF0F172A)
val XPeachText2Light = Color(0xFF334155)
val XPeachMutedLight = Color(0xFF64748B)

// 四层递进式深色表面体系：Level 0 背景 (#0D1117) -> Level 1 卡片 (#161B22) -> Level 2 内嵌槽 (#1F2631) -> Level 3 交互胶囊 (#262F3D)
val XPeachBgDark = Color(0xFF0D1117)
val XPeachCardDark = Color(0xFF161B22)
val XPeachWellDark = Color(0xFF1F2631)
val XPeachElevatedPillDark = Color(0xFF262F3D)
val XPeachSidebarDark = Color(0xFF11161E)
val XPeachBorderDark = Color(0xFF283140)
val XPeachBorderStrongDark = Color(0xFF384457)
val XPeachTextDark = Color(0xFFF1F5F9)
val XPeachText2Dark = Color(0xFFCBD5E1)
val XPeachMutedDark = Color(0xFF94A3B8)

private val LightColors = lightColorScheme(
    primary = XPeachBrandLight,
    onPrimary = Color.White,
    primaryContainer = XPeachBrandSoftLight,
    onPrimaryContainer = Color(0xFF004C99),
    secondary = XPeachInfo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = XPeachOk,
    onTertiary = Color.White,
    tertiaryContainer = XPeachOkSoft,
    onTertiaryContainer = Color(0xFF15803D),
    error = XPeachDanger,
    onError = Color.White,
    errorContainer = XPeachDangerSoft,
    onErrorContainer = Color(0xFFB91C1C),
    background = XPeachBgLight,
    onBackground = XPeachTextLight,
    surface = XPeachCardLight,
    onSurface = XPeachTextLight,
    surfaceVariant = XPeachWellLight,
    onSurfaceVariant = XPeachMutedLight,
    surfaceContainerLow = XPeachSidebarLight,
    surfaceContainer = XPeachCardLight,
    surfaceContainerHigh = Color(0xFFF8FAFC),
    surfaceContainerHighest = Color(0xFFEEF2F7),
    outline = XPeachBorderStrongLight,
    outlineVariant = XPeachBorderLight
)

private val DarkColors = darkColorScheme(
    primary = XPeachBrandDark,
    onPrimary = Color.White,
    primaryContainer = XPeachBrandSoftDark,
    onPrimaryContainer = Color(0xFFD8EBFF),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF162C40),
    onSecondaryContainer = Color(0xFFD6F0FF),
    tertiary = XPeachOkDark,
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = XPeachOkSoftDark,
    onTertiaryContainer = Color(0xFFD1FAE5),
    error = XPeachDangerDark,
    onError = Color.White,
    errorContainer = XPeachDangerSoftDark,
    onErrorContainer = Color(0xFFFEE2E2),
    background = XPeachBgDark,
    onBackground = XPeachTextDark,
    surface = XPeachCardDark,
    onSurface = XPeachTextDark,
    surfaceVariant = XPeachWellDark,
    onSurfaceVariant = XPeachMutedDark,
    surfaceContainerLow = XPeachSidebarDark,
    surfaceContainer = XPeachCardDark,
    surfaceContainerHigh = Color(0xFF212834),
    surfaceContainerHighest = XPeachElevatedPillDark,
    outline = XPeachBorderStrongDark,
    outlineVariant = XPeachBorderDark
)

/**
 * 深浅色与双设计语言（Flutter M3 vs 透明液态毛玻璃）自适应语义色彩与表面层级访问器：
 * 统一管控卡片透明度、折射流光边框、弹窗磨砂底色与课程色块对比度。
 */
object XPeachTheme {
    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalIsDarkTheme.current

    val uiDesignStyle: UiDesignStyle
        @Composable
        @ReadOnlyComposable
        get() = LocalUiDesignStyle.current

    val isLiquidGlass: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalUiDesignStyle.current == UiDesignStyle.LIQUID_GLASS

    val isFreshBreeze: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalUiDesignStyle.current == UiDesignStyle.FRESH_BREEZE

    val isCreamPastel: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalUiDesignStyle.current == UiDesignStyle.CREAM_PASTEL

    val isSoftFreshStyle: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalUiDesignStyle.current == UiDesignStyle.FRESH_BREEZE ||
            LocalUiDesignStyle.current == UiDesignStyle.CREAM_PASTEL

    val hasActiveBackdrop: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalHasActiveBackdrop.current

    val glassCardAlpha: Float
        @Composable
        @ReadOnlyComposable
        get() = LocalGlassCardAlpha.current

    val glassBlurDp: Float
        @Composable
        @ReadOnlyComposable
        get() = LocalGlassBlurDp.current

    val backdropAlpha: Float
        @Composable
        @ReadOnlyComposable
        get() = LocalBackdropAlpha.current

    /**
     * 主色调高亮卡片底色（如待取快递提醒卡、高亮模块卡）：
     * 在液态毛玻璃模式下自动跟随用户调节的毛玻璃透明度 `glassCardAlpha`。
     */
    val primaryAccentCardColor: Color
        @Composable get() {
            val base = if (isDark) {
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, 0.52f)
            } else {
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, 0.65f)
            }
            return if (hasActiveBackdrop) {
                base.copy(alpha = (glassCardAlpha * 1.02f).coerceIn(0.18f, 0.98f))
            } else {
                base
            }
        }

    val secondaryAccentCardColor: Color
        @Composable get() {
            val base = if (isDark) {
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.secondaryContainer, 0.52f)
            } else {
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.secondaryContainer, 0.65f)
            }
            return if (hasActiveBackdrop) {
                base.copy(alpha = (glassCardAlpha * 1.02f).coerceIn(0.18f, 0.98f))
            } else {
                base
            }
        }

    val tertiaryAccentCardColor: Color
        @Composable get() {
            val base = if (isDark) {
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.tertiaryContainer, 0.55f)
            } else {
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.tertiaryContainer, 0.72f)
            }
            return if (hasActiveBackdrop) {
                base.copy(alpha = (glassCardAlpha * 1.02f).coerceIn(0.18f, 0.98f))
            } else {
                base
            }
        }

    val errorAccentCardColor: Color
        @Composable get() {
            val base = if (isDark) {
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.errorContainer, 0.58f)
            } else {
                lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.errorContainer, 0.75f)
            }
            return if (hasActiveBackdrop) {
                base.copy(alpha = (glassCardAlpha * 1.04f).coerceIn(0.22f, 0.98f))
            } else {
                base
            }
        }

    /**
     * 卡片内部二级内嵌容器表面色（在深色模式下始终比一级卡片更亮一级，避免内嵌框显得凹陷发黑）。
     */
    val elevatedWellColor: Color
        @Composable get() = if (isDark) {
            if (hasActiveBackdrop) {
                MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = (glassCardAlpha * 0.86f).coerceIn(0.16f, 0.94f)
                )
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        } else {
            if (hasActiveBackdrop) {
                MaterialTheme.colorScheme.surface.copy(
                    alpha = (glassCardAlpha * 0.88f).coerceIn(0.18f, 0.95f)
                )
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            }
        }

    /**
     * 内嵌容器内部的三级条目表面色（如驿站分组内的单个快递条目）。
     */
    val nestedItemColor: Color
        @Composable get() = if (isDark) {
            if (hasActiveBackdrop) {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                    alpha = (glassCardAlpha * 0.88f).coerceIn(0.20f, 0.95f)
                )
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = if (hasActiveBackdrop) (glassCardAlpha * 0.78f).coerceIn(0.16f, 0.90f) else 0.68f
            )
        }

    /**
     * 悬浮地图 HUD / 室内平面图工具栏表面色：
     * 在液态毛玻璃模式下呈现带折射质感的半透明磨砂底色，同时保留最低可读性下限防止地图线条干扰文字。
     */
    val hudSurfaceColor: Color
        @Composable get() = if (isLiquidGlass || hasActiveBackdrop) {
            MaterialTheme.colorScheme.surface.copy(
                alpha = glassCardAlpha.coerceIn(0.38f, 0.94f)
            )
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        }

    val glassChipColor: Color
        @Composable get() = if (isLiquidGlass || hasActiveBackdrop) {
            val base = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
            base.copy(alpha = (glassCardAlpha * 0.78f).coerceIn(0.22f, 0.90f))
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        }

    val glassBorderColor: Color
        @Composable get() = if (isDark) {
            Color.White.copy(alpha = 0.20f)
        } else {
            Color.White.copy(alpha = 0.72f)
        }

    /**
     * 弹窗 (`XPeachAlertDialog` / 模态面板) 表面色：
     * 在液态毛玻璃模式下呈现晶莹磨砂质感，同时保障弹窗表单与正文的对比度。
     */
    val dialogSurfaceColor: Color
        @Composable get() = if (isLiquidGlass || hasActiveBackdrop) {
            val base = if (isDark) Color(0xFF141D2B) else Color(0xFFFAFCFF)
            val tinted = lerp(base, MaterialTheme.colorScheme.primaryContainer, if (isDark) 0.14f else 0.10f)
            tinted.copy(alpha = (glassCardAlpha * 1.08f).coerceIn(0.56f, 0.96f))
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }

    val successContainer: Color
        @Composable get() = if (isDark) XPeachOkSoftDark else Color(0xFFDCFCE7)

    val onSuccessContainer: Color
        @Composable get() = if (isDark) Color(0xFF6EE7B7) else Color(0xFF15803D)

    val warnContainer: Color
        @Composable get() = if (isDark) XPeachWarnSoftDark else XPeachWarnSoft

    val onWarnContainer: Color
        @Composable get() = if (isDark) XPeachWarnDark else Color(0xFFB45309)

    val dangerContainer: Color
        @Composable get() = if (isDark) XPeachDangerSoftDark else XPeachDangerSoft

    val onDangerContainer: Color
        @Composable get() = if (isDark) Color(0xFFFCA5A5) else Color(0xFFB91C1C)

    /**
     * 在深色卡片或徽章背景上具有高对比度的主题主色文字。
     */
    val readablePrimary: Color
        @Composable get() = readableAccent(MaterialTheme.colorScheme.primary)

    /**
     * 液态毛玻璃斜向流光折射边框画刷（左上高光反射 -> 中段主题色微光 -> 右下折射冷光）。
     */
    @Composable
    fun specularRimBrush(
        accentTint: Color? = null,
        intensity: Float = 1f
    ): Brush {
        val dark = isDark
        val primary = MaterialTheme.colorScheme.primary
        val effectiveTint = accentTint?.takeIf { it != Color.Transparent } ?: primary
        val safeIntensity = intensity.coerceIn(0.25f, 1.6f)
        return remember(dark, effectiveTint, safeIntensity) {
            Brush.linearGradient(
                colors = if (dark) {
                    listOf(
                        Color.White.copy(alpha = (0.36f * safeIntensity).coerceIn(0f, 1f)),
                        effectiveTint.copy(alpha = (0.32f * safeIntensity).coerceIn(0f, 1f)),
                        Color.White.copy(alpha = (0.14f * safeIntensity).coerceIn(0f, 1f))
                    )
                } else {
                    listOf(
                        Color.White.copy(alpha = (0.88f * safeIntensity).coerceIn(0f, 1f)),
                        effectiveTint.copy(alpha = (0.30f * safeIntensity).coerceIn(0f, 1f)),
                        Color.White.copy(alpha = (0.52f * safeIntensity).coerceIn(0f, 1f))
                    )
                },
                start = Offset(0f, 0f),
                end = Offset(860f, 520f)
            )
        }
    }

    /**
     * 将任意颜色（如课程色、主题预设种子色、自定义色）转换为当前深/浅色背景上高对比度可读的强调色。
     */
    @Composable
    fun readableAccent(rawColor: Color): Color {
        val dark = isDark
        return remember(rawColor, dark) {
            adjustColorForReadability(rawColor, dark)
        }
    }

    @Composable
    fun courseAccent(rawArgb: Long): Color {
        val dark = isDark
        return remember(rawArgb, dark) {
            adjustColorForReadability(Color(rawArgb), dark)
        }
    }

    @Composable
    fun courseContainer(rawArgb: Long, active: Boolean = false): Color {
        val dark = isDark
        val liquid = isLiquidGlass
        val freshBreeze = isFreshBreeze
        val creamPastel = isCreamPastel
        val hasBackdrop = hasActiveBackdrop
        val cardAlpha = glassCardAlpha
        val baseWell = MaterialTheme.colorScheme.surfaceVariant
        return remember(rawArgb, dark, active, liquid, freshBreeze, creamPastel, hasBackdrop, cardAlpha, baseWell) {
            val raw = Color(rawArgb)
            if (!dark) {
                when {
                    liquid || hasBackdrop -> {
                        val frostedTint = lerp(Color.White, raw, if (active) 0.28f else 0.19f)
                        val targetAlpha = (cardAlpha * (if (active) 0.94f else 0.84f)).coerceIn(0.20f, 0.96f)
                        frostedTint.copy(alpha = targetAlpha)
                    }
                    creamPastel -> {
                        lerp(Color(0xFFFFFDF8), raw, if (active) 0.24f else 0.16f)
                    }
                    freshBreeze -> {
                        lerp(Color(0xFFFAFFFD), raw, if (active) 0.23f else 0.15f)
                    }
                    else -> {
                        raw.copy(alpha = if (active) 0.14f else 0.10f)
                    }
                }
            } else {
                val accent = adjustColorForReadability(raw, darkTheme = true)
                val blended = lerp(baseWell.copy(alpha = 1f), accent, if (active) 0.28f else 0.19f)
                if (liquid || hasBackdrop) {
                    blended.copy(alpha = (cardAlpha * (if (active) 0.95f else 0.86f)).coerceIn(0.20f, 0.96f))
                } else {
                    blended
                }
            }
        }
    }

    @Composable
    fun courseBorder(rawArgb: Long, active: Boolean = false): Color {
        val dark = isDark
        val liquid = isLiquidGlass
        return remember(rawArgb, dark, active, liquid) {
            val accent = adjustColorForReadability(Color(rawArgb), dark)
            accent.copy(
                alpha = if (dark) {
                    if (active) 0.62f else if (liquid) 0.44f else 0.32f
                } else {
                    if (active) 0.55f else if (liquid) 0.38f else 0.24f
                }
            )
        }
    }

    fun adjustColorForReadability(color: Color, darkTheme: Boolean): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        val h = hsv[0]
        val s = hsv[1]
        val v = hsv[2]
        return if (darkTheme) {
            val targetSat = if (s < 0.08f) s else (s * 0.66f).coerceIn(0.30f, 0.64f)
            val targetVal = v.coerceIn(0.82f, 0.95f)
            Color(android.graphics.Color.HSVToColor(floatArrayOf(h, targetSat, targetVal)))
        } else {
            val targetSat = if (s < 0.08f) s else s.coerceIn(0.45f, 0.92f)
            val targetVal = v.coerceIn(0.32f, 0.72f)
            Color(android.graphics.Color.HSVToColor(floatArrayOf(h, targetSat, targetVal)))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 对齐 Flutter SDK (Material 3 `_CardDefaultsM3` / `_FilterChipDefaultsM3` / `_SegmentedButtonDefaultsM3` /
 * `_FilledButtonDefaultsM3` 及 Cupertino `CupertinoSlidingSegmentedControl`) 的全局组件尺寸与圆角规范，
 * 同时提供 Liquid Glass 液态毛玻璃圆角与折射高光扩展。
 */
object FlutterM3Tokens {
    val PageHorizontalPadding: Dp = 20.dp
    val SectionSpacing: Dp = 12.dp
    val CardCornerRadius: Dp = 16.dp
    val LiquidCardCornerRadius: Dp = 20.dp
    val CardPadding: Dp = 16.dp
    val WellCornerRadius: Dp = 12.dp
    val LiquidWellCornerRadius: Dp = 14.dp
    val WellPadding: Dp = 12.dp
    val ControlCornerRadius: Dp = 12.dp
    val ButtonMinHeight: Dp = 42.dp
    val CompactButtonMinHeight: Dp = 36.dp
    val IconButtonSize: Dp = 38.dp
    val IconButtonCornerRadius: Dp = 12.dp
    val FilterPillMinHeight: Dp = 32.dp
    val CompactFilterPillMinHeight: Dp = 28.dp
    val SegmentedControlMinHeight: Dp = 36.dp
    val SegmentedContainerRadius: Dp = 12.dp
    val SegmentedThumbRadius: Dp = 9.dp
    val BadgeMinHeight: Dp = 22.dp
}

val HautShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(FlutterM3Tokens.ControlCornerRadius),
    medium = RoundedCornerShape(FlutterM3Tokens.CardCornerRadius),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

val HautTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.15.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp
    )
)

enum class PresetThemePalette(
    val key: String,
    val label: String,
    val subtitle: String,
    val seedArgb: Long,
    val activatesLiquidGlass: Boolean = false,
    val recommendedStyle: UiDesignStyle? = null
) {
    MINT_BREEZE("MINT_BREEZE", "薄荷晨风", "氧气森系绿", 0xFF10B981, recommendedStyle = UiDesignStyle.FRESH_BREEZE),
    SKY_SODA("SKY_SODA", "晴空苏打", "清透冰川蓝", 0xFF0EA5E9, recommendedStyle = UiDesignStyle.FRESH_BREEZE),
    PEACH_OOLONG("PEACH_OOLONG", "白桃乌龙", "温柔蜜桃粉", 0xFFF472B6, recommendedStyle = UiDesignStyle.CREAM_PASTEL),
    MATCHA_CREAM("MATCHA_CREAM", "抹茶奶盖", "清新春茶绿", 0xFF22C55E, recommendedStyle = UiDesignStyle.FRESH_BREEZE),
    LAVENDER_MIST("LAVENDER_MIST", "海盐香芋", "治愈薰衣草", 0xFF8B5CF6, recommendedStyle = UiDesignStyle.CREAM_PASTEL),
    POMELO_SUN("POMELO_SUN", "西柚暖阳", "元气奶油橙", 0xFFFB7185, recommendedStyle = UiDesignStyle.CREAM_PASTEL),
    LIQUID_GLASS("LIQUID_GLASS", "透明毛玻璃", "液态流光磨砂", 0xFF0EA5E9, activatesLiquidGlass = true, recommendedStyle = UiDesignStyle.LIQUID_GLASS),
    HAUT_BLUE("HAUT_BLUE", "工大蓝", "经典沉稳蓝", 0xFF0066CC, recommendedStyle = UiDesignStyle.FLUTTER_M3),
    PINE_TEAL("PINE_TEAL", "松石青", "清透青碧色", 0xFF0D9488),
    JADE_GREEN("JADE_GREEN", "翡翠绿", "自然护眼绿", 0xFF16A34A),
    AMBER_GOLD("AMBER_GOLD", "琥珀金", "暖阳活力橙", 0xFFD97706),
    CORAL_RED("CORAL_RED", "朱砂红", "明快珊瑚红", 0xFFE11D48),
    IRIS_PURPLE("IRIS_PURPLE", "鸢尾紫", "静谧学院紫", 0xFF7C3AED),
    SAKURA_PINK("SAKURA_PINK", "樱花粉", "柔和春日粉", 0xFFDB2777),
    SLATE_INK("SLATE_INK", "玄墨灰", "极简水墨灰", 0xFF334155);

    companion object {
        fun fromKey(key: String): PresetThemePalette? = entries.firstOrNull { it.key == key }
    }
}

data class WallpaperImportResult(
    val localFilePath: String,
    val primarySeedArgb: Long,
    val extractedSwatches: List<Long>,
    val statusMessage: String = "已应用背景图并提取主题色"
)

/**
 * 自定义背景图导入、系统桌面背景同步、内存缓存与多簇 HSV 智能取色引擎：
 * - 支持一键同步 Android 系统桌面壁纸 (`WallpaperManager`) 或提取系统桌面主题色 (`WallpaperColors` + Material You)
 * - 当系统处于动态壁纸或受 Android 13+ 沙箱限制无法直接读取像素时，基于系统桌面真实色彩自动合成高分辨率液态流光壁纸
 * - 通过 18 色相桶加权聚类提取最具代表性的 5 款主题色，赋色给按钮、菜单、导航栏与毛玻璃卡片模板
 */
object WallpaperThemeEngine {

    private val bitmapMemoryCache = ConcurrentHashMap<String, ImageBitmap>()

    fun getCachedBitmap(path: String): ImageBitmap? = bitmapMemoryCache[path]

    fun hasSystemWallpaperPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredWallpaperPermissionName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    suspend fun loadOrDecodeBitmap(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        bitmapMemoryCache[path]?.let { return@withContext it }
        runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@runCatching null
            val imageBitmap = bmp.asImageBitmap()
            bitmapMemoryCache[path] = imageBitmap
            imageBitmap
        }.getOrNull()
    }

    suspend fun importWallpaperAndExtractPalette(
        context: Context,
        sourceUri: Uri
    ): Result<WallpaperImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOpts)
            }
            val rawW = boundsOpts.outWidth.coerceAtLeast(1)
            val rawH = boundsOpts.outHeight.coerceAtLeast(1)
            var sampleSize = 1
            val maxEdge = maxOf(rawW, rawH)
            while (maxEdge / sampleSize > 1600) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decodedBitmap = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } ?: throw IllegalStateException("无法读取所选图片内容")

            saveBitmapAndBuildResult(
                context = context,
                bitmap = decodedBitmap,
                extraSwatches = emptyList(),
                statusMessage = "已应用相册壁纸并提取主题色"
            )
        }
    }

    /**
     * 一键获取用户的系统桌面壁纸并提取桌面主题色：
     * 1. 优先通过 `WallpaperManager` 读取系统桌面静态壁纸文件或 Drawable 位图；
     * 2. 同时通过 `WallpaperManager.getWallpaperColors(FLAG_SYSTEM)` 与 Android 12+ 系统动态色板提取桌面真实主/辅/强调色；
     * 3. 若用户使用动态壁纸或受系统沙箱限制无法直接拷贝原图，则使用提取到的桌面真实主题色渲染高分辨率液态流光桌面背景。
     */
    suspend fun syncSystemDesktopWallpaper(
        context: Context
    ): Result<WallpaperImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val wm = WallpaperManager.getInstance(context)
            val systemColors = extractSystemDesktopSwatchesInternal(context, wm)
            val directBitmap = tryReadSystemWallpaperBitmap(wm)

            if (directBitmap != null && directBitmap.width >= 32 && directBitmap.height >= 32) {
                val scaled = downscaleBitmapIfNeeded(directBitmap, maxEdgeLimit = 1600)
                saveBitmapAndBuildResult(
                    context = context,
                    bitmap = scaled,
                    extraSwatches = systemColors,
                    statusMessage = "已同步系统桌面背景并提取桌面主题色"
                )
            } else {
                val synthesized = renderSystemDesktopLiquidMeshBitmap(
                    swatches = systemColors,
                    width = 1080,
                    height = 2160
                )
                saveBitmapAndBuildResult(
                    context = context,
                    bitmap = synthesized,
                    extraSwatches = systemColors,
                    statusMessage = "已提取系统桌面主题色并生成液态毛玻璃背景"
                )
            }
        }
    }

    /**
     * 仅提取系统桌面主题色（不覆盖当前自定义壁纸图片，返回最多 5 款桌面色彩）。
     */
    suspend fun extractSystemDesktopColorsOnly(
        context: Context
    ): Result<List<Long>> = withContext(Dispatchers.IO) {
        runCatching {
            val wm = WallpaperManager.getInstance(context)
            val directBitmap = tryReadSystemWallpaperBitmap(wm)
            val osColors = extractSystemDesktopSwatchesInternal(context, wm)
            val bitmapColors = if (directBitmap != null && directBitmap.width >= 32) {
                extractPaletteSwatchesFromBitmap(directBitmap)
            } else {
                emptyList()
            }
            mergeDistinctSwatches(osColors + bitmapColors).ifEmpty {
                listOf(0xFF0EA5E9L, 0xFF6366F1L, 0xFF0D9488L, 0xFF7C3AEDL, 0xFF0066CCL)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryReadSystemWallpaperBitmap(wm: WallpaperManager): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            for (flag in intArrayOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK)) {
                val pfd = runCatching { wm.getWallpaperFile(flag) }.getOrNull()
                if (pfd != null) {
                    val decoded = runCatching {
                        pfd.use { descriptor ->
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
                            var sample = 1
                            val maxEdge = maxOf(bounds.outWidth.coerceAtLeast(1), bounds.outHeight.coerceAtLeast(1))
                            while (maxEdge / sample > 1600) {
                                sample *= 2
                            }
                            val opts = BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                            BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, opts)
                        }
                    }.getOrNull()
                    if (decoded != null) return decoded
                }
            }
        }
        val drawableCandidates = listOf(
            runCatching { wm.peekDrawable() }.getOrNull(),
            runCatching { wm.drawable }.getOrNull(),
            runCatching { wm.fastDrawable }.getOrNull()
        )
        for (d in drawableCandidates) {
            if (d != null) {
                val bmp = runCatching { drawableToBitmap(d) }.getOrNull()
                if (bmp != null && bmp.width >= 32 && bmp.height >= 32) {
                    return bmp
                }
            }
        }
        return null
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val w = drawable.intrinsicWidth.coerceIn(360, 1440)
        val h = drawable.intrinsicHeight.coerceIn(640, 2560)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun downscaleBitmapIfNeeded(source: Bitmap, maxEdgeLimit: Int): Bitmap {
        val maxEdge = maxOf(source.width, source.height)
        if (maxEdge <= maxEdgeLimit) return source
        val ratio = maxEdgeLimit.toFloat() / maxEdge.toFloat()
        val targetW = (source.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    @SuppressLint("MissingPermission")
    private fun extractSystemDesktopSwatchesInternal(
        context: Context,
        wm: WallpaperManager
    ): List<Long> {
        val rawCandidates = mutableListOf<Long>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            for (flag in intArrayOf(WallpaperManager.FLAG_SYSTEM, WallpaperManager.FLAG_LOCK)) {
                val wc = runCatching { wm.getWallpaperColors(flag) }.getOrNull()
                if (wc != null) {
                    rawCandidates.add(0xFF000000L or (wc.primaryColor.toArgb().toLong() and 0xFFFFFFL))
                    wc.secondaryColor?.let {
                        rawCandidates.add(0xFF000000L or (it.toArgb().toLong() and 0xFFFFFFL))
                    }
                    wc.tertiaryColor?.let {
                        rawCandidates.add(0xFF000000L or (it.toArgb().toLong() and 0xFFFFFFL))
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sysResIds = intArrayOf(
                android.R.color.system_accent1_500,
                android.R.color.system_accent2_500,
                android.R.color.system_accent3_500,
                android.R.color.system_accent1_600,
                android.R.color.system_accent3_600
            )
            for (resId in sysResIds) {
                runCatching {
                    val c = ContextCompat.getColor(context, resId)
                    rawCandidates.add(0xFF000000L or (c.toLong() and 0xFFFFFFL))
                }
            }
        }
        return mergeDistinctSwatches(rawCandidates).ifEmpty {
            listOf(0xFF0EA5E9L, 0xFF6366F1L, 0xFF0D9488L, 0xFF7C3AEDL, 0xFF0066CCL)
        }
    }

    private fun mergeDistinctSwatches(candidates: List<Long>): List<Long> {
        val result = mutableListOf<Long>()
        val hsvA = FloatArray(3)
        val hsvB = FloatArray(3)
        for (raw in candidates) {
            android.graphics.Color.colorToHSV(raw.toInt(), hsvA)
            val normSat = hsvA[1].coerceIn(0.32f, 0.88f)
            val normVal = hsvA[2].coerceIn(0.45f, 0.88f)
            val normalizedInt = android.graphics.Color.HSVToColor(floatArrayOf(hsvA[0], normSat, normVal))
            val normalizedLong = 0xFF000000L or (normalizedInt.toLong() and 0xFFFFFFL)
            val isDuplicate = result.any { existing ->
                android.graphics.Color.colorToHSV(existing.toInt(), hsvB)
                val hueDiff = abs(hsvA[0] - hsvB[0])
                val circularDiff = minOf(hueDiff, 360f - hueDiff)
                circularDiff < 20f && abs(hsvA[1] - hsvB[1]) < 0.22f
            }
            if (!isDuplicate) {
                result.add(normalizedLong)
                if (result.size >= 5) break
            }
        }
        return result
    }

    private fun renderSystemDesktopLiquidMeshBitmap(
        swatches: List<Long>,
        width: Int,
        height: Int
    ): Bitmap {
        val c0 = (swatches.getOrElse(0) { 0xFF0EA5E9L }).toInt()
        val c1 = (swatches.getOrElse(1) { 0xFF6366F1L }).toInt()
        val c2 = (swatches.getOrElse(2) { 0xFF14B8A6L }).toInt()
        val c3 = (swatches.getOrElse(3) { 0xFF8B5CF6L }).toInt()
        val c4 = (swatches.getOrElse(4) { 0xFF38BDF8L }).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(c0, c1, c2),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), basePaint)

        fun drawOrb(cx: Float, cy: Float, radius: Float, colorInt: Int, alphaInt: Int) {
            val r = (colorInt shr 16) and 0xFF
            val g = (colorInt shr 8) and 0xFF
            val b = colorInt and 0xFF
            val centerColor = (alphaInt shl 24) or (r shl 16) or (g shl 8) or b
            val edgeColor = (0x00 shl 24) or (r shl 16) or (g shl 8) or b
            val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    cx,
                    cy,
                    radius,
                    intArrayOf(centerColor, edgeColor),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawCircle(cx, cy, radius, orbPaint)
        }

        drawOrb(width * 0.18f, height * 0.20f, width * 0.78f, c4, 210)
        drawOrb(width * 0.85f, height * 0.28f, width * 0.72f, c3, 195)
        drawOrb(width * 0.25f, height * 0.74f, width * 0.82f, c2, 185)
        drawOrb(width * 0.80f, height * 0.82f, width * 0.75f, c0, 200)
        return bitmap
    }

    private fun saveBitmapAndBuildResult(
        context: Context,
        bitmap: Bitmap,
        extraSwatches: List<Long>,
        statusMessage: String
    ): WallpaperImportResult {
        val wallpapersDir = File(context.filesDir, "wallpapers").apply { mkdirs() }
        wallpapersDir.listFiles()?.forEach { oldFile ->
            runCatching { oldFile.delete() }
        }
        val targetFile = File(wallpapersDir, "custom_theme_wallpaper_${System.currentTimeMillis()}.jpg")
        FileOutputStream(targetFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
        }

        bitmapMemoryCache.clear()
        bitmapMemoryCache[targetFile.absolutePath] = bitmap.asImageBitmap()

        val bitmapSwatches = extractPaletteSwatchesFromBitmap(bitmap)
        val mergedSwatches = mergeDistinctSwatches(extraSwatches + bitmapSwatches).ifEmpty { bitmapSwatches }
        val primarySeed = mergedSwatches.firstOrNull() ?: 0xFF0EA5E9L

        return WallpaperImportResult(
            localFilePath = targetFile.absolutePath,
            primarySeedArgb = primarySeed,
            extractedSwatches = mergedSwatches,
            statusMessage = statusMessage
        )
    }

    /**
     * 基于 18 色相桶与饱和度/明度视觉显著性权重的智能取色算法：
     * 自动过滤过暗、过曝与死灰噪点，提取最多 5 款适合作为按钮、菜单与卡片模板的主色调。
     */
    fun extractPaletteSwatchesFromBitmap(bitmap: Bitmap): List<Long> {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return listOf(0xFF0066CCL)

        val stepX = (w / 48).coerceAtLeast(1)
        val stepY = (h / 48).coerceAtLeast(1)

        val bucketCount = 18
        val bucketWeight = FloatArray(bucketCount)
        val bucketHueSum = FloatArray(bucketCount)
        val bucketSatSum = FloatArray(bucketCount)
        val bucketValSum = FloatArray(bucketCount)

        val hsv = FloatArray(3)
        var x = 0
        while (x < w) {
            var y = 0
            while (y < h) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha >= 128) {
                    android.graphics.Color.colorToHSV(pixel, hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (sat >= 0.12f && value in 0.16f..0.96f) {
                        val idx = ((hue / 20f).toInt()).coerceIn(0, bucketCount - 1)
                        val vibrancyWeight = (sat * 1.5f + 0.15f) * (1.05f - abs(value - 0.62f))
                        bucketWeight[idx] += vibrancyWeight
                        bucketHueSum[idx] += hue * vibrancyWeight
                        bucketSatSum[idx] += sat * vibrancyWeight
                        bucketValSum[idx] += value * vibrancyWeight
                    }
                }
                y += stepY
            }
            x += stepX
        }

        val rankedBuckets = (0 until bucketCount)
            .filter { bucketWeight[it] > 0.1f }
            .sortedByDescending { bucketWeight[it] }

        val extracted = mutableListOf<Long>()
        val chosenHues = mutableListOf<Float>()

        for (idx in rankedBuckets) {
            val weight = bucketWeight[idx]
            val avgHue = (bucketHueSum[idx] / weight).coerceIn(0f, 360f)
            val avgSat = (bucketSatSum[idx] / weight).coerceIn(0.45f, 0.85f)
            val avgVal = (bucketValSum[idx] / weight).coerceIn(0.52f, 0.82f)

            val tooClose = chosenHues.any { existingHue ->
                val diff = abs(existingHue - avgHue)
                minOf(diff, 360f - diff) < 28f
            }
            if (!tooClose) {
                chosenHues.add(avgHue)
                val colorInt = android.graphics.Color.HSVToColor(floatArrayOf(avgHue, avgSat, avgVal))
                val argbLong = 0xFF000000L or (colorInt.toLong() and 0xFFFFFFL)
                extracted.add(argbLong)
                if (extracted.size >= 5) break
            }
        }

        if (extracted.isEmpty()) {
            extracted.add(0xFF0EA5E9L)
            extracted.add(0xFF0066CCL)
            extracted.add(0xFF0D9488L)
        }
        return extracted
    }
}

fun buildSeedColorScheme(
    seedArgb: Long,
    darkTheme: Boolean,
    hasCustomWallpaper: Boolean = false,
    cardTemplateAlpha: Float = 0.82f,
    uiDesignStyle: UiDesignStyle = UiDesignStyle.FLUTTER_M3
): ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seedArgb.toInt(), hsv)
    val hue = hsv[0]
    val sat = hsv[1].coerceIn(0.10f, 0.94f)
    val value = hsv[2].coerceIn(0.24f, 0.94f)

    fun hsvColor(h: Float, s: Float, v: Float): Color {
        val normH = ((h % 360f) + 360f) % 360f
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(normH, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
    }

    val safeCardAlpha = if (hasCustomWallpaper) cardTemplateAlpha.coerceIn(0.18f, 0.98f) else 1f
    val safeWellAlpha = if (hasCustomWallpaper) (cardTemplateAlpha * 0.86f).coerceIn(0.15f, 0.94f) else 1f
    val safeContainerAlpha = if (hasCustomWallpaper) (cardTemplateAlpha * 0.90f).coerceIn(0.20f, 0.96f) else 1f
    val safeHighContainerAlpha = if (hasCustomWallpaper) (cardTemplateAlpha * 1.05f).coerceIn(0.32f, 0.96f) else 1f

    return if (!darkTheme) {
        val isCream = uiDesignStyle == UiDesignStyle.CREAM_PASTEL
        val isFresh = uiDesignStyle == UiDesignStyle.FRESH_BREEZE
        val primary = hsvColor(
            hue,
            if (isFresh || isCream) sat.coerceIn(0.28f, 0.78f) else sat.coerceAtLeast(0.22f),
            if (isFresh || isCream) value.coerceIn(0.48f, 0.80f) else value.coerceIn(0.42f, 0.82f)
        )
        val primaryContainer = hsvColor(
            hue,
            if (isCream) (sat * 0.20f).coerceIn(0.07f, 0.22f) else (sat * 0.24f).coerceIn(0.08f, 0.26f),
            0.975f
        ).copy(alpha = safeContainerAlpha)
        val onPrimaryContainer = hsvColor(hue, (sat * 0.92f).coerceIn(0.45f, 0.92f), 0.38f)
        val secondary = hsvColor(hue + 20f, (sat * 0.72f).coerceIn(0.24f, 0.76f), 0.68f)
        val secondaryContainer = hsvColor(hue + 20f, (sat * 0.18f).coerceIn(0.06f, 0.18f), 0.965f)
            .copy(alpha = safeContainerAlpha)
        val onSecondaryContainer = hsvColor(hue + 20f, (sat * 0.82f).coerceIn(0.4f, 0.82f), 0.36f)

        val surfaceTint = when {
            hasCustomWallpaper -> hsvColor(hue, (sat * 0.08f).coerceIn(0.02f, 0.09f), 0.995f).copy(alpha = safeCardAlpha)
            isCream -> Color(0xFFFFFDF9)
            isFresh -> Color(0xFFFFFFFF)
            else -> XPeachCardLight
        }
        val wellTint = when {
            hasCustomWallpaper -> hsvColor(hue, (sat * 0.14f).coerceIn(0.04f, 0.14f), 0.96f).copy(alpha = safeWellAlpha)
            isCream -> lerp(Color(0xFFF7F2EA), hsvColor(hue, 0.08f, 0.97f), 0.35f)
            isFresh -> hsvColor(hue, (sat * 0.10f).coerceIn(0.03f, 0.09f), 0.968f)
            else -> hsvColor(hue, (sat * 0.08f).coerceIn(0.02f, 0.07f), 0.96f).copy(alpha = safeWellAlpha)
        }
        val bgTint = when {
            hasCustomWallpaper -> Color.Transparent
            isCream -> lerp(Color(0xFFFAF6F0), hsvColor(hue, 0.06f, 0.985f), 0.25f)
            isFresh -> hsvColor(hue, (sat * 0.07f).coerceIn(0.02f, 0.06f), 0.982f)
            else -> hsvColor(hue, (sat * 0.05f).coerceIn(0.01f, 0.045f), 0.975f)
        }
        val borderTint = when {
            isCream -> lerp(Color(0xFFEDE3D5), hsvColor(hue, 0.15f, 0.91f), 0.35f)
            isFresh -> hsvColor(hue, (sat * 0.18f).coerceIn(0.06f, 0.16f), 0.92f)
            else -> hsvColor(hue, (sat * 0.14f).coerceIn(0.04f, 0.12f), 0.90f)
        }
        val borderStrong = when {
            isCream -> lerp(Color(0xFFE2D5C3), hsvColor(hue, 0.20f, 0.86f), 0.35f)
            isFresh -> hsvColor(hue, (sat * 0.22f).coerceIn(0.08f, 0.19f), 0.86f)
            else -> hsvColor(hue, (sat * 0.18f).coerceIn(0.05f, 0.15f), 0.84f)
        }

        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = XPeachOk,
            onTertiary = Color.White,
            tertiaryContainer = XPeachOkSoft.copy(alpha = safeContainerAlpha),
            onTertiaryContainer = Color(0xFF15803D),
            error = XPeachDanger,
            onError = Color.White,
            errorContainer = XPeachDangerSoft.copy(alpha = safeContainerAlpha),
            onErrorContainer = Color(0xFFB91C1C),
            background = bgTint,
            onBackground = XPeachTextLight,
            surface = surfaceTint,
            onSurface = XPeachTextLight,
            surfaceVariant = wellTint,
            onSurfaceVariant = XPeachMutedLight,
            surfaceContainerLow = XPeachSidebarLight.copy(alpha = safeCardAlpha),
            surfaceContainer = surfaceTint,
            surfaceContainerHigh = hsvColor(hue, (sat * 0.06f).coerceIn(0.01f, 0.06f), 0.985f)
                .copy(alpha = safeHighContainerAlpha),
            surfaceContainerHighest = hsvColor(hue, (sat * 0.08f).coerceIn(0.02f, 0.08f), 0.965f)
                .copy(alpha = safeHighContainerAlpha),
            outline = borderStrong,
            outlineVariant = borderTint
        )
    } else {
        val primaryDark = hsvColor(hue, (sat * 0.72f).coerceIn(0.36f, 0.74f), 0.88f)
        val onPrimaryDark = if (primaryDark.luminance() > 0.42f) Color(0xFF09111E) else Color.White
        val primaryContainerDark = hsvColor(hue, (sat * 0.62f).coerceIn(0.24f, 0.66f), 0.25f)
            .copy(alpha = safeContainerAlpha)
        val onPrimaryContainerDark = hsvColor(hue, (sat * 0.25f).coerceIn(0.08f, 0.28f), 0.96f)
        val secondaryDark = hsvColor(hue + 18f, (sat * 0.60f).coerceIn(0.28f, 0.64f), 0.86f)
        val onSecondaryDark = if (secondaryDark.luminance() > 0.42f) Color(0xFF09111E) else Color.White
        val secondaryContainerDark = hsvColor(hue + 18f, (sat * 0.56f).coerceIn(0.22f, 0.58f), 0.24f)
            .copy(alpha = safeContainerAlpha)
        val onSecondaryContainerDark = hsvColor(hue + 18f, (sat * 0.22f).coerceIn(0.08f, 0.25f), 0.95f)

        val surfaceDarkTint = if (hasCustomWallpaper) {
            hsvColor(hue, (sat * 0.26f).coerceIn(0.08f, 0.26f), 0.145f).copy(alpha = safeCardAlpha)
        } else {
            XPeachCardDark
        }
        val wellDarkTint = if (hasCustomWallpaper) {
            hsvColor(hue, (sat * 0.28f).coerceIn(0.10f, 0.28f), 0.205f).copy(alpha = safeWellAlpha)
        } else {
            XPeachWellDark
        }
        val bgDarkTint = if (hasCustomWallpaper) Color.Transparent else XPeachBgDark

        darkColorScheme(
            primary = primaryDark,
            onPrimary = onPrimaryDark,
            primaryContainer = primaryContainerDark,
            onPrimaryContainer = onPrimaryContainerDark,
            secondary = secondaryDark,
            onSecondary = onSecondaryDark,
            secondaryContainer = secondaryContainerDark,
            onSecondaryContainer = onSecondaryContainerDark,
            tertiary = Color(0xFF34D399),
            onTertiary = Color(0xFF052E16),
            tertiaryContainer = Color(0xFF103121).copy(alpha = safeContainerAlpha),
            onTertiaryContainer = Color(0xFFD1FAE5),
            error = Color(0xFFF87171),
            onError = Color(0xFF450A0A),
            errorContainer = Color(0xFF3B1A1F).copy(alpha = safeContainerAlpha),
            onErrorContainer = Color(0xFFFEE2E2),
            background = bgDarkTint,
            onBackground = XPeachTextDark,
            surface = surfaceDarkTint,
            onSurface = XPeachTextDark,
            surfaceVariant = wellDarkTint,
            onSurfaceVariant = XPeachMutedDark,
            surfaceContainerLow = XPeachSidebarDark.copy(alpha = safeCardAlpha),
            surfaceContainer = surfaceDarkTint,
            surfaceContainerHigh = Color(0xFF212834).copy(alpha = safeHighContainerAlpha),
            surfaceContainerHighest = XPeachElevatedPillDark.copy(alpha = safeHighContainerAlpha),
            outline = XPeachBorderStrongDark,
            outlineVariant = XPeachBorderDark
        )
    }
}

private fun applyGlassBackdropToScheme(
    scheme: ColorScheme,
    hasActiveBackdrop: Boolean,
    cardTemplateAlpha: Float
): ColorScheme {
    if (!hasActiveBackdrop) return scheme
    val safeCardAlpha = cardTemplateAlpha.coerceIn(0.18f, 0.98f)
    val safeWellAlpha = (cardTemplateAlpha * 0.86f).coerceIn(0.15f, 0.94f)
    val safeContainerAlpha = (cardTemplateAlpha * 0.90f).coerceIn(0.20f, 0.96f)
    val safeHighContainerAlpha = (cardTemplateAlpha * 1.05f).coerceIn(0.32f, 0.96f)
    return scheme.copy(
        background = Color.Transparent,
        surface = scheme.surface.copy(alpha = safeCardAlpha),
        surfaceVariant = scheme.surfaceVariant.copy(alpha = safeWellAlpha),
        surfaceContainerLow = scheme.surfaceContainerLow.copy(alpha = safeCardAlpha),
        surfaceContainer = scheme.surfaceContainer.copy(alpha = safeCardAlpha),
        surfaceContainerHigh = scheme.surfaceContainerHigh.copy(alpha = safeHighContainerAlpha),
        surfaceContainerHighest = scheme.surfaceContainerHighest.copy(alpha = safeHighContainerAlpha),
        primaryContainer = scheme.primaryContainer.copy(alpha = safeContainerAlpha),
        secondaryContainer = scheme.secondaryContainer.copy(alpha = safeContainerAlpha),
        tertiaryContainer = scheme.tertiaryContainer.copy(alpha = safeContainerAlpha),
        errorContainer = scheme.errorContainer.copy(alpha = safeContainerAlpha)
    )
}

@Composable
fun HautCampusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: String = "SYSTEM",
    uiDesignStyleKey: String = "FLUTTER_M3",
    dynamicColor: Boolean = false,
    themePaletteKey: String = "HAUT_BLUE",
    customSeedArgb: Long = 0xFF0066CC,
    customWallpaperPath: String? = null,
    wallpaperAlpha: Float = ThemeVisualDefaults.DEFAULT_WALLPAPER_ALPHA,
    wallpaperBlurDp: Float = ThemeVisualDefaults.DEFAULT_WALLPAPER_BLUR_DP,
    wallpaperCardAlpha: Float = ThemeVisualDefaults.DEFAULT_WALLPAPER_CARD_ALPHA,
    content: @Composable () -> Unit
) {
    val effectiveDark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> darkTheme
    }
    val effectiveDesignStyle = if (themePaletteKey == PresetThemePalette.LIQUID_GLASS.key) {
        UiDesignStyle.LIQUID_GLASS
    } else {
        UiDesignStyle.fromKey(uiDesignStyleKey)
    }
    val isLiquidGlass = effectiveDesignStyle == UiDesignStyle.LIQUID_GLASS
    val isFreshBreeze = effectiveDesignStyle == UiDesignStyle.FRESH_BREEZE
    val isCreamPastel = effectiveDesignStyle == UiDesignStyle.CREAM_PASTEL

    val resolvedSeedArgb = when (themePaletteKey) {
        "CUSTOM" -> customSeedArgb
        else -> PresetThemePalette.fromKey(themePaletteKey)?.seedArgb ?: 0xFF0066CC
    }

    var wallpaperBitmap by remember(customWallpaperPath) {
        mutableStateOf(customWallpaperPath?.let { WallpaperThemeEngine.getCachedBitmap(it) })
    }
    LaunchedEffect(customWallpaperPath) {
        if (!customWallpaperPath.isNullOrBlank()) {
            wallpaperBitmap = WallpaperThemeEngine.loadOrDecodeBitmap(customWallpaperPath)
        } else {
            wallpaperBitmap = null
        }
    }

    val hasActiveWallpaper = wallpaperBitmap != null
    val hasActiveBackdrop = isLiquidGlass || hasActiveWallpaper
    val safeCardAlpha = ThemeVisualDefaults.clampWallpaperCardAlpha(wallpaperCardAlpha)
    val safeWallpaperAlpha = ThemeVisualDefaults.clampWallpaperAlpha(wallpaperAlpha)
    val safeBlurDp = ThemeVisualDefaults.clampWallpaperBlurDp(wallpaperBlurDp)

    val rawScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        themePaletteKey == "HAUT_BLUE" && !hasActiveBackdrop && !isFreshBreeze && !isCreamPastel -> {
            if (effectiveDark) DarkColors else LightColors
        }
        else -> buildSeedColorScheme(
            seedArgb = resolvedSeedArgb,
            darkTheme = effectiveDark,
            hasCustomWallpaper = hasActiveBackdrop,
            cardTemplateAlpha = safeCardAlpha,
            uiDesignStyle = effectiveDesignStyle
        )
    }
    val colorScheme = applyGlassBackdropToScheme(
        scheme = rawScheme,
        hasActiveBackdrop = hasActiveBackdrop,
        cardTemplateAlpha = safeCardAlpha
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !effectiveDark
            insetsController.isAppearanceLightNavigationBars = !effectiveDark
        }
    }

    CompositionLocalProvider(
        LocalIsDarkTheme provides effectiveDark,
        LocalUiDesignStyle provides effectiveDesignStyle,
        LocalHasActiveBackdrop provides hasActiveBackdrop,
        LocalGlassCardAlpha provides safeCardAlpha,
        LocalGlassBlurDp provides safeBlurDp,
        LocalBackdropAlpha provides safeWallpaperAlpha
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HautTypography,
            shapes = HautShapes
        ) {
            val activeBmp = wallpaperBitmap
            if (activeBmp != null) {
                val baseBackdrop = if (effectiveDark) Color(0xFF080D16) else Color(0xFFF0F5FB)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(baseBackdrop)
                ) {
                    Image(
                        bitmap = activeBmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = safeWallpaperAlpha,
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (safeBlurDp > 0.2f) {
                                    Modifier.blur(
                                        radius = safeBlurDp.dp,
                                        edgeTreatment = BlurredEdgeTreatment.Unbounded
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                    // 自适应护眼柔光与液态折射层
                    val veilAlpha = if (isLiquidGlass) {
                        ((1f - safeWallpaperAlpha) * 0.28f).coerceIn(0.04f, 0.42f)
                    } else {
                        ((1f - safeWallpaperAlpha) * 0.42f).coerceIn(0.06f, 0.50f)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(baseBackdrop.copy(alpha = veilAlpha))
                    )
                    if (isLiquidGlass) {
                        LiquidGlassRefractiveVeil(
                            isDark = effectiveDark,
                            primaryColor = colorScheme.primary,
                            secondaryColor = colorScheme.secondary,
                            intensity = safeWallpaperAlpha
                        )
                    }
                    content()
                }
            } else if (isLiquidGlass) {
                Box(modifier = Modifier.fillMaxSize()) {
                    LiquidGlassAmbientBackdrop(
                        isDark = effectiveDark,
                        primaryColor = colorScheme.primary,
                        secondaryColor = colorScheme.secondary,
                        tertiaryColor = colorScheme.tertiary,
                        backdropAlpha = safeWallpaperAlpha,
                        blurDp = safeBlurDp
                    )
                    content()
                }
            } else if (isFreshBreeze || isCreamPastel) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FreshPastelAmbientBackdrop(
                        isDark = effectiveDark,
                        isCreamPastel = isCreamPastel,
                        backgroundColor = colorScheme.background,
                        primaryColor = colorScheme.primary,
                        secondaryColor = colorScheme.secondary,
                        tertiaryColor = colorScheme.tertiary
                    )
                    content()
                }
            } else {
                content()
            }
        }
    }
}

/**
 * 「清新森系 (`FRESH_BREEZE`)」与「奶油手账 (`CREAM_PASTEL`)」专属的顶部晨雾柔光环境底幕：
 * 在不依赖重型模糊的前提下，以高明度、低饱和度的晨风/奶霜渐变光晕烘托顶部导航与卡片呼吸感。
 */
@Composable
private fun FreshPastelAmbientBackdrop(
    isDark: Boolean,
    isCreamPastel: Boolean,
    backgroundColor: Color,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val topAlpha = if (isDark) 0.16f else if (isCreamPastel) 0.13f else 0.15f
                val sideAlpha = if (isDark) 0.12f else if (isCreamPastel) 0.10f else 0.12f
                val warmOrFreshTint = if (isCreamPastel) Color(0xFFFDE68A) else tertiaryColor

                val topBloom = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = topAlpha), Color.Transparent),
                    center = Offset(w * 0.18f, h * 0.06f),
                    radius = w * 0.88f
                )
                val rightBloom = Brush.radialGradient(
                    colors = listOf(secondaryColor.copy(alpha = sideAlpha), Color.Transparent),
                    center = Offset(w * 0.88f, h * 0.14f),
                    radius = w * 0.72f
                )
                val softSubtleBloom = Brush.radialGradient(
                    colors = listOf(warmOrFreshTint.copy(alpha = sideAlpha * 0.75f), Color.Transparent),
                    center = Offset(w * 0.50f, h * 0.42f),
                    radius = w * 0.90f
                )
                onDrawBehind {
                    drawRect(brush = topBloom)
                    drawRect(brush = rightBloom)
                    drawRect(brush = softSubtleBloom)
                }
            }
    )
}

/**
 * 当用户启用“透明液态毛玻璃”主题但尚未导入图片壁纸时，
 * 自动渲染多层流光折射环境背景（支持随主题色、深浅色模式、背景透光度与模糊度实时联动）。
 */
@Composable
private fun LiquidGlassAmbientBackdrop(
    isDark: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    backdropAlpha: Float,
    blurDp: Float
) {
    val baseColor = if (isDark) Color(0xFF070C14) else Color(0xFFEEF4FB)
    val glowAlpha = backdropAlpha.coerceIn(0.12f, 1.0f)
    val blurModifier = if (blurDp > 0.5f) {
        Modifier.blur(radius = (blurDp * 0.6f).dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(baseColor)
            .then(blurModifier)
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val orb1Color = primaryColor.copy(alpha = (if (isDark) 0.42f else 0.34f) * glowAlpha)
                val orb2Color = secondaryColor.copy(alpha = (if (isDark) 0.36f else 0.30f) * glowAlpha)
                val orb3Color = Color(0xFF6366F1).copy(alpha = (if (isDark) 0.30f else 0.22f) * glowAlpha)
                val orb4Color = tertiaryColor.copy(alpha = (if (isDark) 0.26f else 0.20f) * glowAlpha)

                val brush1 = Brush.radialGradient(
                    colors = listOf(orb1Color, Color.Transparent),
                    center = Offset(w * 0.16f, h * 0.14f),
                    radius = w * 0.85f
                )
                val brush2 = Brush.radialGradient(
                    colors = listOf(orb2Color, Color.Transparent),
                    center = Offset(w * 0.88f, h * 0.26f),
                    radius = w * 0.78f
                )
                val brush3 = Brush.radialGradient(
                    colors = listOf(orb3Color, Color.Transparent),
                    center = Offset(w * 0.24f, h * 0.68f),
                    radius = w * 0.82f
                )
                val brush4 = Brush.radialGradient(
                    colors = listOf(orb4Color, Color.Transparent),
                    center = Offset(w * 0.82f, h * 0.84f),
                    radius = w * 0.76f
                )
                onDrawBehind {
                    drawRect(brush = brush1)
                    drawRect(brush = brush2)
                    drawRect(brush = brush3)
                    drawRect(brush = brush4)
                }
            }
    )
}

@Composable
private fun LiquidGlassRefractiveVeil(
    isDark: Boolean,
    primaryColor: Color,
    secondaryColor: Color,
    intensity: Float
) {
    val alphaScale = (intensity * 0.22f).coerceIn(0.04f, 0.24f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val topGlow = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = if (isDark) alphaScale else alphaScale * 0.85f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.15f, h * 0.08f),
                    radius = w * 0.9f
                )
                val bottomGlow = Brush.radialGradient(
                    colors = listOf(
                        secondaryColor.copy(alpha = if (isDark) alphaScale * 0.85f else alphaScale * 0.70f),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.85f, h * 0.90f),
                    radius = w * 0.85f
                )
                onDrawBehind {
                    drawRect(brush = topGlow)
                    drawRect(brush = bottomGlow)
                }
            }
    )
}

@Composable
fun XPeachCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
    cornerRadius: Dp = FlutterM3Tokens.CardCornerRadius,
    contentPadding: PaddingValues = PaddingValues(FlutterM3Tokens.CardPadding),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = XPeachTheme.isDark
    val isLiquid = XPeachTheme.isLiquidGlass
    val isFreshBreeze = XPeachTheme.isFreshBreeze
    val isCreamPastel = XPeachTheme.isCreamPastel
    val hasBackdrop = XPeachTheme.hasActiveBackdrop
    val glassAlpha = XPeachTheme.glassCardAlpha

    val baseContainerColor = when (containerColor) {
        scheme.primaryContainer -> XPeachTheme.primaryAccentCardColor
        scheme.secondaryContainer -> XPeachTheme.secondaryAccentCardColor
        scheme.tertiaryContainer -> XPeachTheme.tertiaryAccentCardColor
        scheme.errorContainer -> XPeachTheme.errorAccentCardColor
        else -> containerColor
    }
    // 在液态毛玻璃或全局壁纸模式下，即便外部传入了高不透明度的 surface（如 0.96f/0.97f），也自动随毛玻璃透明度滑块联动
    val resolvedContainerColor = if ((isLiquid || hasBackdrop) && baseContainerColor.alpha > 0.90f) {
        baseContainerColor.copy(alpha = glassAlpha.coerceIn(0.18f, 0.98f))
    } else {
        baseContainerColor
    }

    val defaultBorder = scheme.outlineVariant.copy(alpha = 0.65f)
    val customAccentBorder = if (borderColor != defaultBorder && borderColor != Color.Transparent) {
        borderColor
    } else {
        when (containerColor) {
            scheme.primaryContainer -> scheme.primary
            scheme.secondaryContainer -> scheme.secondary
            scheme.tertiaryContainer -> scheme.tertiary
            scheme.errorContainer -> scheme.error
            else -> null
        }
    }

    val resolvedBorderStroke: BorderStroke? = when {
        isLiquid -> {
            BorderStroke(
                width = 0.95.dp,
                brush = XPeachTheme.specularRimBrush(
                    accentTint = customAccentBorder,
                    intensity = if (customAccentBorder != null) 1.18f else 1.0f
                )
            )
        }
        borderColor == Color.Transparent -> null
        isFreshBreeze && borderColor == defaultBorder -> {
            val freshBorder = if (isDark) {
                (customAccentBorder ?: scheme.primary).copy(alpha = 0.26f)
            } else {
                (customAccentBorder ?: scheme.primary).copy(alpha = 0.18f)
            }
            BorderStroke(0.8.dp, freshBorder)
        }
        isCreamPastel && borderColor == defaultBorder -> {
            val creamBorder = if (isDark) {
                scheme.outlineVariant.copy(alpha = 0.38f)
            } else {
                lerp(Color(0xFFEBDCCB), customAccentBorder ?: scheme.primary, 0.22f).copy(alpha = 0.65f)
            }
            BorderStroke(0.85.dp, creamBorder)
        }
        borderColor == defaultBorder -> {
            val m3Border = if (isDark) {
                customAccentBorder?.copy(alpha = 0.30f) ?: scheme.outlineVariant.copy(alpha = 0.36f)
            } else {
                scheme.outlineVariant.copy(alpha = 0.30f)
            }
            BorderStroke(0.5.dp, m3Border)
        }
        else -> BorderStroke(0.6.dp, borderColor)
    }

    val targetCornerRadius by animateDpAsState(
        targetValue = when {
            cornerRadius != FlutterM3Tokens.CardCornerRadius -> cornerRadius
            isCreamPastel -> 24.dp
            isFreshBreeze -> 22.dp
            isLiquid -> FlutterM3Tokens.LiquidCardCornerRadius
            else -> cornerRadius
        },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "cardCornerRadius"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.984f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "cardPressScale"
    )
    val shape = RoundedCornerShape(targetCornerRadius)
    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = resolvedContainerColor,
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = resolvedBorderStroke
    ) {
        Box(
            modifier = if (isLiquid) {
                Modifier.drawWithCache {
                    val sheenColor = Color.White.copy(alpha = if (isDark) 0.07f else 0.36f)
                    val sheenBrush = Brush.verticalGradient(
                        colors = listOf(sheenColor, Color.Transparent),
                        startY = 0f,
                        endY = 26.dp.toPx()
                    )
                    onDrawBehind {
                        drawRect(brush = sheenBrush)
                    }
                }
            } else {
                Modifier
            }
        ) {
            Column(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }
}

@Composable
fun XPeachWell(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = FlutterM3Tokens.WellCornerRadius,
    borderColor: Color = Color.Transparent,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentPadding: PaddingValues = PaddingValues(FlutterM3Tokens.WellPadding),
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val isLiquid = XPeachTheme.isLiquidGlass
    val isSoftFresh = XPeachTheme.isSoftFreshStyle
    val hasBackdrop = XPeachTheme.hasActiveBackdrop
    val glassAlpha = XPeachTheme.glassCardAlpha

    val resolvedWellColor = if ((isLiquid || hasBackdrop) && containerColor.alpha > 0.90f) {
        containerColor.copy(alpha = (glassAlpha * 0.85f).coerceIn(0.15f, 0.94f))
    } else {
        containerColor
    }
    val effectiveRadius = when {
        cornerRadius != FlutterM3Tokens.WellCornerRadius -> cornerRadius
        isSoftFresh -> 16.dp
        isLiquid -> FlutterM3Tokens.LiquidWellCornerRadius
        else -> cornerRadius
    }
    val resolvedBorder: BorderStroke? = when {
        isLiquid -> BorderStroke(
            width = 0.7.dp,
            brush = XPeachTheme.specularRimBrush(
                accentTint = borderColor.takeIf { it != Color.Transparent },
                intensity = 0.75f
            )
        )
        borderColor != Color.Transparent -> BorderStroke(0.5.dp, borderColor)
        else -> null
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(effectiveRadius),
        color = resolvedWellColor,
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = resolvedBorder
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * 统一的双设计语言悬浮表面组件 (`XPeachGlassSurface`)：
 * 适用于地图悬浮 HUD、室内导航栏、底部悬浮胶囊及全屏模态容器，
 * 在 `FLUTTER_M3` 下呈现高对比度实体面板，在 `LIQUID_GLASS` 下呈现带斜向折射高光边的半透明液态毛玻璃。
 */
@Composable
fun XPeachGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color = XPeachTheme.hudSurfaceColor,
    accentTint: Color? = null,
    borderColor: Color = Color.Unspecified,
    borderWidth: Dp = 0.6.dp,
    shadowElevation: Dp = 2.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val isLiquid = XPeachTheme.isLiquidGlass
    val hasBackdrop = XPeachTheme.hasActiveBackdrop
    val glassAlpha = XPeachTheme.glassCardAlpha
    val resolvedColor = if ((isLiquid || hasBackdrop) && containerColor.alpha > 0.92f) {
        containerColor.copy(alpha = glassAlpha.coerceIn(0.35f, 0.94f))
    } else {
        containerColor
    }
    val effectiveTint = accentTint ?: borderColor.takeIf { it != Color.Unspecified && it != Color.Transparent }
    val borderStroke: BorderStroke? = when {
        borderWidth <= 0.dp -> null
        isLiquid -> BorderStroke(0.95.dp, XPeachTheme.specularRimBrush(accentTint = effectiveTint, intensity = 1.05f))
        borderColor != Color.Unspecified && borderColor != Color.Transparent -> BorderStroke(borderWidth, borderColor)
        else -> BorderStroke(
            borderWidth,
            effectiveTint ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (XPeachTheme.isDark) 0.45f else 0.55f)
        )
    }
    Surface(
        modifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier,
        shape = shape,
        color = resolvedColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = if (isLiquid) (shadowElevation * 1.2f) else shadowElevation,
        border = borderStroke
    ) {
        Box(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
    }
}

/**
 * 统一的双设计语言模态弹窗组件 (`XPeachAlertDialog`)：
 * 与 Material 3 `AlertDialog` 保持完全一致的参数签名，可零成本平替全应用所有弹窗。
 * - `FLUTTER_M3` 模式：使用高对比度 M3 弹窗表面与细腻边框；
 * - `LIQUID_GLASS` 模式：使用半透明磨砂玻璃底色 (`dialogSurfaceColor`)、24dp 液态圆角、1.2dp 斜向流光折射边框与顶部内高光。
 */
@Composable
fun XPeachAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape? = null,
    containerColor: Color = Color.Unspecified,
    iconContentColor: Color = MaterialTheme.colorScheme.secondary,
    titleContentColor: Color = MaterialTheme.colorScheme.onSurface,
    textContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties()
) {
    val isLiquid = XPeachTheme.isLiquidGlass
    val hasBackdrop = XPeachTheme.hasActiveBackdrop
    val isDark = XPeachTheme.isDark
    val effectiveShape = shape ?: RoundedCornerShape(if (isLiquid) 24.dp else 20.dp)
    val effectiveContainerColor = if (containerColor != Color.Unspecified) {
        containerColor
    } else {
        XPeachTheme.dialogSurfaceColor
    }

    if (isLiquid || hasBackdrop) {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = properties
        ) {
            Surface(
                modifier = modifier
                    .widthIn(min = 280.dp, max = 560.dp)
                    .fillMaxWidth(),
                shape = effectiveShape,
                color = effectiveContainerColor,
                contentColor = titleContentColor,
                tonalElevation = 0.dp,
                shadowElevation = if (isDark) 22.dp else 16.dp,
                border = BorderStroke(
                    width = 1.15.dp,
                    brush = XPeachTheme.specularRimBrush(intensity = 1.15f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .drawWithCache {
                            val sheen = Color.White.copy(alpha = if (isDark) 0.08f else 0.42f)
                            val brush = Brush.verticalGradient(
                                colors = listOf(sheen, Color.Transparent),
                                startY = 0f,
                                endY = 32.dp.toPx()
                            )
                            onDrawBehind {
                                drawRect(brush = brush)
                            }
                        }
                        .padding(start = 22.dp, top = 22.dp, end = 22.dp, bottom = 18.dp)
                ) {
                    if (icon != null) {
                        CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 12.dp)
                                    .align(Alignment.CenterHorizontally)
                            ) {
                                icon()
                            }
                        }
                    }
                    if (title != null) {
                        CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.titleLarge) {
                                Box(
                                    modifier = Modifier
                                        .padding(bottom = if (text != null) 12.dp else 16.dp)
                                        .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally)
                                ) {
                                    title()
                                }
                            }
                        }
                    }
                    if (text != null) {
                        CompositionLocalProvider(LocalContentColor provides textContentColor) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                Box(
                                    modifier = Modifier
                                        .weight(weight = 1f, fill = false)
                                        .padding(bottom = 18.dp)
                                        .align(Alignment.Start)
                                ) {
                                    text()
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (dismissButton != null) {
                            dismissButton()
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        confirmButton()
                    }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = effectiveShape,
            containerColor = effectiveContainerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties
        )
    }
}

@Composable
fun XPeachInsetDivider(
    modifier: Modifier = Modifier,
    startIndent: Dp = 0.dp,
    verticalPadding: Dp = 8.dp
) {
    val isDark = XPeachTheme.isDark
    val isLiquid = XPeachTheme.isLiquidGlass
    val divColor = if (isLiquid) {
        if (isDark) Color.White.copy(alpha = 0.14f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.34f else 0.42f)
    }
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent, top = verticalPadding, bottom = verticalPadding),
        thickness = 0.5.dp,
        color = divColor
    )
}

@Composable
fun XPeachSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (trailing != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = trailing
            )
        }
    }
}

@Composable
fun XPeachStatusDotText(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color = XPeachTheme.readablePrimary,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val resolvedDot = XPeachTheme.readableAccent(dotColor)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(resolvedDot, CircleShape)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun XPeachTopBar(
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    onSubtitleClick: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val hasBackdrop = XPeachTheme.hasActiveBackdrop
    val topBarBg = if (hasBackdrop) Color.Transparent else MaterialTheme.colorScheme.background
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(topBarBg)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FlutterM3Tokens.PageHorizontalPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            ) {
                if (navigationIcon != null) {
                    navigationIcon()
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!badgeText.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            XPeachStatusDotText(text = badgeText)
                        }
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.padding(top = 1.dp))
                        if (onSubtitleClick != null) {
                            Text(
                                text = "$subtitle ▾",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = XPeachTheme.readablePrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable(onClick = onSubtitleClick)
                                    .padding(vertical = 2.dp)
                            )
                        } else {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}

@Composable
fun XPeachIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = XPeachTheme.isDark
    val iconTint = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.90f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    XPeachIconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun XPeachIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val isDark = XPeachTheme.isDark
    val isLiquid = XPeachTheme.isLiquidGlass
    val hasBackdrop = XPeachTheme.hasActiveBackdrop
    val glassAlpha = XPeachTheme.glassCardAlpha

    val baseBg = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    val bgColor = if (isLiquid || hasBackdrop) {
        baseBg.copy(alpha = (glassAlpha * 0.85f).coerceIn(0.22f, 0.94f))
    } else {
        baseBg
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 560f),
        label = "iconBtnCustomScale"
    )
    val shape = RoundedCornerShape(if (isLiquid) 14.dp else FlutterM3Tokens.IconButtonCornerRadius)
    val borderMod = if (isLiquid) {
        Modifier.border(0.85.dp, XPeachTheme.specularRimBrush(intensity = 0.9f), shape)
    } else {
        val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.42f else 0.35f)
        Modifier.border(0.5.dp, borderColor, shape)
    }

    Box(
        modifier = modifier
            .size(FlutterM3Tokens.IconButtonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(bgColor)
            .then(borderMod)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun XPeachFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    compact: Boolean = false
) {
    val isDark = XPeachTheme.isDark
    val selectedFg = XPeachTheme.readablePrimary
    val unselectedFg = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val fg by animateColorAsState(
        targetValue = if (selected) selectedFg else unselectedFg,
        animationSpec = tween(180),
        label = "pillIconFg"
    )
    if (leadingIcon != null) {
        XPeachFilterPill(
            label = label,
            selected = selected,
            onClick = onClick,
            modifier = modifier,
            compact = compact,
            leadingIcon = {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(14.dp)
                )
            }
        )
    } else {
        XPeachFilterPillInternal(
            label = label,
            selected = selected,
            onClick = onClick,
            modifier = modifier,
            compact = compact,
            leadingIcon = null
        )
    }
}

@Composable
fun XPeachFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    leadingIcon: @Composable () -> Unit
) {
    XPeachFilterPillInternal(
        label = label,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        compact = compact,
        leadingIcon = leadingIcon
    )
}

@Composable
private fun XPeachFilterPillInternal(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    val isDark = XPeachTheme.isDark
    val isLiquid = XPeachTheme.isLiquidGlass
    val hasBackdrop = XPeachTheme.hasActiveBackdrop
    val glassAlpha = XPeachTheme.glassCardAlpha

    val unselectedBg = if (isLiquid || hasBackdrop) {
        val base = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
        base.copy(alpha = (glassAlpha * 0.76f).coerceIn(0.20f, 0.88f))
    } else if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    }
    val selectedBg = if (isDark) {
        XPeachTheme.readablePrimary.copy(alpha = if (isLiquid) 0.26f else 0.20f)
    } else if (isLiquid || hasBackdrop) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = (glassAlpha * 0.92f).coerceIn(0.32f, 0.95f))
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val selectedFg = XPeachTheme.readablePrimary
    val unselectedFg = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bg by animateColorAsState(
        targetValue = if (selected) selectedBg else unselectedBg,
        animationSpec = tween(180),
        label = "pillBgCustom"
    )
    val border by animateColorAsState(
        targetValue = when {
            selected -> XPeachTheme.readablePrimary.copy(alpha = if (isDark) 0.55f else 0.42f)
            isLiquid -> if (isDark) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.68f)
            else -> Color.Transparent
        },
        animationSpec = tween(180),
        label = "pillBorderCustom"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) selectedFg else unselectedFg,
        animationSpec = tween(180),
        label = "pillFgCustom"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.955f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 540f),
        label = "pillCustomScale"
    )
    val pillShape = RoundedCornerShape(999.dp)

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .defaultMinSize(
                minHeight = if (compact) FlutterM3Tokens.CompactFilterPillMinHeight else FlutterM3Tokens.FilterPillMinHeight
            )
            .clip(pillShape)
            .background(bg)
            .then(
                if (border != Color.Transparent) {
                    Modifier.border(if (isLiquid) 0.85.dp else 0.75.dp, border, pillShape)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 4.dp else 6.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leadingIcon != null) {
            leadingIcon()
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun <T> XPeachSegmentedTabs(
    options: List<T>,
    selectedOption: T,
    labelProvider: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = XPeachTheme.isDark
    val isLiquid = XPeachTheme.isLiquidGlass
    val hasBackdrop = XPeachTheme.hasActiveBackdrop
    val glassAlpha = XPeachTheme.glassCardAlpha

    val trackBg = when {
        isLiquid || hasBackdrop -> {
            val base = if (isDark) Color(0xFF0E1520) else MaterialTheme.colorScheme.surfaceVariant
            base.copy(alpha = (glassAlpha * 0.72f).coerceIn(0.18f, 0.86f))
        }
        isDark -> Color(0xFF10151D)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    }
    val selectedThumbBg = when {
        isLiquid || hasBackdrop -> {
            val base = if (isDark) MaterialTheme.colorScheme.surfaceContainerHighest else Color.White
            base.copy(alpha = (glassAlpha * 0.96f).coerceIn(0.32f, 0.96f))
        }
        isDark -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surface
    }
    val containerShape = RoundedCornerShape(if (isLiquid) 14.dp else FlutterM3Tokens.SegmentedContainerRadius)
    val thumbShape = RoundedCornerShape(if (isLiquid) 11.dp else FlutterM3Tokens.SegmentedThumbRadius)
    val count = options.size.coerceAtLeast(1)
    val selectedIdx = options.indexOf(selectedOption).coerceIn(0, count - 1)
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIdx.toFloat(),
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
        label = "segSlidingThumbOffset"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(FlutterM3Tokens.SegmentedControlMinHeight)
            .clip(containerShape)
            .background(trackBg)
            .then(
                if (isLiquid) {
                    Modifier.border(0.75.dp, XPeachTheme.specularRimBrush(intensity = 0.7f), containerShape)
                } else {
                    Modifier
                }
            )
            .padding(3.dp)
    ) {
        val slotWidth = maxWidth / count
        val thumbOffsetX = slotWidth * animatedIndex

        // 1. 物理弹簧滑动胶囊滑块（支持 Liquid Glass 高光折射边）
        val thumbBorderMod = if (isLiquid) {
            Modifier.border(0.85.dp, XPeachTheme.specularRimBrush(intensity = 1.05f), thumbShape)
        } else {
            val selectedThumbBorder = if (isDark) {
                XPeachTheme.readablePrimary.copy(alpha = 0.30f)
            } else {
                Color.Black.copy(alpha = 0.04f)
            }
            Modifier.border(0.5.dp, selectedThumbBorder, thumbShape)
        }

        Box(
            modifier = Modifier
                .offset(x = thumbOffsetX)
                .width(slotWidth)
                .fillMaxHeight()
                .then(
                    if (!isDark) {
                        Modifier.shadow(
                            elevation = 2.dp,
                            shape = thumbShape,
                            ambientColor = Color.Black.copy(alpha = 0.10f),
                            spotColor = Color.Black.copy(alpha = 0.12f)
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(thumbShape)
                .background(selectedThumbBg)
                .then(thumbBorderMod)
        )

        // 2. 前景标签行
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { idx, option ->
                val isSelected = idx == selectedIdx
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val labelScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.95f else 1f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 520f),
                    label = "segLabelScale_$idx"
                )
                val tabFg by animateColorAsState(
                    targetValue = if (isSelected) {
                        if (isLiquid) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isPressed) 0.65f else 0.92f)
                    },
                    animationSpec = tween(160),
                    label = "segTabFg_$idx"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(thumbShape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onSelect(option) }
                        .padding(horizontal = if (options.size >= 4) 3.dp else 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = labelProvider(option),
                        style = if (options.size >= 3) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = tabFg,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            scaleX = labelScale
                            scaleY = labelScale
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun XPeachSegmentedTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val indices = items.indices.toList()
    XPeachSegmentedTabs(
        options = indices,
        selectedOption = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        labelProvider = { idx -> items.getOrElse(idx) { "" } },
        onSelect = onSelect,
        modifier = modifier
    )
}

@Composable
fun XPeachStatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    borderColor: Color = Color.Transparent
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = XPeachTheme.isDark
    val isLiquid = XPeachTheme.isLiquidGlass
    val resolvedContentColor = when (contentColor) {
        scheme.onPrimary,
        scheme.onSecondary,
        scheme.onTertiary,
        scheme.onError,
        scheme.onSurface,
        scheme.onSurfaceVariant,
        XPeachTheme.onSuccessContainer,
        XPeachTheme.onWarnContainer,
        XPeachTheme.onDangerContainer,
        Color.White -> contentColor
        scheme.primary -> XPeachTheme.readablePrimary
        else -> XPeachTheme.readableAccent(contentColor)
    }
    val resolvedContainerColor = when {
        isDark && containerColor == scheme.primaryContainer -> XPeachTheme.readablePrimary.copy(alpha = 0.18f)
        isDark && containerColor == scheme.secondaryContainer -> XPeachTheme.readableAccent(scheme.secondary).copy(alpha = 0.18f)
        isDark && containerColor == scheme.tertiaryContainer -> XPeachTheme.readableAccent(scheme.tertiary).copy(alpha = 0.18f)
        else -> containerColor
    }
    val effectiveBorderColor = when {
        borderColor != Color.Transparent -> borderColor
        isLiquid -> resolvedContentColor.copy(alpha = if (isDark) 0.34f else 0.26f)
        else -> Color.Transparent
    }
    val badgeShape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = FlutterM3Tokens.BadgeMinHeight)
            .clip(badgeShape)
            .background(resolvedContainerColor)
            .then(
                if (effectiveBorderColor != Color.Transparent) {
                    Modifier.border(0.6.dp, effectiveBorderColor, badgeShape)
                } else Modifier
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = resolvedContentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
