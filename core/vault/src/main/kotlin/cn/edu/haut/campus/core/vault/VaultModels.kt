package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SourceRef
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** W1-03 实体变更 journal 的一条不可变记录。before/after 是 canonical JSON 快照，事件本体只携带引用与 changeId。 */
data class EntityChange(
    val changeId: Long,
    val writeGroupId: String,
    val ref: EntityRef,
    val op: ChangeKind,
    val before: CanonicalJson?,
    val after: CanonicalJson?,
    val actor: Actor,
    val actorDetail: String?,
    val at: Instant,
    val source: SourceRef?,
    val revertedByChangeId: Long?,
    val eventId: String?
) {
    init {
        require(changeId > 0) { "changeId 必须大于 0" }
        require(writeGroupId.isNotBlank() && writeGroupId.none { it.isISOControl() || it.isWhitespace() }) {
            "writeGroupId 为空或含空白/控制字符"
        }
        require(before != null || after != null) { "journal 至少需要 before 或 after 快照" }
        if (op == ChangeKind.UPDATED) {
            require(before != null && after != null) { "UPDATED 必须同时有 before/after" }
            require(before.text != after.text) { "UPDATED 的 canonical 快照不能相同" }
        }
        if (op == ChangeKind.CREATED) {
            require(before == null && after != null) { "CREATED 必须只有 after" }
        }
        if (op == ChangeKind.DELETED) {
            require(before != null && after == null) { "DELETED 必须只有 before" }
        }
        require(actorDetail == null || actorDetail.length <= 512) { "actorDetail 过长" }
        require(eventId == null || eventId.isNotBlank()) { "eventId 不能为空白" }
    }
}

/** EntityWriter 的自动快照边界。实现必须只读写同一个 Room 库，使调用方事务能覆盖业务写入、journal 与 outbox。 */
interface EntitySnapshotStore {
    val type: EntityType

    /** 读取实体当前 canonical 快照；实体及其必要聚合不存在时返回 null。 */
    suspend fun read(ref: EntityRef): CanonicalJson?

    /** 将实体恢复为 snapshot；snapshot 为 null 表示删除。只在 RoomVaultJournal 的撤销事务内调用。 */
    suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?)
}

/**
 * MASTER_PLAN §6.2 的实体写入口。
 *
 * 实现先在同一事务读取 before，再执行调用方业务写入，然后读取 after 并推断 CREATED/UPDATED/DELETED；
 * 快照没有变化时不写 journal、不发布事件，避免同步重放制造审计噪声。
 */
interface EntityWriter {
    suspend fun <T> write(
        ref: EntityRef,
        actor: Actor,
        actorDetail: String? = null,
        source: SourceRef? = null,
        writeGroupId: String? = null,
        block: suspend () -> T
    ): T
}

/** 能把当前业务行投影为搜索文档的 snapshot store；projection 在写入/撤销事务内读取，保证无幽灵结果。 */
interface SearchIndexedEntitySnapshotStore : EntitySnapshotStore {
    suspend fun projectSearchDocument(ref: EntityRef): SearchDocument?
}

interface ChangeJournal {
    fun history(ref: EntityRef): Flow<List<EntityChange>>
    fun recent(scope: SchoolAccountScope, types: Set<EntityType>, limit: Int): Flow<List<EntityChange>>
    suspend fun revert(changeId: Long, actor: Actor = Actor.USER): RevertResult
}

enum class RevertStatus {
    APPLIED,
    ALREADY_REVERTED
}

data class RevertResult(
    val status: RevertStatus,
    val originalChangeId: Long,
    val revertChangeId: Long?
)

class UnsupportedVaultEntityException(type: EntityType) :
    IllegalArgumentException("实体类型尚未注册 Vault snapshot store: " + type)

class MissingJournalEntryException(changeId: Long) :
    IllegalArgumentException("journal 不存在: " + changeId)

class JournalRevertInvariantException(message: String) :
    IllegalStateException(message)

class SensitiveSnapshotException(fieldName: String) :
    IllegalArgumentException("Vault 快照不得携带凭据/Cookie/秘密字段: " + fieldName)
