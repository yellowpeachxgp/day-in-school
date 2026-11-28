package cn.edu.haut.campus.core.vault

import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import cn.edu.haut.campus.core.database.DomainOutboxEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.events.CanonicalEventEnvelopeCodec
import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.events.EventAppendResult
import cn.edu.haut.campus.core.events.EventAppendStatus
import cn.edu.haut.campus.core.events.EventEnvelope
import cn.edu.haut.campus.core.events.EventIdConflictException
import cn.edu.haut.campus.core.events.EventOutbox
import cn.edu.haut.campus.core.events.OutboxEventRecord
import cn.edu.haut.campus.core.events.UnknownEventException
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Room 实现的 at-least-once outbox；sequence 由 SQLite 单调分配，ack 按 consumer group 持久化。
 *
 * live 通知不跟随 append 直接发出，而由 Room InvalidationTracker 在最外层事务 endTransaction
 * 之后触发。Rollback 会一并回滚 invalidation shadow table，因此未提交事件不会通知进程内消费者；
 * 已提交事件按 sequence 扫描去重，嵌套事务也只有最外层提交后才可见。
 *
 * 生命周期契约：注册用的是公开 API [InvalidationTracker.addObserver]，tracker 对 observer
 * 是强引用持有，observer 又反向强引用本实例，因此本实例不再使用时必须调用 [close]——
 * close 会 removeObserver 并清空 live listener，防止废弃门面被长寿命数据库的 tracker 钉住
 * （同一数据库重建门面、测试或未来按域拆分场景）。close 幂等；close 之后所有读写操作
 * fail-closed 抛出 [IllegalStateException]，也不再产生任何 live 通知。进程内生产路径
 * 一个账号库只建一个门面且与库同寿命（库随进程存活，无更早 teardown 路径），无需额外接线。
 */
class RoomEventOutbox(
    private val database: HautDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val domainEventCodecRegistry: DomainEventCodecRegistry = DomainEventCodecRegistry.DEFAULT
) : EventOutbox, AutoCloseable {
    private val dao = database.vaultDao()
    private val nextListenerId = AtomicLong(0)
    private val listeners = ConcurrentHashMap<Long, (OutboxEventRecord) -> Unit>()
    private val flushLock = Any()
    private val closed = AtomicBoolean(false)
    private var liveFloorInitialized = false
    private var lastScannedSequence = 0L

    private val invalidationObserver = object : InvalidationTracker.Observer("domain_outbox") {
        override fun onInvalidated(tables: Set<String>) {
            if ("domain_outbox" in tables) flushCommittedOutbox()
        }
    }

    init {
        // 公开 API addObserver 为强引用持有；废弃实例的泄漏防护由 close() 的
        // removeObserver 承担，见类文档生命周期契约。
        database.invalidationTracker.addObserver(invalidationObserver)
    }

    override suspend fun append(envelope: EventEnvelope): EventAppendResult = appendDeferred(envelope)

    /**
     * 只持久化不立即通知 live listener。提交后的可见性通知由 Room invalidation 回调统一触发，
     * 因此 direct append、journal 写入和嵌套 Room 事务都遵循同一条最外层提交边界。
     */
    internal suspend fun appendDeferred(envelope: EventEnvelope): EventAppendResult {
        checkOpen()
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
        checkOpen()
        requireValidConsumerGroup(consumerGroup)
        return dao.getOutboxSnapshot()
            .filter { entity -> consumerGroup !in decodeGroups(entity.ackedConsumerGroupsJson) }
            .map { entity -> OutboxEventRecord(entity.sequence, envelopeOf(entity)) }
    }

    override suspend fun acknowledge(consumerGroup: String, eventId: String): Boolean {
        checkOpen()
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
        checkOpen()
        requireValidConsumerGroup(consumerGroup)
        val entity = dao.getOutboxByEventId(eventId) ?: return false
        return consumerGroup in decodeGroups(entity.ackedConsumerGroupsJson)
    }

    override suspend fun unackedCount(consumerGroup: String): Int {
        checkOpen()
        requireValidConsumerGroup(consumerGroup)
        return dao.getOutboxSnapshot().count { entity ->
            consumerGroup !in decodeGroups(entity.ackedConsumerGroupsJson)
        }
    }

    internal suspend fun addLiveListener(listener: (OutboxEventRecord) -> Unit): () -> Unit {
        checkOpen()
        ensureLiveFloorInitialized()
        val id = nextListenerId.incrementAndGet()
        listeners[id] = listener
        return { listeners.remove(id) }
    }

    /**
     * 订阅开始前把 high-water mark 放到当前已提交最大值。历史未 ack 行仍由 backlog 读取负责；
     * live 扫描只承担注册之后新提交的事件，避免把 backlog 重复推成 live event。
     */
    private suspend fun ensureLiveFloorInitialized() {
        val initialized = synchronized(flushLock) { liveFloorInitialized }
        if (initialized) return
        val maxCommittedSequence = dao.maxOutboxSequence()
        synchronized(flushLock) {
            if (!liveFloorInitialized) {
                lastScannedSequence = maxOf(lastScannedSequence, maxCommittedSequence)
                liveFloorInitialized = true
            }
        }
    }

    /**
     * 从 InvalidationTracker 摘除强引用 observer 并清空 live listener。幂等。
     * 返回后本实例不再产生任何 live 通知；已进入临界区的当次 flush 可能在 close
     * 返回前完成其回调，属于 close 之前已开始的通知，不计入关闭后通知。
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        database.invalidationTracker.removeObserver(invalidationObserver)
        synchronized(flushLock) {
            listeners.clear()
        }
    }

    private fun checkOpen() {
        check(!closed.get()) { "RoomEventOutbox 已关闭，禁止继续读写" }
    }

    private fun flushCommittedOutbox() {
        synchronized(flushLock) {
            // close() 在同一锁内清空 listener；进入临界区前先确认未关闭，
            // 保证 close 返回后不再产生任何 live 通知。
            if (closed.get()) return
            if (!liveFloorInitialized) {
                // 还没有 live 订阅时只推进 high-water mark；历史事件由订阅 backlog 负责，避免重放为 live。
                lastScannedSequence = maxOf(lastScannedSequence, dao.maxOutboxSequenceBlocking())
                liveFloorInitialized = true
                return
            }
            val committedRows = dao.getOutboxAfterBlocking(lastScannedSequence)
            if (committedRows.isEmpty()) return
            lastScannedSequence = maxOf(lastScannedSequence, committedRows.maxOf { it.sequence })
            committedRows.forEach { row ->
                notifyAppended(OutboxEventRecord(row.sequence, envelopeOf(row)))
            }
        }
    }

    private fun notifyAppended(record: OutboxEventRecord) {
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
