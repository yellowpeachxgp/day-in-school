package cn.edu.haut.campus.feature.timetable

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.feature.timetable.state.TimetableUiAction
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteCompletion
import cn.edu.haut.campus.feature.timetable.state.TimetableWriteKind
import cn.edu.haut.campus.feature.timetable.ui.TimetableEditingDialogs
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PR#104 复审回归：失败必须保留学期对话框现场并显错，成功才关闭。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TimetableWriteFailureUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun termSwitchFailureKeepsDialogOpenAndShowsError() {
        val current = Term(
            id = "2026-2027-1",
            academicYear = "2026-2027",
            termNumber = 1,
            startDate = LocalDate.of(2026, 8, 31),
            current = true
        )
        val target = Term(
            id = "2026-2027-2",
            academicYear = "2026-2027",
            termNumber = 2,
            startDate = LocalDate.of(2027, 2, 22),
            current = false
        )
        val actions = mutableListOf<TimetableUiAction>()
        val completions = mutableStateOf(emptyMap<String, TimetableWriteCompletion>())
        var dismissed = false
        composeRule.setContent {
            TimetableEditingDialogs(
                editingOccurrence = null,
                onEditingOccurrenceDismiss = {},
                editingMasterRule = null,
                onEditingMasterRuleDismiss = {},
                confirmDeleteMasterRule = null,
                onConfirmDeleteMasterRuleChange = {},
                schedulingPracticalCourse = null,
                onSchedulingPracticalCourseDismiss = {},
                showAddCourseDialog = false,
                onAddCourseDialogDismiss = {},
                showTermSwitcherDialog = true,
                onTermSwitcherDialogDismiss = { dismissed = true },
                term = current,
                terms = listOf(current, target),
                courses = emptyList(),
                rules = emptyList(),
                today = LocalDate.of(2026, 10, 12),
                writeCompletions = completions.value,
                onAction = { actions.add(it) },
                onNavigateToImporter = {},
                onNavigateToMapWithRoom = {}
            )
        }

        composeRule.onNodeWithText(target.displayName).performClick()
        val select = actions.filterIsInstance<TimetableUiAction.SelectCurrentTerm>().single()
        assertNotNull(select.writeToken)
        composeRule.onAllNodesWithText("写入中…").onFirst().assertIsDisplayed()

        val token = requireNotNull(select.writeToken)
        completions.value = mapOf(
            token to TimetableWriteCompletion(
                id = 7L,
                kind = TimetableWriteKind.SelectCurrentTerm,
                token = token,
                errorMessage = "写入失败：测试"
            )
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("写入失败：测试").assertIsDisplayed()
        composeRule.onNodeWithText("学期与校历").assertIsDisplayed()
        assertFalse(dismissed)
        assertTrue(
            actions.any { it is TimetableUiAction.ConsumeWriteCompletion && it.token == token }
        )
    }
}
