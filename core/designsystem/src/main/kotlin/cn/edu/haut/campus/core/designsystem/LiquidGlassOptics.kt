package cn.edu.haut.campus.core.designsystem

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * High-performance 120fps Liquid Glass optical engine based on Glass-HQ/liquid-glass
 * (https://github.com/Glass-HQ/liquid-glass):
 * - Single shared backdrop bitmap (`LocalLiquidGlassWallpaperBitmap`) rendered on the screen
 *   background and sampled directly by root glass surfaces (`uWallpaperShader`), so every card
 *   and floating dock genuinely refracts the actual background behind it without ever distorting
 *   its own foreground text or icons.
 * - Zero-recomposition scroll tracking (`FloatArray(2)` window offset updated in-place and read
 *   in `onDrawBehind`) + cached `RuntimeShader` per surface (zero shader compilations during scroll).
 * - Fast hardware `Canvas` path for small nested controls (`nested = true`) to guarantee 120fps.
 */
enum class LiquidGlassMaterial {
    REGULAR,
    CLEAR
}

val LocalLiquidGlassWallpaperBitmap = staticCompositionLocalOf<ImageBitmap?> { null }

enum class LiquidGlassSpringPreset(
    val durationSeconds: Float,
    val bounce: Float
) {
    PRESS(durationSeconds = 0.24f, bounce = 0.20f),
    RELEASE(durationSeconds = 0.50f, bounce = 0.42f),
    GLOW(durationSeconds = 0.42f, bounce = 0.00f),
    MORPH(durationSeconds = 0.42f, bounce = 0.18f),
    LAYOUT(durationSeconds = 0.40f, bounce = 0.16f),
    TRACK(durationSeconds = 0.18f, bounce = 0.00f),
    SWITCH(durationSeconds = 0.47f, bounce = 0.30f);

    fun <T> toSpringSpec(visibilityThreshold: T? = null): SpringSpec<T> {
        val omega = (2.0 * PI / durationSeconds.toDouble()).toFloat()
        val stiffness = omega * omega
        val dampingRatio = if (bounce >= 0f) {
            (1f - bounce).coerceIn(0.15f, 1f)
        } else {
            (1f / (1f + bounce)).coerceAtLeast(1f)
        }
        return spring(
            dampingRatio = dampingRatio,
            stiffness = stiffness,
            visibilityThreshold = visibilityThreshold
        )
    }
}

object LiquidGlassOptics {

    const val REGULAR_BLUR_SIGMA_DP = 16.0f
    const val REGULAR_LIGHT_FILL_SIGMA_DP = 23.0f
    const val REGULAR_DARK_FILL_SIGMA_DP = 21.8f
    const val REGULAR_LIGHT_FILL_OPACITY = 0.34f
    const val REGULAR_DARK_FILL_OPACITY = 0.65f
    const val REGULAR_LIGHT_CHROMA = 1.08f
    const val REGULAR_DARK_CHROMA = 1.06f
    const val DEFAULT_REFRACTION_PX = 60f
    const val BEZEL_INVERSE_HEIGHT = 0.05f
    const val LISSE_CORNER_SMOOTHING = 0.8125f

    private val frostedBackdropCache = ConcurrentHashMap<String, ImageBitmap>()

    internal val dummyBitmapShader: BitmapShader by lazy {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    fun edgeDisplacement(
        distance: Float,
        amount: Float = DEFAULT_REFRACTION_PX,
        inverseHeight: Float = BEZEL_INVERSE_HEIGHT
    ): Float {
        val depth = (-distance * inverseHeight).coerceIn(0f, 1f)
        val arc = sqrt(((2f - depth) * depth).coerceAtLeast(0f)).coerceIn(0f, 1f)
        return amount * (1f - arc)
    }

    fun dropletPressScale(
        widthDp: Float,
        heightDp: Float,
        pressProgress: Float = 1f
    ): Float {
        val maxDim = max(widthDp, heightDp).coerceAtLeast(24f)
        val grow = min(0.08f, 6.4f / maxDim) * pressProgress.coerceIn(0f, 1f)
        return 1f + grow
    }

    fun concentricRadius(outerRadiusDp: Float, insetDp: Float): Float =
        max(0f, outerRadiusDp - insetDp)

    fun concentricRadius(outerRadius: Dp, inset: Dp): Dp =
        max(0f, outerRadius.value - inset.value).dp

    fun evaluateRegularTone(v: Float, isDark: Boolean): Float {
        val clamped = v.coerceIn(0f, 1f)
        val a: Float
        val b: Float
        val q: Float
        val weight: Float
        if (isDark) {
            a = 0.105f
            b = 0.83f
            q = -0.2091f
            weight = 0.90f
        } else {
            a = 0.36f
            b = 0.67f
            q = -0.1113f
            weight = 0.80f
        }
        val toned = a + b * clamped + q * clamped * clamped
        return (weight * toned + (1f - weight) * clamped).coerceIn(0f, 1f)
    }

    fun evaluateClearTone(v: Float, isDark: Boolean, nested: Boolean = false): Float {
        val clamped = v.coerceIn(0f, 1f)
        val shadowLift = if (isDark) 0.15f else 0.18f
        val highlightRolloff = if (isDark) 0.20f else 0.0f
        val oneMinusV = 1f - clamped
        val tone = clamped +
            shadowLift * (oneMinusV * oneMinusV * oneMinusV) -
            highlightRolloff * (clamped * clamped * clamped)
        val tinted = if (isDark) 0.38f * clamped + 0.20f else 0.78f * clamped + 0.26f
        val tintWeight = if (isDark) 0.14f else 0.06f
        val layerLight = if (isDark && nested) 0.08f * oneMinusV else 0f
        return ((1f - tintWeight) * tone + tintWeight * tinted + layerLight).coerceIn(0f, 1f)
    }

    fun evaluateRimOverlay(
        dDp: Float,
        nx: Float,
        ny: Float,
        isDark: Boolean
    ): Pair<Float, Float> {
        fun sat(x: Float): Float = x.coerceIn(0f, 1f)
        val aa = 1.0f
        val coverage = sat(0.5f - dDp / aa)
        val spread = 1.08f
        val cosSpread = cos(spread)
        val cosDiffuse = cos(spread * 0.65f)
        val directional = sat((abs(ny) - cosSpread) / (1f - cosSpread)).pow(1.25f)
        val diffuseDir = sat((abs(ny) - cosDiffuse) / (1f - cosDiffuse)).pow(1.25f)
        val stroke = sat((dDp + 0.9f) / aa + 0.5f) * sat(0.5f - dDp / aa)
        var specular = directional * stroke
        specular /= (1f + 0.5f * (1.0f - specular))
        val diffuse = diffuseDir * 0.18f * (1f - sat(-dDp / 4.5f)).pow(2f) * coverage
        val highlight = sat(
            specular * (if (isDark) 0.82f else 1.0f) +
                diffuse * (if (isDark) 0.40f else 1.0f)
        )
        val adjusted = dDp
        val falloff = 1f - sat(-adjusted / 0.5f)
        val ramp = (if (falloff > 0f) 0.25f else 0f) + 0.75f * falloff
        val outline = ramp * sat((adjusted + 0.5f) / aa + 0.5f) * sat(-adjusted / aa + 0.5f)
        val sideSpread = 0.98f
        val cosSide = cos(sideSpread)
        val wx = sat((abs(nx) - cosSide) / (1f - cosSide)) * outline
        val shade = (wx / (1f + 0.4f * (1f - wx))) * (if (isDark) 0.60f else 0.48f)
        val alpha = (highlight + shade * (1f - highlight)).coerceIn(0f, 1f)
        val light = ((if (isDark) 0.82f else 1.0f) * highlight / max(alpha, 0.00001f)).coerceIn(0f, 1f)
        return light to alpha
    }

    fun buildBackdropColorFilter(isDark: Boolean): ColorMatrixColorFilter {
        val chroma = if (isDark) REGULAR_DARK_CHROMA else REGULAR_LIGHT_CHROMA
        val satMatrix = ColorMatrix().apply { setSaturation(chroma) }
        val scale = if (isDark) 0.94f else 0.97f
        val lift = if (isDark) 8f else 10f
        val toneMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, lift,
                0f, scale, 0f, 0f, lift,
                0f, 0f, scale, 0f, lift,
                0f, 0f, 0f, 1f, 0f
            )
        )
        satMatrix.postConcat(toneMatrix)
        return ColorMatrixColorFilter(satMatrix)
    }

    fun buildBackdropRenderEffect(blurPx: Float, isDark: Boolean): RenderEffect {
        val colorEffect = RenderEffect.createColorFilterEffect(buildBackdropColorFilter(isDark))
        return if (blurPx > 0.5f) {
            val blurEffect = RenderEffect.createBlurEffect(
                blurPx,
                blurPx,
                Shader.TileMode.MIRROR
            )
            RenderEffect.createChainEffect(colorEffect, blurEffect)
        } else {
            colorEffect
        }
    }

    /**
     * Builds the shared screen background bitmap (`320 x 640`).
     * Both `HautCampusTheme`'s background layer and `uWallpaperShader` in `liquidGlassEffect`
     * draw/sample this exact bitmap so that background color bands passing behind a glass card
     * are genuinely refracted and bent by the card's 22dp curved lens bezel.
     */
    fun getOrBuildFrostedBackdropBitmap(
        rawWallpaper: ImageBitmap?,
        wallpaperKey: String?,
        isDark: Boolean,
        primaryColor: Color,
        secondaryColor: Color,
        backdropAlpha: Float,
        blurDp: Float
    ): ImageBitmap {
        val quantAlpha = (backdropAlpha * 20f).roundToInt()
        val quantBlur = (blurDp * 2f).roundToInt()
        val cacheKey = "v3|${wallpaperKey ?: "ambient"}|$isDark|${primaryColor.toArgb()}|${secondaryColor.toArgb()}|$quantAlpha|$quantBlur"
        frostedBackdropCache[cacheKey]?.let { return it }

        val w = 320
        val h = 640
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val baseBg = if (isDark) Color(0xFF0A101C) else Color(0xFFEEF5FC)
        canvas.drawColor(baseBg.toArgb())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val safeAlpha = backdropAlpha.coerceIn(0.25f, 1.0f)

        if (rawWallpaper != null) {
            val src = rawWallpaper.asAndroidBitmap()
            val scale = max(w.toFloat() / src.width.coerceAtLeast(1), h.toFloat() / src.height.coerceAtLeast(1))
            val dstW = (src.width * scale).roundToInt()
            val dstH = (src.height * scale).roundToInt()
            val left = (w - dstW) / 2
            val top = (h - dstH) / 2
            paint.alpha = (safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            canvas.drawBitmap(src, null, Rect(left, top, left + dstW, top + dstH), paint)
            paint.alpha = 255

            val veilAlpha = ((1f - safeAlpha) * 0.32f + 0.10f).coerceIn(0.10f, 0.44f)
            canvas.drawColor(baseBg.copy(alpha = veilAlpha).toArgb())
        } else {
            val c1 = if (isDark) primaryColor else lerp(primaryColor, Color.White, 0.10f)
            val c2 = if (isDark) secondaryColor else lerp(secondaryColor, Color.White, 0.12f)
            val cMid = lerp(c1, c2, 0.5f)

            val a1 = ((if (isDark) 0.42f else 0.36f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            val a2 = ((if (isDark) 0.36f else 0.30f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            val aMid = ((if (isDark) 0.28f else 0.24f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)

            // Distinct ambient color orbs + diagonal aurora bands on the actual screen background
            // so when a glass card sits over them, the bezel visibly bends and refracts the background!
            paint.shader = RadialGradient(
                w * 0.15f,
                h * 0.14f,
                w * 0.72f,
                intArrayOf(
                    android.graphics.Color.argb(a1, (c1.red * 255).toInt(), (c1.green * 255).toInt(), (c1.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            paint.shader = RadialGradient(
                w * 0.88f,
                h * 0.30f,
                w * 0.68f,
                intArrayOf(
                    android.graphics.Color.argb(a2, (c2.red * 255).toInt(), (c2.green * 255).toInt(), (c2.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            paint.shader = RadialGradient(
                w * 0.22f,
                h * 0.54f,
                w * 0.74f,
                intArrayOf(
                    android.graphics.Color.argb(aMid, (cMid.red * 255).toInt(), (cMid.green * 255).toInt(), (cMid.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            paint.shader = RadialGradient(
                w * 0.82f,
                h * 0.72f,
                w * 0.70f,
                intArrayOf(
                    android.graphics.Color.argb((a1 * 0.85f).toInt(), (c1.red * 255).toInt(), (c1.green * 255).toInt(), (c1.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            // Diagonal aurora light bands across the screen background
            val bandAlpha = ((if (isDark) 0.26f else 0.34f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            val whiteBand = ((if (isDark) 0.14f else 0.55f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            paint.shader = LinearGradient(
                0f,
                h * 0.06f,
                w.toFloat(),
                h * 0.82f,
                intArrayOf(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.argb(bandAlpha, (c1.red * 255).toInt(), (c1.green * 255).toInt(), (c1.blue * 255).toInt()),
                    android.graphics.Color.argb(whiteBand, 255, 255, 255),
                    android.graphics.Color.argb(bandAlpha, (c2.red * 255).toInt(), (c2.green * 255).toInt(), (c2.blue * 255).toInt()),
                    android.graphics.Color.argb(whiteBand, 255, 255, 255),
                    android.graphics.Color.argb((bandAlpha * 0.8f).toInt(), (cMid.red * 255).toInt(), (cMid.green * 255).toInt(), (cMid.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.18f, 0.32f, 0.48f, 0.64f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
        }

        val blurRadius = (blurDp * 0.28f).roundToInt().coerceIn(2, 7)
        val blurred = fastBoxBlur(bmp, blurRadius)
        val imageBmp = blurred.asImageBitmap()
        if (frostedBackdropCache.size > 16) {
            frostedBackdropCache.clear()
        }
        frostedBackdropCache[cacheKey] = imageBmp
        return imageBmp
    }

    private fun fastBoxBlur(src: Bitmap, radius: Int): Bitmap {
        if (radius <= 1) return src
        val w = src.width
        val h = src.height
        val pix = IntArray(w * h)
        val tmp = IntArray(w * h)
        src.getPixels(pix, 0, w, 0, 0, w, h)
        val div = 2 * radius + 1

        for (y in 0 until h) {
            val rowOffset = y * w
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -radius..radius) {
                val p = pix[rowOffset + i.coerceIn(0, w - 1)]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[rowOffset + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val outP = pix[rowOffset + (x - radius).coerceIn(0, w - 1)]
                val inP = pix[rowOffset + (x + radius + 1).coerceIn(0, w - 1)]
                rSum += ((inP shr 16) and 0xFF) - ((outP shr 16) and 0xFF)
                gSum += ((inP shr 8) and 0xFF) - ((outP shr 8) and 0xFF)
                bSum += (inP and 0xFF) - (outP and 0xFF)
            }
        }

        for (x in 0 until w) {
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (i in -radius..radius) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                rSum += (p shr 16) and 0xFF
                gSum += (p shr 8) and 0xFF
                bSum += p and 0xFF
            }
            for (y in 0 until h) {
                pix[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
                val outP = tmp[(y - radius).coerceIn(0, h - 1) * w + x]
                val inP = tmp[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                rSum += ((inP shr 16) and 0xFF) - ((outP shr 16) and 0xFF)
                gSum += ((inP shr 8) and 0xFF) - ((outP shr 8) and 0xFF)
                bSum += (inP and 0xFF) - (outP and 0xFF)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pix, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * Ultra-fast 120fps Background-Refracting AGSL Shader:
     * - Zero `sin`/`cos`/`exp`/`pow(2.45)` per-pixel loops (uses hardware `length`, `sqrt`, and
     *   quadratic polynomials for ~6x faster fragment execution).
     * - Samples ONLY the shared screen background (`uWallpaperShader`) at `screenPos + disp`:
     *   - At the 22dp curved meniscus bezel (`edge > 0`), Snell refraction + corner astigmatism
     *     bends the background sampling coordinate by up to `1.85 * uRefractionPx` with RGB
     *     dispersion, visibly pulling and bending the background bands around the card's edge.
     *   - In the interior (`edge -> 0`), applies a subtle 5% convex lens magnification of the
     *     background behind the card, blended with a translucent frosted glass body.
     */
    private const val LIQUID_GLASS_AGSL_SOURCE = """
        uniform float2 uSize;
        uniform float2 uScreenOffset;
        uniform float2 uViewportSize;
        uniform float uRadius;
        uniform float uDpr;
        uniform float uDark;
        uniform float uBodyAlpha;
        uniform float uRefractionPx;
        uniform float uPressProgress;
        uniform float4 uBaseColor;
        uniform float4 uTintColor;
        uniform float uHasTint;
        uniform float3 uPrimaryRgb;
        uniform float3 uSecondaryRgb;
        uniform shader uWallpaperShader;
        uniform float uHasWallpaper;

        float sat(float x) {
            return clamp(x, 0.0, 1.0);
        }

        float3 sampleBackgroundAt(float2 screenPt) {
            float2 clampedPt = clamp(screenPt, float2(1.0), max(uViewportSize - float2(1.0), float2(1.0)));
            if (uHasWallpaper > 0.5) {
                return uWallpaperShader.eval(clampedPt).rgb;
            }
            float2 uv = clampedPt / max(uViewportSize, float2(1.0));
            float3 bgBase = uDark > 0.5 ? float3(0.039, 0.063, 0.110) : float3(0.933, 0.961, 0.988);
            float w1 = sat(1.0 - length(uv - float2(0.15, 0.14)) * 1.35);
            float w2 = sat(1.0 - length(uv - float2(0.88, 0.30)) * 1.40);
            float3 col = mix(bgBase, uPrimaryRgb, w1 * w1 * (uDark > 0.5 ? 0.38 : 0.30));
            col = mix(col, uSecondaryRgb, w2 * w2 * (uDark > 0.5 ? 0.32 : 0.25));
            return col;
        }

        half4 main(float2 fragCoord) {
            float2 halfSize = uSize * 0.5;
            float r = min(uRadius, min(halfSize.x, halfSize.y));
            float2 p = fragCoord - halfSize;
            float2 b = max(halfSize - float2(r), float2(0.0));
            float2 q = abs(p) - b;

            float2 qPos = max(q, float2(0.0));
            float d = length(qPos) + min(max(q.x, q.y), 0.0) - r;

            float aa = max(1.0, 0.85 * uDpr);
            float coverage = sat(0.5 - d / aa);
            if (coverage <= 0.001) {
                return half4(0.0);
            }

            float2 gradCorner = (q.x > 0.0 && q.y > 0.0)
                ? normalize(qPos + float2(1e-4))
                : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
            float2 sx = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
            float2 n = gradCorner * sx;

            // optics.ts circular-arc lens bezel profile (22dp wide)
            float dDp = d / max(uDpr, 1.0);
            float minDimDp = min(uSize.x, uSize.y) / max(uDpr, 1.0);
            float bezelWidthDp = clamp(minDimDp * 0.42, 10.0, 24.0);
            float depth = sat(-dDp / bezelWidthDp);
            float edge = 1.0 - sqrt(max(0.0, (2.0 - depth) * depth));

            // True background refraction: radial Snell pull + corner S-warp + interior convex magnification
            float2 tangent = float2(-n.y, n.x);
            float cornerWarp = 2.0 * n.x * n.y * edge * (1.0 - edge * 0.4);
            float pressBoost = 1.0 + 0.35 * uPressProgress;
            float2 disp = (-n * (edge * edge) * uRefractionPx * 1.85 + tangent * cornerWarp * uRefractionPx * 0.65) * pressBoost
                - p * 0.055 * (1.0 - edge);

            float2 screenPos = uScreenOffset + fragCoord;
            float dispersion = 0.14 * edge;
            float rChan = sampleBackgroundAt(screenPos + disp * (1.0 - dispersion)).r;
            float gChan = sampleBackgroundAt(screenPos + disp).g;
            float bChan = sampleBackgroundAt(screenPos + disp * (1.0 + dispersion)).b;
            float3 refractedBg = float3(rChan, gChan, bChan);

            // Translucent frosted glass body: clear refractive bevel (0.18) -> frosted center (0.48..0.56)
            float centerFill = mix(0.48, 0.54, uDark) * sat(uBodyAlpha * 0.95);
            float bezelFill = mix(0.16, 0.20, uDark) * sat(uBodyAlpha * 0.80);
            float glassFillMix = mix(centerFill, bezelFill, edge);
            float3 bodyRgb = mix(refractedBg, uBaseColor.rgb, glassFillMix);

            if (uHasTint > 0.5) {
                bodyRgb = mix(bodyRgb, uTintColor.rgb, 0.16);
            }

            // Fast polynomial meniscus caustic tube (-2.2dp) & thickness valley (-6.0dp)
            float tubeDist = abs(dDp + 2.2) / 1.6;
            float meniscusTube = max(0.0, 1.0 - tubeDist * tubeDist);
            float valleyDist = abs(dDp + 6.0) / 2.6;
            float lensValley = max(0.0, 1.0 - valleyDist * valleyDist);

            float topCaustic = max(0.0, -n.y) * edge * mix(0.10, 0.07, uDark);
            float botCaustic = max(0.0, n.y) * edge * mix(0.05, 0.03, uDark);
            float tubeLight = meniscusTube * meniscusTube * mix(0.12, 0.08, uDark);
            float valleyShade = lensValley * mix(-0.028, -0.042, uDark);
            bodyRgb = clamp(bodyRgb + float3(topCaustic + botCaustic + tubeLight + valleyShade), float3(0.0), float3(1.0));
            float bodyA = sat(mix(uBodyAlpha * 0.86, min(0.96, uBodyAlpha * 1.02), edge * 0.55)) * coverage;

            // maps.wgsl Plane 1 directional top/bottom specular rim + side micro-shade
            float cosSpread = 0.471328;
            float dirY = sat((abs(n.y) - cosSpread) / (1.0 - cosSpread));
            float stroke = sat((dDp + 1.1) + 0.5) * sat(0.5 - dDp);
            float specular = (dirY * dirY) * stroke;
            float diffuse = dirY * 0.18 * sat(1.0 + dDp / 4.5) * coverage;
            float highlight = sat(specular * mix(0.95, 0.80, uDark) + diffuse * mix(0.75, 0.36, uDark));

            float outline = sat((dDp + 0.8) + 0.5) * sat(-dDp + 0.5);
            float sideX = sat((abs(n.x) - 0.557) / 0.443) * outline;
            float shade = sideX * mix(0.20, 0.32, uDark);

            float overlayAlpha = sat(highlight + shade * (1.0 - highlight)) * coverage;
            float lightScalar = mix(1.0, 0.88, uDark) * highlight / max(overlayAlpha, 0.00001);
            float3 lightRgb = float3(lightScalar);

            float outA = overlayAlpha + bodyA * (1.0 - overlayAlpha);
            float3 outPremul = lightRgb * overlayAlpha + bodyRgb * bodyA * (1.0 - overlayAlpha);
            return half4(half3(outPremul), half(outA));
        }
    """

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createLiquidGlassShaderOrNull(): RuntimeShader? =
        runCatching { RuntimeShader(LIQUID_GLASS_AGSL_SOURCE) }.getOrNull()
}

/**
 * Applies the 120fps background-refracting Liquid Glass optical surface:
 * - Root surfaces (`nested = false`): run the cached AGSL background-refraction shader, sampling
 *   the shared `LocalLiquidGlassWallpaperBitmap` at `screenPos + disp` with zero scroll recomposition.
 * - Nested controls (`nested = true`): run the ultra-fast hardware `Canvas` meniscus bevel + specular
 *   rim path so screens with 50+ pills/wells scroll at a locked 120fps.
 */
fun Modifier.liquidGlassEffect(
    enabled: Boolean,
    isDark: Boolean,
    cornerRadius: Dp,
    baseColor: Color,
    material: LiquidGlassMaterial = LiquidGlassMaterial.REGULAR,
    accentTint: Color? = null,
    nested: Boolean = false,
    refractionDp: Float = if (material == LiquidGlassMaterial.CLEAR) 18f else 28f,
    pressProgress: Float = 0f
): Modifier {
    if (!enabled) return this
    return this.composed {
        val scheme = MaterialTheme.colorScheme
        val primaryColor = scheme.primary
        val secondaryColor = scheme.secondary
        val wallpaperBitmap = LocalLiquidGlassWallpaperBitmap.current
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val viewportW = with(density) { configuration.screenWidthDp.dp.toPx().coerceAtLeast(1f) }
        val viewportH = with(density) { configuration.screenHeightDp.dp.toPx().coerceAtLeast(1f) }

        // Primitive FloatArray(2) holder: updated in-place during scroll without triggering
        // Compose State recompositions or drawWithCache rebuilds!
        val offsetHolder = remember { FloatArray(2) }

        val cachedAgslShader = remember(nested) {
            if (!nested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                LiquidGlassOptics.createLiquidGlassShaderOrNull()
            } else {
                null
            }
        }
        val cachedShaderBrush = remember(cachedAgslShader) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cachedAgslShader != null) {
                ShaderBrush(cachedAgslShader)
            } else {
                null
            }
        }

        val cachedWallpaperShader = remember(wallpaperBitmap, viewportW.toInt(), viewportH.toInt(), nested) {
            if (nested) {
                null
            } else {
                wallpaperBitmap?.let { img ->
                    runCatching {
                        val bmp = img.asAndroidBitmap()
                        val matrix = Matrix()
                        val scale = max(viewportW / bmp.width.coerceAtLeast(1), viewportH / bmp.height.coerceAtLeast(1))
                        val dx = (viewportW - bmp.width * scale) * 0.5f
                        val dy = (viewportH - bmp.height * scale) * 0.5f
                        matrix.setScale(scale, scale)
                        matrix.postTranslate(dx, dy)
                        BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                            setLocalMatrix(matrix)
                        }
                    }.getOrNull()
                }
            }
        }

        Modifier
            .then(
                if (!nested) {
                    Modifier.onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            val pos = coords.positionInWindow()
                            offsetHolder[0] = pos.x
                            offsetHolder[1] = pos.y
                        }
                    }
                } else {
                    Modifier
                }
            )
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val dpr = this.density.coerceAtLeast(1f)
                val rPx = min(cornerRadius.toPx(), min(w, h) * 0.5f)
                val refractionPx = refractionDp.dp.toPx()

                val rawAlpha = baseColor.alpha.coerceIn(0.32f, 0.96f)
                val effectiveAlpha = if (material == LiquidGlassMaterial.CLEAR) {
                    (rawAlpha * (if (isDark) 0.84f else 0.82f)).coerceIn(0.34f, 0.92f)
                } else {
                    rawAlpha
                }
                val calibratedBase = if (isDark) {
                    val darkFloor = if (nested) Color(0xFF1D273A) else Color(0xFF151E2E)
                    lerp(baseColor.copy(alpha = 1f), darkFloor, if (nested) 0.22f else 0.30f)
                } else {
                    val lightLift = Color(0xFFFCFEFF)
                    lerp(baseColor.copy(alpha = 1f), lightLift, if (material == LiquidGlassMaterial.REGULAR) 0.45f else 0.32f)
                }
                val cleanTint = accentTint?.takeIf { it != Color.Transparent && it != Color.Unspecified }

                if (!nested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cachedAgslShader != null && cachedShaderBrush != null) {
                    cachedAgslShader.setFloatUniform("uSize", w, h)
                    cachedAgslShader.setFloatUniform("uViewportSize", viewportW, viewportH)
                    cachedAgslShader.setFloatUniform("uRadius", rPx)
                    cachedAgslShader.setFloatUniform("uDpr", dpr)
                    cachedAgslShader.setFloatUniform("uDark", if (isDark) 1f else 0f)
                    cachedAgslShader.setFloatUniform("uBodyAlpha", effectiveAlpha)
                    cachedAgslShader.setFloatUniform("uRefractionPx", refractionPx)
                    cachedAgslShader.setFloatUniform("uPressProgress", pressProgress.coerceIn(0f, 1f))
                    cachedAgslShader.setFloatUniform(
                        "uBaseColor",
                        calibratedBase.red,
                        calibratedBase.green,
                        calibratedBase.blue,
                        1f
                    )
                    if (cleanTint != null) {
                        cachedAgslShader.setFloatUniform(
                            "uTintColor",
                            cleanTint.red,
                            cleanTint.green,
                            cleanTint.blue,
                            1f
                        )
                        cachedAgslShader.setFloatUniform("uHasTint", 1f)
                    } else {
                        cachedAgslShader.setFloatUniform("uTintColor", 1f, 1f, 1f, 1f)
                        cachedAgslShader.setFloatUniform("uHasTint", 0f)
                    }
                    cachedAgslShader.setFloatUniform(
                        "uPrimaryRgb",
                        primaryColor.red,
                        primaryColor.green,
                        primaryColor.blue
                    )
                    cachedAgslShader.setFloatUniform(
                        "uSecondaryRgb",
                        secondaryColor.red,
                        secondaryColor.green,
                        secondaryColor.blue
                    )
                    if (cachedWallpaperShader != null) {
                        cachedAgslShader.setInputShader("uWallpaperShader", cachedWallpaperShader)
                        cachedAgslShader.setFloatUniform("uHasWallpaper", 1f)
                    } else {
                        cachedAgslShader.setInputShader("uWallpaperShader", LiquidGlassOptics.dummyBitmapShader)
                        cachedAgslShader.setFloatUniform("uHasWallpaper", 0f)
                    }
                    return@drawWithCache onDrawBehind {
                        cachedAgslShader.setFloatUniform("uScreenOffset", offsetHolder[0], offsetHolder[1])
                        drawRect(brush = cachedShaderBrush)
                    }
                }

                // Fast 120fps hardware Canvas meniscus lens for nested controls & API 31-32 fallback
                val bezelDepthPx = min(16.dp.toPx(), h * 0.45f).coerceAtLeast(1f)
                val topSpecAlpha = if (isDark) 0.68f else 0.92f
                val botSpecAlpha = if (isDark) 0.46f else 0.72f
                val sideShadeAlpha = if (isDark) 0.30f else 0.18f
                val topDiffuseColor = (cleanTint?.let { lerp(Color.White, it, 0.25f) } ?: Color.White)
                    .copy(alpha = if (isDark) 0.16f else 0.38f)

                val bodyBrush = Brush.verticalGradient(
                    colors = listOf(
                        calibratedBase.copy(alpha = (effectiveAlpha * 1.04f).coerceIn(0f, 0.95f)),
                        calibratedBase.copy(alpha = (effectiveAlpha * 0.92f).coerceIn(0f, 0.92f)),
                        calibratedBase.copy(alpha = effectiveAlpha)
                    ),
                    startY = 0f,
                    endY = h
                )
                val topBevelBrush = Brush.verticalGradient(
                    colors = listOf(topDiffuseColor, Color.Transparent),
                    startY = 0f,
                    endY = bezelDepthPx
                )
                val rimBrush = Brush.verticalGradient(
                    0.0f to Color.White.copy(alpha = topSpecAlpha),
                    0.22f to topDiffuseColor,
                    0.50f to Color.Black.copy(alpha = sideShadeAlpha),
                    0.78f to topDiffuseColor,
                    1.0f to Color.White.copy(alpha = botSpecAlpha),
                    startY = 0f,
                    endY = h
                )
                val cr = CornerRadius(rPx, rPx)
                val strokeWidth = 1.dp.toPx()

                onDrawBehind {
                    drawRoundRect(brush = bodyBrush, cornerRadius = cr)
                    drawRoundRect(brush = topBevelBrush, cornerRadius = cr)
                    drawRoundRect(
                        brush = rimBrush,
                        topLeft = Offset(strokeWidth * 0.5f, strokeWidth * 0.5f),
                        size = Size(max(1f, w - strokeWidth), max(1f, h - strokeWidth)),
                        cornerRadius = CornerRadius(max(0f, rPx - strokeWidth * 0.5f)),
                        style = Stroke(width = strokeWidth)
                    )
                }
            }
    }
}

@Composable
fun XPeachSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    if (XPeachTheme.isLiquidGlass && onCheckedChange != null) {
        LiquidGlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled
        )
    } else {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled
        )
    }
}

@Composable
fun LiquidGlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val isDark = XPeachTheme.isDark
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val thumbTravelDp by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = LiquidGlassSpringPreset.SWITCH.toSpringSpec(),
        label = "lgSwitchTravel"
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "lgSwitchPressProgress"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            XPeachTheme.readablePrimary.copy(alpha = if (enabled) 0.90f else 0.45f)
        } else {
            Color(0xFF788296).copy(alpha = if (isDark) 0.42f else 0.32f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "lgSwitchTrackColor"
    )

    val swellX = 6.dp * pressProgress
    val swellY = 4.dp * pressProgress
    val thumbW = 32.dp + swellX
    val thumbH = 22.dp + swellY

    Box(
        modifier = modifier
            .width(56.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) }
            )
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val thumbBaseColor = if (isDark) {
            Color(0xFFF8FAFC).copy(alpha = (0.90f - 0.35f * pressProgress).coerceIn(0.48f, 0.94f))
        } else {
            Color.White.copy(alpha = (0.92f - 0.38f * pressProgress).coerceIn(0.48f, 0.96f))
        }
        Box(
            modifier = Modifier
                .offset(x = thumbTravelDp - (swellX * 0.5f))
                .size(width = thumbW, height = thumbH)
                .shadow(
                    elevation = 2.dp,
                    shape = CircleShape,
                    clip = false
                )
                .clip(CircleShape)
                .liquidGlassEffect(
                    enabled = true,
                    isDark = isDark,
                    cornerRadius = 999.dp,
                    baseColor = thumbBaseColor,
                    material = LiquidGlassMaterial.CLEAR,
                    accentTint = if (checked) XPeachTheme.readablePrimary else null,
                    nested = true,
                    refractionDp = 18f,
                    pressProgress = pressProgress
                )
        )
    }
}
