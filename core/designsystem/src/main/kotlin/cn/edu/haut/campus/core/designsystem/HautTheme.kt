package cn.edu.haut.campus.core.designsystem

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
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
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

val LocalIsDarkTheme = staticCompositionLocalOf { false }

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
 * 深浅色自适应语义色彩与表面层级访问器：
 * 解决深色模式下卡片底色与文字颜色冲突、低对比度课程色块、内嵌组件与卡片同色等问题。
 */
object XPeachTheme {
    val isDark: Boolean
        @Composable get() = LocalIsDarkTheme.current

    /**
     * 主色调高亮卡片底色（如待取快递提醒卡、高亮模块卡）：
     * 深色模式下采用克制的深蓝灰混色表面（而非高饱和刺眼色块），保证白色主标题与灰蓝副标题依然清晰协调。
     */
    val primaryAccentCardColor: Color
        @Composable get() = if (isDark) {
            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, 0.52f)
        } else {
            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primaryContainer, 0.65f)
        }

    val secondaryAccentCardColor: Color
        @Composable get() = if (isDark) {
            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.secondaryContainer, 0.52f)
        } else {
            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.secondaryContainer, 0.65f)
        }

    val tertiaryAccentCardColor: Color
        @Composable get() = if (isDark) {
            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.tertiaryContainer, 0.55f)
        } else {
            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.tertiaryContainer, 0.72f)
        }

    val errorAccentCardColor: Color
        @Composable get() = if (isDark) {
            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.errorContainer, 0.58f)
        } else {
            lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.errorContainer, 0.75f)
        }

    /**
     * 卡片内部二级内嵌容器表面色（在深色模式下始终比一级卡片更亮一级，避免内嵌框显得凹陷发黑）。
     */
    val elevatedWellColor: Color
        @Composable get() = if (isDark) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
        }

    /**
     * 内嵌容器内部的三级条目表面色（如驿站分组内的单个快递条目）。
     */
    val nestedItemColor: Color
        @Composable get() = if (isDark) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f)
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
        val baseWell = MaterialTheme.colorScheme.surfaceVariant
        return remember(rawArgb, dark, active, baseWell) {
            val raw = Color(rawArgb)
            if (!dark) {
                raw.copy(alpha = if (active) 0.14f else 0.10f)
            } else {
                val accent = adjustColorForReadability(raw, darkTheme = true)
                lerp(baseWell, accent, if (active) 0.25f else 0.17f)
            }
        }
    }

    @Composable
    fun courseBorder(rawArgb: Long, active: Boolean = false): Color {
        val dark = isDark
        return remember(rawArgb, dark, active) {
            val accent = adjustColorForReadability(Color(rawArgb), dark)
            accent.copy(alpha = if (dark) {
                if (active) 0.52f else 0.32f
            } else {
                if (active) 0.45f else 0.24f
            })
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
 * `_FilledButtonDefaultsM3` 及 Cupertino `CupertinoSlidingSegmentedControl`) 的全局组件尺寸与圆角规范。
 */
object FlutterM3Tokens {
    val PageHorizontalPadding: Dp = 20.dp
    val SectionSpacing: Dp = 12.dp
    val CardCornerRadius: Dp = 16.dp
    val CardPadding: Dp = 16.dp
    val WellCornerRadius: Dp = 12.dp
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
    val seedArgb: Long
) {
    HAUT_BLUE("HAUT_BLUE", "工大蓝", "经典沉稳蓝", 0xFF0066CC),
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
    val extractedSwatches: List<Long>
)

/**
 * 自定义背景图导入、内存缓存与多簇 HSV 智能取色引擎：
 * - 将用户上传的图片安全采样压缩保存至应用私有目录，避免 URI 权限失效与大图 OOM
 * - 通过 18 色相桶加权聚类提取图片中最具代表性的 5 款主题色，自动赋色给按钮、菜单、导航栏与卡片背景模板
 */
object WallpaperThemeEngine {

    private val bitmapMemoryCache = ConcurrentHashMap<String, ImageBitmap>()

    fun getCachedBitmap(path: String): ImageBitmap? = bitmapMemoryCache[path]

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

            val wallpapersDir = File(context.filesDir, "wallpapers").apply { mkdirs() }
            wallpapersDir.listFiles()?.forEach { oldFile ->
                runCatching { oldFile.delete() }
            }
            val targetFile = File(wallpapersDir, "custom_theme_wallpaper_${System.currentTimeMillis()}.jpg")
            FileOutputStream(targetFile).use { out ->
                decodedBitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.flush()
            }

            bitmapMemoryCache.clear()
            bitmapMemoryCache[targetFile.absolutePath] = decodedBitmap.asImageBitmap()

            val swatches = extractPaletteSwatchesFromBitmap(decodedBitmap)
            val primarySeed = swatches.firstOrNull() ?: 0xFF0066CCL

            WallpaperImportResult(
                localFilePath = targetFile.absolutePath,
                primarySeedArgb = primarySeed,
                extractedSwatches = swatches
            )
        }
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
            extracted.add(0xFF0066CCL)
            extracted.add(0xFF334155L)
            extracted.add(0xFF0D9488L)
        }
        return extracted
    }
}

fun buildSeedColorScheme(
    seedArgb: Long,
    darkTheme: Boolean,
    hasCustomWallpaper: Boolean = false,
    cardTemplateAlpha: Float = 0.82f
): androidx.compose.material3.ColorScheme {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(seedArgb.toInt(), hsv)
    val hue = hsv[0]
    val sat = hsv[1].coerceIn(0.18f, 0.92f)
    val value = hsv[2].coerceIn(0.28f, 0.92f)

    fun hsvColor(h: Float, s: Float, v: Float): Color {
        val normH = ((h % 360f) + 360f) % 360f
        return Color(android.graphics.Color.HSVToColor(floatArrayOf(normH, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
    }

    val safeCardAlpha = if (hasCustomWallpaper) cardTemplateAlpha.coerceIn(0.50f, 0.98f) else 1f
    val safeWellAlpha = if (hasCustomWallpaper) (cardTemplateAlpha * 0.88f).coerceIn(0.42f, 0.94f) else 1f
    val safeContainerAlpha = if (hasCustomWallpaper) (cardTemplateAlpha * 0.92f).coerceIn(0.55f, 0.96f) else 1f

    return if (!darkTheme) {
        val primary = hsvColor(hue, sat, value.coerceIn(0.42f, 0.82f))
        val primaryContainer = hsvColor(hue, (sat * 0.24f).coerceIn(0.08f, 0.26f), 0.97f)
            .copy(alpha = safeContainerAlpha)
        val onPrimaryContainer = hsvColor(hue, (sat * 0.95f).coerceIn(0.45f, 0.95f), 0.40f)
        val secondary = hsvColor(hue + 18f, (sat * 0.75f).coerceIn(0.25f, 0.78f), 0.68f)
        val secondaryContainer = hsvColor(hue + 18f, (sat * 0.20f).coerceIn(0.06f, 0.20f), 0.96f)
            .copy(alpha = safeContainerAlpha)
        val onSecondaryContainer = hsvColor(hue + 18f, (sat * 0.85f).coerceIn(0.4f, 0.85f), 0.38f)

        // 当启用自定义背景图时，卡片与菜单表面自动染上微弱主题色并应用毛玻璃透明度
        val surfaceTint = if (hasCustomWallpaper) {
            hsvColor(hue, (sat * 0.08f).coerceIn(0.02f, 0.08f), 0.99f).copy(alpha = safeCardAlpha)
        } else {
            XPeachCardLight
        }
        val wellTint = hsvColor(
            hue,
            if (hasCustomWallpaper) (sat * 0.14f).coerceIn(0.04f, 0.13f) else (sat * 0.08f).coerceIn(0.02f, 0.07f),
            0.955f
        ).copy(alpha = safeWellAlpha)
        val bgTint = if (hasCustomWallpaper) {
            Color.Transparent
        } else {
            hsvColor(hue, (sat * 0.05f).coerceIn(0.01f, 0.045f), 0.975f)
        }
        val borderTint = hsvColor(hue, (sat * 0.14f).coerceIn(0.04f, 0.12f), 0.90f)
        val borderStrong = hsvColor(hue, (sat * 0.18f).coerceIn(0.05f, 0.15f), 0.84f)

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
            errorContainer = XPeachDangerSoft,
            onErrorContainer = Color(0xFFB91C1C),
            background = bgTint,
            onBackground = XPeachTextLight,
            surface = surfaceTint,
            onSurface = XPeachTextLight,
            surfaceVariant = wellTint,
            onSurfaceVariant = XPeachMutedLight,
            surfaceContainerLow = XPeachSidebarLight,
            surfaceContainer = surfaceTint,
            surfaceContainerHigh = hsvColor(hue, (sat * 0.06f).coerceIn(0.01f, 0.06f), 0.98f),
            surfaceContainerHighest = hsvColor(hue, (sat * 0.08f).coerceIn(0.02f, 0.08f), 0.96f),
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
            hsvColor(hue, (sat * 0.24f).coerceIn(0.08f, 0.24f), 0.14f).copy(alpha = safeCardAlpha)
        } else {
            XPeachCardDark
        }
        val wellDarkTint = if (hasCustomWallpaper) {
            hsvColor(hue, (sat * 0.28f).coerceIn(0.10f, 0.28f), 0.20f).copy(alpha = safeWellAlpha)
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
            errorContainer = Color(0xFF3B1A1F),
            onErrorContainer = Color(0xFFFEE2E2),
            background = bgDarkTint,
            onBackground = XPeachTextDark,
            surface = surfaceDarkTint,
            onSurface = XPeachTextDark,
            surfaceVariant = wellDarkTint,
            onSurfaceVariant = XPeachMutedDark,
            surfaceContainerLow = XPeachSidebarDark,
            surfaceContainer = surfaceDarkTint,
            surfaceContainerHigh = Color(0xFF212834),
            surfaceContainerHighest = XPeachElevatedPillDark,
            outline = XPeachBorderStrongDark,
            outlineVariant = XPeachBorderDark
        )
    }
}

@Composable
fun HautCampusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: String = "SYSTEM",
    dynamicColor: Boolean = false,
    themePaletteKey: String = "HAUT_BLUE",
    customSeedArgb: Long = 0xFF0066CC,
    customWallpaperPath: String? = null,
    wallpaperAlpha: Float = 0.48f,
    wallpaperBlurDp: Float = 10f,
    wallpaperCardAlpha: Float = 0.82f,
    content: @Composable () -> Unit
) {
    val effectiveDark = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> darkTheme
    }
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
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasActiveWallpaper -> {
            val context = LocalContext.current
            if (effectiveDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        themePaletteKey == "HAUT_BLUE" && !hasActiveWallpaper -> {
            if (effectiveDark) DarkColors else LightColors
        }
        else -> buildSeedColorScheme(
            seedArgb = resolvedSeedArgb,
            darkTheme = effectiveDark,
            hasCustomWallpaper = hasActiveWallpaper,
            cardTemplateAlpha = wallpaperCardAlpha
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !effectiveDark
            insetsController.isAppearanceLightNavigationBars = !effectiveDark
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides effectiveDark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = HautTypography,
            shapes = HautShapes
        ) {
            val activeBmp = wallpaperBitmap
            if (activeBmp != null) {
                val baseBackdrop = if (effectiveDark) XPeachBgDark else XPeachBgLight
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(baseBackdrop)
                ) {
                    Image(
                        bitmap = activeBmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = wallpaperAlpha.coerceIn(0.10f, 1.0f),
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (wallpaperBlurDp > 0.2f) {
                                    Modifier.blur(
                                        radius = wallpaperBlurDp.dp,
                                        edgeTreatment = BlurredEdgeTreatment.Unbounded
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    )
                    // 自适应护眼柔光层，保证高饱和度壁纸下文字依然清晰易读
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                baseBackdrop.copy(
                                    alpha = (1f - wallpaperAlpha.coerceIn(0.10f, 1.0f)) * 0.45f
                                )
                            )
                    )
                    content()
                }
            } else {
                content()
            }
        }
    }
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
    val resolvedContainerColor = when (containerColor) {
        scheme.primaryContainer -> XPeachTheme.primaryAccentCardColor
        scheme.secondaryContainer -> XPeachTheme.secondaryAccentCardColor
        scheme.tertiaryContainer -> XPeachTheme.tertiaryAccentCardColor
        scheme.errorContainer -> XPeachTheme.errorAccentCardColor
        else -> containerColor
    }
    val defaultBorder = scheme.outlineVariant.copy(alpha = 0.65f)
    val resolvedBorderColor = if (borderColor == defaultBorder) {
        if (isDark) {
            when (containerColor) {
                scheme.primaryContainer -> scheme.primary.copy(alpha = 0.30f)
                scheme.secondaryContainer -> scheme.secondary.copy(alpha = 0.30f)
                scheme.tertiaryContainer -> scheme.tertiary.copy(alpha = 0.30f)
                scheme.errorContainer -> scheme.error.copy(alpha = 0.32f)
                else -> scheme.outlineVariant.copy(alpha = 0.36f)
            }
        } else {
            scheme.outlineVariant.copy(alpha = 0.30f)
        }
    } else {
        borderColor
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.984f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "cardPressScale"
    )
    val shape = RoundedCornerShape(cornerRadius)
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
        border = if (resolvedBorderColor != Color.Transparent) {
            BorderStroke(0.5.dp, resolvedBorderColor)
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
        )
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
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = if (borderColor != Color.Transparent) BorderStroke(0.5.dp, borderColor) else null
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
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
    HorizontalDivider(
        modifier = modifier.padding(start = startIndent, top = verticalPadding, bottom = verticalPadding),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.34f else 0.42f)
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
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
    val bgColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.42f else 0.35f)
    val iconTint = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 560f),
        label = "iconBtnScale"
    )
    val shape = RoundedCornerShape(FlutterM3Tokens.IconButtonCornerRadius)
    Box(
        modifier = modifier
            .size(FlutterM3Tokens.IconButtonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(bgColor)
            .border(0.5.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
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
    val bgColor = if (isDark) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.42f else 0.35f)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 560f),
        label = "iconBtnCustomScale"
    )
    val shape = RoundedCornerShape(FlutterM3Tokens.IconButtonCornerRadius)
    Box(
        modifier = modifier
            .size(FlutterM3Tokens.IconButtonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(bgColor)
            .border(0.5.dp, borderColor, shape)
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
    val unselectedBg = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    }
    val selectedBg = if (isDark) {
        XPeachTheme.readablePrimary.copy(alpha = 0.20f)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val selectedFg = XPeachTheme.readablePrimary
    val unselectedFg = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bg by animateColorAsState(
        targetValue = if (selected) selectedBg else unselectedBg,
        animationSpec = tween(180),
        label = "pillBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) {
            XPeachTheme.readablePrimary.copy(alpha = if (isDark) 0.48f else 0.32f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(180),
        label = "pillBorder"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) selectedFg else unselectedFg,
        animationSpec = tween(180),
        label = "pillFg"
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.955f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 540f),
        label = "pillScale"
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
                    Modifier.border(0.75.dp, border, pillShape)
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
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(14.dp)
            )
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
fun XPeachFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    leadingIcon: @Composable () -> Unit
) {
    val isDark = XPeachTheme.isDark
    val unselectedBg = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    }
    val selectedBg = if (isDark) {
        XPeachTheme.readablePrimary.copy(alpha = 0.20f)
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val selectedFg = XPeachTheme.readablePrimary
    val unselectedFg = if (isDark) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bg by animateColorAsState(
        targetValue = if (selected) selectedBg else unselectedBg,
        animationSpec = tween(180),
        label = "pillBgCustom"
    )
    val border by animateColorAsState(
        targetValue = if (selected) {
            XPeachTheme.readablePrimary.copy(alpha = if (isDark) 0.48f else 0.32f)
        } else {
            Color.Transparent
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
                    Modifier.border(0.75.dp, border, pillShape)
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
        leadingIcon()
        Spacer(modifier = Modifier.width(4.dp))
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
    val trackBg = if (isDark) Color(0xFF10151D) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val selectedThumbBg = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surface
    }
    val selectedThumbBorder = if (isDark) {
        XPeachTheme.readablePrimary.copy(alpha = 0.30f)
    } else {
        Color.Black.copy(alpha = 0.04f)
    }
    val containerShape = RoundedCornerShape(FlutterM3Tokens.SegmentedContainerRadius)
    val thumbShape = RoundedCornerShape(FlutterM3Tokens.SegmentedThumbRadius)
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
            .padding(3.dp)
    ) {
        val slotWidth = maxWidth / count
        val thumbOffsetX = slotWidth * animatedIndex

        // 1. 物理弹簧滑动胶囊滑块（CupertinoSlidingSegmentedControl 同款滑动阻尼）
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
                .border(0.5.dp, selectedThumbBorder, thumbShape)
        )

        // 2. 前景标签行与细微分隔线
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
                        MaterialTheme.colorScheme.onSurface
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
        isDark && containerColor == scheme.primaryContainer -> XPeachTheme.readablePrimary.copy(alpha = 0.16f)
        isDark && containerColor == scheme.secondaryContainer -> XPeachTheme.readableAccent(scheme.secondary).copy(alpha = 0.16f)
        isDark && containerColor == scheme.tertiaryContainer -> XPeachTheme.readableAccent(scheme.tertiary).copy(alpha = 0.16f)
        else -> containerColor
    }
    val badgeShape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = FlutterM3Tokens.BadgeMinHeight)
            .clip(badgeShape)
            .background(resolvedContainerColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(0.5.dp, borderColor, badgeShape)
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
