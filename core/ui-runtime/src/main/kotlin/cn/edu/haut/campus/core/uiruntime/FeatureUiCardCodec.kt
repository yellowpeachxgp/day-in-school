package cn.edu.haut.campus.core.uiruntime

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/** feature-ui-v1 声明式卡片解码器；未知节点降级为 UnsupportedNode，而不是让宿主崩溃。 */
object FeatureUiCardCodec {
    val RUNTIME_UI_SLOTS = setOf(
        "home.cards",
        "campus.services",
        "library.actions",
        "parcel.cards",
        "parcel.providers",
        "settings.entries"
    )

    private val CONTRACT_LEGACY_SLOTS = setOf(
        "home.bento.primary",
        "home.bento.secondary",
        "campus.services.grid",
        "academic.overview.panel",
        "timetable.detail.action",
        "library.booking.preference",
        "profile.extensions",
        "settings.integrations",
        "widget.generic.card",
        "console.dashboard.widget",
        "console.feature.page",
        "parcel.sources",
        "parcel.details",
        "parcel.settings"
    )

    private val TEMPLATES = setOf("metric-card/v1", "key-value-card/v1", "action-list/v1", "query-form/v1")
    private val IMPLEMENTED_KINDS = setOf("Text", "Metric", "KeyValue", "Badge", "ActionButton", "List", "EmptyState", "ErrorState")
    private val RESERVED_KINDS = setOf("Field", "Form", "Chart")
    private val COMMAND_ID = Regex("^[a-z0-9._-]+$")
    private val COMPONENT_ID = Regex("^[a-z0-9-]+$")
    private val SLOT_ID = Regex("^[a-z0-9][a-z0-9.-]{1,119}$")
    private val TOP_LEVEL_KEYS = setOf("slot", "component_id", "template", "title", "accessibility_label", "nodes")
    private val NODE_KEYS = setOf("kind", "label", "value", "command")

    sealed interface DecodeResult {
        data class Valid(val card: FeatureUiCardModel) : DecodeResult
        data class Invalid(val reason: String) : DecodeResult
    }

    fun parse(jsonText: String): DecodeResult {
        val root = try {
            Json.parse(jsonText).asObjOrNull ?: return DecodeResult.Invalid("卡片模型顶层必须是 JSON 对象")
        } catch (error: Exception) {
            return DecodeResult.Invalid("卡片模型 JSON 解析失败：" + (error.message ?: error.javaClass.simpleName))
        }
        val unknownKeys = root.fields.keys - TOP_LEVEL_KEYS
        if (unknownKeys.isNotEmpty()) return DecodeResult.Invalid("卡片模型包含未知字段：" + unknownKeys.sorted().joinToString(","))

        val slot = requiredString(root, "slot") ?: return DecodeResult.Invalid("卡片模型缺少 slot")
        if (!SLOT_ID.matches(slot) || (slot !in RUNTIME_UI_SLOTS && slot !in CONTRACT_LEGACY_SLOTS)) {
            return DecodeResult.Invalid("卡片模型 slot 不受支持：" + slot)
        }
        val componentId = requiredString(root, "component_id") ?: return DecodeResult.Invalid("卡片模型缺少 component_id")
        if (!COMPONENT_ID.matches(componentId) || componentId.length > 80) {
            return DecodeResult.Invalid("卡片模型 component_id 格式非法")
        }
        val template = requiredString(root, "template") ?: return DecodeResult.Invalid("卡片模型缺少 template")
        if (template !in TEMPLATES) return DecodeResult.Invalid("卡片模型 template 不受支持：" + template)
        val title = requiredString(root, "title") ?: return DecodeResult.Invalid("卡片模型缺少 title")
        if (title.isBlank() || title.length > 60) return DecodeResult.Invalid("卡片模型 title 长度必须为 1..60")
        val accessibilityLabel = root.fields["accessibility_label"]?.stringOrNull()
        if (accessibilityLabel != null && accessibilityLabel.length > 120) {
            return DecodeResult.Invalid("卡片模型 accessibility_label 超过 120 字")
        }
        val nodeValues = root.fields["nodes"]?.asArrOrNull?.items ?: return DecodeResult.Invalid("卡片模型缺少 nodes 数组")
        if (nodeValues.size > 20) return DecodeResult.Invalid("卡片模型 nodes 超过 20 个")
        val nodes = mutableListOf<FeatureUiNode>()
        for ((index, item) in nodeValues.withIndex()) {
            val obj = item.asObjOrNull ?: return DecodeResult.Invalid("nodes[" + index + "] 必须是对象")
            val unknownNodeKeys = obj.fields.keys - NODE_KEYS
            if (unknownNodeKeys.isNotEmpty()) {
                return DecodeResult.Invalid("nodes[" + index + "] 包含未知字段：" + unknownNodeKeys.sorted().joinToString(","))
            }
            val kind = requiredString(obj, "kind") ?: return DecodeResult.Invalid("nodes[" + index + "] 缺少 kind")
            val label = requiredString(obj, "label") ?: return DecodeResult.Invalid("nodes[" + index + "] 缺少 label")
            if (label.length > 80) return DecodeResult.Invalid("nodes[" + index + "] label 超过 80 字")
            val value = obj.fields["value"]?.stringOrNull()
            if (value != null && value.length > 240) return DecodeResult.Invalid("nodes[" + index + "] value 超过 240 字")
            val command = obj.fields["command"]?.stringOrNull()
            if (command != null && !COMMAND_ID.matches(command)) return DecodeResult.Invalid("nodes[" + index + "] command 格式非法")
            nodes += when (kind) {
                "Text" -> FeatureUiNode.TextNode(label, value)
                "Metric" -> FeatureUiNode.MetricNode(label, value)
                "KeyValue" -> FeatureUiNode.KeyValueNode(label, value)
                "Badge" -> FeatureUiNode.BadgeNode(label, value)
                "ActionButton" -> {
                    if (command == null) return DecodeResult.Invalid("ActionButton 缺少 command")
                    FeatureUiNode.ActionButtonNode(label = label, command = command, value = value)
                }
                "List" -> FeatureUiNode.ListNode(label, value)
                "EmptyState" -> FeatureUiNode.EmptyStateNode(label, value)
                "ErrorState" -> FeatureUiNode.ErrorStateNode(label, value)
                in RESERVED_KINDS -> FeatureUiNode.UnsupportedNode(
                    kind = kind,
                    label = label,
                    value = value,
                    command = command,
                    reason = kind + " 节点已保留，首批渲染器尚未实现"
                )
                else -> FeatureUiNode.UnsupportedNode(
                    kind = kind,
                    label = label,
                    value = value,
                    command = command,
                    reason = "未知节点 " + kind + "，已按 ErrorState 降级"
                )
            }
        }
        return DecodeResult.Valid(
            FeatureUiCardModel(
                slot = slot,
                componentId = componentId,
                template = template,
                title = title,
                accessibilityLabel = accessibilityLabel,
                nodes = nodes
            )
        )
    }

    /** manifest 的 card_id 允许点号；feature-ui-v1 component_id 只允许小写字母数字和连字符。 */
    fun componentIdForCardId(cardId: String): String {
        val normalized = cardId.lowercase().map { ch ->
            if (ch in 'a'..'z' || ch in '0'..'9' || ch == '-') ch else '-'
        }.joinToString("").replace(Regex("-+"), "-").trim('-')
        return normalized.ifBlank { "extension-card" }.take(80)
    }

    /** 从已验签 manifest 的槽位声明合成最小声明式卡片；输入本身已经过签名与摘要校验。 */
    fun fallbackCard(
        slotId: String,
        cardId: String,
        title: String,
        subtitle: String,
        actionCommand: String
    ): FeatureUiCardModel {
        val normalizedCardId = componentIdForCardId(cardId)
        val nodes = buildList {
            add(FeatureUiNode.TextNode(label = title, value = subtitle.ifBlank { null }))
            if (actionCommand.isNotBlank()) {
                add(FeatureUiNode.ActionButtonNode(label = "打开", command = actionCommand))
            }
        }
        return FeatureUiCardModel(
            slot = slotId,
            componentId = normalizedCardId,
            template = "action-list/v1",
            title = title,
            accessibilityLabel = subtitle.ifBlank { null },
            nodes = nodes
        )
    }

    private fun requiredString(obj: JsonValue.Obj, key: String): String? = obj.fields[key]?.stringOrNull()
}
