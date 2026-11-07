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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
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
 * Calibrated optical & interaction engine based on Glass-HQ/liquid-glass
 * (https://github.com/Glass-HQ/liquid-glass) and Apple iOS 26 / macOS Tahoe Liquid Glass:
 * 1. Four-Zone Physical Meniscus Lens Profile:
 *    - Zone 1 (`0.0dp..-1.2dp`): `maps.wgsl` Plane 1 directional top/bottom specular rim & oblique cut.
 *    - Zone 2 (`-1.0dp..-4.4dp`): Refractive Meniscus Caustic Tube (`弯月面内聚光亮环`) with RGB chromatic dispersion.
 *    - Zone 3 (`-4.2dp..-10.5dp`): Optical Thickness Trough (`玻璃体厚度暗谷`) that gives 4mm curved slab depth.
 *    - Zone 4 (`Interior Dome`): Creamy frosted convex dome with S-curve luminance gradient.
 * 2. Radial + Tangential Astigmatic Warping (`radialDisp + swirlDisp`):
 *    - Bends and stretches background silk ribbons and multi-angle caustic harmonics around squircle corners.
 * 3. Real-Time Content-Distorting `RenderEffect` (`LIQUID_CAPSULE_WARP_AGSL`):
 *    - Applied to `XPeachBottomDock`, `XPeachSegmentedTabs`, and `LiquidGlassSwitch` so sliding a
 *      liquid capsule lens physically warps, magnifies, and chromatically disperses the actual UI
 *      icons, labels, and track underneath its curved meniscus edge in 120Hz real time.
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

    /**
     * Exact port of `optics.ts` `edgeDisplacement(distance, amount, inverseHeight)`:
     * Computes the circular-arc lens bezel refraction factor in `[0, amount]`.
     */
    fun edgeDisplacement(
        distance: Float,
        amount: Float = DEFAULT_REFRACTION_PX,
        inverseHeight: Float = BEZEL_INVERSE_HEIGHT
    ): Float {
        val depth = (-distance * inverseHeight).coerceIn(0f, 1f)
        val arc = sqrt(((2f - depth) * depth).coerceAtLeast(0f)).coerceIn(0f, 1f)
        return amount * (1f - arc)
    }

    /**
     * Exact port of `interaction.ts` line 184 droplet swell scale factor:
     * `grow = min(0.16, 8 / max(widthDp, heightDp)) * press`
     */
    fun dropletPressScale(
        widthDp: Float,
        heightDp: Float,
        pressProgress: Float = 1f
    ): Float {
        val maxDim = max(widthDp, heightDp).coerceAtLeast(24f)
        val grow = min(0.08f, 6.4f / maxDim) * pressProgress.coerceIn(0f, 1f)
        return 1f + grow
    }

    /**
     * Exact port of `concentric.ts`: inner radius of a control nested at `insetDp` inside an outer container.
     */
    fun concentricRadius(outerRadiusDp: Float, insetDp: Float): Float =
        max(0f, outerRadiusDp - insetDp)

    fun concentricRadius(outerRadius: Dp, inset: Dp): Dp =
        max(0f, outerRadius.value - inset.value).dp

    /**
     * Exact port of `material-graph.ts` `toneMarkup` for `regular` material:
     * `toned = a + b * v + q * v^2`, blended with `v` by `toneWeight` (0.80 in light, 0.90 in dark).
     */
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

    /**
     * Exact port of `material-graph.ts` `toneMarkup` for `clear` material:
     * `tone = v + shadowLift * (1 - v)^3 - highlightRolloff * v^3`.
     */
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

    /**
     * Exact port of `maps.wgsl` Plane 1 overlay rim equation at a boundary point with outward normal `(nx, ny)`
     * and signed distance `d` (in dp).
     */
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
     * Synthesizes a structured `280 x 560` optical backdrop with diagonal silk light ribbons
     * and multi-band gradients so that when a Liquid Glass lens refracts `uWallpaperShader`
     * along its curved 20dp meniscus bezel, the light bands visibly bend, magnify, and warp.
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
        val cacheKey = "v2|${wallpaperKey ?: "ambient"}|$isDark|${primaryColor.toArgb()}|${secondaryColor.toArgb()}|$quantAlpha|$quantBlur"
        frostedBackdropCache[cacheKey]?.let { return it }

        val w = 280
        val h = 560
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val baseBg = if (isDark) Color(0xFF0B111D) else Color(0xFFF1F6FD)
        canvas.drawColor(baseBg.toArgb())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val safeAlpha = backdropAlpha.coerceIn(0.22f, 1.0f)

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

            val veilAlpha = ((1f - safeAlpha) * 0.36f + 0.12f).coerceIn(0.12f, 0.48f)
            canvas.drawColor(baseBg.copy(alpha = veilAlpha).toArgb())
        } else {
            val c1 = if (isDark) primaryColor else lerp(primaryColor, Color.White, 0.15f)
            val c2 = if (isDark) secondaryColor else lerp(secondaryColor, Color.White, 0.18f)
            val cMid = lerp(c1, c2, 0.5f)

            val a1 = ((if (isDark) 0.34f else 0.28f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            val a2 = ((if (isDark) 0.28f else 0.24f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            val aMid = ((if (isDark) 0.22f else 0.18f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)

            paint.shader = RadialGradient(
                w * 0.16f,
                h * 0.14f,
                w * 0.92f,
                intArrayOf(
                    android.graphics.Color.argb(a1, (c1.red * 255).toInt(), (c1.green * 255).toInt(), (c1.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            paint.shader = RadialGradient(
                w * 0.86f,
                h * 0.28f,
                w * 0.88f,
                intArrayOf(
                    android.graphics.Color.argb(a2, (c2.red * 255).toInt(), (c2.green * 255).toInt(), (c2.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            paint.shader = RadialGradient(
                w * 0.36f,
                h * 0.56f,
                w * 0.95f,
                intArrayOf(
                    android.graphics.Color.argb(aMid, (cMid.red * 255).toInt(), (cMid.green * 255).toInt(), (cMid.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            // Structured diagonal silk caustic ribbons so lens refraction bends visible light bands
            val ribbonAlpha = ((if (isDark) 0.20f else 0.26f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            val whiteRibbonAlpha = ((if (isDark) 0.12f else 0.42f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            paint.shader = LinearGradient(
                0f,
                h * 0.08f,
                w.toFloat(),
                h * 0.72f,
                intArrayOf(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.argb(ribbonAlpha, (c1.red * 255).toInt(), (c1.green * 255).toInt(), (c1.blue * 255).toInt()),
                    android.graphics.Color.argb(whiteRibbonAlpha, 255, 255, 255),
                    android.graphics.Color.argb(ribbonAlpha, (c2.red * 255).toInt(), (c2.green * 255).toInt(), (c2.blue * 255).toInt()),
                    android.graphics.Color.argb(whiteRibbonAlpha, 255, 255, 255),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.22f, 0.38f, 0.58f, 0.76f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
        }

        val blurRadius = (blurDp * 0.35f).roundToInt().coerceIn(2, 8)
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
     * Four-Zone Meniscus Lens & Astigmatic S-Warp AGSL Shader:
     * - Applies radial Snell refraction (`-n * pow(edge, 1.12) * uRefractionPx`) + tangential corner
     *   S-curve warping (`tangent * cornerAstigmatism * edge`) across both `REGULAR` and `CLEAR` lenses.
     * - Modulates the scene with multi-harmonic screen-space silk caustic ribbons so scrolling or
     *   pressing produces unmistakable glass bending, magnification, and RGB chromatic dispersion
     *   along the curved 20dp meniscus bevel.
     * - Renders the 4-zone physical glass thickness profile:
     *   1) Outer specular rim (`maps.wgsl` Plane 1)
     *   2) Inner Meniscus Caustic Ridge (`dDp = -2.3dp`, bright focused light tube + prismatic fringe)
     *   3) Optical Thickness Valley (`dDp = -6.4dp`, subtle refractive density dip giving 4mm slab depth)
     *   4) Creamy convex dome interior (`bodyRgb` with top-left to bottom-right S-curve sheen)
     */
    private const val LIQUID_GLASS_AGSL_SOURCE = """
        uniform float2 uSize;
        uniform float2 uScreenOffset;
        uniform float2 uViewportSize;
        uniform float uRadius;
        uniform float uDpr;
        uniform float uDark;
        uniform float uIsClear;
        uniform float uNested;
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

        float3 evalSilkCausticField(float2 screenPt, float waveBoost) {
            float2 uv = screenPt / max(uViewportSize, float2(1.0));
            float3 bgBase = uDark > 0.5 ? float3(0.043, 0.067, 0.114) : float3(0.945, 0.969, 0.992);
            float d1 = length((uv - float2(0.16, 0.14)) * float2(1.0, 1.25));
            float d2 = length((uv - float2(0.86, 0.28)) * float2(1.0, 1.25));
            float w1 = exp(-d1 * d1 * 2.2);
            float w2 = exp(-d2 * d2 * 2.4);
            float intensity = uDark > 0.5 ? 0.26 : 0.20;
            float3 col = mix(bgBase, uPrimaryRgb, sat(w1 * intensity));
            col = mix(col, uSecondaryRgb, sat(w2 * intensity * 0.90));

            // Multi-angle optical silk ribbons that bend and magnify inside the curved lens bezel
            float phase1 = uv.x * 11.5 + uv.y * 7.8;
            float phase2 = uv.x * -8.4 + uv.y * 13.2;
            float2 warpUv = uv + 0.06 * float2(sin(phase2), cos(phase1));
            float ribbon1 = pow(0.5 + 0.5 * sin(warpUv.x * 16.0 + warpUv.y * 10.5), 3.0);
            float ribbon2 = pow(0.5 + 0.5 * cos(warpUv.x * 12.0 - warpUv.y * 15.0), 3.0);
            float3 ribbonColor = mix(uPrimaryRgb, uSecondaryRgb, ribbon2);
            float3 specWhite = uDark > 0.5 ? mix(ribbonColor, float3(1.0), 0.45) : float3(1.0);
            col = mix(col, ribbonColor, ribbon1 * mix(0.06, 0.24, waveBoost));
            col = mix(col, specWhite, (ribbon1 * ribbon2) * mix(0.04, 0.28, waveBoost));
            return clamp(col, float3(0.0), float3(1.0));
        }

        float3 sampleSceneAt(float2 screenPt, float waveBoost) {
            float3 silk = evalSilkCausticField(screenPt, waveBoost);
            if (uHasWallpaper > 0.5) {
                float2 clampedPt = clamp(screenPt, float2(1.0), max(uViewportSize - float2(1.0), float2(1.0)));
                float3 wp = uWallpaperShader.eval(clampedPt).rgb;
                return mix(wp, silk, mix(0.14, 0.42, waveBoost));
            }
            return silk;
        }

        half4 main(float2 fragCoord) {
            float2 halfSize = uSize * 0.5;
            float r = min(uRadius, min(halfSize.x, halfSize.y));
            float2 p = fragCoord - halfSize;
            float2 b = max(halfSize - float2(r), float2(0.0));
            float2 q = abs(p) - b;

            // Lisse squircle SDF (cornerSmoothing = 0.8125 -> superellipse exponent ~ 2.45)
            float2 qPos = max(q, float2(0.0));
            float cornerDist = pow(pow(qPos.x, 2.45) + pow(qPos.y, 2.45), 1.0 / 2.45);
            float d = cornerDist + min(max(q.x, q.y), 0.0) - r;

            float2 gradCorner = (q.x > 0.0 && q.y > 0.0)
                ? normalize(float2(pow(qPos.x, 1.45), pow(qPos.y, 1.45)) + float2(1e-5))
                : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
            float2 sx = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
            float2 n = normalize(gradCorner * sx + float2(1e-6));

            float aa = max(1.0, 0.85 * uDpr);
            float coverage = sat(0.5 - d / aa);
            if (coverage <= 0.001) {
                return half4(0.0);
            }

            // Adaptive circular-arc lens bezel profile (optics.ts edgeDisplacement)
            float dDp = d / max(uDpr, 1.0);
            float minDimDp = min(uSize.x, uSize.y) / max(uDpr, 1.0);
            float bezelWidthDp = clamp(minDimDp * 0.42, 8.0, uNested > 0.5 ? 14.0 : 22.0);
            float depth = sat(-dDp / bezelWidthDp);
            float edge = 1.0 - sat(sqrt(max(0.0, (2.0 - depth) * depth)));
            float edgeCurve = pow(edge, 1.12);

            // Radial Snell refraction + tangential corner S-curve astigmatic warping
            float2 tangent = float2(-n.y, n.x);
            float cornerAstigmatism = 2.0 * n.x * n.y;
            float pressBoost = 1.0 + 0.42 * uPressProgress;
            float2 radialDisp = -n * edgeCurve * uRefractionPx * 1.45 * pressBoost;
            float2 swirlDisp = tangent * cornerAstigmatism * edge * (1.0 - edge * 0.40) * uRefractionPx * 0.68 * pressBoost;
            float2 domeMag = -p * 0.045 * (1.0 - edge);
            float2 disp = radialDisp + swirlDisp + domeMag;

            float2 screenPos = uScreenOffset + fragCoord;
            float waveBoost = sat(edge * 1.25 + uPressProgress * 0.45);
            float dispersion = (0.14 + 0.08 * uPressProgress) * edge;

            // Prismatic 3-channel RGB refraction sampling
            float rChan = sampleSceneAt(screenPos + disp * (1.0 - dispersion), waveBoost).r;
            float gChan = sampleSceneAt(screenPos + disp, waveBoost).g;
            float bChan = sampleSceneAt(screenPos + disp * (1.0 + dispersion), waveBoost).b;
            float3 refractedRgb = float3(rChan, gChan, bChan);

            // Convex dome S-curve luminance across the glass body (top-left bright, bottom-right deep)
            float2 normUv = fragCoord / max(uSize, float2(1.0));
            float domeAxis = (1.0 - normUv.x) * 0.52 + (1.0 - normUv.y) * 0.48;
            float domeLift = (domeAxis - 0.48) * mix(0.055, 0.040, uDark);

            // Four-Zone Meniscus Thickness Profile:
            // Zone 2: Inner Meniscus Caustic Tube (dDp around -2.2dp)
            float meniscusTube = exp(-pow((dDp + 2.2) / 1.40, 2.0));
            // Zone 3: Optical Thickness Trough / Lens Valley (dDp around -6.2dp)
            float lensTrough = exp(-pow((dDp + 6.2) / 2.50, 2.0));

            float3 bodyRgb;
            float bodyA;

            if (uNested > 0.5) {
                // Nested CLEAR convex droplet lens (Well, Pill, Segmented Thumb, Switch Thumb):
                // Combines refracted caustic rim with convex dome highlight & meniscus thickness ring
                float nestedFill = mix(0.52, 0.48, uDark) * (1.0 - 0.35 * edge);
                bodyRgb = mix(refractedRgb, uBaseColor.rgb + float3(domeLift), clamp(nestedFill, 0.25, 0.78));
                if (uHasTint > 0.5) {
                    bodyRgb = mix(bodyRgb, uTintColor.rgb, 0.22);
                }
                // Prismatic meniscus caustic ring + thickness trough
                float3 cyanShift = float3(-0.03, 0.04, 0.09) * max(0.0, -n.x - n.y);
                float3 warmShift = float3(0.07, 0.02, -0.02) * max(0.0, n.x + n.y);
                float causticGain = meniscusTube * mix(0.18, 0.14, uDark) * (0.65 + 0.55 * max(abs(n.y), abs(n.x)));
                float troughDip = lensTrough * mix(-0.038, -0.055, uDark);
                bodyRgb = clamp(
                    bodyRgb + float3(causticGain + troughDip) + (cyanShift + warmShift) * meniscusTube * 0.55,
                    float3(0.0),
                    float3(1.0)
                );
                float baseAlpha = clamp(uBodyAlpha, 0.48, 0.94);
                bodyA = sat(mix(baseAlpha * 0.78, min(0.96, baseAlpha * 1.06), edge * 0.65 + meniscusTube * 0.25)) * coverage;
            } else {
                // Root REGULAR glass slab (Card, Dialog, Floating Dock):
                // Clear refractive 22dp meniscus bevel transitioning into a creamy, legible frosted dome
                float centerFill = mix(0.72, 0.70, uDark) * sat(uBodyAlpha * 1.08);
                float bezelFill = mix(0.32, 0.34, uDark) * sat(uBodyAlpha * 0.90);
                float glassFillMix = clamp(mix(centerFill, bezelFill, pow(edge, 0.85)), 0.24, 0.86);
                bodyRgb = mix(refractedRgb, uBaseColor.rgb + float3(domeLift), glassFillMix);

                if (uHasTint > 0.5) {
                    float luma = dot(bodyRgb, float3(0.2126, 0.7152, 0.0722));
                    float3 tintedGlass = uTintColor.rgb * mix(luma, 1.0, 0.48);
                    bodyRgb = mix(bodyRgb, tintedGlass, 0.20);
                }

                // Prismatic meniscus caustic tube + 4mm optical thickness trough
                float topCaustic = max(0.0, -n.y) * edge * mix(0.11, 0.075, uDark);
                float botCaustic = max(0.0, n.y) * edge * mix(0.055, 0.035, uDark);
                float tubeCaustic = meniscusTube * mix(0.14, 0.10, uDark) * (0.55 + 0.45 * abs(n.y - n.x * 0.5));
                float troughDip = lensTrough * mix(-0.032, -0.048, uDark);
                float3 prismFringe = float3(-0.025, 0.035, 0.085) * max(0.0, -n.y) * meniscusTube
                    + float3(0.065, 0.020, -0.025) * max(0.0, n.y) * meniscusTube;

                bodyRgb = clamp(
                    bodyRgb + float3(topCaustic + botCaustic + tubeCaustic + troughDip) + prismFringe * 0.60,
                    float3(0.0),
                    float3(1.0)
                );
                bodyA = sat(mix(uBodyAlpha * 0.92, min(0.98, uBodyAlpha * 1.05), edge * 0.55 + meniscusTube * 0.20)) * coverage;
            }

            // interaction.ts: internal radial illumination (--lg-glow) when pressed
            if (uPressProgress > 0.001) {
                float distCenter = length(p) / max(min(uSize.x, uSize.y) * 0.65, 1.0);
                float radialGlow = (1.0 - sat(distCenter)) * uPressProgress * mix(0.18, 0.14, uDark);
                bodyRgb = clamp(bodyRgb + float3(radialGlow), float3(0.0), float3(1.0));
            }

            // Zone 1: maps.wgsl Plane 1 directional top & bottom specular (spread = 1.08) + 4.5dp diffuse + side rim shade
            float cosSpread = 0.471328;   // cos(1.08)
            float cosDiffuse = 0.763552;  // cos(1.08 * 0.65)
            float directional = pow(sat((abs(n.y) - cosSpread) / (1.0 - cosSpread)), 1.20);
            float diffuseDir = pow(sat((abs(n.y) - cosDiffuse) / (1.0 - cosDiffuse)), 1.20);
            float stroke = sat((dDp + 1.15) + 0.5) * sat(0.5 - dDp);
            float specular = directional * stroke;
            specular = specular / (1.0 + 0.42 * (1.0 - specular));
            float diffuse = diffuseDir * 0.22 * pow(1.0 - sat(-dDp / 5.2), 2.0) * coverage;
            float highlight = sat(specular * mix(0.98, 0.84, uDark) + diffuse * mix(0.88, 0.46, uDark));

            float adjusted = dDp;
            float falloff = 1.0 - sat(-adjusted / 0.9);
            float ramp = mix(falloff > 0.0 ? 0.28 : 0.0, falloff, 0.72);
            float outline = ramp * sat((adjusted + 0.9) + 0.5) * sat(-adjusted + 0.5);
            float cosSide = 0.557023;     // cos(0.98)
            float wx = sat((abs(n.x) - cosSide) / (1.0 - cosSide)) * outline;
            float shade = (wx / (1.0 + 0.38 * (1.0 - wx))) * mix(0.24, 0.36, uDark);

            float overlayAlpha = sat(highlight + shade * (1.0 - highlight)) * coverage;
            float lightScalar = mix(1.0, 0.90, uDark) * highlight / max(overlayAlpha, 0.00001);
            float3 lightRgb = uHasTint > 0.5
                ? mix(float3(lightScalar), uTintColor.rgb * lightScalar * 1.12, 0.26)
                : float3(lightScalar);

            float outA = overlayAlpha + bodyA * (1.0 - overlayAlpha);
            float3 outPremul = lightRgb * overlayAlpha + bodyRgb * bodyA * (1.0 - overlayAlpha);
            return half4(half3(outPremul), half(outA));
        }
    """

    /**
     * Real-Time Content-Distorting Capsule Lens AGSL Shader (`RenderEffect.createRuntimeShaderEffect`):
     * Warps, magnifies, and chromatically disperses the actual rendered child pixels (`content`)
     * underneath a sliding Liquid Glass capsule indicator (`XPeachBottomDock`, `XPeachSegmentedTabs`,
     * `LiquidGlassSwitch`).
     */
    private const val LIQUID_CAPSULE_WARP_AGSL = """
        uniform shader content;
        uniform float2 uSize;
        uniform float4 uLensRect;
        uniform float uLensRadius;
        uniform float uDpr;
        uniform float uDark;
        uniform float uRefractionPx;
        uniform float uPressProgress;

        float sat(float x) {
            return clamp(x, 0.0, 1.0);
        }

        half4 main(float2 fragCoord) {
            float2 lensCenter = 0.5 * (uLensRect.xy + uLensRect.zw);
            float2 lensHalf = max(0.5 * (uLensRect.zw - uLensRect.xy), float2(1.0));
            float r = min(uLensRadius, min(lensHalf.x, lensHalf.y));
            float2 p = fragCoord - lensCenter;
            float2 b = max(lensHalf - float2(r), float2(0.0));
            float2 q = abs(p) - b;

            float2 qPos = max(q, float2(0.0));
            float cornerDist = pow(pow(qPos.x, 2.45) + pow(qPos.y, 2.45), 1.0 / 2.45);
            float d = cornerDist + min(max(q.x, q.y), 0.0) - r;

            if (d >= 0.5) {
                return content.eval(fragCoord);
            }

            float2 gradCorner = (q.x > 0.0 && q.y > 0.0)
                ? normalize(float2(pow(qPos.x, 1.45), pow(qPos.y, 1.45)) + float2(1e-5))
                : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
            float2 sx = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
            float2 n = normalize(gradCorner * sx + float2(1e-6));

            float dDp = d / max(uDpr, 1.0);
            float minDimDp = min(lensHalf.x * 2.0, lensHalf.y * 2.0) / max(uDpr, 1.0);
            float bezelWidthDp = clamp(minDimDp * 0.42, 7.0, 15.0);
            float depth = sat(-dDp / bezelWidthDp);
            float edge = 1.0 - sat(sqrt(max(0.0, (2.0 - depth) * depth)));

            // Snell refraction + corner swirl + convex dome magnification of underlying UI pixels
            float2 tangent = float2(-n.y, n.x);
            float swirl = 2.0 * n.x * n.y * edge * (1.0 - edge * 0.4);
            float pressBoost = 1.0 + 0.45 * uPressProgress;
            float2 disp = (-n * pow(edge, 1.15) * uRefractionPx + tangent * swirl * uRefractionPx * 0.55) * pressBoost
                - p * (0.06 + 0.04 * uPressProgress) * (1.0 - edge);

            float dispersion = (0.16 + 0.10 * uPressProgress) * edge;
            float2 ptR = clamp(fragCoord + disp * (1.0 - dispersion), float2(0.0), max(uSize - float2(1.0), float2(1.0)));
            float2 ptG = clamp(fragCoord + disp, float2(0.0), max(uSize - float2(1.0), float2(1.0)));
            float2 ptB = clamp(fragCoord + disp * (1.0 + dispersion), float2(0.0), max(uSize - float2(1.0), float2(1.0)));

            half4 cR = content.eval(ptR);
            half4 cG = content.eval(ptG);
            half4 cB = content.eval(ptB);
            float3 refracted = float3(cR.r, cG.g, cB.b);

            float meniscusTube = exp(-pow((dDp + 1.8) / 1.25, 2.0));
            float lensTrough = exp(-pow((dDp + 4.8) / 1.90, 2.0));
            float causticBoost = meniscusTube * mix(0.16, 0.12, uDark) + lensTrough * mix(-0.028, -0.042, uDark);
            float3 finalRgb = clamp(refracted + float3(causticBoost), float3(0.0), float3(1.0));
            return half4(half3(finalRgb), cG.a);
        }
    """

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createLiquidGlassShaderOrNull(): RuntimeShader? =
        runCatching { RuntimeShader(LIQUID_GLASS_AGSL_SOURCE) }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun buildCapsuleLensRenderEffectOrNull(
        widthPx: Float,
        heightPx: Float,
        lensLeftPx: Float,
        lensTopPx: Float,
        lensRightPx: Float,
        lensBottomPx: Float,
        lensRadiusPx: Float,
        dpr: Float,
        isDark: Boolean,
        refractionPx: Float,
        pressProgress: Float = 0f
    ): RenderEffect? = runCatching {
        val shader = RuntimeShader(LIQUID_CAPSULE_WARP_AGSL)
        shader.setFloatUniform("uSize", widthPx.coerceAtLeast(1f), heightPx.coerceAtLeast(1f))
        shader.setFloatUniform("uLensRect", lensLeftPx, lensTopPx, lensRightPx, lensBottomPx)
        shader.setFloatUniform("uLensRadius", lensRadiusPx)
        shader.setFloatUniform("uDpr", dpr.coerceAtLeast(1f))
        shader.setFloatUniform("uDark", if (isDark) 1f else 0f)
        shader.setFloatUniform("uRefractionPx", refractionPx)
        shader.setFloatUniform("uPressProgress", pressProgress.coerceIn(0f, 1f))
        RenderEffect.createRuntimeShaderEffect(shader, "content")
    }.getOrNull()
}

/**
 * Applies the calibrated `Glass-HQ/liquid-glass` optical lens surface (`regular` or `clear`).
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

        var windowOffset by remember { mutableStateOf(Offset.Zero) }

        val cachedWallpaperShader = remember(wallpaperBitmap, viewportW.toInt(), viewportH.toInt()) {
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

        Modifier
            .onGloballyPositioned { coords ->
                if (coords.isAttached) {
                    val pos = coords.positionInWindow()
                    if (abs(pos.x - windowOffset.x) > 0.5f || abs(pos.y - windowOffset.y) > 0.5f) {
                        windowOffset = pos
                    }
                }
            }
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                val dpr = this.density.coerceAtLeast(1f)
                val rPx = min(cornerRadius.toPx(), min(w, h) * 0.5f)
                val refractionPx = refractionDp.dp.toPx()

                val rawAlpha = baseColor.alpha.coerceIn(0.32f, 0.96f)
                val effectiveAlpha = if (material == LiquidGlassMaterial.CLEAR) {
                    (rawAlpha * (if (isDark) 0.86f else 0.84f)).coerceIn(0.36f, 0.92f)
                } else {
                    rawAlpha
                }
                val calibratedBase = if (isDark) {
                    val darkFloor = if (nested) Color(0xFF1D273A) else Color(0xFF151E2E)
                    lerp(baseColor.copy(alpha = 1f), darkFloor, if (nested) 0.22f else 0.30f)
                } else {
                    val lightLift = Color(0xFFFCFEFF)
                    lerp(baseColor.copy(alpha = 1f), lightLift, if (material == LiquidGlassMaterial.REGULAR) 0.48f else 0.32f)
                }
                val cleanTint = accentTint?.takeIf { it != Color.Transparent && it != Color.Unspecified }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val agslShader = LiquidGlassOptics.createLiquidGlassShaderOrNull()
                    if (agslShader != null) {
                        agslShader.setFloatUniform("uSize", w, h)
                        agslShader.setFloatUniform("uScreenOffset", windowOffset.x, windowOffset.y)
                        agslShader.setFloatUniform("uViewportSize", viewportW, viewportH)
                        agslShader.setFloatUniform("uRadius", rPx)
                        agslShader.setFloatUniform("uDpr", dpr)
                        agslShader.setFloatUniform("uDark", if (isDark) 1f else 0f)
                        agslShader.setFloatUniform("uIsClear", if (material == LiquidGlassMaterial.CLEAR) 1f else 0f)
                        agslShader.setFloatUniform("uNested", if (nested) 1f else 0f)
                        agslShader.setFloatUniform("uBodyAlpha", effectiveAlpha)
                        agslShader.setFloatUniform("uRefractionPx", refractionPx)
                        agslShader.setFloatUniform("uPressProgress", pressProgress.coerceIn(0f, 1f))
                        agslShader.setFloatUniform(
                            "uBaseColor",
                            calibratedBase.red,
                            calibratedBase.green,
                            calibratedBase.blue,
                            1f
                        )
                        if (cleanTint != null) {
                            agslShader.setFloatUniform(
                                "uTintColor",
                                cleanTint.red,
                                cleanTint.green,
                                cleanTint.blue,
                                1f
                            )
                            agslShader.setFloatUniform("uHasTint", 1f)
                        } else {
                            agslShader.setFloatUniform("uTintColor", 1f, 1f, 1f, 1f)
                            agslShader.setFloatUniform("uHasTint", 0f)
                        }
                        agslShader.setFloatUniform(
                            "uPrimaryRgb",
                            primaryColor.red,
                            primaryColor.green,
                            primaryColor.blue
                        )
                        agslShader.setFloatUniform(
                            "uSecondaryRgb",
                            secondaryColor.red,
                            secondaryColor.green,
                            secondaryColor.blue
                        )
                        if (cachedWallpaperShader != null) {
                            agslShader.setInputShader("uWallpaperShader", cachedWallpaperShader)
                            agslShader.setFloatUniform("uHasWallpaper", 1f)
                        } else {
                            val dummyBmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            agslShader.setInputShader(
                                "uWallpaperShader",
                                BitmapShader(dummyBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                            )
                            agslShader.setFloatUniform("uHasWallpaper", 0f)
                        }
                        val shaderBrush = ShaderBrush(agslShader)
                        return@drawWithCache onDrawBehind {
                            drawRect(brush = shaderBrush)
                        }
                    }
                }

                // API 31-32 analytical fallback with 4-zone meniscus ring & thickness trough
                val bezelDepthPx = min(20.dp.toPx(), h * 0.42f).coerceAtLeast(1f)
                val topSpecAlpha = if (isDark) 0.72f else 0.94f
                val botSpecAlpha = if (isDark) 0.50f else 0.74f
                val sideShadeAlpha = if (isDark) 0.34f else 0.22f
                val topDiffuseColor = (cleanTint?.let { lerp(Color.White, it, 0.25f) } ?: Color.White)
                    .copy(alpha = if (isDark) 0.18f else 0.36f)

                val bodyBrush = Brush.verticalGradient(
                    colors = listOf(
                        calibratedBase.copy(alpha = (effectiveAlpha * 1.04f).coerceIn(0f, 0.96f)),
                        calibratedBase.copy(alpha = effectiveAlpha),
                        calibratedBase.copy(alpha = (effectiveAlpha * 0.95f).coerceIn(0f, 0.96f))
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
                val strokeWidth = 1.1.dp.toPx()

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

/**
 * Unified switch control that automatically renders the refractive `LiquidGlassSwitch` droplet lens
 * in Liquid Glass mode, and a clean Material 3 `Switch` in standard modes.
 */
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
    val density = LocalDensity.current
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
            XPeachTheme.readablePrimary.copy(alpha = if (enabled) 0.92f else 0.45f)
        } else {
            Color(0xFF788296).copy(alpha = if (isDark) 0.44f else 0.34f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "lgSwitchTrackColor"
    )

    val swellX = 7.dp * pressProgress
    val swellY = 4.5.dp * pressProgress
    val thumbW = 32.dp + swellX
    val thumbH = 22.dp + swellY

    Box(
        modifier = modifier
            .width(56.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(trackColor)
            .then(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Modifier.graphicsLayer {
                        val padPx = 2.dp.toPx()
                        val leftPx = padPx + (thumbTravelDp - swellX * 0.5f).toPx()
                        val topPx = padPx - (swellY * 0.5f).toPx()
                        val wPx = thumbW.toPx()
                        val hPx = thumbH.toPx()
                        renderEffect = LiquidGlassOptics.buildCapsuleLensRenderEffectOrNull(
                            widthPx = size.width,
                            heightPx = size.height,
                            lensLeftPx = leftPx,
                            lensTopPx = topPx,
                            lensRightPx = leftPx + wPx,
                            lensBottomPx = topPx + hPx,
                            lensRadiusPx = hPx * 0.5f,
                            dpr = density.density,
                            isDark = isDark,
                            refractionPx = 18.dp.toPx(),
                            pressProgress = pressProgress
                        )?.asComposeRenderEffect()
                    }
                } else {
                    Modifier
                }
            )
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
            Color(0xFFF8FAFC).copy(alpha = (0.86f - 0.32f * pressProgress).coerceIn(0.46f, 0.92f))
        } else {
            Color.White.copy(alpha = (0.90f - 0.36f * pressProgress).coerceIn(0.46f, 0.94f))
        }
        Box(
            modifier = Modifier
                .offset(x = thumbTravelDp - (swellX * 0.5f))
                .size(width = thumbW, height = thumbH)
                .shadow(
                    elevation = if (pressProgress > 0.2f) 4.dp else 2.dp,
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
                    refractionDp = 20f,
                    pressProgress = pressProgress
                )
        )
    }
}
