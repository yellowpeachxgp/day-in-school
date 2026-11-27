package cn.edu.haut.campus.core.vault

import androidx.room.withTransaction
import cn.edu.haut.campus.core.database.EntityJournalEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.events.CanonicalJson
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
import cn.edu.haut.campus.core.model.json.JsonValue
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
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) : EntityWriter, ChangeJournal {
    private val dao = database.vaultDao()
    private val snapshotStores = snapshotStores.toMap()

    constructor(
        database: HautDatabase,
        outbox: EventOutbox,
        snapshotStores: List<EntitySnapshotStore>,
        clock: () -> Instant = Instant::now,
        idGenerator: () -> String = { UUID.randomUUID().toString() }
    ) : this(
        database = database,
        outbox = outbox,
        snapshotStores = snapshotStores.associateBy { it.type },
        clock = clock,
        idGenerator = idGenerator
    )

    override suspend fun <T> write(
        ref: EntityRef,
        actor: Actor,
        actorDetail: String?,
        source: SourceRef?,
        writeGroupId: String?,
        block: suspend () -> T
    ): T {
        validateSourceScope(ref, source)
        val store = snapshotStores[ref.type] ?: throw UnsupportedVaultEntityException(ref.type)
        return database.withTransaction {
            val before = store.read(ref)
            validateSnapshotSafety(before)
            val result = block()
            val after = store.read(ref)
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
                    at = clock(),
                    source = source,
                    writeGroupId = writeGroupId ?: idGenerator()
                )
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

    override suspend fun revert(changeId: Long, actor: Actor): RevertResult = database.withTransaction {
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
        outbox.append(toEventEnvelope(change))
        return change
    }

    private fun inferChangeKind(before: CanonicalJson?, after: CanonicalJson?): ChangeKind? = when {
        before == null && after != null -> ChangeKind.CREATED
        before != null && after == null -> ChangeKind.DELETED
        before != null && after != null && before.text != after.text -> ChangeKind.UPDATED
        else -> null
    }

    private fun toEventEnvelope(change: EntityChange): EventEnvelope {
        val payload = canonicalJsonOf(
            jsonObjectOf(
                "changeId" to JsonValue.Num(change.changeId.toString()),
                "writeGroupId" to JsonValue.Str(change.writeGroupId),
                "entity" to encodeEntityRef(change.ref),
                "op" to JsonValue.Str(change.op.name),
                "actor" to JsonValue.Str(change.actor.name),
                "actorDetail" to jsonString(change.actorDetail),
                "occurredAt" to JsonValue.Str(change.at.toString())
            )
        )
        return EventEnvelope(
            eventId = checkNotNull(change.eventId) { "journal 事件缺少 eventId" },
            eventType = VAULT_ENTITY_CHANGED_EVENT_TYPE,
            schemaVersion = VAULT_ENTITY_CHANGED_SCHEMA_VERSION,
            occurredAt = change.at,
            scopeKey = VaultScopeKeyCodec.encode(change.ref.scope),
            payload = payload,
            metadata = emptyMap()
        )
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
