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
}
