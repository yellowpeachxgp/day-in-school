package cn.edu.haut.campus.core.events

/** outbox 中不可变的单条事件记录；sequence 只在单个 outbox 实例内单调递增。 */
data class OutboxEventRecord(
    val sequence: Long,
    val envelope: EventEnvelope
)

enum class EventAppendStatus {
    APPENDED,
    DUPLICATE_IDEMPOTENT
}

data class EventAppendResult(
    val status: EventAppendStatus,
    val record: OutboxEventRecord
)

class EventIdConflictException(eventId: String) :
    IllegalStateException("eventId 已存在但 envelope 内容不同: " + eventId)

class UnknownEventException(eventId: String) :
    IllegalArgumentException("不存在的 eventId: " + eventId)

/**
 * 事务型 outbox 的最小协议。
 *
 * W1-01a 只提供内存实现；业务写事务、Room 持久化和 DomainEvent 类型化映射留给 W1-01b。
 * ack 按 consumer group 记录：同组任意消费者确认后，该组后续 backlog 不再包含此事件。
 */
interface EventOutbox {
    suspend fun append(envelope: EventEnvelope): EventAppendResult
    suspend fun pendingUnacked(consumerGroup: String): List<OutboxEventRecord>
    suspend fun acknowledge(consumerGroup: String, eventId: String): Boolean
    suspend fun isAcked(consumerGroup: String, eventId: String): Boolean
    suspend fun unackedCount(consumerGroup: String): Int
}
