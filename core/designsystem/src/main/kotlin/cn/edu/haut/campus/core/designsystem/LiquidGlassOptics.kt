package cn.edu.haut.campus.core.designsystem

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Port of Glass-HQ/liquid-glass (https://github.com/Glass-HQ/liquid-glass):
 * - `materials.ts`: `regular` (blur = 7.33, refraction = 60, dual-sigma fill 23/21.8, quadratic tone & chroma)
 *   and `clear` (blur = 0, refraction = 60, selective shadow lift & highlight rolloff).
 * - `optics.ts`: circular-arc bezel refraction profile `edgeDisplacement`.
 * - `maps.wgsl`: Lisse squircle SDF + Plane 1 directional top/bottom specular rim (`spread = 1.08`),
 *   4.5dp inner bevel diffuse glow (`spread * 0.65`), and left/right side rim micro-shade (`sideSpread = 0.98`).
 * - `concentric.ts`: concentric corner radius calculation for nested controls.
 * - `spring.ts`: perceptual duration + bounce harmonic oscillator presets.
 */
enum class LiquidGlassMaterial {
    REGULAR,
    CLEAR
}

enum class LiquidGlassSpringPreset(
    val durationSeconds: Float,
    val bounce: Float
) {
    PRESS(durationSeconds = 0.24f, bounce = 0.20f),
    RELEASE(durationSeconds = 0.50f, bounce = 0.42f),
    GLOW(durationSeconds = 0.42f, bounce = 0.00f),
    MORPH(durationSeconds = 0.42f, bounce = 0.18f),
    LAYOUT(durationSeconds = 0.40f, bounce = 0.16f),
    TRACK(durationSeconds = 0.18f, bounce = 0.00f);

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
        val directional = sat((kotlin.math.abs(ny) - cosSpread) / (1f - cosSpread)).pow(1.25f)
        val diffuseDir = sat((kotlin.math.abs(ny) - cosDiffuse) / (1f - cosDiffuse)).pow(1.25f)
        val stroke = sat((dDp + 0.9f) / aa + 0.5f) * sat(0.5f - dDp / aa)
        var specular = directional * stroke
        specular /= (1f + 0.5f * (1f - specular))
        val diffuse = diffuseDir * 0.18f * (1f - sat(-dDp / 4.5f)).pow(2f) * coverage
        val highlight = sat(
            specular * (if (isDark) 0.82f else 1.0f) +
                diffuse * (if (isDark) 0.40f else 1.0f)
        )
        val adjusted = dDp - 0.5f
        val falloff = 1f - sat(-adjusted / 0.5f)
        val ramp = (if (falloff > 0f) 0.25f else 0f) + 0.75f * falloff
        val outline = ramp * sat((adjusted + 0.5f) / aa + 0.5f) * sat(-adjusted / aa + 0.5f)
        val sideSpread = 0.98f
        val cosSide = cos(sideSpread)
        val wx = sat((kotlin.math.abs(nx) - cosSide) / (1f - cosSide)) * outline
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
                android.graphics.Shader.TileMode.MIRROR
            )
            RenderEffect.createChainEffect(colorEffect, blurEffect)
        } else {
            colorEffect
        }
    }

    /**
     * AGSL translation of `Glass-HQ/liquid-glass` `maps.wgsl` + `optics.ts` + `materials.ts`:
     * Renders the Lisse squircle SDF, 20dp circular-arc bezel refraction caustic,
     * top/bottom directional specular rim (`spread = 1.08`), 4.5dp inner bevel diffuse glow,
     * and left/right side rim micro-shade (`sideSpread = 0.98`).
     */
    private const val LIQUID_GLASS_AGSL_SOURCE = """
        uniform float2 uSize;
        uniform float uRadius;
        uniform float uDpr;
        uniform float uDark;
        uniform float uIsClear;
        uniform float uNested;
        uniform float uBodyAlpha;
        uniform float4 uBaseColor;
        uniform float4 uTintColor;
        uniform float uHasTint;

        float sat(float x) {
            return clamp(x, 0.0, 1.0);
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

            float aa = max(1.0, 0.85 * uDpr);
            float coverage = sat(0.5 - d / aa);
            if (coverage <= 0.001) {
                return half4(0.0);
            }

            // optics.ts: 20dp circular-arc lens bezel profile
            float dDp = d / max(uDpr, 1.0);
            float depth = sat(-dDp * 0.05);
            float edge = 1.0 - sat(sqrt(max(0.0, (2.0 - depth) * depth)));

            // Calibrated regular / clear glass body with lens bezel refraction caustic
            float3 bodyRgb = uBaseColor.rgb;
            if (uHasTint > 0.5) {
                float tintBody = mix(0.78, 0.68, uIsClear);
                bodyRgb = mix(bodyRgb, uTintColor.rgb, tintBody * 0.32);
            }
            // Top bezel refracts sky/light (+caustic), bottom bezel condenses depth
            float bezelCaustic = (-n.y * edge) * mix(0.11, 0.06, uDark);
            float innerLift = (1.0 - depth) * mix(0.06, 0.03, uDark) * (1.0 - uIsClear * 0.45);
            bodyRgb = clamp(bodyRgb + float3(bezelCaustic + innerLift), float3(0.0), float3(1.0));
            float bodyA = sat(uBodyAlpha * (1.0 + edge * 0.10)) * coverage;

            // maps.wgsl Plane 1: directional top & bottom specular + 4.5dp diffuse + side rim shade
            float cosSpread = 0.471328;   // cos(1.08)
            float cosDiffuse = 0.763552;  // cos(1.08 * 0.65)
            float directional = pow(sat((abs(n.y) - cosSpread) / (1.0 - cosSpread)), 1.25);
            float diffuseDir = pow(sat((abs(n.y) - cosDiffuse) / (1.0 - cosDiffuse)), 1.25);
            float stroke = sat((d + 0.9 * uDpr) / aa + 0.5) * sat(0.5 - d / aa);
            float specular = directional * stroke;
            specular = specular / (1.0 + 0.5 * (1.0 - specular));
            float diffuse = diffuseDir * 0.18 * pow(1.0 - sat(-dDp / 4.5), 2.0) * coverage;
            float highlight = sat(specular * mix(1.0, 0.82, uDark) + diffuse * mix(1.0, 0.40, uDark));

            float adjusted = dDp - 0.5;
            float falloff = 1.0 - sat(-adjusted / 0.5);
            float ramp = mix(falloff > 0.0 ? 1.0 : 0.0, falloff, 0.75);
            float outline = ramp * sat((adjusted + 0.5) / 1.0 + 0.5) * sat(-adjusted / 1.0 + 0.5);
            float cosSide = 0.557023;     // cos(0.98)
            float wx = sat((abs(n.x) - cosSide) / (1.0 - cosSide)) * outline;
            float shade = (wx / (1.0 + 0.4 * (1.0 - wx))) * mix(0.48, 0.60, uDark) * 0.38;

            float overlayAlpha = sat(highlight + shade * (1.0 - highlight));
            float lightScalar = mix(1.0, 0.82, uDark) * highlight / max(overlayAlpha, 0.00001);
            float3 lightRgb = uHasTint > 0.5
                ? mix(float3(lightScalar), uTintColor.rgb * lightScalar * 1.15, 0.28)
                : float3(lightScalar);

            // Source-over composite of Plane 1 rim overlay onto calibrated glass body
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
 * Applies the `Glass-HQ/liquid-glass` optical material surface (`regular` or `clear`)
 * using hardware AGSL `RuntimeShader` on Android 13+ (API 33–36) and an analytical
 * multi-layer Canvas bevel/specular/side-shade fallback on Android 12 (API 31–32).
 */
fun Modifier.liquidGlassEffect(
    enabled: Boolean,
    isDark: Boolean,
    cornerRadius: Dp,
    baseColor: Color,
    material: LiquidGlassMaterial = LiquidGlassMaterial.REGULAR,
    accentTint: Color? = null,
    nested: Boolean = false
): Modifier {
    if (!enabled) return this
    return this.drawWithCache {
        val w = size.width.coerceAtLeast(1f)
        val h = size.height.coerceAtLeast(1f)
        val dpr = density.coerceAtLeast(1f)
        val rPx = min(cornerRadius.toPx(), min(w, h) * 0.5f)

        // Calibrate base color luminance using Glass-HQ/liquid-glass tone transfer
        val rawAlpha = baseColor.alpha.coerceIn(0.14f, 0.96f)
        val effectiveAlpha = if (material == LiquidGlassMaterial.CLEAR) {
            (rawAlpha * (if (isDark) 0.72f else 0.64f)).coerceIn(0.12f, 0.88f)
        } else {
            rawAlpha
        }
        val calibratedBase = if (isDark) {
            val darkFloor = Color(0xFF1A2230)
            lerp(baseColor.copy(alpha = 1f), darkFloor, if (nested) 0.18f else 0.10f)
        } else {
            val lightLift = Color(0xFFFCFEFF)
            lerp(baseColor.copy(alpha = 1f), lightLift, if (material == LiquidGlassMaterial.REGULAR) 0.34f else 0.18f)
        }

        val cleanTint = accentTint?.takeIf { it != Color.Transparent && it != Color.Unspecified }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val agslShader = LiquidGlassOptics.createLiquidGlassShaderOrNull()
            if (agslShader != null) {
                agslShader.setFloatUniform("uSize", w, h)
                agslShader.setFloatUniform("uRadius", rPx)
                agslShader.setFloatUniform("uDpr", dpr)
                agslShader.setFloatUniform("uDark", if (isDark) 1f else 0f)
                agslShader.setFloatUniform("uIsClear", if (material == LiquidGlassMaterial.CLEAR) 1f else 0f)
                agslShader.setFloatUniform("uNested", if (nested) 1f else 0f)
                agslShader.setFloatUniform("uBodyAlpha", effectiveAlpha)
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
                val shaderBrush = ShaderBrush(agslShader)
                return@drawWithCache onDrawBehind {
                    drawRect(brush = shaderBrush)
                }
            }
        }

        // API 31-32 analytical fallback matching maps.wgsl Plane 1 (top/bottom directional specular + 4.5dp diffuse + side shade)
        val bezelDepthPx = min(20.dp.toPx(), h * 0.45f).coerceAtLeast(1f)
        val diffuseDepthPx = min(4.5.dp.toPx(), h * 0.25f).coerceAtLeast(1f)
        val topSpecAlpha = if (isDark) 0.56f else 0.86f
        val botSpecAlpha = if (isDark) 0.34f else 0.58f
        val sideShadeAlpha = if (isDark) 0.28f else 0.16f
        val topDiffuseColor = (cleanTint?.let { lerp(Color.White, it, 0.22f) } ?: Color.White)
            .copy(alpha = if (isDark) 0.10f else 0.26f)

        val bodyBrush = Brush.verticalGradient(
            colors = listOf(
                calibratedBase.copy(alpha = (effectiveAlpha * 1.04f).coerceIn(0f, 0.98f)),
                calibratedBase.copy(alpha = effectiveAlpha),
                calibratedBase.copy(alpha = (effectiveAlpha * 0.96f).coerceIn(0f, 0.98f))
            ),
            startY = 0f,
            endY = h
        )
        val topBevelBrush = Brush.verticalGradient(
            colors = listOf(topDiffuseColor, Color.Transparent),
            startY = 0f,
            endY = max(bezelDepthPx, diffuseDepthPx)
        )
        val rimBrush = Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = topSpecAlpha),
            0.26f to Color.Black.copy(alpha = sideShadeAlpha),
            0.74f to Color.Black.copy(alpha = sideShadeAlpha),
            1.0f to Color.White.copy(alpha = botSpecAlpha),
            startY = 0f,
            endY = h
        )
        val cr = CornerRadius(rPx, rPx)
        val strokeWidth = 1.dp.toPx()

        onDrawBehind {
            drawRoundRect(
                brush = bodyBrush,
                cornerRadius = cr
            )
            drawRoundRect(
                brush = topBevelBrush,
                cornerRadius = cr
            )
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
