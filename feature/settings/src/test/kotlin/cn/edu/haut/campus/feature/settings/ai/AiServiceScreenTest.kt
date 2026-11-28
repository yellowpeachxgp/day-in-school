package cn.edu.haut.campus.feature.settings.ai

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import cn.edu.haut.campus.feature.settings.ai.preview.AiServicePreviewFixtures
import cn.edu.haut.campus.feature.settings.ai.state.AiServiceUiAction
import cn.edu.haut.campus.feature.settings.ai.ui.AiServiceScreenContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiServiceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editorRequiresKeyAndPassesItOnlyAsTransientChars() {
        val actions = RecordingActions()
        composeRule.setContent {
            AiServiceScreenContent(state = AiServicePreviewFixtures.editor, onBack = {}, actions = actions)
        }

        composeRule.onNodeWithTag("ai-save-config").assertIsNotEnabled()
        composeRule.onNodeWithTag("ai-input-key").performTextInput("ui-test-key")
        composeRule.onNodeWithTag("ai-save-config").assertIsEnabled().performClick()

        assertEquals(listOf("save:ui-test-key"), actions.events)
    }

    @Test
    fun connectivitySendIsUnreachableBeforePreviewAndConfirmedFromPreview() {
        val actions = RecordingActions()
        composeRule.setContent {
            AiServiceScreenContent(state = AiServicePreviewFixtures.content, onBack = {}, actions = actions)
        }

        composeRule.onNodeWithTag("ai-connectivity-start").performClick()
        assertEquals(listOf("start-test"), actions.events)
        composeRule.onNodeWithText("发送前预览").assertDoesNotExist()

        composeRule.setContent {
            AiServiceScreenContent(state = AiServicePreviewFixtures.previewReady, onBack = {}, actions = actions)
        }
        composeRule.onNodeWithText("发送前预览").assertIsDisplayed()
        composeRule.onNodeWithTag("ai-preview-send").performClick()

        assertEquals(listOf("start-test", "confirm-send"), actions.events)
    }

    @Test
    fun previewShowsRedactionCountsWithoutOriginalPii() {
        composeRule.setContent {
            AiServiceScreenContent(state = AiServicePreviewFixtures.previewReady, onBack = {}, actions = RecordingActions())
        }

        composeRule.onNodeWithText("· 手机号 × 1").assertIsDisplayed()
        composeRule.onNodeWithText("13812345678").assertDoesNotExist()
    }

    @Test
    fun previewFixturesCoverElevenRequiredStates() {
        assertEquals(
            setOf(
                "loading", "empty", "content", "partial", "stale", "error_retryable", "error_terminal",
                "needs_permission", "needs_login", "offline", "unsupported"
            ),
            AiServicePreviewFixtures.matrixStates.keys
        )
    }

    private class RecordingActions : AiServiceUiAction {
        val events = mutableListOf<String>()
        override fun openEditor() { events += "open-editor" }
        override fun closeEditor() { events += "close-editor" }
        override fun saveConfiguration(baseUrl: String, model: String, supportsVision: Boolean, key: CharArray) {
            events += "save:" + String(key)
        }
        override fun setEnabled(enabled: Boolean) { events += "enabled:" + enabled }
        override fun openClearConfirmation() { events += "open-clear" }
        override fun closeClearConfirmation() { events += "close-clear" }
        override fun clearConfiguration() { events += "clear" }
        override fun startConnectivityTest() { events += "start-test" }
        override fun cancelConnectivityTest() { events += "cancel-test" }
        override fun confirmConnectivitySend() { events += "confirm-send" }
    }
}
