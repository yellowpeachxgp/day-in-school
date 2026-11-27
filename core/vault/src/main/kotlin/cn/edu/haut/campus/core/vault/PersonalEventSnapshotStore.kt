package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.PersonalEventEntity
import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.json.JsonValue

/** personal_events 行级快照；恢复时同步维护 search_docs，禁止撤销后留下 FTS 幽灵结果。 */
class PersonalEventSnapshotStore(
    database: HautDatabase,
    private val searchIndex: RoomSearchIndex
) : SearchIndexedEntitySnapshotStore {
    private val dao = database.vaultDao()
    override val type: EntityType = EntityType.PERSONAL_EVENT

    override suspend fun read(ref: EntityRef): CanonicalJson? {
        require(ref.type == type) { "PersonalEventSnapshotStore 只能读取 PERSONAL_EVENT" }
        val row = dao.getPersonalEvent(ref.id) ?: return null
        if (!row.inScope(ref.scope)) return null
        return canonicalJsonOf(
            jsonObjectOf(
                "format" to JsonValue.Str(FORMAT),
                "row" to encodePersonalEvent(row)
            )
        )
    }

    override suspend fun projectSearchDocument(ref: EntityRef): SearchDocument? =
        dao.getPersonalEvent(ref.id)
            ?.takeIf { it.inScope(ref.scope) }
            ?.let { personalEventSearchDocument(it, ref.scope) }

    override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) {
        require(ref.type == type) { "PersonalEventSnapshotStore 只能恢复 PERSONAL_EVENT" }
        if (snapshot == null) {
            dao.deletePersonalEventById(ref.id)
            searchIndex.delete(ref)
            return
        }
        val root = parseCanonicalObject(snapshot)
        require(root.requiredString("format") == FORMAT) { "个人日程快照格式不受支持" }
        val row = decodePersonalEvent(root.requiredObject("row"))
        require(row.id == ref.id) { "个人日程快照 id 与 EntityRef 不一致" }
        require(row.inScope(ref.scope)) { "个人日程快照 scope 与 EntityRef 不一致" }
        dao.upsertPersonalEvent(row)
        searchIndex.upsert(personalEventSearchDocument(row, ref.scope))
    }

    private companion object {
        const val FORMAT = "personal-event/v1"
    }
}

internal fun PersonalEventEntity.inScope(scope: SchoolAccountScope): Boolean =
    scopeSchoolId == scope.schoolId.value &&
        scopeAccountId == scope.accountId.value &&
        scopeProfileId == scope.profileId.value &&
        scopeCampusId == scope.campusId?.value

internal fun PersonalEventEntity.scope(): SchoolAccountScope = SchoolAccountScope(
    schoolId = SchoolId(scopeSchoolId),
    accountId = AccountId(scopeAccountId),
    profileId = ProfileId(scopeProfileId),
    campusId = scopeCampusId?.let(::CampusId)
)

internal fun encodePersonalEvent(row: PersonalEventEntity): JsonValue.Obj = jsonObjectOf(
    "id" to JsonValue.Str(row.id),
    "scopeSchoolId" to JsonValue.Str(row.scopeSchoolId),
    "scopeAccountId" to JsonValue.Str(row.scopeAccountId),
    "scopeProfileId" to JsonValue.Str(row.scopeProfileId),
    "scopeCampusId" to jsonString(row.scopeCampusId),
    "kind" to JsonValue.Str(row.kind),
    "title" to JsonValue.Str(row.title),
    "note" to jsonString(row.note),
    "startAtEpochMs" to JsonValue.Num(row.startAtEpochMs.toString()),
    "endAtEpochMs" to jsonLong(row.endAtEpochMs),
    "zoneId" to JsonValue.Str(row.zoneId),
    "placeText" to jsonString(row.placeText),
    "placeRefId" to jsonString(row.placeRefId),
    "status" to JsonValue.Str(row.status),
    "busy" to JsonValue.Str(row.busy),
    "termId" to jsonString(row.termId),
    "revision" to JsonValue.Num(row.revision.toString()),
    "provenanceJson" to JsonValue.Str(row.provenanceJson),
    "sourceEntityType" to jsonString(row.sourceEntityType),
    "sourceEntityId" to jsonString(row.sourceEntityId),
    "linksJson" to JsonValue.Str(row.linksJson),
    "createdAtEpochMs" to JsonValue.Num(row.createdAtEpochMs.toString()),
    "updatedAtEpochMs" to JsonValue.Num(row.updatedAtEpochMs.toString())
)

internal fun decodePersonalEvent(obj: JsonValue.Obj): PersonalEventEntity = PersonalEventEntity(
    id = obj.requiredString("id"),
    scopeSchoolId = obj.requiredString("scopeSchoolId"),
    scopeAccountId = obj.requiredString("scopeAccountId"),
    scopeProfileId = obj.requiredString("scopeProfileId"),
    scopeCampusId = obj.nullableString("scopeCampusId"),
    kind = obj.requiredString("kind"),
    title = obj.requiredString("title"),
    note = obj.nullableString("note"),
    startAtEpochMs = obj.requiredLong("startAtEpochMs"),
    endAtEpochMs = obj.nullableLong("endAtEpochMs"),
    zoneId = obj.requiredString("zoneId"),
    placeText = obj.nullableString("placeText"),
    placeRefId = obj.nullableString("placeRefId"),
    status = obj.requiredString("status"),
    busy = obj.requiredString("busy"),
    termId = obj.nullableString("termId"),
    revision = obj.requiredLong("revision"),
    provenanceJson = obj.requiredString("provenanceJson"),
    sourceEntityType = obj.nullableString("sourceEntityType"),
    sourceEntityId = obj.nullableString("sourceEntityId"),
    linksJson = obj.requiredString("linksJson"),
    createdAtEpochMs = obj.requiredLong("createdAtEpochMs"),
    updatedAtEpochMs = obj.requiredLong("updatedAtEpochMs")
)
