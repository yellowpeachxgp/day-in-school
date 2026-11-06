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
        assertEquals(UiDesignStyle.LIQUID_GLASS, UiDesignStyle.fromKey("LIQUID_GLASS"))
        assertEquals(UiDesignStyle.LIQUID_GLASS, UiDesignStyle.fromKey("liquid_glass"))
        assertEquals(UiDesignStyle.FLUTTER_M3, UiDesignStyle.fromKey("UNKNOWN_STYLE"))
    }

    @Test
    fun presetThemePaletteIncludesLiquidGlassAndResolvesCorrectly() {
        val liquidPreset = PresetThemePalette.fromKey("LIQUID_GLASS")
        assertNotNull(liquidPreset)
        assertEquals(PresetThemePalette.LIQUID_GLASS, liquidPreset)
        assertEquals("透明毛玻璃", liquidPreset?.label)
        assertEquals(0xFF0EA5E9L, liquidPreset?.seedArgb)

        // All 9 presets should have distinct keys and non-blank labels
        assertEquals(9, PresetThemePalette.entries.size)
        val keys = PresetThemePalette.entries.map { it.key }.toSet()
        assertEquals(9, keys.size)
        assertTrue(PresetThemePalette.entries.all { it.label.isNotBlank() })

        assertNull(PresetThemePalette.fromKey("CUSTOM"))
        assertNull(PresetThemePalette.fromKey("WALLPAPER"))
    }
}
