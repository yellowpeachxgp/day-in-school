package cn.edu.haut.campus.feature.settings.root.backup

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import cn.edu.haut.campus.core.vault.BackupConflictResolution
import cn.edu.haut.campus.core.vault.BackupRowKey
import cn.edu.haut.campus.core.vault.RestoreMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupCenterSectionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun entryButtonsInvokeWizardActions() {
        val actions = RecordingActions()
        composeRule.setContent {
            BackupCenterSection(state = BackupCenterPreviewFixtures.content, actions = actions)
        }

        composeRule.onNodeWithTag("backup-open-export").performClick()
        composeRule.onNodeWithTag("backup-open-restore").performClick()

        assertEquals(listOf("open-export", "open-restore"), actions.events)
    }

    @Test
    fun restorePreviewShowsCountsConflictAndRunsRestore() {
        val actions = RecordingActions()
        composeRule.setContent {
            BackupCenterSection(state = BackupCenterPreviewFixtures.restorePreview, actions = actions)
        }

        composeRule.onNodeWithText("格式 v1 · schema v12 · 42 条目").assertIsDisplayed()
        composeRule.onNodeWithText("冲突行（逐条选择）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("backup-conflict-use").performScrollTo().performClick()
        composeRule.onNodeWithTag("backup-restore-run").assertIsEnabled().performClick()

        assertTrue(actions.events.contains("resolve:courses:USE_BACKUP"))
        assertTrue(actions.events.contains("restore"))
    }

    @Test
    fun previewFixturesCoverElevenRequiredStates() {
        assertEquals(
            setOf(
                "loading", "empty", "content", "partial", "stale", "error_retryable", "error_terminal",
                "needs_permission", "needs_login", "offline", "unsupported"
            ),
            BackupCenterPreviewFixtures.matrixStates.keys
        )
    }

    private class RecordingActions : BackupCenterUiAction {
        val events = mutableListOf<String>()
        override fun openExportWizard() { events += "open-export" }
        override fun closeExportWizard() { events += "close-export" }
        override fun exportBackup(target: Uri, passphrase: CharArray) { events += "export" }
        override fun openRestoreWizard() { events += "open-restore" }
        override fun closeRestoreWizard() { events += "close-restore" }
        override fun inspectBackup(source: Uri, passphrase: CharArray) { events += "inspect" }
        override fun setRestoreMode(mode: RestoreMode) { events += "mode:" + mode.name }
        override fun setConflictResolution(key: BackupRowKey, resolution: BackupConflictResolution) {
            events += "resolve:" + key.table + ":" + resolution.name
        }
        override fun restoreBackup() { events += "restore" }
    }
}
