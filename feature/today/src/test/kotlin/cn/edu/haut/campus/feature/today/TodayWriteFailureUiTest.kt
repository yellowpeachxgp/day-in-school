package cn.edu.haut.campus.feature.today

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.feature.today.state.TodayUiAction
import cn.edu.haut.campus.feature.today.state.TodayUiState
import cn.edu.haut.campus.feature.today.state.TodayWriteCompletion
import cn.edu.haut.campus.feature.today.state.TodayWriteKind
import cn.edu.haut.campus.feature.today.ui.TodayScreen
import java.time.LocalDate
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PR#104 复审回归：写入失败必须留在原对话框显错，不得点击即关闭/丢现场。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TodayWriteFailureUiTest {
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
        val actions = mutableListOf<TodayUiAction>()
        val uiState = mutableStateOf(
            TodayUiState(
                terms = listOf(current, target),
                currentTerm = current
            )
        )
        composeRule.setContent {
            TodayScreen(
                state = uiState.value,
                onAction = { actions.add(it) }
            )
        }

        composeRule.onNodeWithText(current.displayName, substring = true).performClick()
        composeRule.onNodeWithText(target.displayName).performClick()
        val select = actions.filterIsInstance<TodayUiAction.SelectCurrentTerm>().single()
        assertNotNull(select.writeToken)
        composeRule.onNodeWithText("写入中…").assertIsDisplayed()

        val token = requireNotNull(select.writeToken)
        uiState.value = uiState.value.copy(
            writeCompletions = mapOf(
                token to TodayWriteCompletion(
                    id = 7L,
                    kind = TodayWriteKind.SelectCurrentTerm,
                    token = token,
                    errorMessage = "写入失败：测试"
                )
            )
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("写入失败：测试").assertIsDisplayed()
        composeRule.onNodeWithText("切换学期").assertIsDisplayed()
        assertTrue(
            actions.any { it is TodayUiAction.ConsumeWriteCompletion && it.token == token }
        )
    }
}
