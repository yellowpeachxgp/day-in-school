package cn.edu.haut.campus.core.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * MASTER_PLAN §6.2 语义下的类型化事件门面。
 *
 * 底层仍复用 W1-01a 的 EventEnvelope outbox 与 EventBus 投递机制；本类只做 codec 映射，不改变
 * at-least-once、按 eventId 幂等、consumer group ack 与重建续投语义。未知类型/版本在收集时 fail-closed 抛出。
 */
class DomainEventBus(
    private val delegate: EventBus,
    private val registry: DomainEventCodecRegistry = DomainEventCodecRegistry.DEFAULT
) {
    suspend fun publish(event: DomainEvent) {
        delegate.publish(registry.encode(event))
    }

    fun subscribe(
        consumerGroup: String,
        filter: (DomainEvent) -> Boolean = { true }
    ): Flow<DomainEvent> = delegate.subscribe(consumerGroup)
        .map { envelope -> registry.decode(envelope) }
        .filter(filter)

    suspend fun ack(consumerGroup: String, eventId: String) {
        delegate.ack(consumerGroup, eventId)
    }
}
