package cn.edu.haut.campus.feature.today

import androidx.compose.runtime.SideEffect
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.feature.today.ui.TodayInteractionState
import cn.edu.haut.campus.feature.today.ui.rememberTodayInteractionState
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W0-06 审阅阻断回归：底栏切换离开组合后，今日页纯界面状态必须重置。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayInteractionStateResetTest {

    private val initialNow: ZonedDateTime = ZonedDateTime.of(2026, 1, 7, 8, 0, 0, 0, HAUT_ZONE_ID)

    @Test
    fun bottomNavSwitchAwayAndBackResetsInteractionState() {
        val harness = TodayCompositionHarness()
        var first: TodayInteractionState? = null
        harness.compose {
            val state = rememberTodayInteractionState(initialNow)
            SideEffect { first = state }
        }
        assertNotNull(first)
        val mutated = first!!

        mutated.currentNow.value = initialNow.plusHours(3)
        mutated.selectedDate.value = initialNow.toLocalDate().plusDays(4)
        mutated.showTermSwitcherDialog.value = true
        mutated.showCardCustomizerDialog.value = true
        mutated.showDiningDialog.value = true
        mutated.diningShuffleOffset.intValue = 7
        mutated.dismissedMealBlockKey.value = "preview-dismissed-block"

        harness.dispose()
        var second: TodayInteractionState? = null
        harness.compose {
            val state = rememberTodayInteractionState(initialNow)
            SideEffect { second = state }
        }
        assertNotNull(second)
        val reset = second!!
        assertNotSame("重新进入必须是全新的界面状态持有者", mutated, reset)
        assertEquals(initialNow, reset.currentNow.value)
        assertEquals(initialNow.toLocalDate(), reset.selectedDate.value)
        assertNull(reset.selectedOccurrenceForDetail.value)
        assertFalse(reset.showTermSwitcherDialog.value)
        assertFalse(reset.showCardCustomizerDialog.value)
        assertFalse(reset.showDiningDialog.value)
        assertEquals(0, reset.diningShuffleOffset.intValue)
        assertNull(reset.dismissedMealBlockKey.value)
    }
}
