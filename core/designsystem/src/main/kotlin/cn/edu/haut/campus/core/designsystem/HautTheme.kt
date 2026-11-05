package cn.edu.haut.campus.core.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

val XPeachBrandLight = Color(0xFF0066CC)
val XPeachBrandHoverLight = Color(0xFF0052A3)
val XPeachBrandSoftLight = Color(0xFFEAF3FF)
val XPeachBrandBorderLight = Color(0xFFC2DDFB)

val XPeachBrandDark = Color(0xFF479BFF)
val XPeachBrandSoftDark = Color(0xFF1A2B42)
val XPeachBrandBorderDark = Color(0xFF2B4C7E)

val XPeachOk = Color(0xFF16A34A)
val XPeachOkSoft = Color(0xFFEAF7EE)
val XPeachWarn = Color(0xFFD97706)
val XPeachWarnSoft = Color(0xFFFEF3C7)
val XPeachDanger = Color(0xFFDC2626)
val XPeachDangerSoft = Color(0xFFFEE2E2)
val XPeachInfo = Color(0xFF0284C7)

// 轻盈柔和的底色体系：以微灰蓝底色衬托纯白卡片，弱化硬边框
val XPeachBgLight = Color(0xFFF5F7FA)
val XPeachCardLight = Color(0xFFFFFFFF)
val XPeachWellLight = Color(0xFFF2F5F9)
val XPeachBorderLight = Color(0xFFE8ECF2)
val XPeachBorderStrongLight = Color(0xFFD8E0EA)
val XPeachTextLight = Color(0xFF111827)
val XPeachText2Light = Color(0xFF374151)
val XPeachMutedLight = Color(0xFF6B7280)

val XPeachBgDark = Color(0xFF111317)
val XPeachCardDark = Color(0xFF1B1E24)
val XPeachWellDark = Color(0xFF232730)
val XPeachSidebarDark = Color(0xFF15181E)
val XPeachBorderDark = Color(0xFF282D37)
val XPeachBorderStrongDark = Color(0xFF363C4A)
val XPeachTextDark = Color(0xFFF3F4F6)
val XPeachText2Dark = Color(0xFFD1D5DB)
val XPeachMutedDark = Color(0xFF9CA3AF)

private val LightColors = lightColorScheme(
    primary = XPeachBrandLight,
    onPrimary = Color.White,
    primaryContainer = XPeachBrandSoftLight,
    onPrimaryContainer = XPeachBrandLight,
    secondary = XPeachInfo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0369A1),
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
    outline = XPeachBorderStrongLight,
    outlineVariant = XPeachBorderLight
)

private val DarkColors = darkColorScheme(
    primary = XPeachBrandDark,
    onPrimary = Color.White,
    primaryContainer = XPeachBrandSoftDark,
    onPrimaryContainer = Color(0xFF7CC0FF),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF16324A),
    onSecondaryContainer = Color(0xFF7DD3FC),
    tertiary = Color(0xFF22C55E),
    onTertiary = Color(0xFF052E16),
    tertiaryContainer = Color(0xFF143823),
    onTertiaryContainer = Color(0xFF4ADE80),
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF3F1D1D),
    onErrorContainer = Color(0xFFF87171),
    background = XPeachBgDark,
    onBackground = XPeachTextDark,
    surface = XPeachCardDark,
    onSurface = XPeachTextDark,
    surfaceVariant = XPeachWellDark,
    onSurfaceVariant = XPeachMutedDark,
    outline = XPeachBorderStrongDark,
    outlineVariant = XPeachBorderDark
)

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
            surfaceContainerHigh = hsvColor(hue, (sat * 0.06f).coerceIn(0.01f, 0.06f), 0.98f),
            surfaceContainerHighest = hsvColor(hue, (sat * 0.08f).coerceIn(0.02f, 0.08f), 0.96f),
            outline = borderStrong,
            outlineVariant = borderTint
        )
    } else {
        val primaryDark = hsvColor(hue, (sat * 0.68f).coerceIn(0.28f, 0.72f), 0.96f)
        val primaryContainerDark = hsvColor(hue, (sat * 0.62f).coerceIn(0.22f, 0.65f), 0.28f)
            .copy(alpha = safeContainerAlpha)
        val onPrimaryContainerDark = hsvColor(hue, (sat * 0.48f).coerceIn(0.18f, 0.52f), 0.98f)
        val secondaryDark = hsvColor(hue + 18f, (sat * 0.58f).coerceIn(0.22f, 0.62f), 0.90f)
        val secondaryContainerDark = hsvColor(hue + 18f, (sat * 0.55f).coerceIn(0.2f, 0.58f), 0.26f)
            .copy(alpha = safeContainerAlpha)
        val onSecondaryContainerDark = hsvColor(hue + 18f, (sat * 0.42f).coerceIn(0.16f, 0.48f), 0.95f)

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
            onPrimary = Color(0xFF09111E),
            primaryContainer = primaryContainerDark,
            onPrimaryContainer = onPrimaryContainerDark,
            secondary = secondaryDark,
            onSecondary = Color(0xFF09111E),
            secondaryContainer = secondaryContainerDark,
            onSecondaryContainer = onSecondaryContainerDark,
            tertiary = Color(0xFF22C55E),
            onTertiary = Color(0xFF052E16),
            tertiaryContainer = Color(0xFF143823).copy(alpha = safeContainerAlpha),
            onTertiaryContainer = Color(0xFF4ADE80),
            error = Color(0xFFEF4444),
            onError = Color.White,
            errorContainer = Color(0xFF3F1D1D),
            onErrorContainer = Color(0xFFF87171),
            background = bgDarkTint,
            onBackground = XPeachTextDark,
            surface = surfaceDarkTint,
            onSurface = XPeachTextDark,
            surfaceVariant = wellDarkTint,
            onSurfaceVariant = XPeachMutedDark,
            surfaceContainerHigh = Color(0xFF1E222B),
            surfaceContainerHighest = Color(0xFF252A34),
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

@Composable
fun XPeachCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
    cornerRadius: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(0.8.dp, borderColor)
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
    cornerRadius: Dp = 12.dp,
    borderColor: Color = Color.Transparent,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentPadding: PaddingValues = PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = if (borderColor != Color.Transparent) BorderStroke(0.8.dp, borderColor) else null
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
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
                            XPeachStatusBadge(text = badgeText)
                        }
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.padding(top = 1.dp))
                        if (onSubtitleClick != null) {
                            Text(
                                text = "$subtitle ▾",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    val shape = RoundedCornerShape(FlutterM3Tokens.IconButtonCornerRadius)
    Box(
        modifier = modifier
            .size(FlutterM3Tokens.IconButtonSize)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
    val shape = RoundedCornerShape(FlutterM3Tokens.IconButtonCornerRadius)
    Box(
        modifier = modifier
            .size(FlutterM3Tokens.IconButtonSize)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                0.8.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                shape
            )
            .clickable(onClick = onClick),
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
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(180),
        label = "pillBg"
    )
    val border by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(180),
        label = "pillBorder"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "pillFg"
    )
    val pillShape = RoundedCornerShape(999.dp)

    Row(
        modifier = modifier
            .defaultMinSize(
                minHeight = if (compact) FlutterM3Tokens.CompactFilterPillMinHeight else FlutterM3Tokens.FilterPillMinHeight
            )
            .clip(pillShape)
            .background(bg)
            .border(0.8.dp, border, pillShape)
            .clickable(onClick = onClick)
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
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(180),
        label = "pillBgCustom"
    )
    val border by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        animationSpec = tween(180),
        label = "pillBorderCustom"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "pillFgCustom"
    )
    val pillShape = RoundedCornerShape(999.dp)

    Row(
        modifier = modifier
            .defaultMinSize(
                minHeight = if (compact) FlutterM3Tokens.CompactFilterPillMinHeight else FlutterM3Tokens.FilterPillMinHeight
            )
            .clip(pillShape)
            .background(bg)
            .border(0.8.dp, border, pillShape)
            .clickable(onClick = onClick)
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
    val containerShape = RoundedCornerShape(FlutterM3Tokens.SegmentedContainerRadius)
    val thumbShape = RoundedCornerShape(FlutterM3Tokens.SegmentedThumbRadius)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FlutterM3Tokens.SegmentedControlMinHeight)
            .clip(containerShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (option in options) {
            val isSelected = option == selectedOption
            val tabBg by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                animationSpec = tween(180),
                label = "segTabBg"
            )
            val tabFg by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                animationSpec = tween(180),
                label = "segTabFg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 30.dp)
                    .clip(thumbShape)
                    .background(tabBg)
                    .then(
                        if (isSelected) {
                            Modifier.border(
                                0.6.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                thumbShape
                            )
                        } else {
                            Modifier
                        }
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
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
                    overflow = TextOverflow.Ellipsis
                )
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
    val badgeShape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = FlutterM3Tokens.BadgeMinHeight)
            .clip(badgeShape)
            .background(containerColor)
            .then(
                if (borderColor != Color.Transparent) {
                    Modifier.border(0.8.dp, borderColor, badgeShape)
                } else Modifier
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
