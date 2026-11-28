package cn.edu.haut.campus.core.uiruntime

/** feature-ui-v1 的运行时卡片模型。模型只含声明式字符串与命令名，绝不包含可执行代码。 */
data class FeatureUiCardModel(
    val slot: String,
    val componentId: String,
    val template: String,
    val title: String,
    val accessibilityLabel: String?,
    val nodes: List<FeatureUiNode>
)

sealed interface FeatureUiNode {
    val label: String
    val value: String?
    val command: String?

    data class TextNode(
        override val label: String,
        override val value: String? = null
    ) : FeatureUiNode {
        override val command: String? = null
    }

    data class MetricNode(
        override val label: String,
        override val value: String? = null
    ) : FeatureUiNode {
        override val command: String? = null
    }

    data class KeyValueNode(
        override val label: String,
        override val value: String? = null
    ) : FeatureUiNode {
        override val command: String? = null
    }

    data class BadgeNode(
        override val label: String,
        override val value: String? = null
    ) : FeatureUiNode {
        override val command: String? = null
    }

    data class ActionButtonNode(
        override val label: String,
        override val command: String,
        override val value: String? = null
    ) : FeatureUiNode

    data class ListNode(
        override val label: String,
        override val value: String? = null
    ) : FeatureUiNode {
        override val command: String? = null
    }

    data class EmptyStateNode(
        override val label: String,
        override val value: String? = null
    ) : FeatureUiNode {
        override val command: String? = null
    }

    data class ErrorStateNode(
        override val label: String,
        override val value: String? = null
    ) : FeatureUiNode {
        override val command: String? = null
    }

    /** Schema 已保留但首批渲染器未实现的节点，或前向兼容出现的未知节点。 */
    data class UnsupportedNode(
        val kind: String,
        override val label: String,
        override val value: String? = null,
        override val command: String? = null,
        val reason: String = "首批渲染器暂不支持该节点"
    ) : FeatureUiNode
}
