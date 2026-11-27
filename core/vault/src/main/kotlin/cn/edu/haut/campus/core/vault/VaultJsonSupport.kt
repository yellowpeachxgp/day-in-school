package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.SourceRef
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.time.Instant

internal const val VAULT_ENTITY_CHANGED_EVENT_TYPE = "vault.entity.changed"
internal const val VAULT_ENTITY_CHANGED_SCHEMA_VERSION = 1
internal const val MAX_ENTITY_SNAPSHOT_BYTES = 64 * 1024

internal object VaultScopeKeyCodec {
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
        require(parts.size == 3 || parts.size == 4) { "scopeKey 必须是 school/account/profile[/campus]" }
        return SchoolAccountScope(
            schoolId = SchoolId(parts[0]),
            accountId = AccountId(parts[1]),
            profileId = ProfileId(parts[2]),
            campusId = parts.getOrNull(3)?.let(::CampusId)
        )
    }
}

internal fun canonicalJsonOf(value: JsonValue): CanonicalJson = CanonicalJson.of(Json.canonical(value))

internal fun parseCanonicalObject(snapshot: CanonicalJson): JsonValue.Obj =
    Json.parse(snapshot.text).asObjOrNull ?: throw IllegalArgumentException("Vault 快照必须是 JSON object")

internal fun validateSnapshotSafety(snapshot: CanonicalJson?) {
    if (snapshot == null) return
    val size = snapshot.text.toByteArray(Charsets.UTF_8).size
    require(size <= MAX_ENTITY_SNAPSHOT_BYTES) { "Vault 快照超过 64 KiB 上限: " + size + " bytes" }
    validateJsonKeysSafe(Json.parse(snapshot.text))
}

private fun validateJsonKeysSafe(value: JsonValue) {
    when (value) {
        is JsonValue.Obj -> value.fields.forEach { (key, child) ->
            validateNotSensitiveFieldName(key)
            validateJsonKeysSafe(child)
        }
        is JsonValue.Arr -> value.items.forEach(::validateJsonKeysSafe)
        else -> Unit
    }
}

private fun validateNotSensitiveFieldName(rawName: String) {
    val normalized = buildString(rawName.length) {
        rawName.lowercase().forEach { ch -> if (ch.isLetterOrDigit()) append(ch) }
    }
    if (normalized.isEmpty()) return
    val forbiddenMarkers = listOf(
        "password", "passwd", "credential", "cookie", "authorization", "token",
        "secret", "apikey", "aikey", "privatekey", "sessionid"
    )
    if (forbiddenMarkers.any { marker -> normalized.contains(marker) }) {
        throw SensitiveSnapshotException(rawName)
    }
}

internal fun jsonString(value: String?): JsonValue = value?.let(JsonValue::Str) ?: JsonValue.Null
internal fun jsonLong(value: Long?): JsonValue = value?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null
internal fun jsonInt(value: Int?): JsonValue = value?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null
internal fun jsonBool(value: Boolean?): JsonValue = value?.let(JsonValue::Bool) ?: JsonValue.Null

internal fun jsonObjectOf(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
    JsonValue.Obj(fields.toMap())

internal fun JsonValue.Obj.requiredField(key: String): JsonValue =
    fields[key] ?: throw IllegalArgumentException("Vault JSON 缺少字段 " + key)

internal fun JsonValue.Obj.requiredString(key: String): String =
    requiredField(key).stringOrNull() ?: throw IllegalArgumentException("Vault JSON 字段不是 string: " + key)

internal fun JsonValue.Obj.nullableString(key: String): String? =
    fields[key]?.stringOrNull()

internal fun JsonValue.longOrNull(): Long? = (this as? JsonValue.Num)?.literal?.toLongOrNull()

internal fun JsonValue.Obj.requiredLong(key: String): Long =
    requiredField(key).longOrNull() ?: throw IllegalArgumentException("Vault JSON 字段不是 integer: " + key)

internal fun JsonValue.Obj.nullableLong(key: String): Long? = fields[key]?.longOrNull()

internal fun JsonValue.Obj.requiredInt(key: String): Int =
    requiredField(key).intOrNull() ?: throw IllegalArgumentException("Vault JSON 字段不是 integer: " + key)

internal fun JsonValue.Obj.nullableInt(key: String): Int? = fields[key]?.intOrNull()

internal fun JsonValue.Obj.requiredBoolean(key: String): Boolean =
    requiredField(key).boolOrNull() ?: throw IllegalArgumentException("Vault JSON 字段不是 boolean: " + key)

internal fun JsonValue.Obj.optionalBoolean(key: String, defaultValue: Boolean): Boolean =
    fields[key]?.boolOrNull() ?: defaultValue

internal fun JsonValue.Obj.requiredObject(key: String): JsonValue.Obj =
    requiredField(key).asObjOrNull ?: throw IllegalArgumentException("Vault JSON 字段不是 object: " + key)

internal fun JsonValue.Obj.requiredArray(key: String): List<JsonValue> =
    requiredField(key).asArrOrNull?.items ?: throw IllegalArgumentException("Vault JSON 字段不是 array: " + key)

internal fun JsonValue.Obj.optionalArray(key: String): List<JsonValue> =
    fields[key]?.asArrOrNull?.items ?: emptyList()

internal fun encodeEntityRef(ref: EntityRef): JsonValue.Obj = jsonObjectOf(
    "type" to JsonValue.Str(ref.type.name),
    "id" to JsonValue.Str(ref.id),
    "scope" to encodeScope(ref.scope)
)

internal fun decodeEntityRef(value: JsonValue): EntityRef {
    val obj = value.asObjOrNull ?: throw IllegalArgumentException("EntityRef 必须是 object")
    return EntityRef(
        type = EntityType.valueOf(obj.requiredString("type")),
        id = obj.requiredString("id"),
        scope = decodeScope(obj.requiredField("scope"))
    )
}

internal fun encodeScope(scope: SchoolAccountScope): JsonValue.Obj = jsonObjectOf(
    "schoolId" to JsonValue.Str(scope.schoolId.value),
    "accountId" to JsonValue.Str(scope.accountId.value),
    "profileId" to JsonValue.Str(scope.profileId.value),
    "campusId" to jsonString(scope.campusId?.value)
)

internal fun decodeScope(value: JsonValue): SchoolAccountScope {
    val obj = value.asObjOrNull ?: throw IllegalArgumentException("scope 必须是 object")
    return SchoolAccountScope(
        schoolId = SchoolId(obj.requiredString("schoolId")),
        accountId = AccountId(obj.requiredString("accountId")),
        profileId = ProfileId(obj.requiredString("profileId")),
        campusId = obj.nullableString("campusId")?.let(::CampusId)
    )
}

internal fun encodeProvenance(provenance: DataProvenance): JsonValue.Obj = jsonObjectOf(
    "providerId" to JsonValue.Str(provenance.providerId),
    "schoolId" to JsonValue.Str(provenance.schoolId.value),
    "accountId" to JsonValue.Str(provenance.accountId.value),
    "sourceSystem" to JsonValue.Str(provenance.sourceSystem),
    "sourceRecordId" to jsonString(provenance.sourceRecordId),
    "fetchedAt" to jsonString(provenance.fetchedAt?.toString()),
    "completeness" to JsonValue.Str(provenance.completeness.name),
    "trust" to JsonValue.Str(provenance.trust.name),
    "schemaVersion" to JsonValue.Num(provenance.schemaVersion.toString()),
    "contentRevision" to jsonString(provenance.contentRevision)
)

internal fun decodeProvenance(value: JsonValue): DataProvenance {
    val obj = value.asObjOrNull ?: throw IllegalArgumentException("DataProvenance 必须是 object")
    return DataProvenance(
        providerId = obj.requiredString("providerId"),
        schoolId = SchoolId(obj.requiredString("schoolId")),
        accountId = AccountId(obj.requiredString("accountId")),
        sourceSystem = obj.requiredString("sourceSystem"),
        sourceRecordId = obj.nullableString("sourceRecordId"),
        fetchedAt = obj.nullableString("fetchedAt")?.let(Instant::parse),
        completeness = SnapshotCompleteness.valueOf(obj.requiredString("completeness")),
        trust = VerificationLevel.valueOf(obj.requiredString("trust")),
        schemaVersion = obj.requiredInt("schemaVersion"),
        contentRevision = obj.nullableString("contentRevision")
    )
}

internal fun encodeSourceRef(source: SourceRef): JsonValue.Obj = jsonObjectOf(
    "entity" to encodeEntityRef(source.entity),
    "provenance" to encodeProvenance(source.provenance)
)

internal fun decodeSourceRef(value: JsonValue): SourceRef {
    val obj = value.asObjOrNull ?: throw IllegalArgumentException("SourceRef 必须是 object")
    return SourceRef(
        entity = decodeEntityRef(obj.requiredField("entity")),
        provenance = decodeProvenance(obj.requiredField("provenance"))
    )
}
