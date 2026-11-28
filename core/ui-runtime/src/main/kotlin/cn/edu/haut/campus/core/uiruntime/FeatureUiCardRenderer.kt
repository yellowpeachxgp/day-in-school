package cn.edu.haut.campus.core.uiruntime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 纯声明式卡片渲染器。输入必须来自已验签包或合成 fixture；这里没有任何反射、脚本或代码加载。 */
@Composable
fun FeatureUiCardContent(
    card: FeatureUiCardModel,
    commands: List<cn.edu.haut.campus.core.packages.v2.PluginCommandDeclaration>,
    grantedPermissions: Set<String>,
    allowedOrigins: Set<String>,
    allowedHostRoutes: Set<String>,
    pluginId: String,
    maxNodes: Int,
    onCommand: (ResolvedDeclarativeCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        card.nodes.take(maxNodes).forEach { node ->
            FeatureUiNodeContent(
                node = node,
                pluginId = pluginId,
                commands = commands,
                grantedPermissions = grantedPermissions,
                allowedOrigins = allowedOrigins,
                allowedHostRoutes = allowedHostRoutes,
                onCommand = onCommand
            )
        }
        if (card.nodes.size > maxNodes) {
            Text(
                text = "已按槽位尺寸限制省略 " + (card.nodes.size - maxNodes) + " 个节点",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FeatureUiNodeContent(
    node: FeatureUiNode,
    pluginId: String,
    commands: List<cn.edu.haut.campus.core.packages.v2.PluginCommandDeclaration>,
    grantedPermissions: Set<String>,
    allowedOrigins: Set<String>,
    allowedHostRoutes: Set<String>,
    onCommand: (ResolvedDeclarativeCommand) -> Unit
) {
    when (node) {
        is FeatureUiNode.TextNode -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(node.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            node.value?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is FeatureUiNode.MetricNode -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(node.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                node.value.orEmpty().ifBlank { "—" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        is FeatureUiNode.KeyValueNode -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                node.label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                node.value.orEmpty().ifBlank { "—" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        is FeatureUiNode.BadgeNode -> Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = node.label + node.value?.takeIf { it.isNotBlank() }?.let { " · " + it }.orEmpty(),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        is FeatureUiNode.ActionButtonNode -> {
            val decision = DeclarativeCommandGate.resolve(
                pluginId = pluginId,
                commandName = node.command,
                declarations = commands,
                grantedPermissions = grantedPermissions,
                allowedOrigins = allowedOrigins,
                allowedHostRoutes = allowedHostRoutes
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Button(
                    onClick = {
                        if (decision is DeclarativeCommandDecision.Allowed) onCommand(decision.command)
                    },
                    enabled = decision is DeclarativeCommandDecision.Allowed,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(node.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                node.value?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (decision is DeclarativeCommandDecision.Blocked) {
                    Text(
                        decision.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        is FeatureUiNode.ListNode -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(node.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            val rows = node.value.orEmpty()
                .lines()
                .flatMap { it.split('；', ';') }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(6)
            if (rows.isEmpty()) {
                Text("暂无条目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                rows.forEach { row ->
                    Text("• " + row, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        is FeatureUiNode.EmptyStateNode -> StateSurface(
            label = node.label,
            value = node.value ?: "暂无内容",
            isError = false
        )
        is FeatureUiNode.ErrorStateNode -> StateSurface(
            label = node.label,
            value = node.value ?: "声明式卡片错误",
            isError = true
        )
        is FeatureUiNode.UnsupportedNode -> StateSurface(
            label = "节点无法渲染",
            value = node.reason,
            isError = true
        )
    }
}

@Composable
private fun StateSurface(label: String, value: String, isError: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
