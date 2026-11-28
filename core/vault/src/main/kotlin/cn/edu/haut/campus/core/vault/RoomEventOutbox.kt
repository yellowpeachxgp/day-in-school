package cn.edu.haut.campus.core.vault

import androidx.room.withTransaction
import cn.edu.haut.campus.core.database.DomainOutboxEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.events.CanonicalEventEnvelopeCodec
import cn.edu.haut.campus.core.events.EventAppendResult
import cn.edu.haut.campus.core.events.EventAppendStatus
import cn.edu.haut.campus.core.events.EventEnvelope
import cn.edu.haut.campus.core.events.EventIdConflictException
import cn.edu.haut.campus.core.events.EventOutbox
import cn.edu.haut.campus.core.events.OutboxEventRecord
import cn.edu.haut.campus.core.events.UnknownEventException
import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Room 实现的 at-least-once outbox；sequence 由 SQLite 单调分配，ack 按 consumer group 持久化。 */
class RoomEventOutbox(
    private val database: HautDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val domainEventCodecRegistry: DomainEventCodecRegistry = DomainEventCodecRegistry.DEFAULT
) : EventOutbox {
    private val dao = database.vaultDao()
    private val nextListenerId = AtomicLong(0)
    private val listeners = ConcurrentHashMap<Long, (OutboxEventRecord) -> Unit>()

    override suspend fun append(envelope: EventEnvelope): EventAppendResult {
        val outcome = appendDeferred(envelope)
        if (outcome.status == EventAppendStatus.APPENDED) notifyAppended(outcome.record)
        return outcome
    }

    /**
     * 只持久化不通知 live listener。RoomVaultJournal 在外层业务事务提交后统一补发通知，
     * 避免嵌套事务最终回滚却把未提交事件先推给进程内消费者。
     */
    internal suspend fun appendDeferred(envelope: EventEnvelope): EventAppendResult {
        // domain_outbox 是共享类型化总线，不是通用裸 envelope 通道。写入前必须能被 registry 解码，
        // 且解码后重新编码必须与待写 envelope 逐字段相同；否则未知类型、私造 schema、绕过 codec 的
        // payload 或敏感字段都不会进入共享表毒化下游消费者。纯内部审计只写 entity_journal，不进本表。
        val decoded = domainEventCodecRegistry.decode(envelope)
        val canonicalTypedEnvelope = domainEventCodecRegistry.encode(decoded)
        require(canonicalTypedEnvelope == envelope) {
            "domain_outbox 只接受 DomainEventCodecRegistry 规范编码的事件"
        }
        val encoded = CanonicalEventEnvelopeCodec.encode(envelope).text
        return database.withTransaction {
            val existing = dao.getOutboxByEventId(envelope.eventId)
            if (existing != null) {
                if (existing.envelopeJson != encoded) throw EventIdConflictException(envelope.eventId)
                EventAppendResult(
                    status = EventAppendStatus.DUPLICATE_IDEMPOTENT,
                    record = OutboxEventRecord(existing.sequence, envelope)
                )
            } else {
                val sequence = dao.insertOutbox(
                    DomainOutboxEntity(
                        eventId = envelope.eventId,
                        eventType = envelope.eventType,
                        schemaVersion = envelope.schemaVersion,
                        occurredAtIso = envelope.occurredAt.toString(),
                        scopeKey = envelope.scopeKey,
                        envelopeJson = encoded,
                        ackedConsumerGroupsJson = VaultStringListJsonCodec.encode(emptySet()),
                        createdAtEpochMs = clock()
                    )
                )
                EventAppendResult(
                    status = EventAppendStatus.APPENDED,
                    record = OutboxEventRecord(sequence, envelope)
                )
            }
        }
    }

    override suspend fun pendingUnacked(consumerGroup: String): List<OutboxEventRecord> {
        requireValidConsumerGroup(consumerGroup)
        return dao.getOutboxSnapshot()
            .filter { entity -> consumerGroup !in decodeGroups(entity.ackedConsumerGroupsJson) }
            .map { entity -> OutboxEventRecord(entity.sequence, envelopeOf(entity)) }
    }

    override suspend fun acknowledge(consumerGroup: String, eventId: String): Boolean {
        requireValidConsumerGroup(consumerGroup)
        require(eventId.isNotBlank()) { "eventId 不能为空" }
        return database.withTransaction {
            val existing = dao.getOutboxByEventId(eventId) ?: throw UnknownEventException(eventId)
            val groups = decodeGroups(existing.ackedConsumerGroupsJson)
            if (!groups.add(consumerGroup)) return@withTransaction false
            dao.updateOutboxAcks(eventId, VaultStringListJsonCodec.encode(groups))
            true
        }
    }

    override suspend fun isAcked(consumerGroup: String, eventId: String): Boolean {
        requireValidConsumerGroup(consumerGroup)
        val entity = dao.getOutboxByEventId(eventId) ?: return false
        return consumerGroup in decodeGroups(entity.ackedConsumerGroupsJson)
    }

    override suspend fun unackedCount(consumerGroup: String): Int {
        requireValidConsumerGroup(consumerGroup)
        return dao.getOutboxSnapshot().count { entity ->
            consumerGroup !in decodeGroups(entity.ackedConsumerGroupsJson)
        }
    }

    internal fun addLiveListener(listener: (OutboxEventRecord) -> Unit): () -> Unit {
        val id = nextListenerId.incrementAndGet()
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    internal fun notifyAppended(record: OutboxEventRecord) {
        listeners.values.forEach { callback -> runCatching { callback(record) } }
    }

    private fun envelopeOf(entity: DomainOutboxEntity): EventEnvelope =
        CanonicalEventEnvelopeCodec.decode(CanonicalJson.of(entity.envelopeJson))

    private fun decodeGroups(raw: String): MutableSet<String> =
        VaultStringListJsonCodec.decode(raw).toMutableSet()

    private fun requireValidConsumerGroup(consumerGroup: String) {
        require(consumerGroup.isNotBlank()) { "consumerGroup 不能为空" }
    }
}

internal object VaultStringListJsonCodec {
    fun encode(values: Set<String>): String =
        Json.canonical(JsonValue.Arr(values.sorted().map(JsonValue::Str)))

    fun decode(raw: String): Set<String> {
        val parsed = Json.parse(raw).asArrOrNull ?: throw IllegalArgumentException("consumer group JSON 必须是 array")
        return parsed.items.map { item ->
            item.stringOrNull() ?: throw IllegalArgumentException("consumer group JSON 元素必须是 string")
        }.toSet()
    }
}
