package cn.edu.haut.campus.core.events

import java.time.Instant

/**
 * W1-01a 的通用事件 envelope。
 *
 * scopeKey 只是不透明字符串；DomainEventCodecRegistry 对类型化事件使用
 * schoolId/accountId/profileId[/campusId] 的稳定格式，通用 envelope 本身仍不依赖 core/model。
 */
data class EventEnvelope(
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAt: Instant,
    val scopeKey: String?,
    val payload: CanonicalJson,
    val metadata: Map<String, String> = emptyMap()
) {
    init {
        require(eventId.isNotBlank()) { "eventId 不能为空" }
        require(eventType.isNotBlank()) { "eventType 不能为空" }
        require(schemaVersion > 0) { "schemaVersion 必须大于 0" }
        require(scopeKey == null || scopeKey.isNotBlank()) { "scopeKey 不能是空白字符串" }
        require(metadata.keys.all { it.isNotBlank() }) { "metadata 键不能为空" }
    }
}
