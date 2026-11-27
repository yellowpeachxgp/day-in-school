package cn.edu.haut.campus.core.uiruntime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 宿主声明式命令回调；宿主只执行 open_url/open_host_route/show_data 三类静态语义。 */
fun interface DeclarativeCommandHandler {
    fun invoke(command: ResolvedDeclarativeCommand)
}

val DEFAULT_EXTENSION_HOST_ROUTES = setOf(
    "home.today",
    "campus.services",
    "library.catalog",
    "library.seats",
    "parcel.hub",
    "settings.extensions"
)

/** 六个宿主槽位共用的渲染入口：数量上限、尺寸约束、停用/出错/无权限占位都在这里统一执行。 */
@Composable
fun ExtensionSlotHost(
    slotId: String,
    contributions: List<ExtensionUiContribution>,
    modifier: Modifier = Modifier,
    showEmptyState: Boolean = false,
    allowedHostRoutes: Set<String> = DEFAULT_EXTENSION_HOST_ROUTES,
    onCommand: DeclarativeCommandHandler = DeclarativeCommandHandler { }
) {
    val policy = ExtensionSlotPolicies.policyFor(slotId) ?: return
    val slotContributions = contributions.filter { it.slotId == slotId }
    if (slotContributions.isEmpty()) {
        if (showEmptyState) {
            SlotMessageCard(
                title = "暂无插件卡片",
                message = "该槽位尚未接入已启用且通过验签的插件"
            )
        }
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        slotContributions.take(policy.maxItems).forEach { contribution ->
            ExtensionContributionCard(
                contribution = contribution,
                policy = policy,
                allowedHostRoutes = allowedHostRoutes,
                onCommand = onCommand
            )
        }
        val overflow = slotContributions.size - policy.maxItems
        if (overflow > 0) {
            Text(
                text = "另有 " + overflow + " 个插件卡片因槽位数量上限未显示",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun ExtensionContributionCard(
    contribution: ExtensionUiContribution,
    policy: ExtensionSlotPolicy,
    allowedHostRoutes: Set<String>,
    onCommand: DeclarativeCommandHandler
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = policy.maxHeightDp.dp)
            .clipToBounds(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        contribution.card?.title ?: contribution.pluginName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        contribution.pluginName + " · " + contribution.activeVersion,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusPill(contribution.status)
            }
            when (contribution.status) {
                ExtensionContributionStatus.READY -> contribution.card?.let { card ->
                    FeatureUiCardContent(
                        card = card,
                        commands = contribution.commands,
                        grantedPermissions = contribution.grantedPermissions,
                        allowedOrigins = contribution.allowedOrigins,
                        allowedHostRoutes = allowedHostRoutes,
                        pluginId = contribution.pluginId,
                        maxNodes = policy.maxNodes,
                        onCommand = { onCommand.invoke(it) }
                    )
                }
                ExtensionContributionStatus.DISABLED -> PlaceholderText("插件已停用", contribution.errorMessage)
                ExtensionContributionStatus.ERROR -> PlaceholderText("插件出错", contribution.errorMessage)
                ExtensionContributionStatus.PERMISSION_DENIED -> PlaceholderText("权限未授予", contribution.errorMessage)
            }
        }
    }
}

@Composable
private fun StatusPill(status: ExtensionContributionStatus) {
    val (label, isError) = when (status) {
        ExtensionContributionStatus.READY -> "已启用" to false
        ExtensionContributionStatus.DISABLED -> "已停用" to false
        ExtensionContributionStatus.ERROR -> "出错" to true
        ExtensionContributionStatus.PERMISSION_DENIED -> "缺权限" to true
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun PlaceholderText(title: String, message: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            message ?: "插件卡片不可用",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SlotMessageCard(title: String, message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
