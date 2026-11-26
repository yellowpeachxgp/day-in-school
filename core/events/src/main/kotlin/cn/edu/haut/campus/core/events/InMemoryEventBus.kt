package cn.edu.haut.campus.core.events

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** 内存事件总线。把同一个 outbox 传给新实例即可模拟进程/对象重建后的未 ack 续投。 */
class InMemoryEventBus(
    val outbox: InMemoryEventOutbox = InMemoryEventOutbox()
) : EventBus {
    override suspend fun publish(event: EventEnvelope) {
        outbox.append(event)
    }

    override fun subscribe(
        consumerGroup: String,
        filter: (EventEnvelope) -> Boolean
    ): Flow<EventEnvelope> = flow {
        require(consumerGroup.isNotBlank()) { "consumerGroup 不能为空" }
        val seenSequences = mutableSetOf<Long>()
        val live = Channel<OutboxEventRecord>(capacity = Channel.UNLIMITED)
        val unregister = outbox.addLiveListener { record ->
            live.trySend(record)
        }
        try {
            // 先注册 live listener 再读 backlog；重叠事件用 sequence 去重，但不会丢事件。
            for (record in outbox.pendingUnacked(consumerGroup)) {
                emitIfNeeded(record, consumerGroup, filter, seenSequences)
            }
            while (true) {
                val record = live.receive()
                emitIfNeeded(record, consumerGroup, filter, seenSequences)
            }
        } finally {
            unregister()
            live.cancel()
        }
    }

    override suspend fun ack(consumerGroup: String, eventId: String) {
        require(consumerGroup.isNotBlank()) { "consumerGroup 不能为空" }
        outbox.acknowledge(consumerGroup, eventId)
    }

    private suspend fun FlowCollector<EventEnvelope>.emitIfNeeded(
        record: OutboxEventRecord,
        consumerGroup: String,
        filter: (EventEnvelope) -> Boolean,
        seenSequences: MutableSet<Long>
    ) {
        if (!seenSequences.add(record.sequence)) return
        if (!filter(record.envelope)) return
        if (outbox.isAcked(consumerGroup, record.envelope.eventId)) return
        emit(record.envelope)
    }
}
