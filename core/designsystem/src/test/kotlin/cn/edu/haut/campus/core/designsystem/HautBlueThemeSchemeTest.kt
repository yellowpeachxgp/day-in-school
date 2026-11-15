package cn.edu.haut.campus.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #14 工大蓝 (#053968) 浅色主题色板回归：
 * HAUT_BLUE 种子的浅色 primary 必须精确为校徽深蓝 #053968，其他种子走 HSV 派生路径。
 * 需要 Robolectric 提供 android.graphics.Color.colorToHSV 实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HautBlueThemeSchemeTest {

    private val hautBlue = Color(0xFF053968)

    @Test
    fun lightSchemePrimaryIsExactlyHautBlueForHautBlueSeed() {
        val scheme = buildSeedColorScheme(
            seedArgb = 0xFF053968L,
            darkTheme = false,
            uiDesignStyle = UiDesignStyle.FLUTTER_M3
        )
        assertEquals(hautBlue, scheme.primary)
        assertEquals(hautBlue, scheme.onPrimaryContainer)

        // 不同设计样式下 HAUT_BLUE 种子的 primary 依然精确锁定
        for (style in UiDesignStyle.entries) {
            val s = buildSeedColorScheme(0xFF053968L, darkTheme = false, uiDesignStyle = style)
            assertEquals("primary must stay #053968 under " + style, hautBlue, s.primary)
        }
    }

    @Test
    fun legacyAndOtherSeedsDoNotProduceExactHautBlue() {
        // 旧版 #0066CC 种子不再复用为工大蓝——走 HSV 派生路径而非精确锁色
        val legacyScheme = buildSeedColorScheme(0xFF0066CCL, darkTheme = false)
        assertNotEquals("legacy #0066CC must not silently become exact #053968", hautBlue, legacyScheme.primary)

        // 其他预设种子也不产生工大蓝精确色
        for (palette in PresetThemePalette.entries) {
            if (palette == PresetThemePalette.HAUT_BLUE) continue
            val scheme = buildSeedColorScheme(palette.seedArgb, darkTheme = false)
            assertNotEquals(palette.key + " must not emit exact #053968 primary", hautBlue, scheme.primary)
        }
    }

    @Test
    fun customSeedArgbResolvesThroughBuildSchemeWithoutMigration() {
        // 自定义用户颜色（含与 #053968 相同的值）按 CUSTOM 语义走种子派生，不被迁移改写
        val custom = buildSeedColorScheme(0xFF8B5CF6L, darkTheme = false)
        assertNotEquals(hautBlue, custom.primary)
        // 用户自定义恰好选了 #053968 时，色板同样精确（种子驱动，不是迁移驱动）
        val userPickedHaut = buildSeedColorScheme(0xFF053968L, darkTheme = false)
        assertEquals(hautBlue, userPickedHaut.primary)
    }
}
