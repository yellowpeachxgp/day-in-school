package cn.edu.haut.campus.feature.settings.plugin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cn.edu.haut.campus.feature.settings.plugin.preview.PluginManagementPreviewFixtures
import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementUiAction
import cn.edu.haut.campus.feature.settings.plugin.ui.PluginManagementScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AC-33 UI wiring tests: publisher/permissions are visible and every lifecycle control invokes the declared action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PluginManagementScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun publisherPermissionsAndUpgradeDiffAreVisible() {
        composeRule.setContent {
            PluginManagementScreen(state = PluginManagementPreviewFixtures.content, onAction = {})
        }

        composeRule.onNodeWithText("fixture-official-examples").assertIsDisplayed()
        composeRule.onNodeWithText("network.request").assertIsDisplayed()
        composeRule.onNodeWithText("新增权限：network.request").assertIsDisplayed()
        composeRule.onNodeWithText("移除权限：parcel.ai.invoke").assertIsDisplayed()
        composeRule.onNodeWithText("签名已验证", substring = true).assertIsDisplayed()
    }

    @Test
    fun revokeDisableRollbackUninstallAndConfirmUpgradeInvokeActions() {
        val actions = mutableListOf<PluginManagementUiAction>()
        composeRule.setContent {
            PluginManagementScreen(state = PluginManagementPreviewFixtures.content, onAction = actions::add)
        }

        composeRule.onNodeWithTag("revoke-network.request").performClick()
        composeRule.onAllNodesWithTag("toggle-plugin")[0].performClick()
        composeRule.onAllNodesWithTag("rollback-plugin")[0].performClick()
        composeRule.onAllNodesWithTag("uninstall-plugin")[0].performClick()
        composeRule.onNodeWithTag("confirm-upgrade").performClick()

        assertEquals(
            listOf(
                PluginManagementUiAction.RevokePermission("org.example.repair-club", "network.request"),
                PluginManagementUiAction.SetEnabled("org.example.repair-club", false),
                PluginManagementUiAction.Rollback("org.example.repair-club"),
                PluginManagementUiAction.Uninstall("org.example.repair-club"),
                PluginManagementUiAction.ConfirmUpgrade("org.example.parcel-notes")
            ),
            actions
        )
    }

    @Test
    fun previewFixturesCoverElevenRequiredStates() {
        assertEquals(
            setOf(
                "Loading", "Empty", "Content", "Partial", "Stale", "ErrorRetryable", "ErrorTerminal",
                "NeedsPermission", "NeedsLogin", "Offline", "Unsupported"
            ),
            PluginManagementPreviewFixtures.states11.keys
        )
    }
}
