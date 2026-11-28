package cn.edu.haut.campus.core.vault

import androidx.room.withTransaction
import cn.edu.haut.campus.core.database.AcademicEntityWritePort
import cn.edu.haut.campus.core.database.EntityJournalEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.events.EventEnvelope
import cn.edu.haut.campus.core.events.EventOutbox
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SourceRef
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room 实现的 EntityWriter + ChangeJournal。
 *
 * 关键不变量：业务行、entity_journal 与 domain_outbox 全部在同一个 Room 事务中成功或一起回滚；
 * outbox 事件不复制 before/after 快照，只携带 changeId 与稳定引用，避免把大快照或敏感正文塞进事件。
 */
class RoomVaultJournal(
    private val database: HautDatabase,
    private val outbox: EventOutbox,
    snapshotStores: Map<EntityType, EntitySnapshotStore>,
    private val clock: () -> Instant = Instant::now,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val searchIndex: RoomSearchIndex? = null
) : EntityWriter, ChangeJournal, AcademicEntityWritePort {
    private val dao = database.vaultDao()
    private val snapshotStores = snapshotStores.toMap()

    constructor(
        database: HautDatabase,
        outbox: EventOutbox,
        snapshotStores: List<EntitySnapshotStore>,
        clock: () -> Instant = Instant::now,
        idGenerator: () -> String = { UUID.randomUUID().toString() },
        searchIndex: RoomSearchIndex? = null
    ) : this(
        database = database,
        outbox = outbox,
        snapshotStores = snapshotStores.associateBy { it.type },
        clock = clock,
        idGenerator = idGenerator,
        searchIndex = searchIndex
    )

    override suspend fun <T> write(
        ref: EntityRef,
        actor: Actor,
        actorDetail: String?,
        source: SourceRef?,
        writeGroupId: String?,
        block: suspend () -> T
    ): T = writeMany(
        refsProvider = { listOf(ref) },
        actor = actor,
        actorDetail = actorDetail,
        source = source,
        writeGroupId = writeGroupId,
        block = block
    )

    override suspend fun <T> writeMany(
        refsProvider: suspend () -> List<EntityRef>,
        actor: Actor,
        actorDetail: String?,
        source: SourceRef?,
        writeGroupId: String?,
        block: suspend () -> T
    ): T {
        val groupId = writeGroupId ?: idGenerator()
        val outcome = database.withTransaction {
            val refs = refsProvider().distinct()
            val beforeByRef = LinkedHashMap<EntityRef, CanonicalJson?>(refs.size)
            refs.forEach { ref ->
                validateSourceScope(ref, source)
                val store = snapshotStores[ref.type] ?: throw UnsupportedVaultEntityException(ref.type)
                val before = store.read(ref)
                validateSnapshotSafety(before)
                beforeByRef[ref] = before
            }

            val result = block()
            val occurredAt = clock()

            refs.forEach { ref ->
                val store = snapshotStores.getValue(ref.type)
                val after = store.read(ref)
                validateSnapshotSafety(after)
                val before = beforeByRef[ref]
                val op = inferChangeKind(before, after)
                if (op != null) {
                    recordChange(
                        ref = ref,
                        op = op,
                        before = before,
                        after = after,
                        actor = actor,
                        actorDetail = actorDetail,
                        at = occurredAt,
                        source = source,
                        writeGroupId = groupId
                    )
                    updateSearchProjection(ref, store)
                }
            }
            result
        }
        return outcome
    }

    /**
     * 供 dis-backup 行级同步使用的受控写边界：调用方提供精确 before/after snapshot 读取器，
     * journal/outbox 仍与业务写入同一 Room 事务。搜索投影只对有注册 SnapshotStore 的实体更新。
     */
    internal suspend fun <T> writeWithSnapshotReader(
        refsProvider: suspend () -> List<EntityRef>,
        snapshotReader: suspend (EntityRef) -> CanonicalJson?,
        actor: Actor,
        actorDetail: String?,
        source: SourceRef?,
        writeGroupId: String?,
        block: suspend () -> T
    ): T {
        val groupId = writeGroupId ?: idGenerator()
        return database.withTransaction {
            val refs = refsProvider().distinct()
            val beforeByRef = LinkedHashMap<EntityRef, CanonicalJson?>(refs.size)
            suspend fun readSnapshot(ref: EntityRef): CanonicalJson? =
                snapshotStores[ref.type]?.read(ref) ?: snapshotReader(ref)
            refs.forEach { ref ->
                validateSourceScope(ref, source)
                val before = readSnapshot(ref)
                validateSnapshotSafety(before)
                beforeByRef[ref] = before
            }
            val result = block()
            val occurredAt = clock()
            refs.forEach { ref ->
                val before = beforeByRef[ref]
                val after = readSnapshot(ref)
                validateSnapshotSafety(after)
                val op = inferChangeKind(before, after)
                if (op != null) {
                    recordChange(
                        ref = ref,
                        op = op,
                        before = before,
                        after = after,
                        actor = actor,
                        actorDetail = actorDetail,
                        at = occurredAt,
                        source = source,
                        writeGroupId = groupId
                    )
                    snapshotStores[ref.type]?.let { store -> updateSearchProjection(ref, store) }
                }
            }
            result
        }
    }

    override fun history(ref: EntityRef): Flow<List<EntityChange>> =
        dao.observeJournalForEntity(ref.type.name, ref.id).map { rows -> rows.map(::toEntityChange) }

    override fun recent(scope: SchoolAccountScope, types: Set<EntityType>, limit: Int): Flow<List<EntityChange>> {
        require(limit in 1..500) { "journal recent limit 必须在 1..500" }
        val typeNames = if (types.isEmpty()) EntityType.entries.map { it.name } else types.map { it.name }
        return dao.observeRecentJournal(
            schoolId = scope.schoolId.value,
            accountId = scope.accountId.value,
            profileId = scope.profileId.value,
            types = typeNames,
            limit = limit
        ).map { rows -> rows.map(::toEntityChange) }
    }

    override suspend fun revert(changeId: Long, actor: Actor): RevertResult {
        val outcome = database.withTransaction {
        val original = dao.getJournalEntry(changeId) ?: throw MissingJournalEntryException(changeId)
        if (original.revertedByChangeId != null) {
            return@withTransaction RevertResult(
                status = RevertStatus.ALREADY_REVERTED,
                originalChangeId = changeId,
                revertChangeId = original.revertedByChangeId
            )
        }
        val ref = entityRefOf(original)
        val store = snapshotStores[ref.type] ?: throw UnsupportedVaultEntityException(ref.type)
        val current = store.read(ref)
        val target = original.beforeJson?.let(CanonicalJson::of)
        validateSnapshotSafety(target)

        store.apply(ref, target)
        val restored = store.read(ref)
        if ((restored?.text) != target?.text) {
            throw JournalRevertInvariantException(
                "撤销后快照与 journal before 不一致: changeId=" + changeId + ", entity=" + ref.type + "/" + ref.id
            )
        }
        val inverseOp = when {
            target == null -> ChangeKind.DELETED
            ChangeKind.valueOf(original.op) == ChangeKind.DELETED || current == null -> ChangeKind.RESTORED
            current?.text == target?.text -> ChangeKind.RESTORED
            else -> ChangeKind.UPDATED
        }
        val reversal = recordChange(
            ref = ref,
            op = inverseOp,
            before = current,
            after = restored,
            actor = actor,
            actorDetail = "revert:" + changeId,
            at = clock(),
            source = null,
            writeGroupId = original.writeGroupId
        )
        updateSearchProjection(ref, store)
        revertCausalChildren(rootRef = ref, parentOriginal = original, actor = actor)
        val marked = dao.markJournalReverted(changeId, reversal.changeId)
        if (marked != 1) {
            throw JournalRevertInvariantException("撤销标记写入失败: changeId=" + changeId)
        }
            RevertResult(
                status = RevertStatus.APPLIED,
                originalChangeId = changeId,
                revertChangeId = reversal.changeId
            )
        }
        return outcome
    }

    /**
     * 撤销因果子实体。子写入必须与根写入共享 writeGroupId，且 journal source 指向根实体；
     * 例如课程资料归档派生作业截止，撤销资料创建/更新时同一事务恢复或删除对应 personal_event，
     * 不留下 Timeline 孤儿。普通 batch 中无 source 指向的兄弟实体不受牵连。
     */
    private suspend fun revertCausalChildren(rootRef: EntityRef, parentOriginal: EntityJournalEntity, actor: Actor) {
        val childRows = dao.getJournalGroup(parentOriginal.writeGroupId)
            .filter { it.changeId != parentOriginal.changeId }
            .filter { it.revertedByChangeId == null }
            .mapNotNull { row ->
                val source = row.sourceJson?.let { sourceJson ->
                    runCatching {
                        decodeSourceRef(cn.edu.haut.campus.core.model.json.Json.parse(sourceJson))
                    }.getOrNull()
                }
                if (source?.entity == rootRef) row to source else null
            }
        childRows.forEach { (childRow, childSource) ->
            val childRef = entityRefOf(childRow)
            val childStore = snapshotStores[childRef.type] ?: throw UnsupportedVaultEntityException(childRef.type)
            val current = childStore.read(childRef)
            val target = childRow.beforeJson?.let(CanonicalJson::of)
            validateSnapshotSafety(target)

            childStore.apply(childRef, target)
            val restored = childStore.read(childRef)
            if ((restored?.text) != target?.text) {
                throw JournalRevertInvariantException(
                    "因果子实体撤销后快照与 journal before 不一致: changeId=" + childRow.changeId +
                        ", entity=" + childRef.type + "/" + childRef.id
                )
            }
            val inverseOp = when {
                target == null -> ChangeKind.DELETED
                ChangeKind.valueOf(childRow.op) == ChangeKind.DELETED || current == null -> ChangeKind.RESTORED
                current?.text == target?.text -> ChangeKind.RESTORED
                else -> ChangeKind.UPDATED
            }
            val reversal = recordChange(
                ref = childRef,
                op = inverseOp,
                before = current,
                after = restored,
                actor = actor,
                actorDetail = "causal-revert:" + parentOriginal.changeId + ":" + childRow.changeId,
                at = clock(),
                // 保留子实体原来的因果来源。这样「撤销一次撤销」时，根实体与其派生
                // personal_event 仍能沿同一 source/writeGroup 一起恢复，不会只还原半边状态。
                source = childSource,
                writeGroupId = parentOriginal.writeGroupId
            )
            updateSearchProjection(childRef, childStore)
            val marked = dao.markJournalReverted(childRow.changeId, reversal.changeId)
            if (marked != 1) {
                throw JournalRevertInvariantException("因果子实体撤销标记写入失败: changeId=" + childRow.changeId)
            }
        }
    }

    internal suspend fun recordChange(
        ref: EntityRef,
        op: ChangeKind,
        before: CanonicalJson?,
        after: CanonicalJson?,
        actor: Actor,
        actorDetail: String?,
        at: Instant,
        source: SourceRef?,
        writeGroupId: String
    ): EntityChange {
        validateSourceScope(ref, source)
        validateSnapshotSafety(before)
        validateSnapshotSafety(after)
        val eventId = "vault-" + idGenerator()
        val insertResult = dao.upsertJournal(
            EntityJournalEntity(
                writeGroupId = writeGroupId,
                entityType = ref.type.name,
                entityId = ref.id,
                scopeSchoolId = ref.scope.schoolId.value,
                scopeAccountId = ref.scope.accountId.value,
                scopeProfileId = ref.scope.profileId.value,
                scopeCampusId = ref.scope.campusId?.value,
                op = op.name,
                beforeJson = before?.text,
                afterJson = after?.text,
                actor = actor.name,
                actorDetail = actorDetail,
                occurredAtEpochMs = at.toEpochMilli(),
                sourceJson = source?.let { canonicalJsonOf(encodeSourceRef(it)).text },
                revertedByChangeId = null,
                eventId = eventId
            )
        )
        val change = EntityChange(
            changeId = insertResult,
            writeGroupId = writeGroupId,
            ref = ref,
            op = op,
            before = before,
            after = after,
            actor = actor,
            actorDetail = actorDetail,
            at = at,
            source = source,
            revertedByChangeId = null,
            eventId = eventId
        )
        val envelopes = buildList {
            add(toEventEnvelope(change))
            CourseCancellationEventProjector.project(change)?.let { courseEvent ->
                add(DomainEventCodecRegistry.DEFAULT.encode(courseEvent))
            }
        }
        val roomOutbox = outbox as? RoomEventOutbox
        for (envelope in envelopes) {
            if (roomOutbox != null) {
                roomOutbox.appendDeferred(envelope)
            } else {
                outbox.append(envelope)
            }
        }
        return change
    }

    private suspend fun updateSearchProjection(ref: EntityRef, store: EntitySnapshotStore) {
        val index = searchIndex ?: return
        if (store !is SearchIndexedEntitySnapshotStore) return
        val document = store.projectSearchDocument(ref)
        if (document == null) {
            index.delete(ref)
        } else {
            index.upsert(document)
        }
    }

    private fun inferChangeKind(before: CanonicalJson?, after: CanonicalJson?): ChangeKind? = when {
        before == null && after != null -> ChangeKind.CREATED
        before != null && after == null -> ChangeKind.DELETED
        before != null && after != null && before.text != after.text -> ChangeKind.UPDATED
        else -> null
    }

    private fun toEventEnvelope(change: EntityChange): EventEnvelope {
        val typedEvent = DomainEvent.VaultEntityChanged(
            eventId = checkNotNull(change.eventId) { "journal 事件缺少 eventId" },
            scope = change.ref.scope,
            occurredAt = change.at,
            changeId = change.changeId,
            writeGroupId = change.writeGroupId,
            entityRef = change.ref,
            change = change.op,
            actor = change.actor,
            actorDetail = change.actorDetail,
            changedPaths = SnapshotChangedPaths.of(change.before, change.after)
        )
        // 共享 outbox 只接收 registry 的 canonical typed envelope。payload 不含 before/after 值，
        // 仅含 changeId、稳定引用、写操作者与脱敏路径；敏感路径由 codec invariant 再次 fail-closed。
        return DomainEventCodecRegistry.DEFAULT.encode(typedEvent)
    }

    private fun entityRefOf(row: EntityJournalEntity): EntityRef = EntityRef(
        type = EntityType.valueOf(row.entityType),
        id = row.entityId,
        scope = SchoolAccountScope(
            schoolId = SchoolId(row.scopeSchoolId),
            accountId = AccountId(row.scopeAccountId),
            profileId = ProfileId(row.scopeProfileId),
            campusId = row.scopeCampusId?.let(::CampusId)
        )
    )

    private fun toEntityChange(row: EntityJournalEntity): EntityChange = EntityChange(
        changeId = row.changeId,
        writeGroupId = row.writeGroupId,
        ref = entityRefOf(row),
        op = ChangeKind.valueOf(row.op),
        before = row.beforeJson?.let(CanonicalJson::of),
        after = row.afterJson?.let(CanonicalJson::of),
        actor = Actor.valueOf(row.actor),
        actorDetail = row.actorDetail,
        at = Instant.ofEpochMilli(row.occurredAtEpochMs),
        source = row.sourceJson?.let { decodeSourceRef(cn.edu.haut.campus.core.model.json.Json.parse(it)) },
        revertedByChangeId = row.revertedByChangeId,
        eventId = row.eventId
    )

    private fun validateSourceScope(ref: EntityRef, source: SourceRef?) {
        if (source == null) return
        require(source.provenance.schoolId == ref.scope.schoolId) { "source 学校与被写实体 scope 不一致" }
        require(source.provenance.accountId == ref.scope.accountId) { "source 账号与被写实体 scope 不一致" }
    }
}
