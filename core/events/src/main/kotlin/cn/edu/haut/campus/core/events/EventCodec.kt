package cn.edu.haut.campus.core.events

import java.time.Instant
import java.time.format.DateTimeParseException

/** 通用 payload 编解码器；§6.2 DomainEvent 使用 DomainEventCodecRegistry 做类型化注册。 */
interface EventCodec<T> {
    val eventType: String
    val schemaVersion: Int
    fun encodePayload(value: T): CanonicalJson
    fun decodePayload(payload: CanonicalJson): T
}

/** envelope 线格式编解码器。 */
interface EventEnvelopeCodec {
    fun encode(envelope: EventEnvelope): CanonicalJson
    fun decode(encoded: CanonicalJson): EventEnvelope
}

/** 基于 CanonicalJson 的确定性 envelope 编解码。 */
object CanonicalEventEnvelopeCodec : EventEnvelopeCodec {
    private val allowedKeys = setOf(
        "eventId",
        "eventType",
        "metadata",
        "occurredAt",
        "payload",
        "schemaVersion",
        "scopeKey"
    )

    override fun encode(envelope: EventEnvelope): CanonicalJson {
        val metadata = JsonValue.Obj(envelope.metadata.mapValues { (_, value) -> JsonValue.Str(value) })
        val fields = linkedMapOf(
            "eventId" to JsonValue.Str(envelope.eventId),
            "eventType" to JsonValue.Str(envelope.eventType),
            "schemaVersion" to JsonValue.Number(envelope.schemaVersion.toString()),
            "occurredAt" to JsonValue.Str(envelope.occurredAt.toString()),
            "scopeKey" to (envelope.scopeKey?.let { JsonValue.Str(it) } ?: JsonValue.Null),
            "payload" to envelope.payload.toJsonValue(),
            "metadata" to metadata
        )
        return CanonicalJson.ofValue(JsonValue.Obj(fields))
    }

    override fun decode(encoded: CanonicalJson): EventEnvelope {
        val root = encoded.toJsonValue() as? JsonValue.Obj
            ?: throw IllegalArgumentException("event envelope 必须是 JSON object")
        if (root.entries.keys != allowedKeys) {
            throw IllegalArgumentException("event envelope 字段集合不匹配: " + root.entries.keys.sorted())
        }
        val eventId = requiredString(root, "eventId")
        val eventType = requiredString(root, "eventType")
        val occurredAtText = requiredString(root, "occurredAt")
        val occurredAt = try {
            Instant.parse(occurredAtText)
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("event envelope occurredAt 不是 ISO Instant", error)
        }
        val schemaText = requiredNumber(root, "schemaVersion")
        val schemaVersion = schemaText.toIntOrNull()
            ?: throw IllegalArgumentException("event envelope schemaVersion 必须是整数字面量")
        val scopeValue = root.entries.getValue("scopeKey")
        val scopeKey = when (scopeValue) {
            JsonValue.Null -> null
            is JsonValue.Str -> scopeValue.value
            else -> throw IllegalArgumentException("event envelope scopeKey 必须是 string 或 null")
        }
        val metadataValue = root.entries.getValue("metadata") as? JsonValue.Obj
            ?: throw IllegalArgumentException("event envelope metadata 必须是 object")
        val metadata = metadataValue.entries.mapValues { (_, value) ->
            (value as? JsonValue.Str)?.value
                ?: throw IllegalArgumentException("event envelope metadata 值必须是 string")
        }
        val payloadValue = root.entries.getValue("payload")
        return EventEnvelope(
            eventId = eventId,
            eventType = eventType,
            schemaVersion = schemaVersion,
            occurredAt = occurredAt,
            scopeKey = scopeKey,
            payload = CanonicalJson.ofValue(payloadValue),
            metadata = metadata
        )
    }

    private fun requiredString(root: JsonValue.Obj, key: String): String {
        val value = root.entries[key] as? JsonValue.Str
            ?: throw IllegalArgumentException("event envelope 缺少 string 字段 " + key)
        return value.value
    }

    private fun requiredNumber(root: JsonValue.Obj, key: String): String {
        val value = root.entries[key] as? JsonValue.Number
            ?: throw IllegalArgumentException("event envelope 缺少 number 字段 " + key)
        return value.lexeme
    }
}
