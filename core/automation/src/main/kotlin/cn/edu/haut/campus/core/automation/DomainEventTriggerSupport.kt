package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * DomainEvent 触发支持。outbox 是 at-least-once；因此事件触发的实例键固定由 eventId 派生，
 * 消费者重启或重复投递后进入 AutomationEngine 仍会得到同一个 dedupeKey。
 */
object DomainEventTriggerSupport {
    const val EVENT_INSTANCE_PREFIX: String = "domain-event:"

    fun triggerInstanceKey(event: DomainEvent): String = EVENT_INSTANCE_PREFIX + event.eventId

    fun triggerInstance(ruleId: String, event: DomainEvent): TriggerInstance = TriggerInstance(
        ruleId = ruleId,
        triggerInstanceKey = triggerInstanceKey(event),
        firedAt = event.occurredAt,
        event = event,
        source = "domain-event"
    )

    /**
     * OnEvent.filter 是“对象子集”匹配：filter 中声明的字段必须递归存在且相等；
     * payload 多出的字段不影响匹配。数组与标量使用规范化 JSON 相等。
     */
    fun matches(
        spec: TriggerSpec.OnEvent,
        event: DomainEvent,
        registry: DomainEventCodecRegistry = DomainEventCodecRegistry.DEFAULT
    ): Boolean = inconsistencyReason(spec, event, registry) == null

    /**
     * 事件与 OnEvent 规则声明的一致性校验。返回 null 表示事件类型与 filter 都满足规则声明；
     * 否则返回不一致原因。原因只携带事件类型名与声明类型名，不携带 payload 内容，
     * 可以安全进入异常消息与审计日志。
     */
    fun inconsistencyReason(
        spec: TriggerSpec.OnEvent,
        event: DomainEvent,
        registry: DomainEventCodecRegistry = DomainEventCodecRegistry.DEFAULT
    ): String? {
        val envelope = registry.encode(event)
        if (envelope.eventType != spec.eventType) {
            return "事件类型 " + envelope.eventType + " 与规则声明的 eventType " + spec.eventType + " 不一致"
        }
        val filter = spec.filter ?: return null
        val payload = Json.parse(envelope.payload.text)
        return if (jsonSubsetMatches(filter, payload)) null else "事件 payload 不满足规则声明的 filter"
    }

    private fun jsonSubsetMatches(expected: JsonValue, actual: JsonValue): Boolean {
        if (expected is JsonValue.Obj) {
            val actualObject = actual as? JsonValue.Obj ?: return false
            return expected.fields.all { (key, expectedChild) ->
                val actualChild = actualObject.fields[key]
                actualChild != null && jsonSubsetMatches(expectedChild, actualChild)
            }
        }
        return jsonEquals(expected, actual)
    }

    private fun jsonEquals(left: JsonValue, right: JsonValue): Boolean {
        if (left == right) return true
        val leftCanonical = runCatching { Json.canonical(left) }.getOrNull()
        val rightCanonical = runCatching { Json.canonical(right) }.getOrNull()
        return leftCanonical != null && leftCanonical == rightCanonical
    }
}
