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
data class BackupCenterUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Content,
    val serviceAvailable: Boolean = true,
    val exportWizard: ExportWizardUiState = ExportWizardUiState(),
    val restoreWizard: RestoreWizardUiState = RestoreWizardUiState()
)
