package cn.edu.haut.campus.core.uiruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureUiCardCodecTest {
    @Test
    fun parsesFirstBatchNodesAndUnknownNodeAsUnsupported() {
        val json = """
            {
              "slot": "home.cards",
              "component_id": "demo-card",
              "template": "action-list/v1",
              "title": "合成插件卡片",
              "accessibility_label": "合成预览",
              "nodes": [
                {"kind":"Text","label":"文本","value":"正文"},
                {"kind":"Metric","label":"指标","value":"42"},
                {"kind":"KeyValue","label":"地点","value":"W111"},
                {"kind":"Badge","label":"状态","value":"开放"},
                {"kind":"ActionButton","label":"打开","command":"open"},
                {"kind":"List","label":"项目","value":"Alpha;Beta"},
                {"kind":"EmptyState","label":"空","value":"暂无"},
                {"kind":"ErrorState","label":"错","value":"失败"},
                {"kind":"FutureNode","label":"未来","value":"?"}
              ]
            }
        """.trimIndent()

        val result = FeatureUiCardCodec.parse(json)

        val card = (result as FeatureUiCardCodec.DecodeResult.Valid).card
        assertEquals(9, card.nodes.size)
        assertTrue(card.nodes[0] is FeatureUiNode.TextNode)
        assertTrue(card.nodes[1] is FeatureUiNode.MetricNode)
        assertTrue(card.nodes[2] is FeatureUiNode.KeyValueNode)
        assertTrue(card.nodes[3] is FeatureUiNode.BadgeNode)
        assertTrue(card.nodes[4] is FeatureUiNode.ActionButtonNode)
        assertTrue(card.nodes[5] is FeatureUiNode.ListNode)
        assertTrue(card.nodes[6] is FeatureUiNode.EmptyStateNode)
        assertTrue(card.nodes[7] is FeatureUiNode.ErrorStateNode)
        assertTrue(card.nodes[8] is FeatureUiNode.UnsupportedNode)
    }

    @Test
    fun malformedOrBoundaryViolatingCardsFailClosed() {
        assertTrue(FeatureUiCardCodec.parse("[]") is FeatureUiCardCodec.DecodeResult.Invalid)
        assertTrue(FeatureUiCardCodec.parse("""{"slot":"home.cards"}""") is FeatureUiCardCodec.DecodeResult.Invalid)
        val tooManyNodes = buildString {
            append("""{"slot":"home.cards","component_id":"demo","template":"metric-card/v1","title":"t","nodes":[""")
            repeat(21) { index ->
                if (index > 0) append(',')
                append("""{"kind":"Text","label":"x"}""")
            }
            append("]}")
        }
        assertTrue(FeatureUiCardCodec.parse(tooManyNodes) is FeatureUiCardCodec.DecodeResult.Invalid)
    }

    @Test
    fun manifestCardIdsNormalizeToFeatureUiComponentIdsDeterministically() {
        assertEquals("hello-card", FeatureUiCardCodec.componentIdForCardId("hello.card"))
        assertEquals("a-b-c", FeatureUiCardCodec.componentIdForCardId("A..B__c"))
        assertEquals("extension-card", FeatureUiCardCodec.componentIdForCardId("..."))
    }
}
