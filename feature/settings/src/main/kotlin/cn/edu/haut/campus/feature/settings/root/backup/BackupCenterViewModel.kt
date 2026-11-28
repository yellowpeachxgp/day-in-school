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
