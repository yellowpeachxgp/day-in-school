package cn.edu.haut.campus.feature.settings.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
            Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PluginManagementScreen(state = PluginManagementPreviewFixtures.content, onAction = {})
            }
        }

        composeRule.onAllNodesWithText("fixture-official-examples")[0].assertIsDisplayed()
        composeRule.onNodeWithTag("revoke-network.request").performScrollTo()
        composeRule.onNodeWithText("network.request").assertIsDisplayed()
        composeRule.onNodeWithTag("permission-diff").performScrollTo()
        composeRule.onNodeWithText("新增权限：network.request").assertIsDisplayed()
        composeRule.onNodeWithText("移除权限：parcel.ai.invoke").assertIsDisplayed()
        composeRule.onAllNodesWithText("签名已验证", substring = true)[0].performScrollTo().assertIsDisplayed()
    }

    @Test
    fun revokeDisableRollbackUninstallAndConfirmUpgradeInvokeActions() {
        val actions = mutableListOf<PluginManagementUiAction>()
        composeRule.setContent {
            Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PluginManagementScreen(state = PluginManagementPreviewFixtures.content, onAction = { action -> actions += action })
            }
        }

        composeRule.onNodeWithTag("revoke-network.request").performScrollTo().performClick()
        composeRule.onAllNodesWithTag("toggle-plugin")[0].performScrollTo().performClick()
        composeRule.onAllNodesWithTag("rollback-plugin")[0].performScrollTo().performClick()
        composeRule.onAllNodesWithTag("uninstall-plugin")[0].performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm-upgrade").performScrollTo().performClick()

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
    fun disabledPendingUpgradeRequiresEnableBeforePermissionConfirmation() {
        val disabledPending = PluginManagementPreviewFixtures.pendingUpgradePlugin.copy(status = "DISABLED")
        val state = PluginManagementPreviewFixtures.content.copy(plugins = listOf(disabledPending))
        composeRule.setContent {
            Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PluginManagementScreen(state = state, onAction = {})
            }
        }

        composeRule.onNodeWithTag("confirm-upgrade").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("先启用进入权限复核", substring = true).assertIsDisplayed()
    }

    @Test
    fun restoredPermissionsAreShownSeparatelyFromNewPermissions() {
        val restored = PluginManagementPreviewFixtures.pendingUpgradePlugin.copy(
            pendingPermissionDiff = PluginManagementPreviewFixtures.pendingUpgradePlugin.pendingPermissionDiff.copy(
                restored = listOf("network.request")
            )
        )
        val state = PluginManagementPreviewFixtures.content.copy(plugins = listOf(restored))
        composeRule.setContent {
            Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                PluginManagementScreen(state = state, onAction = {})
            }
        }

        composeRule.onNodeWithTag("permission-diff").performScrollTo()
        composeRule.onNodeWithText("恢复授权：network.request").assertIsDisplayed()
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
