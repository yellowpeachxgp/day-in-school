package cn.edu.haut.campus.core.events

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.JsonValue as ModelJsonValue
import java.time.Instant

/** DomainEvent payload 的 UTF-8 上限。事件只携带小型事实与引用；大内容应进入 Vault/blob 并以引用关联。 */
internal const val MAX_DOMAIN_EVENT_PAYLOAD_BYTES: Int = 16 * 1024

/** 事件 payload 超过小型事实边界。异常消息不携带 payload 内容。 */
class DomainEventPayloadTooLargeException(sizeBytes: Int, maxBytes: Int) :
    IllegalArgumentException("DomainEvent payload 过大: " + sizeBytes + " bytes，上限 " + maxBytes + " bytes")

/** 事件字段差异中出现凭据、Cookie、令牌或秘密类字段名。异常消息不携带字段值。 */
class SensitiveEventPayloadException(fieldName: String) :
    IllegalArgumentException("DomainEvent 不得携带凭据/Cookie/秘密字段: " + fieldName)

/**
 * MASTER_PLAN §6.2 的领域事件密封层级。
 *
 * 事件本体只携带稳定引用、小型状态与字段差异；不携带凭据、Cookie、AI Key、原文 blob 或大 payload。
 * 公共身份与时间由 [eventId]、[scope]、[occurredAt] 表达，线格式 envelope 的类型与 schemaVersion 由
 * [DomainEventCodecRegistry] 统一注册和解码。
 */
sealed interface DomainEvent {
    val eventId: String
    val scope: SchoolAccountScope
    val occurredAt: Instant

    data class CourseChanged(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val courseRef: EntityRef,
        val change: ChangeKind,
        val diff: FieldDiff
    ) : DomainEvent {
        init {
            validateDomainEventInvariant(this)
        }
    }

    data class ExamPublished(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val examRef: EntityRef
    ) : DomainEvent {
        init {
            validateDomainEventInvariant(this)
        }
    }

    data class GradeReleased(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val attemptRef: EntityRef
    ) : DomainEvent {
        init {
            validateDomainEventInvariant(this)
        }
    }

    data class UtilityBalanceSampled(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val meterRef: EntityRef,
        val balanceCents: Long,
        val estimatedDaysLeft: Double?,
        val provenance: DataProvenance
    ) : DomainEvent {
        init {
            validateDomainEventInvariant(this)
        }
    }

    data class GeofenceTransition(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val campusId: CampusId,
        val enter: Boolean,
        val accuracyMeters: Float
    ) : DomainEvent {
        init {
            validateDomainEventInvariant(this)
        }
    }

    class NoticeConfirmed(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val inboxItemId: String,
        createdRefs: List<EntityRef>
    ) : DomainEvent {
        /**
         * 构造时逐元素复制输入 list，并用不可修改视图保存。调用方即使保留 MutableList 别名，
         * 也不能在校验或事件编码之后追加跨 scope 引用；自定义 Collection 即使让 toArray() 返回自有
         * backing array，也无法在构造后改写快照。手动实现数据类语义是为了让 copy 也经过同一快照边界。
         */
        val createdRefs: List<EntityRef> = snapshotElementCopy(createdRefs)

        init {
            validateDomainEventInvariant(this)
        }

        operator fun component1(): String = eventId

        operator fun component2(): SchoolAccountScope = scope

        operator fun component3(): Instant = occurredAt

        operator fun component4(): String = inboxItemId

        operator fun component5(): List<EntityRef> = createdRefs

        fun copy(
            eventId: String = this.eventId,
            scope: SchoolAccountScope = this.scope,
            occurredAt: Instant = this.occurredAt,
            inboxItemId: String = this.inboxItemId,
            createdRefs: List<EntityRef> = this.createdRefs
        ): NoticeConfirmed = NoticeConfirmed(
            eventId = eventId,
            scope = scope,
            occurredAt = occurredAt,
            inboxItemId = inboxItemId,
            createdRefs = createdRefs
        )

        override fun equals(other: Any?): Boolean = other is NoticeConfirmed &&
            eventId == other.eventId &&
            scope == other.scope &&
            occurredAt == other.occurredAt &&
            inboxItemId == other.inboxItemId &&
            createdRefs == other.createdRefs

        override fun hashCode(): Int {
            var result = eventId.hashCode()
            result = 31 * result + scope.hashCode()
            result = 31 * result + occurredAt.hashCode()
            result = 31 * result + inboxItemId.hashCode()
            result = 31 * result + createdRefs.hashCode()
            return result
        }

        override fun toString(): String = "NoticeConfirmed(eventId=" + eventId +
            ", scope=" + scope +
            ", occurredAt=" + occurredAt +
            ", inboxItemId=" + inboxItemId +
            ", createdRefs=" + createdRefs +
            ")"
    }

    data class GroupEventConfirmed(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val groupId: String,
        val eventRef: EntityRef
    ) : DomainEvent {
        init {
            validateDomainEventInvariant(this)
        }
    }

    /**
     * Vault 实体变更事实。事件只携带 journal changeId、实体引用、写操作者和已脱敏的变更路径；
     * before/after 内容始终留在 entity_journal，不进入共享 outbox payload。
     *
     * changedPaths 构造时逐元素复制，避免调用方在校验后通过 MutableList 别名追加敏感路径。
     */
    class VaultEntityChanged(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val changeId: Long,
        val writeGroupId: String,
        val entityRef: EntityRef,
        val change: ChangeKind,
        val actor: Actor,
        val actorDetail: String?,
        changedPaths: List<String>
    ) : DomainEvent {
        val changedPaths: List<String> = snapshotElementCopy(changedPaths)

        init {
            validateDomainEventInvariant(this)
        }

        operator fun component1(): String = eventId
        operator fun component2(): SchoolAccountScope = scope
        operator fun component3(): Instant = occurredAt
        operator fun component4(): Long = changeId
        operator fun component5(): String = writeGroupId
        operator fun component6(): EntityRef = entityRef
        operator fun component7(): ChangeKind = change
        operator fun component8(): Actor = actor
        operator fun component9(): String? = actorDetail
        operator fun component10(): List<String> = changedPaths

        fun copy(
            eventId: String = this.eventId,
            scope: SchoolAccountScope = this.scope,
            occurredAt: Instant = this.occurredAt,
            changeId: Long = this.changeId,
            writeGroupId: String = this.writeGroupId,
            entityRef: EntityRef = this.entityRef,
            change: ChangeKind = this.change,
            actor: Actor = this.actor,
            actorDetail: String? = this.actorDetail,
            changedPaths: List<String> = this.changedPaths
        ): VaultEntityChanged = VaultEntityChanged(
            eventId = eventId,
            scope = scope,
            occurredAt = occurredAt,
            changeId = changeId,
            writeGroupId = writeGroupId,
            entityRef = entityRef,
            change = change,
            actor = actor,
            actorDetail = actorDetail,
            changedPaths = changedPaths
        )

        override fun equals(other: Any?): Boolean = other is VaultEntityChanged &&
            eventId == other.eventId &&
            scope == other.scope &&
            occurredAt == other.occurredAt &&
            changeId == other.changeId &&
            writeGroupId == other.writeGroupId &&
            entityRef == other.entityRef &&
            change == other.change &&
            actor == other.actor &&
            actorDetail == other.actorDetail &&
            changedPaths == other.changedPaths

        override fun hashCode(): Int {
            var result = eventId.hashCode()
            result = 31 * result + scope.hashCode()
            result = 31 * result + occurredAt.hashCode()
            result = 31 * result + changeId.hashCode()
            result = 31 * result + writeGroupId.hashCode()
            result = 31 * result + entityRef.hashCode()
            result = 31 * result + change.hashCode()
            result = 31 * result + actor.hashCode()
            result = 31 * result + (actorDetail?.hashCode() ?: 0)
            result = 31 * result + changedPaths.hashCode()
            return result
        }

        override fun toString(): String = "VaultEntityChanged(eventId=" + eventId +
            ", scope=" + scope +
            ", occurredAt=" + occurredAt +
            ", changeId=" + changeId +
            ", writeGroupId=" + writeGroupId +
            ", entityRef=" + entityRef +
            ", change=" + change +
            ", actor=" + actor +
            ", actorDetail=" + actorDetail +
            ", changedPaths=" + changedPaths +
            ")"
    }

    data class DataStale(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val capability: CapabilityKind,
        val since: Instant
    ) : DomainEvent {
        init {
            validateDomainEventInvariant(this)
        }
    }
}

/**
 * 重新执行每个 DomainEvent 在构造边界声明的完整 invariant。
 *
 * 构造函数首先调用本函数；codec 在 encode 边界再次调用，形成防御性复核。这样即使未来某个事件
 * 新增可变成分或调用方绕过正常构造路径，敏感字段、scope、引用类型和数值边界也不会只在对象创建时检查一次。
 */
internal fun validateDomainEventInvariant(event: DomainEvent) {
    validateDomainEventId(event.eventId)
    when (event) {
        is DomainEvent.CourseChanged -> {
            validateScopedEntityRef(event.courseRef, event.scope, EntityType.COURSE, "courseRef")
            event.diff.validateInvariant()
            validateFieldDiffSafety(event.diff)
        }
        is DomainEvent.ExamPublished -> {
            validateScopedEntityRef(event.examRef, event.scope, EntityType.EXAM, "examRef")
        }
        is DomainEvent.GradeReleased -> {
            validateScopedEntityRef(event.attemptRef, event.scope, EntityType.GRADE_ATTEMPT, "attemptRef")
        }
        is DomainEvent.UtilityBalanceSampled -> {
            validateScopedEntityRef(event.meterRef, event.scope, EntityType.UTILITY_METER, "meterRef")
            event.estimatedDaysLeft?.let { estimate ->
                require(estimate.isFinite() && estimate >= 0.0 && !isNegativeZero(estimate)) {
                    "estimatedDaysLeft 必须是有限非负数或 null，且不使用负零"
                }
            }
            validateEventProvenance(event.provenance, event.scope)
        }
        is DomainEvent.GeofenceTransition -> {
            require(event.accuracyMeters.isFinite() && event.accuracyMeters >= 0f && !isNegativeZero(event.accuracyMeters)) {
                "accuracyMeters 必须是有限非负数，且不使用负零"
            }
        }
        is DomainEvent.NoticeConfirmed -> {
            validateEventToken(event.inboxItemId, "inboxItemId")
            require(event.createdRefs.distinct().size == event.createdRefs.size) { "createdRefs 不能包含重复引用" }
            event.createdRefs.forEachIndexed { index, ref ->
                require(ref.scope == event.scope) { "createdRefs[" + index + "] 与事件 scope 不一致" }
            }
        }
        is DomainEvent.GroupEventConfirmed -> {
            validateEventToken(event.groupId, "groupId")
            validateScopedEntityRef(event.eventRef, event.scope, EntityType.GROUP_EVENT, "eventRef")
        }
        is DomainEvent.VaultEntityChanged -> {
            require(event.changeId > 0) { "VaultEntityChanged changeId 必须大于 0" }
            validateEventToken(event.writeGroupId, "writeGroupId")
            require(event.entityRef.scope == event.scope) { "entityRef 与事件 scope 不一致" }
            require(event.actorDetail == null || (event.actorDetail.length <= 512 && event.actorDetail.none { it.isISOControl() })) {
                "actorDetail 过长或含控制字符"
            }
            validateRedactedChangedPathsSafety(event.changedPaths)
        }
        is DomainEvent.DataStale -> Unit
    }
}

/**
 * 逐元素复制到新建 ArrayList，再包不可修改视图。
 *
 * 不能使用 ArrayList(Collection) 构造复制：其内部直接采用 collection.toArray() 返回的数组，
 * 当调用方传入自定义 Collection 且 toArray() 违反约定返回自有 backing array 时，快照与调用方
 * 共享同一数组，构造后仍可被改写；该行为随 JDK/Android 版本不同而不可依赖。逐元素 add 只依赖
 * 迭代器语义，backing array 始终由这里新建的 ArrayList 自己持有，调用方无法取得引用。
 */
private fun <T> snapshotElementCopy(source: List<T>): List<T> {
    val copy = ArrayList<T>(source.size)
    for (element in source) {
        copy.add(element)
    }
    return java.util.Collections.unmodifiableList(copy)
}

private fun isNegativeZero(value: Double): Boolean =
    java.lang.Double.doubleToRawLongBits(value) == java.lang.Double.doubleToRawLongBits(-0.0)

private fun isNegativeZero(value: Float): Boolean =
    java.lang.Float.floatToRawIntBits(value) == java.lang.Float.floatToRawIntBits(-0.0f)

internal fun validateDomainEventId(eventId: String) {
    validateEventToken(eventId, "eventId")
}

/** 与 W1-00 的实体稳定 ID 使用同一字符边界，避免事件 ID 因空白/控制字符破坏日志、存储和过滤。 */
internal fun validateEventToken(value: String, label: String, maxLength: Int = 256) {
    require(value.length in 1..maxLength) { label + " 为空或过长" }
    require(value.none { it.isISOControl() || it.isWhitespace() }) { label + " 不能包含空白或控制字符" }
    require(value.all { it.isLetterOrDigit() || it in "_-./:" }) { label + " 包含非法字符" }
}

internal fun validateScopedEntityRef(
    ref: EntityRef,
    scope: SchoolAccountScope,
    expectedType: EntityType,
    label: String
) {
    require(ref.type == expectedType) { label + " 必须是 " + expectedType + "，实际为 " + ref.type }
    require(ref.scope == scope) { label + " 与事件 scope 不一致" }
}

internal fun validateEventProvenance(provenance: DataProvenance, scope: SchoolAccountScope) {
    require(provenance.schoolId == scope.schoolId) { "provenance 学校与事件 scope 不一致" }
    require(provenance.accountId == scope.accountId) { "provenance 账号与事件 scope 不一致" }
    requireValidProvenancePart(provenance.providerId, "providerId", 128)
    requireValidProvenancePart(provenance.sourceSystem, "sourceSystem", 256)
    provenance.sourceRecordId?.let { value ->
        require(value.isNotBlank() && value.length <= 512 && value.none { it.isISOControl() }) {
            "provenance.sourceRecordId 为空、过长或含控制字符"
        }
    }
    provenance.contentRevision?.let { value ->
        require(value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() || it.isWhitespace() }) {
            "provenance.contentRevision 为空、过长或含空白/控制字符"
        }
    }
    require(provenance.schemaVersion > 0) { "provenance.schemaVersion 必须大于 0" }
}

private fun requireValidProvenancePart(value: String, label: String, maxLength: Int) {
    require(value.isNotBlank() && value.length <= maxLength) { "provenance 缺少有效的 " + label }
    require(value.none { it.isISOControl() || it.isWhitespace() }) { "provenance." + label + " 不能包含空白或控制字符" }
}

/**
 * Vault 事件不携带 before/after 值，因此用一个只在内存中存在的 FieldDiff 探针复用统一敏感字段防线：
 * 探针值永不编码、永不持久化，仅让 JSON Pointer 结构校验与 validateFieldDiffSafety 的路径扫描作用于
 * changedPaths。这样敏感字段规则只有一份实现，codec encode/decode 都会重复执行。
 */
internal fun validateRedactedChangedPathsSafety(paths: List<String>) {
    require(paths.isNotEmpty()) { "changedPaths 至少要包含一个字段路径" }
    require(paths.distinct().size == paths.size) { "changedPaths 包含重复路径" }
    val probe = FieldDiff(
        paths.map { path ->
            FieldChange(
                path = path,
                kind = FieldChangeKind.MODIFIED,
                before = ModelJsonValue.Null,
                after = ModelJsonValue.Str("redacted-probe")
            )
        }
    )
    validateFieldDiffSafety(probe)
}

internal fun validateFieldDiffSafety(diff: FieldDiff) {
    diff.changes.forEach { change ->
        decodeJsonPointerSegments(change.path).forEach { segment ->
            validateNotSensitiveFieldName(segment)
        }
        change.before?.let { validateJsonValueSafety(it) }
        change.after?.let { validateJsonValueSafety(it) }
    }
}

private fun validateJsonValueSafety(value: ModelJsonValue) {
    when (value) {
        is ModelJsonValue.Obj -> value.fields.forEach { (key, child) ->
            validateNotSensitiveFieldName(key)
            validateJsonValueSafety(child)
        }
        is ModelJsonValue.Arr -> value.items.forEach(::validateJsonValueSafety)
        else -> Unit
    }
}

private fun validateNotSensitiveFieldName(rawName: String) {
    val normalized = buildString(rawName.length) {
        rawName.lowercase().forEach { ch ->
            if (ch.isLetterOrDigit()) append(ch)
        }
    }
    if (normalized.isEmpty()) return
    val forbiddenMarkers = listOf(
        "password",
        "passwd",
        "credential",
        "cookie",
        "authorization",
        "token",
        "secret",
        "apikey",
        "aikey",
        "privatekey",
        "sessionid"
    )
    if (forbiddenMarkers.any { marker -> normalized.contains(marker) }) {
        throw SensitiveEventPayloadException(rawName)
    }
}

private fun decodeJsonPointerSegments(path: String): List<String> {
    if (!path.startsWith('/')) return emptyList()
    return path.substring(1).split('/').map { segment ->
        segment.replace("~1", "/").replace("~0", "~")
    }
}

internal fun validatePayloadSize(payload: CanonicalJson) {
    val size = payload.text.toByteArray(Charsets.UTF_8).size
    if (size > MAX_DOMAIN_EVENT_PAYLOAD_BYTES) {
        throw DomainEventPayloadTooLargeException(size, MAX_DOMAIN_EVENT_PAYLOAD_BYTES)
    }
}
