package cn.edu.haut.campus.core.events

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
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
            validateDomainEventId(eventId)
            validateScopedEntityRef(courseRef, scope, EntityType.COURSE, "courseRef")
            validateFieldDiffSafety(diff)
        }
    }

    data class ExamPublished(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val examRef: EntityRef
    ) : DomainEvent {
        init {
            validateDomainEventId(eventId)
            validateScopedEntityRef(examRef, scope, EntityType.EXAM, "examRef")
        }
    }

    data class GradeReleased(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val attemptRef: EntityRef
    ) : DomainEvent {
        init {
            validateDomainEventId(eventId)
            validateScopedEntityRef(attemptRef, scope, EntityType.GRADE_ATTEMPT, "attemptRef")
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
            validateDomainEventId(eventId)
            validateScopedEntityRef(meterRef, scope, EntityType.UTILITY_METER, "meterRef")
            estimatedDaysLeft?.let { estimate ->
                require(estimate.isFinite() && estimate >= 0.0 && !isNegativeZero(estimate)) {
                    "estimatedDaysLeft 必须是有限非负数或 null，且不使用负零"
                }
            }
            validateEventProvenance(provenance, scope)
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
            validateDomainEventId(eventId)
            require(accuracyMeters.isFinite() && accuracyMeters >= 0f && !isNegativeZero(accuracyMeters)) {
                "accuracyMeters 必须是有限非负数，且不使用负零"
            }
        }
    }

    data class NoticeConfirmed(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val inboxItemId: String,
        val createdRefs: List<EntityRef>
    ) : DomainEvent {
        init {
            validateDomainEventId(eventId)
            validateEventToken(inboxItemId, "inboxItemId")
            require(createdRefs.distinct().size == createdRefs.size) { "createdRefs 不能包含重复引用" }
            createdRefs.forEachIndexed { index, ref ->
                require(ref.scope == scope) { "createdRefs[" + index + "] 与事件 scope 不一致" }
            }
        }
    }

    data class GroupEventConfirmed(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val groupId: String,
        val eventRef: EntityRef
    ) : DomainEvent {
        init {
            validateDomainEventId(eventId)
            validateEventToken(groupId, "groupId")
            validateScopedEntityRef(eventRef, scope, EntityType.GROUP_EVENT, "eventRef")
        }
    }

    data class DataStale(
        override val eventId: String,
        override val scope: SchoolAccountScope,
        override val occurredAt: Instant,
        val capability: CapabilityKind,
        val since: Instant
    ) : DomainEvent {
        init {
            validateDomainEventId(eventId)
        }
    }
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
