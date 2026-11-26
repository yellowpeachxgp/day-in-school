package cn.edu.haut.campus.core.events

/** 线程安全的内存 outbox；同一实例可在重建 EventBus 后继续持有未 ack 状态。 */
class InMemoryEventOutbox : EventOutbox {
    private val lock = Any()
    private val records = ArrayList<OutboxEventRecord>()
    private val recordsByEventId = LinkedHashMap<String, OutboxEventRecord>()
    private val ackedEventIdsByGroup = LinkedHashMap<String, MutableSet<String>>()
    private val listeners = LinkedHashMap<Long, (OutboxEventRecord) -> Unit>()
    private var nextSequence = 0L
    private var nextListenerId = 0L

    override suspend fun append(envelope: EventEnvelope): EventAppendResult {
        val outcome: EventAppendResult
        val callbacks: List<(OutboxEventRecord) -> Unit>
        synchronized(lock) {
            val existing = recordsByEventId[envelope.eventId]
            if (existing != null) {
                if (existing.envelope != envelope) throw EventIdConflictException(envelope.eventId)
                return EventAppendResult(EventAppendStatus.DUPLICATE_IDEMPOTENT, existing)
            }
            nextSequence += 1
            val record = OutboxEventRecord(sequence = nextSequence, envelope = envelope)
            records += record
            recordsByEventId[envelope.eventId] = record
            outcome = EventAppendResult(EventAppendStatus.APPENDED, record)
            callbacks = listeners.values.toList()
        }
        for (callback in callbacks) callback(outcome.record)
        return outcome
    }

    override suspend fun pendingUnacked(consumerGroup: String): List<OutboxEventRecord> {
        requireValidConsumerGroup(consumerGroup)
        synchronized(lock) {
            val acked = ackedEventIdsByGroup[consumerGroup].orEmpty()
            return records.filter { it.envelope.eventId !in acked }.sortedBy { it.sequence }
        }
    }

    override suspend fun acknowledge(consumerGroup: String, eventId: String): Boolean {
        requireValidConsumerGroup(consumerGroup)
        require(eventId.isNotBlank()) { "eventId 不能为空" }
        synchronized(lock) {
            if (!recordsByEventId.containsKey(eventId)) throw UnknownEventException(eventId)
            val acked = ackedEventIdsByGroup.getOrPut(consumerGroup) { LinkedHashSet() }
            return acked.add(eventId)
        }
    }

    override suspend fun isAcked(consumerGroup: String, eventId: String): Boolean {
        requireValidConsumerGroup(consumerGroup)
        synchronized(lock) {
            return ackedEventIdsByGroup[consumerGroup]?.contains(eventId) == true
        }
    }

    override suspend fun unackedCount(consumerGroup: String): Int {
        requireValidConsumerGroup(consumerGroup)
        synchronized(lock) {
            val acked = ackedEventIdsByGroup[consumerGroup].orEmpty()
            return records.count { it.envelope.eventId !in acked }
        }
    }

    internal fun addLiveListener(listener: (OutboxEventRecord) -> Unit): () -> Unit {
        val id: Long
        synchronized(lock) {
            nextListenerId += 1
            id = nextListenerId
            listeners[id] = listener
        }
        return {
            synchronized(lock) {
                listeners.remove(id)
            }
        }
    }

    private fun requireValidConsumerGroup(consumerGroup: String) {
        require(consumerGroup.isNotBlank()) { "consumerGroup 不能为空" }
    }
}
