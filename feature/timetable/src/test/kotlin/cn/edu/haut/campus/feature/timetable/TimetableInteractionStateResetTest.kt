package cn.edu.haut.campus.feature.timetable

import androidx.compose.runtime.SideEffect
import cn.edu.haut.campus.feature.timetable.ui.TimetableInteractionState
import cn.edu.haut.campus.feature.timetable.ui.rememberTimetableInteractionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W0-06 审阅阻断回归：底栏切换离开组合后，课表页顶层纯界面状态必须重置。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimetableInteractionStateResetTest {

    @Test
    fun bottomNavSwitchAwayAndBackResetsInteractionState() {
        val harness = TimetableCompositionHarness()
        var first: TimetableInteractionState? = null
        harness.compose {
            val state = rememberTimetableInteractionState(initialWeek = 3)
            SideEffect { first = state }
        }
        assertNotNull(first)
        val mutated = first!!

        mutated.showUnscheduled.value = true
        mutated.selectedWeek.intValue = 9
        mutated.showCanceledCoursesDialog.value = true
        mutated.showAddCourseDialog.value = true
        mutated.showTermSwitcherDialog.value = true
        mutated.showPeriodScheduleEditor.value = true
        mutated.viewMode.intValue = 2

        harness.dispose()
        var second: TimetableInteractionState? = null
        harness.compose {
            val state = rememberTimetableInteractionState(initialWeek = 3)
            SideEffect { second = state }
        }
        assertNotNull(second)
        val reset = second!!
        assertNotSame("重新进入必须是全新的界面状态持有者", mutated, reset)
        assertFalse(reset.showUnscheduled.value)
        assertEquals(3, reset.selectedWeek.intValue)
        assertNull(reset.editingOccurrence.value)
        assertNull(reset.editingMasterRule.value)
        assertNull(reset.schedulingPracticalCourse.value)
        assertFalse(reset.showCanceledCoursesDialog.value)
        assertFalse(reset.showAddCourseDialog.value)
        assertFalse(reset.showTermSwitcherDialog.value)
        assertFalse(reset.showPeriodScheduleEditor.value)
        assertNull(reset.confirmDeleteMasterRule.value)
        assertEquals(0, reset.viewMode.intValue)
    }
}
