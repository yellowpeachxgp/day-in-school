package cn.edu.haut.campus.feature.settings.root.backup

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.core.vault.BackupConflictResolution
import cn.edu.haut.campus.core.vault.BackupRowConflict
import cn.edu.haut.campus.core.vault.BackupRowKey
import cn.edu.haut.campus.core.vault.RestoreMode
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

@Immutable
data class BackupTableSummary(
    val table: String,
    val incomingRows: Int,
    val unchangedRows: Int,
    val newRows: Int,
    val conflictRows: Int,
    val removableRowsInReplace: Int
)

@Immutable
data class RestorePreviewSummary(
    val formatVersion: Int,
    val schemaVersion: Int,
    val entryCount: Int,
    val insertCount: Int,
    val unchangedCount: Int,
    val removableCount: Int,
    val conflicts: List<BackupRowConflict>,
    val tables: List<BackupTableSummary>
)

@Immutable
data class ExportWizardUiState(
    val visible: Boolean = false,
    val running: Boolean = false,
    val targetLabel: String? = null,
    val resultMessage: String? = null,
    val errorMessage: String? = null
)

@Immutable
data class RestoreWizardUiState(
    val visible: Boolean = false,
    val running: Boolean = false,
    val fileLabel: String? = null,
    val preview: RestorePreviewSummary? = null,
    val mode: RestoreMode = RestoreMode.MERGE,
    val resolutions: Map<BackupRowKey, BackupConflictResolution> = emptyMap(),
    val resultMessage: String? = null,
    val errorMessage: String? = null
)

@Immutable
data class E2eeSyncConflictSummary(
    val id: String,
    val table: String,
    val rowKey: String,
    val kind: String,
    val reason: String,
    val localExcerpt: String?,
    val remoteExcerpt: String?,
    val remoteDeletion: Boolean,
    val localDeletion: Boolean
)

@Immutable
data class E2eeSyncConsentDialogUiState(
    val visible: Boolean = false,
    val running: Boolean = false,
    val errorMessage: String? = null,
    /** 仅创建新同步空间后一次性展示。该值是秘密，Preview 必须使用假值，生产不得写日志。 */
    val generatedRecoveryKey: String? = null
)

@Immutable
data class E2eeSyncPanelUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Empty,
    val serviceAvailable: Boolean = true,
    val enabled: Boolean = false,
    val deviceLabel: String? = null,
    val deviceIdShort: String? = null,
    val syncSpaceIdShort: String? = null,
    val pendingWriteCount: Int = 0,
    val lastRunSummary: String? = null,
    val lastErrorMessage: String? = null,
    val running: Boolean = false,
    val conflicts: List<E2eeSyncConflictSummary> = emptyList(),
    val consentDialog: E2eeSyncConsentDialogUiState = E2eeSyncConsentDialogUiState()
)

@Immutable
data class BackupCenterUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val serviceAvailable: Boolean = true,
    val exportWizard: ExportWizardUiState = ExportWizardUiState(),
    val restoreWizard: RestoreWizardUiState = RestoreWizardUiState(),
    val sync: E2eeSyncPanelUiState = E2eeSyncPanelUiState()
)
