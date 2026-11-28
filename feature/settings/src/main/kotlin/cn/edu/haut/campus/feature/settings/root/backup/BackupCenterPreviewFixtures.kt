package cn.edu.haut.campus.feature.settings.root.backup

import cn.edu.haut.campus.core.vault.BackupRowConflict
import cn.edu.haut.campus.core.vault.BackupRowKey
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** W1-04 备份中心完整状态矩阵 fixtures；截图与 UI wiring 共用。 */
object BackupCenterPreviewFixtures {
    private val shaA = "a".repeat(64)
    private val shaB = "b".repeat(64)

    val samplePreview = RestorePreviewSummary(
        formatVersion = 1,
        schemaVersion = 12,
        entryCount = 42,
        insertCount = 3,
        unchangedCount = 36,
        removableCount = 2,
        conflicts = listOf(
            BackupRowConflict(
                key = BackupRowKey("courses", "t:8:course-1"),
                existingSha256 = shaA,
                incomingSha256 = shaB
            )
        ),
        tables = listOf(
            BackupTableSummary(
                table = "courses",
                incomingRows = 8,
                unchangedRows = 7,
                newRows = 0,
                conflictRows = 1,
                removableRowsInReplace = 0
            ),
            BackupTableSummary(
                table = "personal_events",
                incomingRows = 4,
                unchangedRows = 4,
                newRows = 0,
                conflictRows = 0,
                removableRowsInReplace = 1
            )
        )
    )

    val loading = BackupCenterUiState(matrix = SettingsUiMatrix.Loading)
    val empty = BackupCenterUiState(matrix = SettingsUiMatrix.Empty)
    val content = BackupCenterUiState(matrix = SettingsUiMatrix.Content)
    val partial = BackupCenterUiState(matrix = SettingsUiMatrix.Partial)
    val stale = BackupCenterUiState(matrix = SettingsUiMatrix.Stale)
    val errorRetryable = BackupCenterUiState(matrix = SettingsUiMatrix.error(retryable = true, message = "备份错误（可重试）"))
    val errorTerminal = BackupCenterUiState(matrix = SettingsUiMatrix.error(retryable = false, message = "备份错误（不可重试）"))
    val needsPermission = BackupCenterUiState(matrix = SettingsUiMatrix.NeedsPermission)
    val needsLogin = BackupCenterUiState(matrix = SettingsUiMatrix.NeedsLogin)
    val offline = BackupCenterUiState(matrix = SettingsUiMatrix.Offline)
    val unsupported = BackupCenterUiState(matrix = SettingsUiMatrix.Unsupported, serviceAvailable = false)

    val exportWizard = content.copy(
        exportWizard = ExportWizardUiState(visible = true)
    )

    val restorePreview = content.copy(
        restoreWizard = RestoreWizardUiState(
            visible = true,
            fileLabel = "fixture-backup.disbackup",
            preview = samplePreview
        )
    )

    val matrixStates: Map<String, BackupCenterUiState> = linkedMapOf(
        "loading" to loading,
        "empty" to empty,
        "content" to content,
        "partial" to partial,
        "stale" to stale,
        "error_retryable" to errorRetryable,
        "error_terminal" to errorTerminal,
        "needs_permission" to needsPermission,
        "needs_login" to needsLogin,
        "offline" to offline,
        "unsupported" to unsupported
    )
}
