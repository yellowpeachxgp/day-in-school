package cn.edu.haut.campus.feature.settings.plugin.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementPluginUi
import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementUiAction
import cn.edu.haut.campus.feature.settings.plugin.state.PluginManagementUiState
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val UPDATE_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

/** AC-33 插件管理页纯 UI：只看声明式验签状态，不提供任何手填安装或代码执行入口。 */
@Composable
fun PluginManagementScreen(
    state: PluginManagementUiState,
    onAction: (PluginManagementUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MatrixBanner(state)
        state.feedback?.let { feedback ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(feedback, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onAction(PluginManagementUiAction.ConsumeFeedback) }) { Text("知道了") }
                }
            }
        }
        if (state.busyPluginId != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        when (state.matrix.status) {
            SettingsUiMatrix.Status.LOADING -> MessageCard("正在读取插件", "正在重复验签并汇总权限、版本与数据占用")
            SettingsUiMatrix.Status.EMPTY -> MessageCard("暂无已安装插件", "插件只能通过签名包安装；这里不会执行插件代码")
            SettingsUiMatrix.Status.NEEDS_LOGIN -> MessageCard("需要登录", "当前身份不可用，无法读取账号作用域插件")
            SettingsUiMatrix.Status.NEEDS_PERMISSION -> MessageCard("需要权限", "缺少读取插件状态所需的宿主权限")
            SettingsUiMatrix.Status.UNSUPPORTED -> MessageCard("当前学校不支持", "插件宿主矩阵未声明该能力")
            else -> Unit
        }
        if (state.matrix.status != SettingsUiMatrix.Status.LOADING && state.matrix.status != SettingsUiMatrix.Status.EMPTY) {
            state.plugins.forEach { plugin ->
                PluginCard(
                    plugin = plugin,
                    busy = state.busyPluginId == plugin.pluginId,
                    actionsEnabled = state.busyPluginId == null,
                    onAction = onAction
                )
            }
        }
    }
}

@Composable
private fun MatrixBanner(state: PluginManagementUiState) {
    when (state.matrix.status) {
        SettingsUiMatrix.Status.PARTIAL -> SmallBanner("部分插件信息不可用", true)
        SettingsUiMatrix.Status.STALE -> SmallBanner("插件信息可能过期", false)
        SettingsUiMatrix.Status.ERROR -> SmallBanner(state.matrix.message ?: "插件状态读取失败", true)
        SettingsUiMatrix.Status.OFFLINE -> SmallBanner("离线状态：展示本机已验签插件", false)
        else -> Unit
    }
}

@Composable
private fun PluginCard(
    plugin: PluginManagementPluginUi,
    busy: Boolean,
    actionsEnabled: Boolean,
    onAction: (PluginManagementUiAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("plugin-card-" + plugin.pluginId),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(plugin.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        plugin.pluginId + " · " + plugin.activeVersion,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusPill(if (plugin.status == "ENABLED") "已启用" else if (plugin.status == "PENDING_PERMISSION_REVIEW") "待确认" else "已停用", plugin.status == "ENABLED")
            }
            InfoRow("发布者", plugin.publisherId)
            InfoRow("来源 / 签名", plugin.schemaFormat + if (plugin.signatureVerified) " · 签名已验证" else " · 签名验证失败")
            InfoRow("槽位", plugin.uiSlotCount.toString() + " 个声明式槽位")
            InfoRow("数据占用", "存储 " + plugin.storageFiles + " 项 · 缓存 " + plugin.cacheFiles + " 项")
            InfoRow("更新时间", UPDATE_TIME_FMT.format(Instant.ofEpochMilli(plugin.updatedAtEpochMillis)))
            plugin.disabledReason?.let { InfoRow("最近错误", it) }
            plugin.recentErrors.lastOrNull()?.let { InfoRow("审计/错误", it.take(160)) }

            if (plugin.pendingVersion != null) {
                PendingUpgradeCard(plugin, actionsEnabled, onAction)
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("权限", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (plugin.declaredPermissions.isEmpty()) {
                    Text("当前版本未声明权限", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                plugin.declaredPermissions.forEach { permission ->
                    val granted = permission in plugin.grantedPermissions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(permission, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(if (granted) "已授予" else "已撤销", style = MaterialTheme.typography.labelSmall, color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                        if (granted) {
                            OutlinedButton(
                                onClick = { onAction(PluginManagementUiAction.RevokePermission(plugin.pluginId, permission)) },
                                enabled = actionsEnabled,
                                modifier = Modifier.testTag("revoke-" + permission)
                            ) { Text("撤权") }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onAction(PluginManagementUiAction.SetEnabled(plugin.pluginId, plugin.status != "ENABLED")) },
                    enabled = actionsEnabled && plugin.status != "PENDING_PERMISSION_REVIEW" && plugin.status != "LEGACY_UNVERIFIED",
                    modifier = Modifier.weight(1f).testTag("toggle-plugin")
                ) { Text(if (plugin.status == "ENABLED") "停用" else "启用") }
                OutlinedButton(
                    onClick = { onAction(PluginManagementUiAction.Rollback(plugin.pluginId)) },
                    enabled = actionsEnabled && plugin.previousVersion != null,
                    modifier = Modifier.weight(1f).testTag("rollback-plugin")
                ) { Text("回滚") }
                OutlinedButton(
                    onClick = { onAction(PluginManagementUiAction.Uninstall(plugin.pluginId)) },
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f).testTag("uninstall-plugin")
                ) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            }
            if (busy) {
                Text("正在执行生命周期操作…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PendingUpgradeCard(
    plugin: PluginManagementPluginUi,
    actionsEnabled: Boolean,
    onAction: (PluginManagementUiAction) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("permission-diff"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("待确认升级：" + plugin.activeVersion + " → " + plugin.pendingVersion, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "新增权限：" + (plugin.pendingPermissionDiff.added.joinToString("、").ifBlank { "无" }),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "移除权限：" + (plugin.pendingPermissionDiff.removed.joinToString("、").ifBlank { "无" }),
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = { onAction(PluginManagementUiAction.ConfirmUpgrade(plugin.pluginId)) },
                enabled = actionsEnabled,
                modifier = Modifier.testTag("confirm-upgrade")
            ) { Text("确认升级权限") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, modifier = Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusPill(label: String, enabled: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SmallBanner(message: String, error: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(message, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MessageCard(title: String, message: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
