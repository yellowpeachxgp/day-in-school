package cn.edu.haut.campus.core.events

import kotlinx.coroutines.flow.Flow

/** 通用事件总线；W1-01a 使用 EventEnvelope，不定义具体 DomainEvent。 */
interface EventBus {
    /** 发布事件；同一 eventId 的完全相同 envelope 幂等去重，不同内容必须失败。 */
    suspend fun publish(event: EventEnvelope)

    /**
     * 订阅一个 consumer group。
     *
     * 冷 Flow 每次收集都会先发送该 group 当前未 ack 的 backlog，再发送 live event；
     * 事件在被 ack 前不消失，因此订阅取消后重新收集会至少再收到一次未 ack 事件。
     */
    fun subscribe(consumerGroup: String, filter: (EventEnvelope) -> Boolean = { true }): Flow<EventEnvelope>

    /** 对 consumer group 确认事件；重复 ack 幂等。 */
    suspend fun ack(consumerGroup: String, eventId: String)
}
