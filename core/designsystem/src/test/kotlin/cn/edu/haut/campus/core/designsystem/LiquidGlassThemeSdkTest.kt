package cn.edu.haut.campus.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassThemeSdkTest {

    @Test
    fun uiDesignStyleResolvesFromKeyWithSafeFallback() {
        assertEquals(UiDesignStyle.FLUTTER_M3, UiDesignStyle.fromKey(null))
        assertEquals(UiDesignStyle.FLUTTER_M3, UiDesignStyle.fromKey(""))
        assertEquals(UiDesignStyle.FLUTTER_M3, UiDesignStyle.fromKey("FLUTTER_M3"))
        assertEquals(UiDesignStyle.FRESH_BREEZE, UiDesignStyle.fromKey("FRESH_BREEZE"))
        assertEquals(UiDesignStyle.FRESH_BREEZE, UiDesignStyle.fromKey("fresh_breeze"))
        assertEquals(UiDesignStyle.CREAM_PASTEL, UiDesignStyle.fromKey("CREAM_PASTEL"))
        assertEquals(UiDesignStyle.CREAM_PASTEL, UiDesignStyle.fromKey("cream_pastel"))
        assertEquals(UiDesignStyle.LIQUID_GLASS, UiDesignStyle.fromKey("LIQUID_GLASS"))
        assertEquals(UiDesignStyle.LIQUID_GLASS, UiDesignStyle.fromKey("liquid_glass"))
        assertEquals(UiDesignStyle.FLUTTER_M3, UiDesignStyle.fromKey("UNKNOWN_STYLE"))
        assertEquals(4, UiDesignStyle.entries.size)
    }

    @Test
    fun presetThemePaletteIncludesFreshAndLiquidGlassAndResolvesCorrectly() {
        val mintPreset = PresetThemePalette.fromKey("MINT_BREEZE")
        assertNotNull(mintPreset)
        assertEquals(PresetThemePalette.MINT_BREEZE, mintPreset)
        assertEquals("薄荷晨风", mintPreset?.label)
        assertEquals(0xFF10B981L, mintPreset?.seedArgb)
        assertEquals(UiDesignStyle.FRESH_BREEZE, mintPreset?.recommendedStyle)

        val peachPreset = PresetThemePalette.fromKey("PEACH_OOLONG")
        assertNotNull(peachPreset)
        assertEquals("白桃乌龙", peachPreset?.label)
        assertEquals(UiDesignStyle.CREAM_PASTEL, peachPreset?.recommendedStyle)

        val liquidPreset = PresetThemePalette.fromKey("LIQUID_GLASS")
        assertNotNull(liquidPreset)
        assertEquals(PresetThemePalette.LIQUID_GLASS, liquidPreset)
        assertEquals("透明毛玻璃", liquidPreset?.label)
        assertEquals(0xFF0EA5E9L, liquidPreset?.seedArgb)

        // All 15 presets should have distinct keys and non-blank labels
        assertEquals(15, PresetThemePalette.entries.size)
        val keys = PresetThemePalette.entries.map { it.key }.toSet()
        assertEquals(15, keys.size)
        assertTrue(PresetThemePalette.entries.all { it.label.isNotBlank() })

        assertNull(PresetThemePalette.fromKey("CUSTOM"))
        assertNull(PresetThemePalette.fromKey("WALLPAPER"))
    }

    @Test
    fun liquidGlassOpticsPortMatchesGlassHqSpecification() {
        // 1. optics.ts edgeDisplacement: at boundary (d = 0), displacement is full amount (60);
        //    at or beyond 20px inward (d <= -20), displacement is 0.
        assertEquals(60f, LiquidGlassOptics.edgeDisplacement(0f, 60f, 0.05f), 1e-4f)
        assertEquals(0f, LiquidGlassOptics.edgeDisplacement(-20f, 60f, 0.05f), 1e-4f)
        val midDisp = LiquidGlassOptics.edgeDisplacement(-5f, 60f, 0.05f)
        assertTrue(midDisp in 10f..50f)

        // 2. concentric.ts inner radius
        assertEquals(11f, LiquidGlassOptics.concentricRadius(14f, 3f), 1e-4f)
        assertEquals(0f, LiquidGlassOptics.concentricRadius(4f, 8f), 1e-4f)

        // 3. material-graph.ts calibrated regular & clear tone curves
        val regularLightZero = LiquidGlassOptics.evaluateRegularTone(0f, isDark = false)
        val regularDarkZero = LiquidGlassOptics.evaluateRegularTone(0f, isDark = true)
        assertEquals(0.80f * 0.36f, regularLightZero, 1e-4f)
        assertEquals(0.90f * 0.105f, regularDarkZero, 1e-4f)

        val clearLightMid = LiquidGlassOptics.evaluateClearTone(0.5f, isDark = false)
        val clearDarkMid = LiquidGlassOptics.evaluateClearTone(0.5f, isDark = true, nested = true)
        assertTrue(clearLightMid in 0.4f..0.7f)
        assertTrue(clearDarkMid in 0.4f..0.7f)

        // 4. maps.wgsl Plane 1 directional top/bottom specular vs side shade
        val (topLight, topAlpha) = LiquidGlassOptics.evaluateRimOverlay(dDp = -0.2f, nx = 0f, ny = -1f, isDark = false)
        val (sideLight, sideAlpha) = LiquidGlassOptics.evaluateRimOverlay(dDp = -0.2f, nx = 1f, ny = 0f, isDark = false)
        assertTrue("Top edge should have strong specular highlight", topLight > sideLight)
        assertTrue(topAlpha > 0f && sideAlpha > 0f)
    }
}
