package cn.edu.haut.campus.feature.settings.root.backup

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import cn.edu.haut.campus.core.vault.BackupConflictResolution
import cn.edu.haut.campus.core.vault.BackupRowKey
import cn.edu.haut.campus.core.vault.RestoreMode
import cn.edu.haut.campus.core.vault.sync.E2eeSyncConflictResolution
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
    fun exportWizardValidatesPassphraseBeforeChoosingTarget() {
        composeRule.setContent {
            BackupCenterSection(state = BackupCenterPreviewFixtures.exportWizard, actions = RecordingActions())
        }

        composeRule.onNodeWithText("导出加密备份").assertIsDisplayed()
        composeRule.onNodeWithTag("backup-export-choose-target").assertIsNotEnabled()
        composeRule.onNodeWithTag("backup-export-passphrase").performTextInput("correct horse")
        composeRule.onNodeWithTag("backup-export-confirm").performTextInput("correct horse")
        composeRule.onNodeWithTag("backup-export-choose-target").assertIsEnabled()
    }

    @Test
    fun restorePreviewShowsCountsConflictAndRunsRestore() {
        val actions = RecordingActions()
        val conflictKey = BackupCenterPreviewFixtures.samplePreview.conflicts.single().key
        val resolvedState = BackupCenterPreviewFixtures.restorePreview.copy(
            restoreWizard = BackupCenterPreviewFixtures.restorePreview.restoreWizard.copy(
                resolutions = mapOf(conflictKey to BackupConflictResolution.USE_BACKUP)
            )
        )
        composeRule.setContent {
            BackupCenterSection(state = resolvedState, actions = actions)
        }

        composeRule.onNodeWithText("格式 v1 · schema v12 · 42 条目").assertIsDisplayed()
        composeRule.onNodeWithText("冲突行（逐条选择）").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("backup-conflict-use").performScrollTo().performClick()
        composeRule.onNodeWithTag("backup-restore-run").assertIsEnabled().performClick()

        assertTrue(actions.events.contains("resolve:courses:USE_BACKUP"))
        assertTrue(actions.events.contains("restore"))
    }

    @Test
    fun syncPanelOpenConsentInvokesAction() {
        val actions = RecordingActions()
        val disabledSyncState = BackupCenterPreviewFixtures.content.copy(
            sync = BackupCenterPreviewFixtures.content.sync.copy(enabled = false, matrix = cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix.Empty)
        )
        composeRule.setContent {
            BackupCenterSection(state = disabledSyncState, actions = actions)
        }

        composeRule.onNodeWithTag("sync-open-consent").performClick()
        assertTrue(actions.events.contains("sync-open-consent"))
    }

    @Test
    fun syncConsentShowsOneTimeRecoveryKeyState() {
        composeRule.setContent {
            BackupCenterSection(state = BackupCenterPreviewFixtures.syncRecoveryKey, actions = RecordingActions())
        }
        composeRule.onNodeWithText("开启端到端跨设备同步").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag("sync-generated-recovery-key").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun syncConflictShowsTwoVersionsAndResolutionActions() {
        val actions = RecordingActions()
        val conflict = E2eeSyncConflictSummary(
            id = "sc-preview",
            table = "courses",
            rowKey = "t:8:course-1",
            kind = "CONCURRENT_UPDATE",
            reason = "本机与远端同时修改同一逻辑行，未静默覆盖",
            localExcerpt = "local-version",
            remoteExcerpt = "remote-version",
            remoteDeletion = false,
            localDeletion = false
        )
        composeRule.setContent {
            SyncConflictRow(conflict, actions, enabled = true)
        }

        assertTrue(composeRule.onAllNodesWithTag("sync-conflict-local").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithTag("sync-conflict-remote").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithTag("sync-conflict-keep-local").performClick()
        composeRule.onNodeWithTag("sync-conflict-use-remote").performClick()

        assertTrue(actions.events.contains("sync-resolve:sc-preview:KEEP_LOCAL"))
        assertTrue(actions.events.contains("sync-resolve:sc-preview:USE_REMOTE"))
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
        override fun openSyncConsent() { events += "sync-open-consent" }
        override fun closeSyncConsent() { events += "sync-close-consent" }
        override fun enableSyncWithNewKey(deviceLabel: String) { events += "sync-enable-new" }
        override fun enableSyncWithRecoveryKey(deviceLabel: String, recoveryKey: String) { events += "sync-enable-existing" }
        override fun runSyncNow() { events += "sync-run" }
        override fun disableSync() { events += "sync-disable" }
        override fun resolveSyncConflict(conflictId: String, resolution: E2eeSyncConflictResolution) {
            events += "sync-resolve:" + conflictId + ":" + resolution.name
        }
    }
}
