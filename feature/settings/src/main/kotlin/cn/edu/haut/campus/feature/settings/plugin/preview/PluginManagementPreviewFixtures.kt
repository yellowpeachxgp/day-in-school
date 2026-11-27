package cn.edu.haut.campus.feature.settings.plugin.preview

import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementPluginUi
import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementUiState
import cn.edu.haut.campus.feature.settings.plugin.state.PluginPermissionDiff
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** 插件管理页 11 状态 fixtures；全部为合成发布者、合成权限与固定时间。 */
object PluginManagementPreviewFixtures {
    private const val UPDATED_AT = 1769901600000L

    val enabledPlugin = PluginManagementPluginUi(
        pluginId = "org.example.repair-club",
        name = "计算机服务社维修预约",
        publisherId = "fixture-official-examples",
        schemaFormat = "dis-extension/v2",
        activeVersion = "1.0.0",
        previousVersion = "0.9.0",
        pendingVersion = null,
        status = "ENABLED",
        signatureVerified = true,
        declaredPermissions = listOf("ui.slot.render", "network.request"),
        grantedPermissions = setOf("ui.slot.render", "network.request"),
        pendingPermissionDiff = PluginPermissionDiff(),
        storageFiles = 2,
        cacheFiles = 1,
        recentErrors = emptyList(),
        disabledReason = null,
        uiSlotCount = 2,
        updatedAtEpochMillis = UPDATED_AT
    )

    val pendingUpgradePlugin = enabledPlugin.copy(
        pluginId = "org.example.parcel-notes",
        name = "快递备注助手",
        activeVersion = "1.0.0",
        previousVersion = "0.9.0",
        pendingVersion = "1.1.0",
        status = "PENDING_PERMISSION_REVIEW",
        declaredPermissions = listOf("ui.slot.render"),
        grantedPermissions = setOf("ui.slot.render"),
        pendingPermissionDiff = PluginPermissionDiff(
            added = listOf("network.request"),
            removed = listOf("parcel.ai.invoke")
        )
    )

    val disabledPlugin = enabledPlugin.copy(
        pluginId = "org.example.disabled",
        name = "已停用示例插件",
        status = "DISABLED",
        disabledReason = "连续 3 次声明式模型错误"
    )

    private val contentPlugins = listOf(enabledPlugin, pendingUpgradePlugin)

    val loading = PluginManagementUiState(matrix = SettingsUiMatrix.Loading)
    val empty = PluginManagementUiState(matrix = SettingsUiMatrix.Empty, plugins = emptyList())
    val content = PluginManagementUiState(matrix = SettingsUiMatrix.Content, plugins = contentPlugins)
    val partial = content.copy(matrix = SettingsUiMatrix.Partial, plugins = listOf(enabledPlugin))
    val stale = content.copy(matrix = SettingsUiMatrix.Stale)
    val errorRetryable = PluginManagementUiState(
        matrix = SettingsUiMatrix.error(retryable = true, message = "插件状态读取失败，可重试"),
        plugins = contentPlugins
    )
    val errorTerminal = PluginManagementUiState(
        matrix = SettingsUiMatrix.error(retryable = false, message = "签名存储损坏"),
        plugins = emptyList()
    )
    val needsPermission = PluginManagementUiState(matrix = SettingsUiMatrix.NeedsPermission)
    val needsLogin = PluginManagementUiState(matrix = SettingsUiMatrix.NeedsLogin)
    val offline = content.copy(matrix = SettingsUiMatrix.Offline)
    val unsupported = PluginManagementUiState(matrix = SettingsUiMatrix.Unsupported)

    val states11: Map<String, PluginManagementUiState> = linkedMapOf(
        "Loading" to loading,
        "Empty" to empty,
        "Content" to content,
        "Partial" to partial,
        "Stale" to stale,
        "ErrorRetryable" to errorRetryable,
        "ErrorTerminal" to errorTerminal,
        "NeedsPermission" to needsPermission,
        "NeedsLogin" to needsLogin,
        "Offline" to offline,
        "Unsupported" to unsupported
    )
}
