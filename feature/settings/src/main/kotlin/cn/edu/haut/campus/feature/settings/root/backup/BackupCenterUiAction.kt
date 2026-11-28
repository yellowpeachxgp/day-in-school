package cn.edu.haut.campus.feature.settings.root.backup

import android.net.Uri
import cn.edu.haut.campus.core.vault.BackupConflictResolution
import cn.edu.haut.campus.core.vault.BackupRowKey
import cn.edu.haut.campus.core.vault.RestoreMode
import cn.edu.haut.campus.core.vault.sync.E2eeSyncConflictResolution

/** 备份中心用户动作面；passphrase 只在内存中传递，不进入 UiState、日志或 saved state。 */
interface BackupCenterUiAction {
    fun openExportWizard()
    fun closeExportWizard()
    fun exportBackup(target: Uri, passphrase: CharArray)

    fun openRestoreWizard()
    fun closeRestoreWizard()
    fun inspectBackup(source: Uri, passphrase: CharArray)
    fun setRestoreMode(mode: RestoreMode)
    fun setConflictResolution(key: BackupRowKey, resolution: BackupConflictResolution)
    fun restoreBackup()

    fun openSyncConsent()
    fun closeSyncConsent()
    fun enableSyncWithNewKey(deviceLabel: String)
    fun enableSyncWithRecoveryKey(deviceLabel: String, recoveryKey: String)
    fun runSyncNow()
    fun disableSync()
    fun revokeSyncDevice(deviceId: String)
    fun resolveSyncConflict(conflictId: String, resolution: E2eeSyncConflictResolution)
}
