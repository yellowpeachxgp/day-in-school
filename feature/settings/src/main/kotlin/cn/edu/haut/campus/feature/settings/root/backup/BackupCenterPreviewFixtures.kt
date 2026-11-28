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

    private val sampleSyncConflict = E2eeSyncConflictSummary(
        id = "sc-preview",
        table = "courses",
        rowKey = "t:8:course-1",
        kind = "CONCURRENT_UPDATE",
        reason = "本机与远端同时修改同一逻辑行，未静默覆盖",
        localExcerpt = "{\"row\":{\"name\":{\"type\":\"text\",\"value\":\"本机课程名\"}}}",
        remoteExcerpt = "{\"row\":{\"name\":{\"type\":\"text\",\"value\":\"远端课程名\"}}}",
        remoteDeletion = false,
        localDeletion = false
    )

    private val sampleDevices = listOf(
        E2eeSyncDeviceSummary(
            deviceId = "a1b2c3d4-0000-4000-8000-000000000001",
            deviceIdShort = "a1b2c3d4",
            label = "本机 Android",
            status = "ACTIVE",
            currentDevice = true,
            lastSeenAt = "2026-11-28T08:00:00Z"
        ),
        E2eeSyncDeviceSummary(
            deviceId = "e5f60708-0000-4000-8000-000000000002",
            deviceIdShort = "e5f60708",
            label = "旧平板",
            status = "ACTIVE",
            currentDevice = false,
            lastSeenAt = "2026-11-27T08:00:00Z"
        )
    )

    private fun syncPanel(matrix: SettingsUiMatrix, conflicts: List<E2eeSyncConflictSummary> = emptyList()) = E2eeSyncPanelUiState(
        matrix = matrix,
        enabled = matrix.status != SettingsUiMatrix.Status.EMPTY,
        deviceIdShort = "a1b2c3d4",
        syncSpaceIdShort = "space01",
        lastRunSummary = "pulled=1,applied=1,uploaded=1,deleted=0,conflicts=" + conflicts.size + ",oversized=0",
        conflicts = conflicts,
        devices = if (matrix.status == SettingsUiMatrix.Status.EMPTY) emptyList() else sampleDevices
    )

    val loading = BackupCenterUiState(matrix = SettingsUiMatrix.Loading, sync = syncPanel(SettingsUiMatrix.Loading))
    val empty = BackupCenterUiState(matrix = SettingsUiMatrix.Empty, sync = syncPanel(SettingsUiMatrix.Empty))
    val content = BackupCenterUiState(matrix = SettingsUiMatrix.Content, sync = syncPanel(SettingsUiMatrix.Content))
    val partial = BackupCenterUiState(matrix = SettingsUiMatrix.Partial, sync = syncPanel(SettingsUiMatrix.Partial, listOf(sampleSyncConflict)))
    val stale = BackupCenterUiState(matrix = SettingsUiMatrix.Stale, sync = syncPanel(SettingsUiMatrix.Stale))
    val errorRetryable = BackupCenterUiState(matrix = SettingsUiMatrix.error(retryable = true, message = "备份错误（可重试）"), sync = syncPanel(SettingsUiMatrix.error(retryable = true, message = "同步错误（可重试）")))
    val errorTerminal = BackupCenterUiState(matrix = SettingsUiMatrix.error(retryable = false, message = "备份错误（不可重试）"), sync = syncPanel(SettingsUiMatrix.error(retryable = false, message = "同步错误（不可重试）")))
    val needsPermission = BackupCenterUiState(matrix = SettingsUiMatrix.NeedsPermission, sync = syncPanel(SettingsUiMatrix.NeedsPermission))
    val needsLogin = BackupCenterUiState(matrix = SettingsUiMatrix.NeedsLogin, sync = syncPanel(SettingsUiMatrix.NeedsLogin))
    val offline = BackupCenterUiState(matrix = SettingsUiMatrix.Offline, sync = syncPanel(SettingsUiMatrix.Offline))
    val unsupported = BackupCenterUiState(matrix = SettingsUiMatrix.Unsupported, serviceAvailable = false, sync = E2eeSyncPanelUiState(matrix = SettingsUiMatrix.Unsupported, serviceAvailable = false))

    val exportWizard = content.copy(
        exportWizard = ExportWizardUiState(visible = true)
    )

    val syncConsent = content.copy(
        sync = content.sync.copy(consentDialog = E2eeSyncConsentDialogUiState(visible = true))
    )

    val syncRecoveryKey = content.copy(
        sync = content.sync.copy(
            consentDialog = E2eeSyncConsentDialogUiState(
                visible = true,
                generatedRecoveryKey = "dssk1.preview-preview-preview-preview"
            )
        )
    )

    val syncConflict = partial
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
