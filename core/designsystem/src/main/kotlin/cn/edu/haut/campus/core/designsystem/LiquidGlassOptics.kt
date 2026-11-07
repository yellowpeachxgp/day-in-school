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
 * Calibrated optical & interaction engine based on Glass-HQ/liquid-glass
 * (https://github.com/Glass-HQ/liquid-glass) and Apple iOS 26 / macOS Tahoe Liquid Glass:
 * - Pre-frosted dual-sigma backdrop synthesis so both the screen backdrop and every root
 *   `REGULAR` glass surface share an identical, silky-smooth optical field.
 * - Hierarchical `REGULAR` (root cards, dialogs, bottom dock) vs `CLEAR` (nested wells,
 *   pills, segmented thumb, switch thumb) composition so nested controls never punch raw
 *   background holes through parent cards.
 * - Adaptive 18dp circular-arc lens bezel refraction (`optics.ts`), positive-only sky/floor
 *   caustics, and `maps.wgsl` Plane 1 directional top/bottom specular rim + side micro-shade.
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
    const val REGULAR_LIGHT_CHROMA = 1.06f
    const val REGULAR_DARK_CHROMA = 1.04f
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
     * Synthesizes a pre-frosted, eye-friendly `240 x 480` backdrop bitmap shared between the
     * screen background and `uWallpaperShader` inside `liquidGlassEffect` so that:
     * 1. Inside vs. outside of cards share the exact same blurred color field (zero mismatch).
     * 2. No harsh bottom color blocks (`底部色块`) or green `tertiaryColor` stains exist.
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
        val cacheKey = "${wallpaperKey ?: "ambient"}|$isDark|${primaryColor.toArgb()}|${secondaryColor.toArgb()}|$quantAlpha|$quantBlur"
        frostedBackdropCache[cacheKey]?.let { return it }

        val w = 240
        val h = 480
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val baseBg = if (isDark) Color(0xFF0A0F18) else Color(0xFFF3F7FC)
        canvas.drawColor(baseBg.toArgb())

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val safeAlpha = backdropAlpha.coerceIn(0.15f, 1.0f)

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

            // Eye-comfort protective veil over custom photo wallpaper
            val veilAlpha = ((1f - safeAlpha) * 0.42f + 0.16f).coerceIn(0.16f, 0.58f)
            canvas.drawColor(baseBg.copy(alpha = veilAlpha).toArgb())
        } else {
            // Calm, top/mid-biased analogous blooms (zero harsh bottom orbs, zero green contamination)
            val c1 = if (isDark) primaryColor else lerp(primaryColor, Color.White, 0.28f)
            val c2 = if (isDark) secondaryColor else lerp(secondaryColor, Color.White, 0.32f)
            val cMid = lerp(c1, c2, 0.5f)

            val a1 = ((if (isDark) 0.24f else 0.18f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            val a2 = ((if (isDark) 0.18f else 0.14f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            val aMid = ((if (isDark) 0.12f else 0.09f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)

            paint.shader = RadialGradient(
                w * 0.18f,
                h * 0.12f,
                w * 1.05f,
                intArrayOf(
                    android.graphics.Color.argb(a1, (c1.red * 255).toInt(), (c1.green * 255).toInt(), (c1.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            paint.shader = RadialGradient(
                w * 0.85f,
                h * 0.24f,
                w * 0.98f,
                intArrayOf(
                    android.graphics.Color.argb(a2, (c2.red * 255).toInt(), (c2.green * 255).toInt(), (c2.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            paint.shader = RadialGradient(
                w * 0.48f,
                h * 0.48f,
                w * 1.15f,
                intArrayOf(
                    android.graphics.Color.argb(aMid, (cMid.red * 255).toInt(), (cMid.green * 255).toInt(), (cMid.blue * 255).toInt()),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)

            // Soft diagonal silk wave in the upper-middle zone for subtle bezel refraction gleam
            val waveAlpha = ((if (isDark) 0.10f else 0.14f) * safeAlpha * 255f).roundToInt().coerceIn(0, 255)
            paint.shader = LinearGradient(
                0f,
                h * 0.16f,
                w.toFloat(),
                h * 0.58f,
                intArrayOf(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.argb(waveAlpha, (c1.red * 255).toInt(), (c1.green * 255).toInt(), (c1.blue * 255).toInt()),
                    android.graphics.Color.argb((waveAlpha * 0.7f).toInt(), 255, 255, 255),
                    android.graphics.Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.38f, 0.56f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
        }

        // Apply fast 2-pass separable box blur on the 240x480 buffer to guarantee silky-smooth frosting
        val blurRadius = (blurDp * 0.65f).roundToInt().coerceIn(2, 18)
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

        // Horizontal pass
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

        // Vertical pass
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
     * Calibrated AGSL shader:
     * - Root surfaces (`uNested < 0.5`): sample the pre-frosted `uWallpaperShader` at displaced
     *   screen coordinates `screenPos - n * edge * uRefractionPx`, blended with a creamy frosted
     *   milk-glass / slate fill (`glassFillMix = 0.68..0.74`) so text is 100% effortless to read.
     * - Nested controls (`uNested > 0.5`): never punch raw background holes through parent cards;
     *   instead render a crystal-clear translucent highlight lens with `maps.wgsl` Plane 1
     *   directional top/bottom specular rim and side micro-shade.
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

        float3 evalCalmAmbientFallback(float2 screenPt) {
            float2 uv = screenPt / max(uViewportSize, float2(1.0));
            float3 bgBase = uDark > 0.5 ? float3(0.039, 0.059, 0.094) : float3(0.953, 0.969, 0.988);
            float d1 = length((uv - float2(0.18, 0.12)) * float2(1.0, 1.25));
            float d2 = length((uv - float2(0.85, 0.24)) * float2(1.0, 1.25));
            float w1 = exp(-d1 * d1 * 2.2);
            float w2 = exp(-d2 * d2 * 2.4);
            float intensity = uDark > 0.5 ? 0.20 : 0.14;
            float3 col = mix(bgBase, uPrimaryRgb, sat(w1 * intensity));
            col = mix(col, uSecondaryRgb, sat(w2 * intensity * 0.85));
            return clamp(col, float3(0.0), float3(1.0));
        }

        float3 sampleSceneAt(float2 screenPt) {
            if (uHasWallpaper > 0.5) {
                float2 clampedPt = clamp(screenPt, float2(1.0), max(uViewportSize - float2(1.0), float2(1.0)));
                return uWallpaperShader.eval(clampedPt).rgb;
            }
            return evalCalmAmbientFallback(screenPt);
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

            // Adaptive circular-arc lens bezel profile (scales down on compact pills/buttons)
            float dDp = d / max(uDpr, 1.0);
            float minDimDp = min(uSize.x, uSize.y) / max(uDpr, 1.0);
            float bezelWidthDp = clamp(minDimDp * 0.36, 6.0, uNested > 0.5 ? 11.0 : 18.0);
            float depth = sat(-dDp / bezelWidthDp);
            float edge = 1.0 - sat(sqrt(max(0.0, (2.0 - depth) * depth)));

            float3 bodyRgb;
            float bodyA;

            if (uNested > 0.5) {
                // Nested control lens (Well, Pill, Segmented Thumb, Dock Pill):
                // Alpha-blends a crystal-clear translucent lens over the parent card without punching a hole!
                bodyRgb = uBaseColor.rgb;
                if (uHasTint > 0.5) {
                    bodyRgb = mix(bodyRgb, uTintColor.rgb, 0.18);
                }
                float topCatch = max(0.0, -n.y) * edge * mix(0.08, 0.05, uDark);
                float botCatch = max(0.0, n.y) * edge * mix(0.04, 0.025, uDark);
                bodyRgb = clamp(bodyRgb + float3(topCatch + botCatch), float3(0.0), float3(1.0));
                bodyA = sat(mix(uBodyAlpha * 0.52, min(0.88, uBodyAlpha * 0.78), edge * 0.6)) * coverage;
            } else {
                // Root REGULAR glass surface (Card, Dialog, Floating Dock):
                // Refracts the pre-frosted backdrop at the 18dp curved bezel and blends with creamy frosted body
                float2 disp = -n * edge * uRefractionPx * (1.0 + 0.16 * uPressProgress);
                float2 screenPos = uScreenOffset + fragCoord;
                float dispersion = 0.025 * edge;
                float rChan = sampleSceneAt(screenPos + disp * (1.0 - dispersion)).r;
                float gChan = sampleSceneAt(screenPos + disp).g;
                float bChan = sampleSceneAt(screenPos + disp * (1.0 + dispersion)).b;
                float3 refractedRgb = float3(rChan, gChan, bChan);

                // Creamy milk-glass / slate fill ratio (more refractive at the 18dp bezel, calmer in the text center)
                float centerFill = mix(0.76, 0.74, uDark) * sat(uBodyAlpha * 1.12);
                float bezelFill = mix(0.58, 0.56, uDark) * sat(uBodyAlpha * 1.05);
                float glassFillMix = clamp(mix(centerFill, bezelFill, edge), 0.45, 0.92);
                bodyRgb = mix(refractedRgb, uBaseColor.rgb, glassFillMix);

                if (uHasTint > 0.5) {
                    float luma = dot(bodyRgb, float3(0.2126, 0.7152, 0.0722));
                    float3 tintedGlass = uTintColor.rgb * mix(luma, 1.0, 0.48);
                    bodyRgb = mix(bodyRgb, tintedGlass, 0.18);
                }

                // Strictly positive top/bottom bezel caustics + ultra-subtle micro-crease (never darkens bottom edge)
                float topCaustic = max(0.0, -n.y) * edge * mix(0.08, 0.05, uDark);
                float botCaustic = max(0.0, n.y) * edge * mix(0.035, 0.02, uDark);
                float innerCrease = exp(-pow((dDp + 1.5) / 0.85, 2.0)) * mix(-0.012, -0.020, uDark);
                bodyRgb = clamp(bodyRgb + float3(topCaustic + botCaustic + innerCrease), float3(0.0), float3(1.0));
                bodyA = sat(mix(uBodyAlpha * 0.92, min(0.96, uBodyAlpha * 1.02), edge * 0.4)) * coverage;
            }

            // interaction.ts: internal radial illumination (--lg-glow) when pressed
            if (uPressProgress > 0.001) {
                float distCenter = length(p) / max(min(uSize.x, uSize.y) * 0.65, 1.0);
                float radialGlow = (1.0 - sat(distCenter)) * uPressProgress * mix(0.14, 0.10, uDark);
                bodyRgb = clamp(bodyRgb + float3(radialGlow), float3(0.0), float3(1.0));
            }

            // maps.wgsl Plane 1: directional top & bottom specular (spread = 1.08) + 4.5dp diffuse + side rim shade
            float cosSpread = 0.471328;   // cos(1.08)
            float cosDiffuse = 0.763552;  // cos(1.08 * 0.65)
            float directional = pow(sat((abs(n.y) - cosSpread) / (1.0 - cosSpread)), 1.25);
            float diffuseDir = pow(sat((abs(n.y) - cosDiffuse) / (1.0 - cosDiffuse)), 1.25);
            float stroke = sat((dDp + 0.95) + 0.5) * sat(0.5 - dDp);
            float specular = directional * stroke;
            specular = specular / (1.0 + 0.5 * (1.0 - specular));
            float diffuse = diffuseDir * 0.15 * pow(1.0 - sat(-dDp / 4.5), 2.0) * coverage;
            float highlight = sat(specular * mix(0.90, 0.74, uDark) + diffuse * mix(0.80, 0.38, uDark));

            float adjusted = dDp;
            float falloff = 1.0 - sat(-adjusted / 0.8);
            float ramp = mix(falloff > 0.0 ? 0.25 : 0.0, falloff, 0.75);
            float outline = ramp * sat((adjusted + 0.8) + 0.5) * sat(-adjusted + 0.5);
            float cosSide = 0.557023;     // cos(0.98)
            float wx = sat((abs(n.x) - cosSide) / (1.0 - cosSide)) * outline;
            float shade = (wx / (1.0 + 0.4 * (1.0 - wx))) * mix(0.18, 0.28, uDark);

            float overlayAlpha = sat(highlight + shade * (1.0 - highlight)) * coverage;
            float lightScalar = mix(1.0, 0.86, uDark) * highlight / max(overlayAlpha, 0.00001);
            float3 lightRgb = uHasTint > 0.5
                ? mix(float3(lightScalar), uTintColor.rgb * lightScalar * 1.10, 0.22)
                : float3(lightScalar);

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
    refractionDp: Float = if (material == LiquidGlassMaterial.CLEAR) 16f else 24f,
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
                if (!nested && coords.isAttached) {
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

                val rawAlpha = baseColor.alpha.coerceIn(0.24f, 0.96f)
                val effectiveAlpha = if (material == LiquidGlassMaterial.CLEAR) {
                    (rawAlpha * (if (isDark) 0.76f else 0.72f)).coerceIn(0.20f, 0.88f)
                } else {
                    rawAlpha
                }
                val calibratedBase = if (isDark) {
                    val darkFloor = if (nested) Color(0xFF1D273A) else Color(0xFF151E2E)
                    lerp(baseColor.copy(alpha = 1f), darkFloor, if (nested) 0.25f else 0.32f)
                } else {
                    val lightLift = Color(0xFFFCFEFF)
                    lerp(baseColor.copy(alpha = 1f), lightLift, if (material == LiquidGlassMaterial.REGULAR) 0.52f else 0.35f)
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

                // API 31-32 analytical fallback
                val bezelDepthPx = min(18.dp.toPx(), h * 0.40f).coerceAtLeast(1f)
                val topSpecAlpha = if (isDark) 0.64f else 0.88f
                val botSpecAlpha = if (isDark) 0.42f else 0.64f
                val sideShadeAlpha = if (isDark) 0.28f else 0.16f
                val topDiffuseColor = (cleanTint?.let { lerp(Color.White, it, 0.22f) } ?: Color.White)
                    .copy(alpha = if (isDark) 0.12f else 0.28f)

                val bodyBrush = Brush.verticalGradient(
                    colors = listOf(
                        calibratedBase.copy(alpha = (effectiveAlpha * 1.02f).coerceIn(0f, 0.96f)),
                        calibratedBase.copy(alpha = effectiveAlpha),
                        calibratedBase.copy(alpha = (effectiveAlpha * 0.96f).coerceIn(0f, 0.96f))
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
        targetValue = if (checked) 18.dp else 0.dp,
        animationSpec = LiquidGlassSpringPreset.SWITCH.toSpringSpec(),
        label = "lgSwitchTravel"
    )
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed && enabled) 1f else 0f,
        animationSpec = tween(durationMillis = 190),
        label = "lgSwitchPressProgress"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) {
            XPeachTheme.readablePrimary.copy(alpha = if (enabled) 0.90f else 0.45f)
        } else {
            Color(0xFF8E8E93).copy(alpha = if (isDark) 0.42f else 0.32f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "lgSwitchTrackColor"
    )

    val swellX = (6.dp * pressProgress)
    val swellY = (4.dp * pressProgress)

    Box(
        modifier = modifier
            .width(54.dp)
            .height(24.dp)
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
        Box(
            modifier = Modifier
                .offset(x = thumbTravelDp - (swellX * 0.5f))
                .size(width = 32.dp + swellX, height = 20.dp + swellY)
                .then(
                    if (pressProgress <= 0.05f) {
                        Modifier.shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                    } else {
                        Modifier
                    }
                )
                .clip(CircleShape)
                .then(
                    if (pressProgress > 0.05f) {
                        Modifier.liquidGlassEffect(
                            enabled = true,
                            isDark = isDark,
                            cornerRadius = 999.dp,
                            baseColor = Color.White.copy(alpha = (1f - 0.52f * pressProgress).coerceIn(0.38f, 0.96f)),
                            material = LiquidGlassMaterial.CLEAR,
                            accentTint = null,
                            nested = true,
                            refractionDp = 16f,
                            pressProgress = pressProgress
                        )
                    } else {
                        Modifier.background(Color.White.copy(alpha = if (enabled) 0.98f else 0.65f))
                    }
                )
        )
    }
}
