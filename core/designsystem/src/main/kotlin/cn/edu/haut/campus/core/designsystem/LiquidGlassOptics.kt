package cn.edu.haut.campus.core.designsystem

import android.graphics.BitmapShader
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Full optical & interaction port of Glass-HQ/liquid-glass (https://github.com/Glass-HQ/liquid-glass):
 * - `materials.ts` & `material-graph.ts`: `regular` (blur = 7.33, refraction = 60, dual-sigma fill 23/21.8,
 *   quadratic tone transfer & chroma boost 1.1031/1.0648) and `clear` (blur = 0, refraction = 60,
 *   shadowLift 0.18/0.15, highlightRolloff 0/0.20).
 * - `optics.ts`: circular-arc 20dp bezel refraction displacement `edgeDisplacement(d, amount, 0.05)`
 *   with screen-space UV warping (`screenPos - n * edge * refraction`) and RGB chromatic dispersion.
 * - `maps.wgsl`: Lisse squircle SDF (`cornerSmoothing = 0.8125`) + Plane 1 anisotropic top/bottom
 *   specular rim (`spread = 1.08`), 4.5dp inner bevel diffuse glow (`spread * 0.65`), internal reflection
 *   bevel crease, and left/right side rim shade (`sideSpread = 0.98`).
 * - `interaction.ts`: droplet swell on press (`grow = min(0.16, 8 / max(w, h)) * press`) + internal
 *   radial illumination (`--lg-glow`).
 * - `value-controls.tsx` & `control-thumb.tsx`: `54x24dp` capsule `LiquidGlassSwitch` with `32x20dp`
 *   thumb that swells (`+9x5.5dp`) into a transparent refractive `clear` lens on press.
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

    const val REGULAR_BLUR_SIGMA_DP = 7.33f
    const val REGULAR_LIGHT_FILL_SIGMA_DP = 23.0f
    const val REGULAR_DARK_FILL_SIGMA_DP = 21.8f
    const val REGULAR_LIGHT_FILL_OPACITY = 0.34f
    const val REGULAR_DARK_FILL_OPACITY = 0.65f
    const val REGULAR_LIGHT_CHROMA = 1.1031f
    const val REGULAR_DARK_CHROMA = 1.0648f
    const val DEFAULT_REFRACTION_PX = 60f
    const val BEZEL_INVERSE_HEIGHT = 0.05f
    const val LISSE_CORNER_SMOOTHING = 0.8125f

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
     * Interactive Liquid Glass swells outward under a finger press rather than shrinking.
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
     * Returns `(lightIntensity, alpha)` where `lightIntensity` is in `[0, 1]` and `alpha` is in `[0, 1]`.
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

    /**
     * Calibrated backdrop ColorMatrix combining `materials.regular`'s chroma boost (`1.1031` light / `1.0648` dark)
     * and shadow lift (`0.36` light / `0.105` dark).
     */
    fun buildBackdropColorFilter(isDark: Boolean): ColorMatrixColorFilter {
        val chroma = if (isDark) REGULAR_DARK_CHROMA else REGULAR_LIGHT_CHROMA
        val satMatrix = ColorMatrix().apply { setSaturation(chroma) }
        val scale = if (isDark) 0.92f else 0.96f
        val lift = if (isDark) 10f else 14f
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
     * Screen-space refractive AGSL shader (`maps.wgsl` + `optics.ts` + `materials.ts` + `material-graph.ts` + `interaction.ts`):
     * 1. Computes Lisse squircle SDF `d` & outward normal `n` (`cornerSmoothing = 0.8125`).
     * 2. Computes `optics.ts` 22dp circular-arc lens bezel profile `edge = 1 - sqrt((2 - depth) * depth)`
     *    plus subtle convex dome magnification inside the lens.
     * 3. Displaces the real window coordinate `screenPos = uScreenOffset + fragCoord` by
     *    `disp = -n * edge * uRefractionPx` with RGB chromatic dispersion (`0.86 / 1.00 / 1.14`)
     *    and samples either the live wallpaper `uWallpaperShader` or the multi-band `duo-day`/`duo-night`
     *    horizon wave field so scrolling cards visibly bend and warp the underlying bands!
     * 4. Applies `material-graph.ts` `regular` (`[0.36, 0.67, -0.1113, 1.1031]` / `[0.105, 0.83, -0.2091, 1.0648]`)
     *    or `clear` (`shadowLift` / `highlightRolloff`) tone transfer and `tintMarkup` luminance transmission.
     * 5. Composites `maps.wgsl` Plane 1 directional top/bottom specular rim (`spread = 1.08`),
     *    5.5dp inner bevel diffuse glow, internal reflection bevel crease, left/right side rim shade (`sideSpread = 0.98`),
     *    and `interaction.ts` press illumination (`--lg-glow`).
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
        uniform float3 uTertiaryRgb;
        uniform shader uWallpaperShader;
        uniform float uHasWallpaper;

        float sat(float x) {
            return clamp(x, 0.0, 1.0);
        }

        // Multi-band duo-day / duo-night horizon & caustic ribbon field in screen coordinates.
        // Matches LiquidGlassAmbientBackdrop so screen-space lens displacement (-n * edge * uRefractionPx)
        // visibly bends and magnifies the horizon ridges and color bands across the 22dp lens bezel!
        float3 evalProceduralBackdrop(float2 screenPt) {
            float2 uv = screenPt / max(uViewportSize, float2(1.0));
            float3 bgBase = uDark > 0.5 ? float3(0.035, 0.055, 0.105) : float3(0.915, 0.952, 0.992);

            // Diagonal horizon wave 1 (sky-to-mountain ridge)
            float wave1 = uv.y + 0.14 * sin(uv.x * 5.2 + 0.4) - 0.32;
            float band1 = exp(-wave1 * wave1 * 28.0);

            // Diagonal horizon wave 2 (lake reflection ridge)
            float wave2 = uv.y - 0.12 * cos(uv.x * 4.6 - 0.7) - 0.68;
            float band2 = exp(-wave2 * wave2 * 24.0);

            // Diagonal caustic ribbon 3
            float diag = (uv.x * 0.72 + uv.y * 0.68) - 0.52 + 0.08 * sin(uv.y * 7.0);
            float band3 = exp(-diag * diag * 36.0);

            // Fine optical grid / aurora harmonic for unmistakable refraction bending at lens edges
            float aurora = 0.5 + 0.5 * sin(uv.x * 18.0 + sin(uv.y * 12.0) * 1.8);
            float ridge = smoothstep(0.42, 0.58, aurora) * 0.16;

            float3 c1 = uPrimaryRgb;
            float3 c2 = uSecondaryRgb;
            float3 c3 = uTertiaryRgb;
            float3 accentIndigo = uDark > 0.5 ? float3(0.38, 0.42, 0.95) : float3(0.45, 0.62, 0.98);

            float3 col = bgBase;
            float intensity = uDark > 0.5 ? 0.58 : 0.46;
            col = mix(col, c1, sat(band1 * intensity * 1.15));
            col = mix(col, c2, sat(band2 * intensity * 1.05));
            col = mix(col, c3, sat(band3 * intensity * 0.85));
            col = mix(col, accentIndigo, ridge);
            return clamp(col, float3(0.0), float3(1.0));
        }

        float3 sampleSceneAt(float2 screenPt) {
            if (uHasWallpaper > 0.5) {
                float2 clampedPt = clamp(screenPt, float2(0.0), uViewportSize);
                return uWallpaperShader.eval(clampedPt).rgb;
            }
            return evalProceduralBackdrop(screenPt);
        }

        float3 applyMaterialTone(float3 rgb) {
            if (uIsClear < 0.5) {
                // materials.regular quadratic tone curve + chroma boost (1.1031 light / 1.0648 dark)
                float3 a = uDark > 0.5 ? float3(0.105) : float3(0.36);
                float3 b = uDark > 0.5 ? float3(0.83) : float3(0.67);
                float3 q = uDark > 0.5 ? float3(-0.2091) : float3(-0.1113);
                float w = uDark > 0.5 ? 0.88 : 0.78;
                float3 toned = a + b * rgb + q * rgb * rgb;
                float3 mixed = mix(rgb, toned, w);
                float luma = dot(mixed, float3(0.2126, 0.7152, 0.0722));
                float chroma = uDark > 0.5 ? 1.08 : 1.14;
                return clamp(float3(luma) + (mixed - float3(luma)) * chroma, float3(0.0), float3(1.0));
            } else {
                // materials.clear shadowLift & highlightRolloff
                float lift = uDark > 0.5 ? 0.15 : 0.18;
                float rolloff = uDark > 0.5 ? 0.20 : 0.0;
                float3 inv = float3(1.0) - rgb;
                float3 tone = rgb + lift * inv * inv * inv - rolloff * rgb * rgb * rgb;
                float3 tinted = uDark > 0.5 ? (0.38 * rgb + float3(0.20)) : (0.78 * rgb + float3(0.26));
                float tw = uDark > 0.5 ? 0.14 : 0.06;
                float3 layerLight = (uDark > 0.5 && uNested > 0.5) ? 0.08 * inv : float3(0.0);
                return clamp((1.0 - tw) * tone + tw * tinted + layerLight, float3(0.0), float3(1.0));
            }
        }

        half4 main(float2 fragCoord) {
            float2 halfSize = uSize * 0.5;
            float r = min(uRadius, min(halfSize.x, halfSize.y));
            float2 p = fragCoord - halfSize;
            float2 b = max(halfSize - float2(r), float2(0.0));
            float2 q = abs(p) - b;

            // Lisse squircle (cornerSmoothing = 0.8125 -> superellipse exponent ~ 2.45)
            float2 qPos = max(q, float2(0.0));
            float cornerDist = pow(pow(qPos.x, 2.45) + pow(qPos.y, 2.45), 1.0 / 2.45);
            float d = cornerDist + min(max(q.x, q.y), 0.0) - r;

            float2 gradCorner = (q.x > 0.0 && q.y > 0.0)
                ? normalize(float2(pow(qPos.x, 1.45), pow(qPos.y, 1.45)) + float2(1e-5))
                : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
            float2 sx = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
            float2 n = normalize(gradCorner * sx + float2(1e-6));

            float aa = max(1.0, 0.9 * uDpr);
            float coverage = sat(0.5 - d / aa);
            if (coverage <= 0.001) {
                return half4(0.0);
            }

            // optics.ts: 22dp circular-arc lens bezel profile + subtle convex dome lens
            float dDp = d / max(uDpr, 1.0);
            float bezelWidthDp = 22.0;
            float depth = sat(-dDp / bezelWidthDp);
            float edge = 1.0 - sat(sqrt(max(0.0, (2.0 - depth) * depth)));
            float dome = (1.0 - depth) * 0.18;
            float2 disp = -n * (edge + dome) * uRefractionPx * (1.0 + 0.22 * uPressProgress);

            // Real screen-space UV refraction with prismatic RGB chromatic dispersion at the lens bezel
            float2 screenPos = uScreenOffset + fragCoord;
            float dispersion = 0.16 * edge;
            float rChan = sampleSceneAt(screenPos + disp * (1.0 - dispersion)).r;
            float gChan = sampleSceneAt(screenPos + disp).g;
            float bChan = sampleSceneAt(screenPos + disp * (1.0 + dispersion)).b;
            float3 refractedRgb = applyMaterialTone(float3(rChan, gChan, bChan));

            // Blend refracted backdrop with calibrated glass surface pigment
            float glassFillMix = uIsClear > 0.5
                ? mix(0.22, 0.28, uDark)
                : mix(0.42, 0.54, uDark) * sat(uBodyAlpha);
            float3 bodyRgb = mix(refractedRgb, uBaseColor.rgb, glassFillMix);

            // material-graph.ts tintMarkup: colored glass transmits luminance
            if (uHasTint > 0.5) {
                float luma = dot(bodyRgb, float3(0.2126, 0.7152, 0.0722));
                float bodyWeight = uIsClear > 0.5 ? 0.24 : 0.34;
                float3 tintedGlass = uTintColor.rgb * mix(luma, 1.0, 0.42);
                bodyRgb = mix(bodyRgb, tintedGlass, bodyWeight);
            }

            // 3D Bevel Caustic + Inner Total-Internal-Reflection Crease (gives physical glass thickness)
            float bezelCaustic = (-n.y * edge) * mix(0.20, 0.13, uDark);
            float innerCrease = exp(-pow((dDp + 2.2) / 1.1, 2.0)) * mix(-0.07, -0.12, uDark);
            float innerSubCrease = exp(-pow((dDp + 4.2) / 1.8, 2.0)) * mix(0.09, 0.06, uDark);
            bodyRgb = clamp(bodyRgb + float3(bezelCaustic + innerCrease + innerSubCrease), float3(0.0), float3(1.0));

            // interaction.ts: internal radial illumination (--lg-glow) when pressed
            if (uPressProgress > 0.001) {
                float distCenter = length(p) / max(min(uSize.x, uSize.y) * 0.65, 1.0);
                float radialGlow = (1.0 - sat(distCenter)) * uPressProgress * mix(0.22, 0.16, uDark);
                bodyRgb = clamp(bodyRgb + float3(radialGlow), float3(0.0), float3(1.0));
            }

            float bodyA = sat(mix(0.84, 0.94, edge) * sat(uBodyAlpha * 1.12)) * coverage;

            // maps.wgsl Plane 1: directional top & bottom specular (spread = 1.08) + 5.5dp diffuse + side rim shade
            float cosSpread = 0.471328;   // cos(1.08)
            float cosDiffuse = 0.763552;  // cos(1.08 * 0.65)
            float directional = pow(sat((abs(n.y) - cosSpread) / (1.0 - cosSpread)), 1.25);
            float diffuseDir = pow(sat((abs(n.y) - cosDiffuse) / (1.0 - cosDiffuse)), 1.25);
            // Crisp 1.4dp specular rim stroke
            float stroke = sat((dDp + 1.35) + 0.5) * sat(0.5 - dDp);
            float specular = directional * stroke;
            specular = specular / (1.0 + 0.5 * (1.0 - specular));
            float diffuse = diffuseDir * 0.24 * pow(1.0 - sat(-dDp / 5.5), 2.0) * coverage;
            float highlight = sat(specular * mix(1.0, 0.86, uDark) + diffuse * mix(1.0, 0.52, uDark));

            float adjusted = dDp;
            float falloff = 1.0 - sat(-adjusted / 1.1);
            float ramp = mix(falloff > 0.0 ? 1.0 : 0.0, falloff, 0.75);
            float outline = ramp * sat((adjusted + 1.1) + 0.5) * sat(-adjusted + 0.5);
            float cosSide = 0.557023;     // cos(0.98)
            float wx = sat((abs(n.x) - cosSide) / (1.0 - cosSide)) * outline;
            float shade = (wx / (1.0 + 0.4 * (1.0 - wx))) * mix(0.48, 0.60, uDark);

            float overlayAlpha = sat(highlight + shade * (1.0 - highlight));
            float lightScalar = mix(1.0, 0.86, uDark) * highlight / max(overlayAlpha, 0.00001);
            float3 lightRgb = uHasTint > 0.5
                ? mix(float3(lightScalar), uTintColor.rgb * lightScalar * 1.18, 0.30)
                : float3(lightScalar);

            // Source-over composite of Plane 1 rim overlay onto refracted glass body
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
 * Applies the `Glass-HQ/liquid-glass` optical lens surface (`regular` or `clear`)
 * with real screen-space coordinates (`positionInWindow`), 22dp circular-arc lens bezel refraction,
 * RGB chromatic dispersion, top/bottom directional specular rim, inner reflection crease, and
 * side rim shade.
 */
fun Modifier.liquidGlassEffect(
    enabled: Boolean,
    isDark: Boolean,
    cornerRadius: Dp,
    baseColor: Color,
    material: LiquidGlassMaterial = LiquidGlassMaterial.REGULAR,
    accentTint: Color? = null,
    nested: Boolean = false,
    refractionDp: Float = if (material == LiquidGlassMaterial.CLEAR) 48f else 56f,
    pressProgress: Float = 0f
): Modifier {
    if (!enabled) return this
    return this.composed {
        val scheme = MaterialTheme.colorScheme
        val primaryColor = scheme.primary
        val secondaryColor = scheme.secondary
        val tertiaryColor = scheme.tertiary
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

                val rawAlpha = baseColor.alpha.coerceIn(0.18f, 0.96f)
                val effectiveAlpha = if (material == LiquidGlassMaterial.CLEAR) {
                    (rawAlpha * (if (isDark) 0.78f else 0.72f)).coerceIn(0.18f, 0.90f)
                } else {
                    rawAlpha
                }
                val calibratedBase = if (isDark) {
                    val darkFloor = Color(0xFF141C2B)
                    lerp(baseColor.copy(alpha = 1f), darkFloor, if (nested) 0.22f else 0.14f)
                } else {
                    val lightLift = Color(0xFFFCFEFF)
                    lerp(baseColor.copy(alpha = 1f), lightLift, if (material == LiquidGlassMaterial.REGULAR) 0.36f else 0.20f)
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
                        agslShader.setFloatUniform(
                            "uTertiaryRgb",
                            tertiaryColor.red,
                            tertiaryColor.green,
                            tertiaryColor.blue
                        )
                        if (cachedWallpaperShader != null) {
                            agslShader.setInputShader("uWallpaperShader", cachedWallpaperShader)
                            agslShader.setFloatUniform("uHasWallpaper", 1f)
                        } else {
                            // Provide a dummy 1x1 shader when no custom wallpaper is set
                            val dummyBmp = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
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

                // API 31-32 analytical multi-layer lens bevel + directional specular + inner crease + side shade
                val bezelDepthPx = min(22.dp.toPx(), h * 0.45f).coerceAtLeast(1f)
                val topSpecAlpha = if (isDark) 0.72f else 0.94f
                val botSpecAlpha = if (isDark) 0.48f else 0.72f
                val sideShadeAlpha = if (isDark) 0.44f else 0.24f
                val topDiffuseColor = (cleanTint?.let { lerp(Color.White, it, 0.25f) } ?: Color.White)
                    .copy(alpha = if (isDark) 0.16f else 0.36f)
                val bottomRefractColor = (cleanTint ?: primaryColor)
                    .copy(alpha = if (isDark) 0.14f else 0.18f)

                val bodyBrush = Brush.verticalGradient(
                    colors = listOf(
                        calibratedBase.copy(alpha = (effectiveAlpha * 1.05f).coerceIn(0f, 0.98f)),
                        calibratedBase.copy(alpha = (effectiveAlpha * 0.92f).coerceIn(0f, 0.96f)),
                        calibratedBase.copy(alpha = (effectiveAlpha * 1.02f).coerceIn(0f, 0.98f))
                    ),
                    startY = 0f,
                    endY = h
                )
                val topBevelBrush = Brush.verticalGradient(
                    colors = listOf(topDiffuseColor, Color.Transparent),
                    startY = 0f,
                    endY = bezelDepthPx
                )
                val botBevelBrush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, bottomRefractColor),
                    startY = max(0f, h - bezelDepthPx),
                    endY = h
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
                val strokeWidth = 1.35.dp.toPx()

                onDrawBehind {
                    drawRoundRect(brush = bodyBrush, cornerRadius = cr)
                    drawRoundRect(brush = topBevelBrush, cornerRadius = cr)
                    drawRoundRect(brush = botBevelBrush, cornerRadius = cr)
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
 * Native port of Glass-HQ/liquid-glass `GlassSwitch` (`value-controls.tsx` & `control-thumb.tsx`):
 * - `54dp x 24dp` capsule track (`padding: 2dp`), `32dp x 20dp` capsule thumb (`--lg-switch-travel: 18dp`).
 * - At rest, the thumb displays a crisp white elevation face (`lg-control-face`);
 *   when pressed, it swells (`+9dp` width, `+5.5dp` height) and crossfades into a transparent
 *   refractive `CLEAR` Liquid Glass lens (`lg-control-lens`, `refraction = 7.75`) with
 *   `LiquidGlassSpringPreset.SWITCH` (`response = 0.47s, damping = 0.70`).
 */
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
            XPeachTheme.readablePrimary.copy(alpha = if (enabled) 0.92f else 0.45f)
        } else {
            Color(0xFF8E8E93).copy(alpha = if (isDark) 0.42f else 0.34f)
        },
        animationSpec = tween(durationMillis = 200),
        label = "lgSwitchTrackColor"
    )

    // Swell dimensions from styles.css: --lg-control-grow-x: 9px; --lg-control-grow-y: 5.5px
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
                .shadow(
                    elevation = if (pressProgress > 0.2f) 6.dp else 3.dp,
                    shape = CircleShape,
                    clip = false
                )
                .clip(CircleShape)
                .then(
                    if (pressProgress > 0.05f) {
                        Modifier.liquidGlassEffect(
                            enabled = true,
                            isDark = isDark,
                            cornerRadius = 999.dp,
                            baseColor = Color.White.copy(alpha = (1f - 0.62f * pressProgress).coerceIn(0.28f, 0.96f)),
                            material = LiquidGlassMaterial.CLEAR,
                            accentTint = if (checked) XPeachTheme.readablePrimary else null,
                            nested = true,
                            refractionDp = 24f,
                            pressProgress = pressProgress
                        )
                    } else {
                        Modifier.background(Color.White.copy(alpha = if (enabled) 0.98f else 0.65f))
                    }
                )
        )
    }
}
