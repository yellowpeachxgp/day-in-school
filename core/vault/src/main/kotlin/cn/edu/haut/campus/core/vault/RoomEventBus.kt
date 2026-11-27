package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.events.EventBus
import cn.edu.haut.campus.core.events.EventEnvelope
import cn.edu.haut.campus.core.events.OutboxEventRecord
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** 进程内 EventBus 门面；持久化和重启续投全部由 RoomEventOutbox 承担。 */
class RoomEventBus(
    val outbox: RoomEventOutbox
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
        val unregister = outbox.addLiveListener { record -> live.trySend(record) }
        try {
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
