package cn.edu.haut.campus.feature.settings.plugin.state

import androidx.compose.runtime.Immutable
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

@Immutable
data class PluginPermissionDiff(
    val added: List<String> = emptyList(),
    val removed: List<String> = emptyList()
)

@Immutable
data class PluginManagementPluginUi(
    val pluginId: String,
    val name: String,
    val publisherId: String,
    val schemaFormat: String,
    val activeVersion: String,
    val previousVersion: String?,
    val pendingVersion: String?,
    val status: String,
    val signatureVerified: Boolean,
    val declaredPermissions: List<String>,
    val grantedPermissions: Set<String>,
    val pendingPermissionDiff: PluginPermissionDiff,
    val storageFiles: Int,
    val storageBytes: Long,
    val cacheFiles: Int,
    val cacheBytes: Long,
    val recentErrors: List<String>,
    val disabledReason: String?,
    val uiSlotCount: Int,
    val updatedAtEpochMillis: Long
)

@Immutable
data class PluginManagementUiState(
    val matrix: SettingsUiMatrix = SettingsUiMatrix.Loading,
    val plugins: List<PluginManagementPluginUi> = emptyList(),
    val busyPluginId: String? = null,
    val feedback: String? = null
)

sealed interface PluginManagementUiAction {
    data class RevokePermission(val pluginId: String, val permission: String) : PluginManagementUiAction
    data class SetEnabled(val pluginId: String, val enabled: Boolean) : PluginManagementUiAction
    data class ConfirmUpgrade(val pluginId: String) : PluginManagementUiAction
    data class Rollback(val pluginId: String) : PluginManagementUiAction
    data class Uninstall(val pluginId: String) : PluginManagementUiAction
    data object ConsumeFeedback : PluginManagementUiAction
}
