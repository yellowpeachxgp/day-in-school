package cn.edu.haut.campus.core.uiruntime.preview

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.packages.v2.PluginCommandDeclaration
import cn.edu.haut.campus.core.uiruntime.ExtensionContributionStatus
import cn.edu.haut.campus.core.uiruntime.ExtensionUiContribution
import cn.edu.haut.campus.core.uiruntime.FeatureUiCardModel
import cn.edu.haut.campus.core.uiruntime.FeatureUiNode

enum class ExtensionSlotFixtureState {
    WITH_PLUGIN,
    NO_PLUGIN,
    DISABLED,
    ERROR
}

enum class ExtensionUiPreviewMatrix {
    LOADING,
    EMPTY,
    CONTENT,
    PARTIAL,
    STALE,
    ERROR_RETRYABLE,
    ERROR_TERMINAL,
    NEEDS_PERMISSION,
    NEEDS_LOGIN,
    OFFLINE,
    UNSUPPORTED
}

/** 固定、无真实学校/账号数据的 feature-ui Preview fixtures。 */
object ExtensionUiPreviewFixtures {
    val commands = listOf(
        PluginCommandDeclaration(
            name = "openSignup",
            description = "打开合成报名页",
            requiredPermission = "network.request",
            type = "open_url",
            url = "https://club.example.com/signup"
        ),
        PluginCommandDeclaration(
            name = "showPrices",
            description = "展示合成价格表",
            requiredPermission = "ui.slot.render",
            type = "show_data",
            artifactPath = "assets/prices.json"
        ),
        PluginCommandDeclaration(
            name = "openParcel",
            description = "打开快递页",
            requiredPermission = null,
            type = "open_host_route",
            route = "parcel.hub"
        )
    )
    val grantedPermissions = setOf("ui.slot.render", "network.request")
    val allowedOrigins = setOf("https://club.example.com")

    val nodeCards: Map<String, FeatureUiCardModel> = mapOf(
        "Text" to nodeCard("text", FeatureUiNode.TextNode("服务时间", "工作日 18:30–21:00")),
        "Metric" to nodeCard("metric", FeatureUiNode.MetricNode("当前排队", "3 人")),
        "KeyValue" to nodeCard("key-value", FeatureUiNode.KeyValueNode("服务地点", "W111")),
        "Badge" to nodeCard("badge", FeatureUiNode.BadgeNode("状态", "开放")),
        "ActionButton" to nodeCard("action-button", FeatureUiNode.ActionButtonNode("打开报名页", "openSignup", "HTTPS 且来源已声明")),
        "List" to nodeCard("list", FeatureUiNode.ListNode("服务项目", "清灰保养；系统重装；硬件检测")),
        "EmptyState" to nodeCard("empty-state", FeatureUiNode.EmptyStateNode("暂无排队", "当前可直接到店")),
        "ErrorState" to nodeCard("error-state", FeatureUiNode.ErrorStateNode("数据不可用", "签名数据包读取失败"))
    )

    val unknownNodeCard = FeatureUiCardModel(
        slot = "home.cards",
        componentId = "unknown-node",
        template = "key-value-card/v1",
        title = "未知节点降级",
        accessibilityLabel = "未知节点按错误状态渲染",
        nodes = listOf(
            FeatureUiNode.UnsupportedNode(
                kind = "FutureChart",
                label = "未来图表",
                reason = "未知节点 FutureChart，已按 ErrorState 降级"
            )
        )
    )

    val matrixStates: Map<ExtensionUiPreviewMatrix, List<ExtensionUiContribution>> = mapOf(
        ExtensionUiPreviewMatrix.LOADING to emptyList(),
        ExtensionUiPreviewMatrix.EMPTY to emptyList(),
        ExtensionUiPreviewMatrix.CONTENT to listOf(contributionFor("home.cards", ExtensionContributionStatus.READY)),
        ExtensionUiPreviewMatrix.PARTIAL to listOf(contributionFor("home.cards", ExtensionContributionStatus.READY)),
        ExtensionUiPreviewMatrix.STALE to listOf(contributionFor("home.cards", ExtensionContributionStatus.READY)),
        ExtensionUiPreviewMatrix.ERROR_RETRYABLE to listOf(contributionFor("home.cards", ExtensionContributionStatus.ERROR)),
        ExtensionUiPreviewMatrix.ERROR_TERMINAL to listOf(contributionFor("home.cards", ExtensionContributionStatus.ERROR)),
        ExtensionUiPreviewMatrix.NEEDS_PERMISSION to listOf(contributionFor("home.cards", ExtensionContributionStatus.PERMISSION_DENIED)),
        ExtensionUiPreviewMatrix.NEEDS_LOGIN to emptyList(),
        ExtensionUiPreviewMatrix.OFFLINE to listOf(contributionFor("home.cards", ExtensionContributionStatus.READY)),
        ExtensionUiPreviewMatrix.UNSUPPORTED to listOf(contributionFor("home.cards", ExtensionContributionStatus.ERROR, unknownNodeCard))
    )

    fun contributionFor(
        slotId: String,
        status: ExtensionContributionStatus,
        card: FeatureUiCardModel? = sampleCard(slotId)
    ): ExtensionUiContribution {
        val message = when (status) {
            ExtensionContributionStatus.READY -> null
            ExtensionContributionStatus.DISABLED -> "插件已停用"
            ExtensionContributionStatus.ERROR -> "签名数据包或卡片模型错误"
            ExtensionContributionStatus.PERMISSION_DENIED -> "缺少 ui.slot.render 授权"
        }
        return ExtensionUiContribution(
            pluginId = "org.example.fixture",
            pluginName = "合成维修预约",
            publisherId = "fixture-publisher",
            activeVersion = "1.0.0",
            slotId = slotId,
            cardId = "fixture.card",
            status = status,
            card = card,
            errorMessage = message,
            commands = commands,
            grantedPermissions = if (status == ExtensionContributionStatus.PERMISSION_DENIED) emptySet() else grantedPermissions,
            allowedOrigins = allowedOrigins
        )
    }

    fun slotFixture(slotId: String, state: ExtensionSlotFixtureState): List<ExtensionUiContribution> = when (state) {
        ExtensionSlotFixtureState.WITH_PLUGIN -> listOf(contributionFor(slotId, ExtensionContributionStatus.READY))
        ExtensionSlotFixtureState.NO_PLUGIN -> emptyList()
        ExtensionSlotFixtureState.DISABLED -> listOf(contributionFor(slotId, ExtensionContributionStatus.DISABLED))
        ExtensionSlotFixtureState.ERROR -> listOf(contributionFor(slotId, ExtensionContributionStatus.ERROR))
    }

    fun sampleCard(slotId: String): FeatureUiCardModel = FeatureUiCardModel(
        slot = slotId,
        componentId = "fixture-card",
        template = "metric-card/v1",
        title = "计算机服务社维修预约",
        accessibilityLabel = "合成维修预约卡片",
        nodes = listOf(
            FeatureUiNode.TextNode("服务时间", "工作日 18:30–21:00"),
            FeatureUiNode.KeyValueNode("地点", "W111"),
            FeatureUiNode.MetricNode("排队", "3 人"),
            FeatureUiNode.BadgeNode("状态", "开放"),
            FeatureUiNode.ListNode("项目", "清灰保养；系统重装；硬件检测"),
            FeatureUiNode.ActionButtonNode("打开报名页", "openSignup")
        )
    )

    private fun nodeCard(componentId: String, node: FeatureUiNode): FeatureUiCardModel = FeatureUiCardModel(
        slot = "home.cards",
        componentId = componentId,
        template = "metric-card/v1",
        title = "节点 " + componentId,
        accessibilityLabel = "合成节点预览",
        nodes = listOf(node)
    )
}

@Composable
fun ExtensionUiPreviewHarness(
    darkTheme: Boolean = false,
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.width(393.dp)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Surface(modifier = Modifier.padding(10.dp)) {
                    content()
                }
            }
        }
    }
}
