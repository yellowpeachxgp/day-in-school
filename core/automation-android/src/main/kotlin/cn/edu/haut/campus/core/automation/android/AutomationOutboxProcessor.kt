package cn.edu.haut.campus.core.automation.android

import cn.edu.haut.campus.core.automation.AutomationEngine
import cn.edu.haut.campus.core.automation.AutomationRuleStore
import cn.edu.haut.campus.core.automation.BuiltinTriggerPlanner
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.EventOutbox
import cn.edu.haut.campus.core.model.Actor
import kotlinx.coroutines.CancellationException

/** domain_outbox 的自动化消费者；ack 只发生在全部匹配规则进入引擎幂等终局之后。 */
class AutomationOutboxProcessor(
    private val outbox: EventOutbox,
    private val ruleStore: AutomationRuleStore,
    private val engine: AutomationEngine,
    private val codecRegistry: DomainEventCodecRegistry = DomainEventCodecRegistry.DEFAULT,
    private val consumerGroup: String = AutomationRuntime.CONSUMER_GROUP
) {
    suspend fun consumeOnce(): Int {
        val pending = outbox.pendingUnacked(consumerGroup)
        if (pending.isEmpty()) return 0
        val rules = ruleStore.list().filter { it.enabled }
        var consumed = 0
        for (record in pending) {
            val event = codecRegistry.decode(record.envelope)
            if (event is DomainEvent.VaultEntityChanged && event.actor == Actor.AUTOMATION) {
                // 自动化写入产生的新事件不得反向触发自动化；否则“创建日程→vault 事件→再创建”会形成后台反馈环。
                outbox.acknowledge(consumerGroup, event.eventId)
                consumed++
                continue
            }
            val instances = rules.mapNotNull { rule ->
                BuiltinTriggerPlanner.instanceForDomainEvent(rule, event)?.let { instance -> rule to instance }
            }
            var failureCause: Throwable? = null
            for ((_, instance) in instances) {
                try {
                    engine.executeTrigger(instance)
                } catch (error: Throwable) {
                    if (error is CancellationException || error is Error) throw error
                    failureCause = error
                    break
                }
            }
            val failed = failureCause != null
            if (failed) throw AutomationOutboxConsumptionException(event.eventId, failureCause)
            outbox.acknowledge(consumerGroup, event.eventId)
            consumed++
        }
        return consumed
    }
}
