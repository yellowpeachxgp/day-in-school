package cn.edu.haut.campus.core.designsystem

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #14 工大蓝数据层纯 JVM 回归：设置页色板种子与默认品牌主色必须精确使用
 * 校徽深蓝 #053968，不得回退到旧版 #0066CC。
 */
class HautBlueIssue14RegressionTest {

    @Test
    fun hautBlueSettingsSeedAndBrandLightAreExact053968() {
        val hautBlue = PresetThemePalette.HAUT_BLUE

        assertEquals("HAUT_BLUE", hautBlue.key)
        assertEquals(0xFF053968L, hautBlue.seedArgb)
        assertEquals(Color(0xFF053968), Color(hautBlue.seedArgb))
        assertTrue(hautBlue.subtitle.contains("#053968"))

        // 设置页色板使用 PresetThemePalette.seedArgb；默认浅色主题使用 XPeachBrandLight。
        // 两处数据来源都必须锁定同一准确色值，避免「数据正确但默认主题仍错色」分裂。
        assertEquals(Color(0xFF053968), XPeachBrandLight)
    }

    @Test
    fun legacy0066ccIsNotReintroducedAsHautBluePresetOrBrandDefault() {
        assertNotEquals(0xFF0066CCL, PresetThemePalette.HAUT_BLUE.seedArgb)
        assertNotEquals(Color(0xFF0066CC), XPeachBrandLight)

        val exactBadgeBluePresets = PresetThemePalette.entries.filter { (it.seedArgb and 0xFFFFFFL) == 0x053968L }
        assertEquals(listOf(PresetThemePalette.HAUT_BLUE), exactBadgeBluePresets)
    }
}
