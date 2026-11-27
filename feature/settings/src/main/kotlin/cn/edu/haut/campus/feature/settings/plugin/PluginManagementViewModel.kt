package cn.edu.haut.campus.feature.settings.plugin

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.packages.v2.ExtensionInstallation
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginInstaller
import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementPluginUi
import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementUiAction
import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementUiState
import cn.edu.haut.campus.feature.settings.plugin.state.PluginPermissionDiff
import cn.edu.haut.campus.feature.settings.state.ProfileSessionScopedViewModel
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** 插件管理页状态 holder；生命周期写操作直连 W6-01a 安装器，不新增 CampusRepository facade。 */
class PluginManagementViewModel(
    private val repository: CampusRepository,
    private val installer: ExtensionPluginInstaller,
    sessionStates: StateFlow<AccountSessionState>
) : ProfileSessionScopedViewModel(repository, sessionStates) {
    private val mutableUiState = MutableStateFlow(PluginManagementUiState())
    val uiState: StateFlow<PluginManagementUiState> = mutableUiState.asStateFlow()

    init {
        launchSessionCollect(installer.revision) {
            launchSessionJob { refreshSnapshot() }
        }
    }

    fun onAction(action: PluginManagementUiAction) {
        when (action) {
            is PluginManagementUiAction.RevokePermission -> runLifecycle(action.pluginId, "已撤销权限 " + action.permission) {
                installer.revokePermission(repository.schoolId, action.pluginId, action.permission)
            }
            is PluginManagementUiAction.SetEnabled -> runLifecycle(
                action.pluginId,
                if (action.enabled) "已启用插件" else "已停用插件"
            ) {
                installer.setEnabled(repository.schoolId, action.pluginId, action.enabled)
            }
            is PluginManagementUiAction.ConfirmUpgrade -> runLifecycle(action.pluginId, "已确认升级权限") {
                installer.confirmPendingPermissions(repository.schoolId, action.pluginId)
            }
            is PluginManagementUiAction.Rollback -> runLifecycle(action.pluginId, "已回滚插件") {
                installer.rollback(repository.schoolId, action.pluginId)
            }
            is PluginManagementUiAction.Uninstall -> runLifecycle(action.pluginId, "已卸载插件并清理数据") {
                installer.uninstall(repository.schoolId, action.pluginId)
            }
            PluginManagementUiAction.ConsumeFeedback -> mutableUiState.update { it.copy(feedback = null) }
        }
    }

    private fun runLifecycle(pluginId: String, successMessage: String, block: () -> Unit) {
        if (mutableUiState.value.busyPluginId != null) return
        launchSessionJob {
            mutableUiState.update { it.copy(busyPluginId = pluginId, feedback = null) }
            val feedback = runCatching {
                withContext(Dispatchers.IO) { block() }
                successMessage
            }.getOrElse { error -> "操作失败：" + (error.message ?: error.javaClass.simpleName) }
            mutableUiState.update { it.copy(busyPluginId = null, feedback = feedback) }
            refreshSnapshot()
        }
    }

    private suspend fun refreshSnapshot() {
        val snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
        if (isSessionValidForEmission()) {
            mutableUiState.update {
                it.copy(
                    matrix = if (snapshot.isEmpty()) SettingsUiMatrix.Empty else SettingsUiMatrix.Content,
                    plugins = snapshot
                )
            }
        }
    }

    private fun loadSnapshot(): List<PluginManagementPluginUi> =
        installer.snapshot()
            .filter { it.schoolId == repository.schoolId }
            .sortedBy { it.pluginId }
            .map(::toUiModel)

    private fun toUiModel(installation: ExtensionInstallation): PluginManagementPluginUi {
        val activeVerified = runCatching {
            installer.verifiedStoredPackage(repository.schoolId, installation.pluginId, installation.activeVersion)
        }.getOrNull()
        val pendingVerified = installation.pendingVersion?.let { pending ->
            runCatching { installer.verifiedStoredPackage(repository.schoolId, installation.pluginId, pending) }.getOrNull()
        }
        val activePermissions = activeVerified?.manifest?.permissions.orEmpty()
        val pendingPermissions = pendingVerified?.manifest?.permissions.orEmpty()
        val usage = installer.scopedStorageUsage(repository.schoolId, installation.pluginId)
        val auditErrors = installer.auditLines()
            .filter { it.contains(installation.pluginId) }
            .takeLast(3)
        return PluginManagementPluginUi(
            pluginId = installation.pluginId,
            name = installation.name,
            publisherId = installation.publisherId,
            schemaFormat = installation.schemaFormat,
            activeVersion = installation.activeVersion,
            previousVersion = installation.previousVersion,
            pendingVersion = installation.pendingVersion,
            status = installation.status,
            signatureVerified = activeVerified != null,
            declaredPermissions = activePermissions.sorted(),
            grantedPermissions = installation.grantedPermissions.toSet(),
            pendingPermissionDiff = if (installation.pendingVersion == null) {
                PluginPermissionDiff()
            } else if (pendingVerified != null && activeVerified != null) {
                PluginPermissionDiff(
                    added = (pendingPermissions - activePermissions).sorted(),
                    removed = (activePermissions - pendingPermissions).sorted()
                )
            } else {
                // 目标版本损坏时不得把“无法验证”误报成“全部权限被移除”。
                PluginPermissionDiff(added = installation.pendingPermissions.sorted())
            },
            storageFiles = usage.first.files,
            storageBytes = usage.first.bytes,
            cacheFiles = usage.second.files,
            cacheBytes = usage.second.bytes,
            recentErrors = auditErrors,
            disabledReason = installation.disabledReason,
            uiSlotCount = activeVerified?.manifest?.uiSlots?.size ?: 0,
            updatedAtEpochMillis = installation.updatedAtEpochMillis
        )
    }

    override fun resetStateForInvalidSession() {
        mutableUiState.value = PluginManagementUiState()
    }

    companion object {
        fun factory(
            repository: CampusRepository,
            installer: ExtensionPluginInstaller,
            sessionStates: StateFlow<AccountSessionState>
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { PluginManagementViewModel(repository, installer, sessionStates) }
        }
    }
}
