package cn.edu.haut.campus.feature.settings.root.backup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 备份中心骨架 UI Roborazzi 基线；完整状态矩阵由 BackupCenterPreviewFixtures.matrixStates 固定。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class BackupCenterRoborazziTest {
    @Test
    fun backupCenterContent() {
        capture(BackupCenterPreviewFixtures.content)
    }

    @Test
    fun backupCenterUnsupported() {
        capture(BackupCenterPreviewFixtures.unsupported)
    }

    @Test
    fun backupRestorePreview() {
        capture(BackupCenterPreviewFixtures.restorePreview)
    }

    private fun capture(state: BackupCenterUiState) {
        captureRoboImage {
            TestFrame {
                Surface(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    BackupCenterSection(state = state, actions = NoopBackupActions)
                }
            }
        }
    }

    @Composable
    private fun TestFrame(content: @Composable () -> Unit) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    private object NoopBackupActions : BackupCenterUiAction {
        override fun openExportWizard() = Unit
        override fun closeExportWizard() = Unit
        override fun exportBackup(target: android.net.Uri, passphrase: CharArray) = Unit
        override fun openRestoreWizard() = Unit
        override fun closeRestoreWizard() = Unit
        override fun inspectBackup(source: android.net.Uri, passphrase: CharArray) = Unit
        override fun setRestoreMode(mode: cn.edu.haut.campus.core.vault.RestoreMode) = Unit
        override fun setConflictResolution(
            key: cn.edu.haut.campus.core.vault.BackupRowKey,
            resolution: cn.edu.haut.campus.core.vault.BackupConflictResolution
        ) = Unit
        override fun restoreBackup() = Unit
        override fun openSyncConsent() = Unit
        override fun closeSyncConsent() = Unit
        override fun enableSyncWithNewKey(deviceLabel: String) = Unit
        override fun enableSyncWithRecoveryKey(deviceLabel: String, recoveryKey: String) = Unit
        override fun runSyncNow() = Unit
        override fun disableSync() = Unit
        override fun revokeSyncDevice(deviceId: String) = Unit
        override fun resolveSyncConflict(
            conflictId: String,
            resolution: cn.edu.haut.campus.core.vault.sync.E2eeSyncConflictResolution
        ) = Unit
    }
}
