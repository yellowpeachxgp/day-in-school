package cn.edu.haut.campus.core.uiruntime

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginInstaller
import cn.edu.haut.campus.core.packages.v2.PluginCommandDeclaration
import cn.edu.haut.campus.core.packages.v2.UiSlotDeclaration
import java.nio.charset.StandardCharsets

enum class ExtensionContributionStatus {
    READY,
    DISABLED,
    ERROR,
    PERMISSION_DENIED
}

/** 一个插件卡片在一个宿主槽位中的已门控渲染输入。 */
data class ExtensionUiContribution(
    val pluginId: String,
    val pluginName: String,
    val publisherId: String,
    val activeVersion: String,
    val slotId: String,
    val cardId: String,
    val status: ExtensionContributionStatus,
    val card: FeatureUiCardModel?,
    val errorMessage: String? = null,
    val commands: List<PluginCommandDeclaration> = emptyList(),
    val grantedPermissions: Set<String> = emptySet(),
    val allowedOrigins: Set<String> = emptySet()
)

data class ExtensionSlotPolicy(
    val slotId: String,
    val maxItems: Int,
    val maxNodes: Int,
    val maxHeightDp: Int
)

object ExtensionSlotPolicies {
    val all: Map<String, ExtensionSlotPolicy> = listOf(
        ExtensionSlotPolicy("home.cards", maxItems = 2, maxNodes = 8, maxHeightDp = 220),
        ExtensionSlotPolicy("campus.services", maxItems = 4, maxNodes = 6, maxHeightDp = 180),
        ExtensionSlotPolicy("library.actions", maxItems = 3, maxNodes = 5, maxHeightDp = 160),
        ExtensionSlotPolicy("parcel.cards", maxItems = 2, maxNodes = 6, maxHeightDp = 180),
        ExtensionSlotPolicy("parcel.providers", maxItems = 4, maxNodes = 5, maxHeightDp = 160),
        ExtensionSlotPolicy("settings.entries", maxItems = 3, maxNodes = 4, maxHeightDp = 150)
    ).associateBy { it.slotId }

    fun policyFor(slotId: String): ExtensionSlotPolicy? = all[slotId]
}

/**
 * 从 W6-01a 签名安装器加载插件 UI。每次读取都会重复验签与摘要校验；
 * 验签失败、模型非法、插件停用或缺少 ui.slot.render 授权时只产生占位状态。
 */
class ExtensionUiPackageLoader(
    private val installer: ExtensionPluginInstaller,
    private val schoolId: String
) {
    fun loadContributions(): List<ExtensionUiContribution> {
        val result = mutableListOf<ExtensionUiContribution>()
        for (installation in installer.snapshot().filter { it.schoolId == schoolId }) {
            val verified = runCatching {
                installer.verifiedStoredPackage(schoolId, installation.pluginId)
            }.getOrNull()
            val slots = verified?.manifest?.uiSlots ?: decodeUiSlotsJson(installation.uiSlotsJson)
            val normalizedCounts = slots.groupBy {
                it.slotId + "/" + FeatureUiCardCodec.componentIdForCardId(it.cardId)
            }.mapValues { entry -> entry.value.map { it.cardId }.toSet().size }
            val seenComponents = mutableSetOf<String>()
            for (slot in slots) {
                if (!seenComponents.add(slot.slotId + "/" + slot.cardId)) continue
                val normalizedCollision = normalizedCounts.getValue(
                    slot.slotId + "/" + FeatureUiCardCodec.componentIdForCardId(slot.cardId)
                ) > 1
                result += contributionFor(installation, slot, verified?.manifest, verified?.artifactMap, normalizedCollision)
            }
        }
        return result.sortedWith(compareBy<ExtensionUiContribution> { it.slotId }.thenBy { it.pluginId }.thenBy { it.cardId })
    }

    private fun contributionFor(
        installation: cn.edu.haut.campus.core.packages.v2.ExtensionInstallation,
        slot: UiSlotDeclaration,
        manifest: cn.edu.haut.campus.core.packages.v2.ExtensionManifestV2?,
        artifactMap: Map<String, ByteArray>?,
        normalizedCollision: Boolean
    ): ExtensionUiContribution {
        val baseCommands = manifest?.commands.orEmpty()
        val granted = installation.grantedPermissions.toSet()
        val origins = manifest?.allowedOrigins.orEmpty()
        fun copy(
            status: ExtensionContributionStatus,
            card: FeatureUiCardModel?,
            error: String? = null
        ) = ExtensionUiContribution(
            pluginId = installation.pluginId,
            pluginName = installation.name,
            publisherId = installation.publisherId,
            activeVersion = installation.activeVersion,
            slotId = slot.slotId,
            cardId = slot.cardId,
            status = status,
            card = card,
            errorMessage = error,
            commands = baseCommands,
            grantedPermissions = granted,
            allowedOrigins = origins
        )

        if (installation.status != "ENABLED") {
            val reason = when (installation.status) {
                "PENDING_PERMISSION_REVIEW" -> "插件等待新增权限确认"
                "DISABLED" -> installation.disabledReason ?: "插件已停用"
                else -> "插件状态不可渲染：" + installation.status
            }
            return copy(ExtensionContributionStatus.DISABLED, errorCard(slot, installation.name, "插件已停用", reason), reason)
        }
        if (manifest == null || artifactMap == null) {
            return copy(ExtensionContributionStatus.ERROR, errorCard(slot, installation.name, "插件验签失败", "签名或产物摘要在读取时未通过"), "签名或产物摘要在读取时未通过")
        }
        if (normalizedCollision) {
            val message = "card_id 规范化后发生冲突，已 fail-closed"
            return copy(ExtensionContributionStatus.ERROR, errorCard(slot, installation.name, "插件卡片冲突", message), message)
        }
        if ("ui.slot.render" !in granted) {
            val message = "缺少 ui.slot.render 授权"
            return copy(ExtensionContributionStatus.PERMISSION_DENIED, errorCard(slot, installation.name, "权限未授予", message), message)
        }

        val artifactPath = selectCardArtifact(slot, artifactMap.keys)
        val decoded = artifactPath?.let { path ->
            val bytes = artifactMap[path]
            if (bytes == null || bytes.size > MAX_CARD_ARTIFACT_BYTES) {
                FeatureUiCardCodec.DecodeResult.Invalid("卡片产物缺失或超过 " + MAX_CARD_ARTIFACT_BYTES + " 字节")
            } else {
                FeatureUiCardCodec.parse(String(bytes, StandardCharsets.UTF_8))
            }
        }
        val card = when (decoded) {
            null -> FeatureUiCardCodec.fallbackCard(slot.slotId, slot.cardId, slot.title, slot.subtitle, slot.actionCommand)
            is FeatureUiCardCodec.DecodeResult.Invalid -> {
                if (artifactPath == GENERIC_CARD_DATA_PATH) {
                    // W6-01b pack fixture uses assets/card-data.json as command data, not necessarily as a feature-ui card.
                    FeatureUiCardCodec.fallbackCard(slot.slotId, slot.cardId, slot.title, slot.subtitle, slot.actionCommand)
                } else {
                    return copy(
                        ExtensionContributionStatus.ERROR,
                        errorCard(slot, installation.name, "插件卡片模型无效", decoded.reason),
                        decoded.reason
                    )
                }
            }
            is FeatureUiCardCodec.DecodeResult.Valid -> {
                val expectedComponent = FeatureUiCardCodec.componentIdForCardId(slot.cardId)
                if (decoded.card.slot != slot.slotId || decoded.card.componentId != expectedComponent) {
                    if (artifactPath == GENERIC_CARD_DATA_PATH) {
                        FeatureUiCardCodec.fallbackCard(slot.slotId, slot.cardId, slot.title, slot.subtitle, slot.actionCommand)
                    } else {
                        val reason = "卡片产物 slot/component_id 与 manifest 不匹配"
                        return copy(ExtensionContributionStatus.ERROR, errorCard(slot, installation.name, "插件卡片不匹配", reason), reason)
                    }
                } else {
                    decoded.card
                }
            }
        }
        return copy(ExtensionContributionStatus.READY, card)
    }

    private fun selectCardArtifact(slot: UiSlotDeclaration, paths: Set<String>): String? {
        val base = FeatureUiCardCodec.componentIdForCardId(slot.cardId)
        val candidates = listOf(
            "cards/" + base + ".json",
            "cards/" + base + ".feature-ui.json",
            "ui/" + base + ".json",
            "assets/" + base + ".json",
            GENERIC_CARD_DATA_PATH
        )
        return candidates.firstOrNull { it in paths }
    }

    private fun errorCard(slot: UiSlotDeclaration, pluginName: String, label: String, message: String): FeatureUiCardModel =
        FeatureUiCardModel(
            slot = slot.slotId,
            componentId = FeatureUiCardCodec.componentIdForCardId(slot.cardId),
            template = "key-value-card/v1",
            title = pluginName,
            accessibilityLabel = message,
            nodes = listOf(FeatureUiNode.ErrorStateNode(label = label, value = message))
        )

    private fun decodeUiSlotsJson(raw: String): List<UiSlotDeclaration> = runCatching {
        Json.parse(raw).asArrOrNull?.items.orEmpty().mapNotNull { item ->
            val obj = item.asObjOrNull ?: return@mapNotNull null
            UiSlotDeclaration(
                slotId = obj.fields["slot_id"]?.stringOrNull() ?: return@mapNotNull null,
                cardId = obj.fields["card_id"]?.stringOrNull() ?: return@mapNotNull null,
                title = obj.fields["title"]?.stringOrNull() ?: "插件卡片",
                subtitle = obj.fields["subtitle"]?.stringOrNull().orEmpty(),
                actionCommand = obj.fields["action_command"]?.stringOrNull().orEmpty()
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_CARD_ARTIFACT_BYTES = 256 * 1024
        const val GENERIC_CARD_DATA_PATH = "assets/card-data.json"
    }
}
