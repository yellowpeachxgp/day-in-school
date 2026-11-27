package cn.edu.haut.campus.core.events

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Json as ModelJson
import cn.edu.haut.campus.core.model.json.JsonValue as ModelJsonValue
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.reflect.KClass

/** 类型化事件解码所需的 envelope 公共头；payload 本身不重复这些字段。 */
data class DomainEventHeader(
    val eventId: String,
    val scope: SchoolAccountScope,
    val occurredAt: Instant
) {
    init {
        validateDomainEventId(eventId)
    }
}

/**
 * 单个 DomainEvent wire schema 的编解码器。
 *
 * payload 只包含事件特有字段；eventId/scope/occurredAt 由 envelope 头提供。实现必须拒绝未知字段和非法枚举，
 * 不得 silently drop 无法映射的内容。
 */
abstract class DomainEventCodec<T : DomainEvent> {
    abstract val eventClass: KClass<*>
    abstract val eventType: String
    abstract val schemaVersion: Int

    abstract fun encodePayload(value: T): CanonicalJson
    abstract fun decodePayload(payload: CanonicalJson, header: DomainEventHeader): T

    internal fun encodeUnchecked(event: DomainEvent): CanonicalJson {
        @Suppress("UNCHECKED_CAST")
        return encodePayload(event as T)
    }

    internal fun decodeUnchecked(payload: CanonicalJson, header: DomainEventHeader): DomainEvent =
        decodePayload(payload, header)
}

class UnknownDomainEventTypeException(eventType: String) :
    IllegalArgumentException("未知 DomainEvent 类型: " + eventType)

class UnsupportedDomainEventSchemaVersionException(eventType: String, actual: Int, supported: Int) :
    IllegalArgumentException("DomainEvent " + eventType + " schemaVersion 不支持: " + actual + "，当前支持 " + supported)

class UnregisteredDomainEventException(event: DomainEvent) :
    IllegalArgumentException("DomainEvent 实现未注册 codec: " + (event::class.simpleName ?: event::class.toString()))

/**
 * §6.2 DomainEvent codec 注册表。
 *
 * 注册表是 fail-closed 边界：未知 eventType、未知/过高 schemaVersion、非空 metadata、缺失或非法 scopeKey、
 * 超大 payload 都会拒绝解码。metadata 暂不允许进入类型化事件，避免绕过 schema 夹带未审计数据。
 */
class DomainEventCodecRegistry(
    codecs: List<DomainEventCodec<out DomainEvent>> = defaultDomainEventCodecs()
) {
    private val codecsByType: Map<String, DomainEventCodec<out DomainEvent>>
    private val codecsByClass: Map<KClass<*>, DomainEventCodec<out DomainEvent>>

    val supportedEventTypes: Set<String>
        get() = codecsByType.keys

    init {
        require(codecs.isNotEmpty()) { "DomainEvent codec 注册表不能为空" }
        val byType = LinkedHashMap<String, DomainEventCodec<out DomainEvent>>()
        val byClass = LinkedHashMap<KClass<*>, DomainEventCodec<out DomainEvent>>()
        codecs.forEach { codec ->
            validateRegisteredEventType(codec.eventType)
            require(codec.schemaVersion > 0) { "DomainEvent codec schemaVersion 必须大于 0: " + codec.eventType }
            require(byType.put(codec.eventType, codec) == null) { "重复注册 DomainEvent eventType: " + codec.eventType }
            val eventClass: KClass<*> = codec.eventClass
            require(byClass.put(eventClass, codec) == null) { "重复注册 DomainEvent 类型: " + eventClass.simpleName }
        }
        codecsByType = byType
        codecsByClass = byClass
    }

    fun encode(event: DomainEvent): EventEnvelope {
        val codec = codecsByClass[event::class] ?: throw UnregisteredDomainEventException(event)
        val payload = codec.encodeUnchecked(event)
        validatePayloadSize(payload)
        return EventEnvelope(
            eventId = event.eventId,
            eventType = codec.eventType,
            schemaVersion = codec.schemaVersion,
            occurredAt = event.occurredAt,
            scopeKey = SchoolAccountScopeKeyCodec.encode(event.scope),
            payload = payload,
            metadata = emptyMap()
        )
    }

    fun decode(envelope: EventEnvelope): DomainEvent {
        if (envelope.metadata.isNotEmpty()) {
            throw IllegalArgumentException("类型化 DomainEvent 不支持非空 metadata，拒绝静默丢弃")
        }
        val codec = codecsByType[envelope.eventType] ?: throw UnknownDomainEventTypeException(envelope.eventType)
        if (envelope.schemaVersion != codec.schemaVersion) {
            throw UnsupportedDomainEventSchemaVersionException(
                eventType = envelope.eventType,
                actual = envelope.schemaVersion,
                supported = codec.schemaVersion
            )
        }
        validatePayloadSize(envelope.payload)
        val scopeKey = envelope.scopeKey
            ?: throw IllegalArgumentException("DomainEvent envelope 缺少 scopeKey")
        val header = DomainEventHeader(
            eventId = envelope.eventId,
            scope = SchoolAccountScopeKeyCodec.decode(scopeKey),
            occurredAt = envelope.occurredAt
        )
        val decoded = codec.decodeUnchecked(envelope.payload, header)
        if (decoded::class != codec.eventClass || decoded.eventId != header.eventId ||
            decoded.scope != header.scope || decoded.occurredAt != header.occurredAt
        ) {
            throw IllegalStateException("DomainEvent codec 解码结果与 envelope 头不一致: " + envelope.eventType)
        }
        return decoded
    }

    companion object {
        val DEFAULT: DomainEventCodecRegistry = DomainEventCodecRegistry()
    }
}

private fun validateRegisteredEventType(eventType: String) {
    require(eventType.length in 3..128) { "DomainEvent eventType 为空或过长" }
    require(eventType.none { it.isISOControl() || it.isWhitespace() }) { "DomainEvent eventType 不能包含空白或控制字符" }
    val segments = eventType.split('.')
    require(segments.size >= 2 && segments.all { segment ->
        segment.isNotEmpty() && segment.all { it.isLowerCase() || it.isDigit() || it == '-' }
    }) { "DomainEvent eventType 必须是 lowercase.dot.segments: " + eventType }
}

/** envelope scopeKey 的紧凑稳定格式：schoolId/accountId/profileId[/campusId]。各 ID 字符集本身不含 '/'。 */
internal object SchoolAccountScopeKeyCodec {
    fun encode(scope: SchoolAccountScope): String {
        val parts = mutableListOf(
            scope.schoolId.value,
            scope.accountId.value,
            scope.profileId.value
        )
        scope.campusId?.let { parts += it.value }
        return parts.joinToString("/")
    }

    fun decode(scopeKey: String): SchoolAccountScope {
        val parts = scopeKey.split('/')
        require(parts.size == 3 || parts.size == 4) { "DomainEvent scopeKey 必须是 school/account/profile[/campus]" }
        return SchoolAccountScope(
            schoolId = SchoolId(parts[0]),
            accountId = AccountId(parts[1]),
            profileId = ProfileId(parts[2]),
            campusId = parts.getOrNull(3)?.let(::CampusId)
        )
    }
}

private fun defaultDomainEventCodecs(): List<DomainEventCodec<out DomainEvent>> = listOf<DomainEventCodec<out DomainEvent>>(
    CourseChangedCodec,
    ExamPublishedCodec,
    GradeReleasedCodec,
    UtilityBalanceSampledCodec,
    GeofenceTransitionCodec,
    NoticeConfirmedCodec,
    GroupEventConfirmedCodec,
    DataStaleCodec
)

private object CourseChangedCodec : DomainEventCodec<DomainEvent.CourseChanged>() {
    override val eventClass = DomainEvent.CourseChanged::class
    override val eventType = "course.changed"
    override val schemaVersion = 1

    override fun encodePayload(value: DomainEvent.CourseChanged): CanonicalJson = CanonicalJson.ofValue(
        jsonObject(
            "change" to JsonValue.Str(value.change.name),
            "courseRef" to encodeEntityRef(value.courseRef),
            "diff" to encodeFieldDiff(value.diff)
        )
    )

    override fun decodePayload(payload: CanonicalJson, header: DomainEventHeader): DomainEvent.CourseChanged {
        val root = payloadObject(payload, eventType, setOf("change", "courseRef", "diff"))
        return DomainEvent.CourseChanged(
            eventId = header.eventId,
            scope = header.scope,
            occurredAt = header.occurredAt,
            courseRef = decodeEntityRef(objectField(root, "courseRef", eventType)),
            change = decodeEnum<ChangeKind>(root, "change", eventType),
            diff = decodeFieldDiff(objectField(root, "diff", eventType))
        )
    }
}

private object ExamPublishedCodec : DomainEventCodec<DomainEvent.ExamPublished>() {
    override val eventClass = DomainEvent.ExamPublished::class
    override val eventType = "exam.published"
    override val schemaVersion = 1

    override fun encodePayload(value: DomainEvent.ExamPublished): CanonicalJson = CanonicalJson.ofValue(
        jsonObject("examRef" to encodeEntityRef(value.examRef))
    )

    override fun decodePayload(payload: CanonicalJson, header: DomainEventHeader): DomainEvent.ExamPublished {
        val root = payloadObject(payload, eventType, setOf("examRef"))
        return DomainEvent.ExamPublished(
            eventId = header.eventId,
            scope = header.scope,
            occurredAt = header.occurredAt,
            examRef = decodeEntityRef(objectField(root, "examRef", eventType))
        )
    }
}

private object GradeReleasedCodec : DomainEventCodec<DomainEvent.GradeReleased>() {
    override val eventClass = DomainEvent.GradeReleased::class
    override val eventType = "grade.released"
    override val schemaVersion = 1

    override fun encodePayload(value: DomainEvent.GradeReleased): CanonicalJson = CanonicalJson.ofValue(
        jsonObject("attemptRef" to encodeEntityRef(value.attemptRef))
    )

    override fun decodePayload(payload: CanonicalJson, header: DomainEventHeader): DomainEvent.GradeReleased {
        val root = payloadObject(payload, eventType, setOf("attemptRef"))
        return DomainEvent.GradeReleased(
            eventId = header.eventId,
            scope = header.scope,
            occurredAt = header.occurredAt,
            attemptRef = decodeEntityRef(objectField(root, "attemptRef", eventType))
        )
    }
}

private object UtilityBalanceSampledCodec : DomainEventCodec<DomainEvent.UtilityBalanceSampled>() {
    override val eventClass = DomainEvent.UtilityBalanceSampled::class
    override val eventType = "utility.balance.sampled"
    override val schemaVersion = 1

    override fun encodePayload(value: DomainEvent.UtilityBalanceSampled): CanonicalJson = CanonicalJson.ofValue(
        jsonObject(
            "balanceCents" to canonicalNumber(value.balanceCents.toString()),
            "estimatedDaysLeft" to (value.estimatedDaysLeft?.let { canonicalNumber(it.toString()) } ?: JsonValue.Null),
            "meterRef" to encodeEntityRef(value.meterRef),
            "provenance" to encodeDataProvenance(value.provenance)
        )
    )

    override fun decodePayload(payload: CanonicalJson, header: DomainEventHeader): DomainEvent.UtilityBalanceSampled {
        val root = payloadObject(payload, eventType, setOf("balanceCents", "estimatedDaysLeft", "meterRef", "provenance"))
        return DomainEvent.UtilityBalanceSampled(
            eventId = header.eventId,
            scope = header.scope,
            occurredAt = header.occurredAt,
            meterRef = decodeEntityRef(objectField(root, "meterRef", eventType)),
            balanceCents = longField(root, "balanceCents", eventType),
            estimatedDaysLeft = nullableDoubleField(root, "estimatedDaysLeft", eventType),
            provenance = decodeDataProvenance(objectField(root, "provenance", eventType))
        )
    }
}

private object GeofenceTransitionCodec : DomainEventCodec<DomainEvent.GeofenceTransition>() {
    override val eventClass = DomainEvent.GeofenceTransition::class
    override val eventType = "geofence.transition"
    override val schemaVersion = 1

    override fun encodePayload(value: DomainEvent.GeofenceTransition): CanonicalJson = CanonicalJson.ofValue(
        jsonObject(
            "accuracyMeters" to canonicalNumber(value.accuracyMeters.toString()),
            "campusId" to JsonValue.Str(value.campusId.value),
            "enter" to JsonValue.Bool(value.enter)
        )
    )

    override fun decodePayload(payload: CanonicalJson, header: DomainEventHeader): DomainEvent.GeofenceTransition {
        val root = payloadObject(payload, eventType, setOf("accuracyMeters", "campusId", "enter"))
        return DomainEvent.GeofenceTransition(
            eventId = header.eventId,
            scope = header.scope,
            occurredAt = header.occurredAt,
            campusId = CampusId(stringField(root, "campusId", eventType)),
            enter = boolField(root, "enter", eventType),
            accuracyMeters = floatField(root, "accuracyMeters", eventType)
        )
    }
}

private object NoticeConfirmedCodec : DomainEventCodec<DomainEvent.NoticeConfirmed>() {
    override val eventClass = DomainEvent.NoticeConfirmed::class
    override val eventType = "notice.confirmed"
    override val schemaVersion = 1

    override fun encodePayload(value: DomainEvent.NoticeConfirmed): CanonicalJson = CanonicalJson.ofValue(
        jsonObject(
            "createdRefs" to JsonValue.Arr(value.createdRefs.map(::encodeEntityRef)),
            "inboxItemId" to JsonValue.Str(value.inboxItemId)
        )
    )

    override fun decodePayload(payload: CanonicalJson, header: DomainEventHeader): DomainEvent.NoticeConfirmed {
        val root = payloadObject(payload, eventType, setOf("createdRefs", "inboxItemId"))
        return DomainEvent.NoticeConfirmed(
            eventId = header.eventId,
            scope = header.scope,
            occurredAt = header.occurredAt,
            inboxItemId = stringField(root, "inboxItemId", eventType),
            createdRefs = arrayField(root, "createdRefs", eventType).map { decodeEntityRef(it) }
        )
    }
}

private object GroupEventConfirmedCodec : DomainEventCodec<DomainEvent.GroupEventConfirmed>() {
    override val eventClass = DomainEvent.GroupEventConfirmed::class
    override val eventType = "group.event.confirmed"
    override val schemaVersion = 1

    override fun encodePayload(value: DomainEvent.GroupEventConfirmed): CanonicalJson = CanonicalJson.ofValue(
        jsonObject(
            "eventRef" to encodeEntityRef(value.eventRef),
            "groupId" to JsonValue.Str(value.groupId)
        )
    )

    override fun decodePayload(payload: CanonicalJson, header: DomainEventHeader): DomainEvent.GroupEventConfirmed {
        val root = payloadObject(payload, eventType, setOf("eventRef", "groupId"))
        return DomainEvent.GroupEventConfirmed(
            eventId = header.eventId,
            scope = header.scope,
            occurredAt = header.occurredAt,
            groupId = stringField(root, "groupId", eventType),
            eventRef = decodeEntityRef(objectField(root, "eventRef", eventType))
        )
    }
}

private object DataStaleCodec : DomainEventCodec<DomainEvent.DataStale>() {
    override val eventClass = DomainEvent.DataStale::class
    override val eventType = "data.stale"
    override val schemaVersion = 1

    override fun encodePayload(value: DomainEvent.DataStale): CanonicalJson = CanonicalJson.ofValue(
        jsonObject(
            "capability" to JsonValue.Str(value.capability.name),
            "since" to JsonValue.Str(value.since.toString())
        )
    )

    override fun decodePayload(payload: CanonicalJson, header: DomainEventHeader): DomainEvent.DataStale {
        val root = payloadObject(payload, eventType, setOf("capability", "since"))
        return DomainEvent.DataStale(
            eventId = header.eventId,
            scope = header.scope,
            occurredAt = header.occurredAt,
            capability = decodeEnum<CapabilityKind>(root, "capability", eventType),
            since = instantField(root, "since", eventType)
        )
    }
}

private fun jsonObject(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
    JsonValue.Obj(linkedMapOf(*fields))

private fun canonicalNumber(raw: String): JsonValue.Number =
    JsonValue.Number(JsonCanonicalizer.canonicalize(raw))

private fun payloadObject(payload: CanonicalJson, eventType: String, expectedKeys: Set<String>): JsonValue.Obj {
    val root = payload.toJsonValue() as? JsonValue.Obj
        ?: throw IllegalArgumentException(eventType + " payload 必须是 JSON object")
    requireExactKeys(root, expectedKeys, eventType + " payload")
    return root
}

private fun requireExactKeys(obj: JsonValue.Obj, expectedKeys: Set<String>, label: String) {
    if (obj.entries.keys != expectedKeys) {
        throw IllegalArgumentException(label + " 字段集合不匹配: " + obj.entries.keys.sorted())
    }
}

private fun requiredField(obj: JsonValue.Obj, key: String, label: String): JsonValue =
    obj.entries[key] ?: throw IllegalArgumentException(label + " 缺少字段 " + key)

private fun objectField(obj: JsonValue.Obj, key: String, label: String): JsonValue.Obj =
    requiredField(obj, key, label) as? JsonValue.Obj
        ?: throw IllegalArgumentException(label + " 字段 " + key + " 必须是 object")

private fun arrayField(obj: JsonValue.Obj, key: String, label: String): List<JsonValue> =
    (requiredField(obj, key, label) as? JsonValue.Arr)?.values
        ?: throw IllegalArgumentException(label + " 字段 " + key + " 必须是 array")

private fun stringField(obj: JsonValue.Obj, key: String, label: String): String =
    (requiredField(obj, key, label) as? JsonValue.Str)?.value
        ?: throw IllegalArgumentException(label + " 字段 " + key + " 必须是 string")

private fun nullableStringField(obj: JsonValue.Obj, key: String, label: String): String? =
    when (val value = requiredField(obj, key, label)) {
        JsonValue.Null -> null
        is JsonValue.Str -> value.value
        else -> throw IllegalArgumentException(label + " 字段 " + key + " 必须是 string 或 null")
    }

private fun boolField(obj: JsonValue.Obj, key: String, label: String): Boolean =
    (requiredField(obj, key, label) as? JsonValue.Bool)?.value
        ?: throw IllegalArgumentException(label + " 字段 " + key + " 必须是 boolean")

private fun numberField(obj: JsonValue.Obj, key: String, label: String): String =
    (requiredField(obj, key, label) as? JsonValue.Number)?.lexeme
        ?: throw IllegalArgumentException(label + " 字段 " + key + " 必须是 number")

private fun intField(obj: JsonValue.Obj, key: String, label: String): Int =
    numberField(obj, key, label).toIntOrNull()
        ?: throw IllegalArgumentException(label + " 字段 " + key + " 必须是 Int")

private fun longField(obj: JsonValue.Obj, key: String, label: String): Long =
    numberField(obj, key, label).toLongOrNull()
        ?: throw IllegalArgumentException(label + " 字段 " + key + " 必须是 Long")

private fun doubleField(obj: JsonValue.Obj, key: String, label: String): Double =
    parseFiniteDouble(numberField(obj, key, label), label + " 字段 " + key)

private fun nullableDoubleField(obj: JsonValue.Obj, key: String, label: String): Double? =
    when (requiredField(obj, key, label)) {
        JsonValue.Null -> null
        is JsonValue.Number -> parseFiniteDouble(numberField(obj, key, label), label + " 字段 " + key)
        else -> throw IllegalArgumentException(label + " 字段 " + key + " 必须是 number 或 null")
    }

private fun floatField(obj: JsonValue.Obj, key: String, label: String): Float {
    val value = numberField(obj, key, label).toFloatOrNull()
        ?: throw IllegalArgumentException(label + " 字段 " + key + " 必须是 Float")
    if (!value.isFinite()) throw IllegalArgumentException(label + " 字段 " + key + " 必须是有限 Float")
    return value
}

private fun parseFiniteDouble(lexeme: String, label: String): Double {
    val value = lexeme.toDoubleOrNull()
        ?: throw IllegalArgumentException(label + " 必须是 Double")
    if (!value.isFinite()) throw IllegalArgumentException(label + " 必须是有限 Double")
    return value
}

private fun instantField(obj: JsonValue.Obj, key: String, label: String): Instant =
    parseInstant(stringField(obj, key, label), label + " 字段 " + key)

private fun nullableInstantField(obj: JsonValue.Obj, key: String, label: String): Instant? =
    nullableStringField(obj, key, label)?.let { parseInstant(it, label + " 字段 " + key) }

private fun parseInstant(raw: String, label: String): Instant = try {
    Instant.parse(raw)
} catch (error: DateTimeParseException) {
    throw IllegalArgumentException(label + " 必须是 ISO Instant", error)
}

private inline fun <reified T : Enum<T>> decodeEnum(obj: JsonValue.Obj, key: String, label: String): T {
    val raw = stringField(obj, key, label)
    return enumValues<T>().firstOrNull { it.name == raw }
        ?: throw IllegalArgumentException(label + " 字段 " + key + " 含未知枚举值: " + raw)
}

private fun encodeScope(scope: SchoolAccountScope): JsonValue.Obj = jsonObject(
    "accountId" to JsonValue.Str(scope.accountId.value),
    "campusId" to (scope.campusId?.let { JsonValue.Str(it.value) } ?: JsonValue.Null),
    "profileId" to JsonValue.Str(scope.profileId.value),
    "schoolId" to JsonValue.Str(scope.schoolId.value)
)

private fun decodeScope(value: JsonValue.Obj): SchoolAccountScope {
    requireExactKeys(value, setOf("accountId", "campusId", "profileId", "schoolId"), "SchoolAccountScope")
    return SchoolAccountScope(
        schoolId = SchoolId(stringField(value, "schoolId", "SchoolAccountScope")),
        accountId = AccountId(stringField(value, "accountId", "SchoolAccountScope")),
        profileId = ProfileId(stringField(value, "profileId", "SchoolAccountScope")),
        campusId = nullableStringField(value, "campusId", "SchoolAccountScope")?.let(::CampusId)
    )
}

private fun encodeEntityRef(ref: EntityRef): JsonValue.Obj = jsonObject(
    "id" to JsonValue.Str(ref.id),
    "scope" to encodeScope(ref.scope),
    "type" to JsonValue.Str(ref.type.name)
)

private fun decodeEntityRef(value: JsonValue): EntityRef {
    val obj = value as? JsonValue.Obj
        ?: throw IllegalArgumentException("EntityRef 必须是 object")
    requireExactKeys(obj, setOf("id", "scope", "type"), "EntityRef")
    return EntityRef(
        type = decodeEnum<EntityType>(obj, "type", "EntityRef"),
        id = stringField(obj, "id", "EntityRef"),
        scope = decodeScope(objectField(obj, "scope", "EntityRef"))
    )
}

private fun encodeFieldDiff(diff: FieldDiff): JsonValue.Obj = jsonObject(
    "changes" to JsonValue.Arr(diff.changes.map(::encodeFieldChange))
)

private fun decodeFieldDiff(value: JsonValue.Obj): FieldDiff {
    requireExactKeys(value, setOf("changes"), "FieldDiff")
    return FieldDiff(arrayField(value, "changes", "FieldDiff").map(::decodeFieldChange))
}

private fun encodeFieldChange(change: FieldChange): JsonValue.Obj = jsonObject(
    "after" to encodeNullableModelJson(change.after),
    "before" to encodeNullableModelJson(change.before),
    "kind" to JsonValue.Str(change.kind.name),
    "path" to JsonValue.Str(change.path)
)

private fun decodeFieldChange(value: JsonValue): FieldChange {
    val obj = value as? JsonValue.Obj
        ?: throw IllegalArgumentException("FieldChange 必须是 object")
    requireExactKeys(obj, setOf("after", "before", "kind", "path"), "FieldChange")
    return FieldChange(
        path = stringField(obj, "path", "FieldChange"),
        kind = decodeEnum<FieldChangeKind>(obj, "kind", "FieldChange"),
        before = decodeNullableModelJson(objectField(obj, "before", "FieldChange")),
        after = decodeNullableModelJson(objectField(obj, "after", "FieldChange"))
    )
}

private fun encodeNullableModelJson(value: ModelJsonValue?): JsonValue.Obj = jsonObject(
    "present" to JsonValue.Bool(value != null),
    "value" to (value?.let(::modelToEventJson) ?: JsonValue.Null)
)

private fun decodeNullableModelJson(value: JsonValue.Obj): ModelJsonValue? {
    requireExactKeys(value, setOf("present", "value"), "NullableJsonValue")
    val present = boolField(value, "present", "NullableJsonValue")
    val raw = requiredField(value, "value", "NullableJsonValue")
    if (!present) {
        if (raw != JsonValue.Null) {
            throw IllegalArgumentException("NullableJsonValue absent 时 value 必须是 null")
        }
        return null
    }
    return eventToModelJson(raw)
}

private fun modelToEventJson(value: ModelJsonValue): JsonValue {
    val canonical = ModelJson.parse(ModelJson.canonical(value))
    return canonicalModelToEventJson(canonical)
}

private fun canonicalModelToEventJson(value: ModelJsonValue): JsonValue = when (value) {
    is ModelJsonValue.Obj -> JsonValue.Obj(value.fields.mapValues { (_, child) -> canonicalModelToEventJson(child) })
    is ModelJsonValue.Arr -> JsonValue.Arr(value.items.map(::canonicalModelToEventJson))
    is ModelJsonValue.Str -> JsonValue.Str(value.value)
    is ModelJsonValue.Num -> canonicalNumber(value.literal)
    is ModelJsonValue.Bool -> JsonValue.Bool(value.value)
    ModelJsonValue.Null -> JsonValue.Null
}

private fun eventToModelJson(value: JsonValue): ModelJsonValue =
    ModelJson.parse(JsonCanonicalizer.write(value))

private fun encodeDataProvenance(provenance: DataProvenance): JsonValue.Obj = jsonObject(
    "accountId" to JsonValue.Str(provenance.accountId.value),
    "completeness" to JsonValue.Str(provenance.completeness.name),
    "contentRevision" to (provenance.contentRevision?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    "fetchedAt" to (provenance.fetchedAt?.let { JsonValue.Str(it.toString()) } ?: JsonValue.Null),
    "providerId" to JsonValue.Str(provenance.providerId),
    "schemaVersion" to canonicalNumber(provenance.schemaVersion.toString()),
    "schoolId" to JsonValue.Str(provenance.schoolId.value),
    "sourceRecordId" to (provenance.sourceRecordId?.let { JsonValue.Str(it) } ?: JsonValue.Null),
    "sourceSystem" to JsonValue.Str(provenance.sourceSystem),
    "trust" to JsonValue.Str(provenance.trust.name)
)

private fun decodeDataProvenance(value: JsonValue.Obj): DataProvenance {
    requireExactKeys(
        value,
        setOf(
            "accountId",
            "completeness",
            "contentRevision",
            "fetchedAt",
            "providerId",
            "schemaVersion",
            "schoolId",
            "sourceRecordId",
            "sourceSystem",
            "trust"
        ),
        "DataProvenance"
    )
    return DataProvenance(
        providerId = stringField(value, "providerId", "DataProvenance"),
        schoolId = SchoolId(stringField(value, "schoolId", "DataProvenance")),
        accountId = AccountId(stringField(value, "accountId", "DataProvenance")),
        sourceSystem = stringField(value, "sourceSystem", "DataProvenance"),
        sourceRecordId = nullableStringField(value, "sourceRecordId", "DataProvenance"),
        fetchedAt = nullableInstantField(value, "fetchedAt", "DataProvenance"),
        completeness = decodeEnum<SnapshotCompleteness>(value, "completeness", "DataProvenance"),
        trust = decodeEnum<VerificationLevel>(value, "trust", "DataProvenance"),
        schemaVersion = intField(value, "schemaVersion", "DataProvenance"),
        contentRevision = nullableStringField(value, "contentRevision", "DataProvenance")
    )
}
