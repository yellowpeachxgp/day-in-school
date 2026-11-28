package cn.edu.haut.campus.feature.settings.root.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.vault.BackupConflictResolution
import cn.edu.haut.campus.core.vault.BackupException
import cn.edu.haut.campus.core.vault.BackupRowKey
import cn.edu.haut.campus.core.vault.BackupSelection
import cn.edu.haut.campus.core.vault.BackupService
import cn.edu.haut.campus.core.vault.RestoreMode
import cn.edu.haut.campus.core.vault.RestorePreview
import cn.edu.haut.campus.core.vault.VaultBackupServiceRegistry
import cn.edu.haut.campus.core.vault.sync.E2eeSyncApiException
import cn.edu.haut.campus.core.vault.sync.E2eeSyncClient
import cn.edu.haut.campus.core.vault.sync.E2eeSyncClientFactory
import cn.edu.haut.campus.core.vault.sync.E2eeSyncConflict
import cn.edu.haut.campus.core.vault.sync.E2eeSyncConflictResolution
import cn.edu.haut.campus.core.vault.sync.E2eeSyncEnableMode
import cn.edu.haut.campus.core.vault.sync.E2eeSyncScheduler
import java.io.IOException
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 备份中心 ViewModel：只保存预览摘要；口令、输入流和 PreparedRestore 明文不进入 UiState。 */
class BackupCenterViewModel(
    private val appContext: Context,
    private val backupService: BackupService?,
    private val syncClient: E2eeSyncClient? = null,
    private val cloudSessionProvider: suspend () -> Boolean = { false },
    private val syncWorkProfileId: String? = null,
    private val syncWorkGeneration: Long = -1L,
    private val postRestoreHooks: suspend () -> Unit = {}
) : ViewModel(), BackupCenterUiAction {
    private val mutableUiState = MutableStateFlow(
        if (backupService == null) {
            BackupCenterUiState(matrix = SettingsUiMatrix.Unsupported, serviceAvailable = false)
        } else {
            BackupCenterUiState()
        }
    )
    val uiState: StateFlow<BackupCenterUiState> = mutableUiState.asStateFlow()

    private var preparedPreview: RestorePreview? = null

    init {
        viewModelScope.launch { refreshSyncStatus() }
    }

    override fun openExportWizard() {
        mutableUiState.update {
            it.copy(exportWizard = ExportWizardUiState(visible = true))
        }
    }

    override fun closeExportWizard() {
        mutableUiState.update {
            it.copy(exportWizard = ExportWizardUiState(visible = false))
        }
    }

    override fun exportBackup(target: Uri, passphrase: CharArray) {
        val service = backupService ?: return
        val passCopy = passphrase.copyOf()
        passphrase.fill('\u0000')
        mutableUiState.update {
            it.copy(
                matrix = SettingsUiMatrix.Loading,
                exportWizard = it.exportWizard.copy(running = true, errorMessage = null, resultMessage = null)
            )
        }
        viewModelScope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(target, "w")?.use { out ->
                        service.export(service.scope, BackupSelection.ALL, passCopy, out)
                    } ?: throw IllegalStateException("无法打开备份保存位置")
                }
                mutableUiState.update {
                    it.copy(
                        matrix = SettingsUiMatrix.Content,
                        exportWizard = it.exportWizard.copy(
                            running = false,
                            targetLabel = displayName(target),
                            resultMessage = "已加密导出 " + manifest.entryCount + " 个条目"
                        )
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableUiState.update {
                    it.copy(
                        matrix = SettingsUiMatrix.error(retryable = true, message = "导出失败"),
                        exportWizard = it.exportWizard.copy(running = false, errorMessage = userSafeMessage(error, "导出备份失败"))
                    )
                }
            } finally {
                passCopy.fill('\u0000')
            }
        }
    }

    override fun openRestoreWizard() {
        mutableUiState.update {
            it.copy(restoreWizard = RestoreWizardUiState(visible = true))
        }
    }

    override fun closeRestoreWizard() {
        discardPreparedPreview()
        mutableUiState.update {
            it.copy(restoreWizard = RestoreWizardUiState(visible = false))
        }
    }

    override fun inspectBackup(source: Uri, passphrase: CharArray) {
        val service = backupService ?: return
        val passCopy = passphrase.copyOf()
        passphrase.fill('\u0000')
        mutableUiState.update {
            it.copy(
                matrix = SettingsUiMatrix.Loading,
                restoreWizard = it.restoreWizard.copy(
                    running = true,
                    fileLabel = displayName(source),
                    errorMessage = null,
                    resultMessage = null,
                    preview = null
                )
            )
        }
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(source)?.use { input ->
                        service.inspect(input, passCopy)
                    } ?: throw IllegalStateException("无法读取备份文件")
                }
                discardPreparedPreview()
                preparedPreview = preview
                mutableUiState.update {
                    it.copy(
                        matrix = SettingsUiMatrix.Content,
                        restoreWizard = it.restoreWizard.copy(
                            running = false,
                            preview = preview.toSummary(),
                            errorMessage = null
                        )
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                discardPreparedPreview()
                mutableUiState.update {
                    it.copy(
                        matrix = SettingsUiMatrix.error(retryable = true, message = "检查备份失败"),
                        restoreWizard = it.restoreWizard.copy(
                            running = false,
                            preview = null,
                            errorMessage = userSafeMessage(error, "检查备份失败")
                        )
                    )
                }
            } finally {
                passCopy.fill('\u0000')
            }
        }
    }

    override fun setRestoreMode(mode: RestoreMode) {
        mutableUiState.update {
            it.copy(restoreWizard = it.restoreWizard.copy(mode = mode, errorMessage = null))
        }
    }

    override fun setConflictResolution(key: BackupRowKey, resolution: BackupConflictResolution) {
        mutableUiState.update {
            val next = it.restoreWizard.resolutions + (key to resolution)
            it.copy(restoreWizard = it.restoreWizard.copy(resolutions = next))
        }
    }

    override fun restoreBackup() {
        val service = backupService ?: return
        val preview = preparedPreview
        val wizard = mutableUiState.value.restoreWizard
        if (preview == null || wizard.preview == null || wizard.running) return
        mutableUiState.update {
            it.copy(
                matrix = SettingsUiMatrix.Loading,
                restoreWizard = it.restoreWizard.copy(running = true, errorMessage = null, resultMessage = null)
            )
        }
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    service.restore(preview, wizard.mode, wizard.resolutions)
                }
                discardPreparedPreview()
                withContext(Dispatchers.IO) {
                    runCatching { postRestoreHooks() }
                }
                mutableUiState.update {
                    it.copy(
                        matrix = SettingsUiMatrix.Content,
                        restoreWizard = it.restoreWizard.copy(
                            running = false,
                            preview = null,
                            resultMessage = "恢复完成：新增 " + report.insertedRows +
                                "，更新 " + report.updatedRows +
                                "，保留 " + report.keptExistingRows +
                                "，移除 " + report.removedRows
                        )
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableUiState.update {
                    it.copy(
                        matrix = SettingsUiMatrix.error(retryable = true, message = "恢复失败"),
                        restoreWizard = it.restoreWizard.copy(
                            running = false,
                            errorMessage = userSafeMessage(error, "恢复备份失败，未发生部分写入")
                        )
                    )
                }
            }
        }
    }

    override fun openSyncConsent() {
        mutableUiState.update {
            it.copy(sync = it.sync.copy(consentDialog = it.sync.consentDialog.copy(visible = true, errorMessage = null)))
        }
        viewModelScope.launch { refreshSyncStatus() }
    }

    override fun closeSyncConsent() {
        mutableUiState.update {
            it.copy(sync = it.sync.copy(consentDialog = E2eeSyncConsentDialogUiState(visible = false)))
        }
    }

    override fun enableSyncWithNewKey(deviceLabel: String) {
        enableSync(E2eeSyncEnableMode.CREATE_NEW_SYNC_SPACE, deviceLabel, null)
    }

    override fun enableSyncWithRecoveryKey(deviceLabel: String, recoveryKey: String) {
        enableSync(E2eeSyncEnableMode.JOIN_EXISTING_SYNC_SPACE, deviceLabel, recoveryKey)
    }

    private fun enableSync(mode: E2eeSyncEnableMode, deviceLabel: String, recoveryKey: String?) {
        val client = syncClient ?: return
        mutableUiState.update {
            it.copy(sync = it.sync.copy(consentDialog = it.sync.consentDialog.copy(running = true, errorMessage = null)))
        }
        viewModelScope.launch {
            try {
                val result = client.enable(mode, deviceLabel, recoveryKey)
                refreshSyncStatus()
                syncWorkProfileId?.takeIf { syncWorkGeneration > 0L }?.let { profileId ->
                    E2eeSyncScheduler.schedulePeriodicEnabled(appContext, profileId, syncWorkGeneration)
                    E2eeSyncScheduler.enqueueImmediate(appContext, profileId, syncWorkGeneration)
                }
                mutableUiState.update {
                    it.copy(
                        sync = it.sync.copy(
                            consentDialog = it.sync.consentDialog.copy(
                                running = false,
                                generatedRecoveryKey = result.recoveryKeyForUserCopy,
                                errorMessage = null
                            )
                        )
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableUiState.update {
                    it.copy(
                        sync = it.sync.copy(
                            matrix = syncMatrixForError(error),
                            consentDialog = it.sync.consentDialog.copy(
                                running = false,
                                errorMessage = syncUserSafeMessage(error)
                            )
                        )
                    )
                }
            }
        }
    }

    override fun runSyncNow() {
        val client = syncClient ?: return
        mutableUiState.update {
            it.copy(sync = it.sync.copy(running = true, matrix = SettingsUiMatrix.Loading, lastErrorMessage = null))
        }
        viewModelScope.launch {
            try {
                client.runOnce()
                refreshSyncStatus()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableUiState.update {
                    it.copy(
                        sync = it.sync.copy(
                            running = false,
                            matrix = syncMatrixForError(error),
                            lastErrorMessage = syncUserSafeMessage(error)
                        )
                    )
                }
            }
        }
    }

    override fun disableSync() {
        val client = syncClient ?: return
        mutableUiState.update { it.copy(sync = it.sync.copy(running = true, lastErrorMessage = null)) }
        viewModelScope.launch {
            val report = client.disableAndWithdraw()
            syncWorkProfileId?.takeIf { syncWorkGeneration > 0L }?.let { profileId ->
                E2eeSyncScheduler.cancel(appContext, profileId, syncWorkGeneration)
            }
            refreshSyncStatus()
            if (!report.deviceRevoked || !report.consentWithdrawn) {
                mutableUiState.update {
                    it.copy(
                        sync = it.sync.copy(
                            lastErrorMessage = "本机已停止同步；云端设备撤销或同意撤回未确认，请在网络恢复后复核设备列表"
                        )
                    )
                }
            }
        }
    }

    override fun revokeSyncDevice(deviceId: String) {
        val client = syncClient ?: return
        mutableUiState.update { it.copy(sync = it.sync.copy(running = true, lastErrorMessage = null)) }
        viewModelScope.launch {
            try {
                client.revokeDevice(deviceId)
                refreshSyncStatus()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableUiState.update {
                    it.copy(
                        sync = it.sync.copy(
                            running = false,
                            matrix = syncMatrixForError(error),
                            lastErrorMessage = syncUserSafeMessage(error)
                        )
                    )
                }
            }
        }
    }

    override fun resolveSyncConflict(conflictId: String, resolution: E2eeSyncConflictResolution) {
        val client = syncClient ?: return
        mutableUiState.update { it.copy(sync = it.sync.copy(running = true, lastErrorMessage = null)) }
        viewModelScope.launch {
            try {
                client.resolveConflict(conflictId, resolution)
                refreshSyncStatus()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableUiState.update {
                    it.copy(
                        sync = it.sync.copy(
                            running = false,
                            matrix = syncMatrixForError(error),
                            lastErrorMessage = syncUserSafeMessage(error)
                        )
                    )
                }
            }
        }
    }

    private suspend fun refreshSyncStatus() {
        val client = syncClient
        if (client == null) {
            mutableUiState.update {
                it.copy(sync = E2eeSyncPanelUiState(matrix = SettingsUiMatrix.Unsupported, serviceAvailable = false))
            }
            return
        }
        val status = runCatching { client.status() }.getOrNull()
        val hasCloudSession = runCatching { cloudSessionProvider() }.getOrDefault(false)
        val matrix = when {
            status == null -> SettingsUiMatrix.error(retryable = true, message = "读取同步状态失败")
            !hasCloudSession -> SettingsUiMatrix.NeedsLogin
            !status.enabled -> SettingsUiMatrix.Empty
            status.conflicts.isNotEmpty() || status.pendingWriteCount > 0 -> SettingsUiMatrix.Partial
            else -> SettingsUiMatrix.Content
        }
        val devices = if (status?.enabled == true && hasCloudSession) {
            runCatching { client.listDevices() }.getOrDefault(emptyList()).map { device ->
                E2eeSyncDeviceSummary(
                    deviceId = device.deviceId,
                    deviceIdShort = device.deviceId.take(8),
                    label = device.deviceLabel,
                    status = device.status,
                    currentDevice = device.deviceId == status.deviceId,
                    lastSeenAt = device.lastSeenAt
                )
            }
        } else {
            emptyList()
        }
        mutableUiState.update {
            it.copy(
                sync = it.sync.copy(
                    matrix = matrix,
                    serviceAvailable = true,
                    enabled = status?.enabled == true,
                    deviceIdShort = status?.deviceId?.take(8),
                    syncSpaceIdShort = status?.syncSpaceId?.take(8),
                    pendingWriteCount = status?.pendingWriteCount ?: 0,
                    lastRunSummary = status?.lastRunSummary,
                    running = false,
                    conflicts = status?.conflicts.orEmpty().map(::toConflictSummary),
                    devices = devices
                )
            )
        }
    }

    private fun toConflictSummary(conflict: E2eeSyncConflict): E2eeSyncConflictSummary = E2eeSyncConflictSummary(
        id = conflict.id,
        table = conflict.table,
        rowKey = conflict.rowKey,
        kind = conflict.kind,
        reason = conflict.reason,
        localExcerpt = payloadExcerpt(conflict.localPayloadJson),
        remoteExcerpt = payloadExcerpt(conflict.remotePayloadJson),
        remoteDeletion = conflict.remoteDeletion,
        localDeletion = conflict.localDeletion
    )

    private fun payloadExcerpt(raw: String?): String? = raw?.let {
        if (it.length <= 220) it else it.take(220) + "…"
    }

    private fun syncMatrixForError(error: Throwable): SettingsUiMatrix {
        val api = error as? E2eeSyncApiException
        return when {
            api?.errorCode in setOf("CLOUD_AUTH_REQUIRED", "UNAUTHORIZED", "INVALID_TOKEN") -> SettingsUiMatrix.NeedsLogin
            api?.errorCode == "SYNC_CONSENT_REQUIRED" -> SettingsUiMatrix.NeedsPermission
            api?.errorCode == "SYNC_CURSOR_EXPIRED" || api?.errorCode == "SYNC_CURSOR_INVALID" -> SettingsUiMatrix.Stale
            error is IOException -> SettingsUiMatrix.Offline
            else -> SettingsUiMatrix.error(retryable = true, message = "同步失败")
        }
    }

    private fun syncUserSafeMessage(error: Throwable): String {
        val api = error as? E2eeSyncApiException
        return when {
            api?.errorCode == "SYNC_CONSENT_REQUIRED" -> "需要重新确认 E2EE_CLOUD_SYNC 同意"
            api?.errorCode == "SYNC_DEVICE_REVOKED" -> "本机同步设备已被撤销，请检查设备列表"
            api != null -> "云端同步失败（" + api.errorCode + "）"
            error is IOException -> "当前网络不可用，同步将稍后重试"
            else -> "同步失败，请重试"
        }
    }

    private fun discardPreparedPreview() {
        preparedPreview?.discardSensitiveMaterial()
        preparedPreview = null
    }

    private fun displayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun userSafeMessage(error: Throwable, fallback: String): String =
        when (error) {
            is BackupException -> error.message ?: fallback
            else -> fallback + "，请重试"
        }

    private fun RestorePreview.toSummary(): RestorePreviewSummary = RestorePreviewSummary(
        formatVersion = manifest.formatVersion,
        schemaVersion = manifest.schemaVersion,
        entryCount = manifest.entryCount,
        insertCount = insertCount,
        unchangedCount = unchangedCount,
        removableCount = removableCount,
        conflicts = conflicts,
        tables = tablePreviews.map { table ->
            BackupTableSummary(
                table = table.table,
                incomingRows = table.incomingRows,
                unchangedRows = table.unchangedRows,
                newRows = table.newRows,
                conflictRows = table.conflictRows,
                removableRowsInReplace = table.removableRowsInReplace
            )
        }
    )

    companion object {
        fun factory(context: Context, repository: CampusRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BackupCenterViewModel(
                    appContext = context.applicationContext,
                    backupService = VaultBackupServiceRegistry.forProfile(repository.profileId),
                    syncClient = E2eeSyncClientFactory.create(context.applicationContext, repository),
                    cloudSessionProvider = { repository.snapshotCloudIdentitySession() != null },
                    syncWorkProfileId = repository.profileId,
                    syncWorkGeneration = repository.sessionGeneration ?: -1L,
                    postRestoreHooks = {
                        runCatching { repository.rescheduleActiveLocalBookingPlans() }
                        runCatching {
                            ReminderCoordinator.getInstance(context).reconcileNow(
                                reason = "BACKUP_RESTORE",
                                forceRescheduleAlarms = true
                            )
                        }
                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                    }
                )
            }
        }
    }
}
