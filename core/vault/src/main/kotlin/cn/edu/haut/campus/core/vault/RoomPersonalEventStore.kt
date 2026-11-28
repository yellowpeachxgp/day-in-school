package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.PersonalEventEntity
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SourceRef
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.PersonalEventDraft
import cn.edu.haut.campus.core.timeline.PersonalEventRecord
import cn.edu.haut.campus.core.timeline.PersonalEventStore
import cn.edu.haut.campus.core.timeline.PersonalEventTimelineInputProvider
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** Room personal_events 实现；业务行、journal、outbox 与 search_docs 更新都复用 EntityWriter 的同一事务边界。 */
class RoomPersonalEventStore(
    private val database: HautDatabase,
    private val scope: SchoolAccountScope,
    private val writer: EntityWriter,
    private val searchIndex: RoomSearchIndex,
    private val clock: () -> Instant = Instant::now,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) : PersonalEventStore, PersonalEventTimelineInputProvider {
    private val dao = database.vaultDao()

    override suspend fun upsert(event: PersonalEventDraft, actor: Actor): TimelineEventId =
        upsert(event = event, actor = actor, writeGroupId = null)

    suspend fun upsert(event: PersonalEventDraft, actor: Actor, writeGroupId: String?): TimelineEventId {
        require(event.scope == scope) { "PersonalEventStore 只能写入本 profile scope" }
        val timelineId = event.id ?: TimelineEventId(
            PersonalEventDraft.PERSONAL_TIMELINE_SOURCE + ":" + idGenerator()
        )
        require(timelineId.sourceId == PersonalEventDraft.PERSONAL_TIMELINE_SOURCE) {
            "个人日程 TimelineEventId source 必须是 personal"
        }
        val ref = EntityRef(EntityType.PERSONAL_EVENT, timelineId.value, scope)
        val source = event.sourceEntity?.let { SourceRef(it, event.provenance) }
        return writer.write(
            ref = ref,
            actor = actor,
            actorDetail = "personal-event-upsert",
            source = source,
            writeGroupId = writeGroupId
        ) {
            val nowMs = clock().toEpochMilli()
            val existing = dao.getPersonalEvent(timelineId.value)
            val row = PersonalEventEntity(
                id = timelineId.value,
                scopeSchoolId = scope.schoolId.value,
                scopeAccountId = scope.accountId.value,
                scopeProfileId = scope.profileId.value,
                scopeCampusId = scope.campusId?.value,
                kind = event.kind.name,
                title = event.title,
                note = event.note,
                startAtEpochMs = event.start.toEpochMilli(),
                endAtEpochMs = event.end?.toEpochMilli(),
                timeSemantics = event.timeSemantics.name,
                zoneId = event.zone.id,
                placeText = event.placeText,
                placeRefId = event.place?.id,
                status = event.status.name,
                busy = event.busy.name,
                termId = event.termId,
                revision = (existing?.revision ?: 0L) + 1L,
                provenanceJson = canonicalJsonOf(encodeProvenance(event.provenance)).text,
                sourceEntityType = event.sourceEntity?.type?.name,
                sourceEntityId = event.sourceEntity?.id,
                linksJson = encodeEntityRefList(event.links),
                createdAtEpochMs = existing?.createdAtEpochMs ?: nowMs,
                updatedAtEpochMs = nowMs
            )
            if (existing == null || !existing.sameUserContentAs(row)) {
                dao.upsertPersonalEvent(row)
            }
            // 即使内容未变也幂等修复索引；EntityWriter 会因实体快照无变化而不制造审计噪声。
            searchIndex.upsert(personalEventSearchDocument(row, scope))
            timelineId
        }
    }

    override suspend fun delete(id: TimelineEventId, actor: Actor) {
        delete(id = id, actor = actor, writeGroupId = null)
    }

    suspend fun delete(
        id: TimelineEventId,
        actor: Actor,
        writeGroupId: String?,
        source: SourceRef? = null
    ) {
        require(id.sourceId == PersonalEventDraft.PERSONAL_TIMELINE_SOURCE) {
            "个人日程 TimelineEventId source 必须是 personal"
        }
        val ref = EntityRef(EntityType.PERSONAL_EVENT, id.value, scope)
        writer.write(
            ref = ref,
            actor = actor,
            actorDetail = "personal-event-delete",
            source = source,
            writeGroupId = writeGroupId
        ) {
            dao.deletePersonalEventById(id.value)
            searchIndex.delete(ref)
        }
    }

    private val timelineInputProvider = RoomPersonalEventTimelineInputProvider(database, scope)

    override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<PersonalEventRecord>> =
        timelineInputProvider.observe(scope, window)

    private fun PersonalEventEntity.sameUserContentAs(other: PersonalEventEntity): Boolean =
        id == other.id &&
            scopeSchoolId == other.scopeSchoolId &&
            scopeAccountId == other.scopeAccountId &&
            scopeProfileId == other.scopeProfileId &&
            scopeCampusId == other.scopeCampusId &&
            kind == other.kind &&
            title == other.title &&
            note == other.note &&
            startAtEpochMs == other.startAtEpochMs &&
            endAtEpochMs == other.endAtEpochMs &&
            timeSemantics == other.timeSemantics &&
            zoneId == other.zoneId &&
            placeText == other.placeText &&
            placeRefId == other.placeRefId &&
            status == other.status &&
            busy == other.busy &&
            termId == other.termId &&
            provenanceJson == other.provenanceJson &&
            sourceEntityType == other.sourceEntityType &&
            sourceEntityId == other.sourceEntityId &&
            linksJson == other.linksJson
}

internal fun encodeEntityRefList(links: List<EntityRef>): String =
    canonicalJsonOf(JsonValue.Arr(links.map(::encodeEntityRef))).text

internal fun decodeEntityRefList(raw: String): List<EntityRef> =
    Json.parse(raw).asArrOrNull?.items?.map(::decodeEntityRef) ?: emptyList()

internal fun PersonalEventEntity.toPersonalEventRecord(): PersonalEventRecord {
    val rowScope = scope()
    val provenance = decodeProvenance(Json.parse(provenanceJson))
    val sourceType = sourceEntityType
    val sourceId = sourceEntityId
    val sourceEntity = if (sourceType != null && sourceId != null) {
        EntityRef(EntityType.valueOf(sourceType), sourceId, rowScope)
    } else {
        null
    }
    val place = placeRefId?.let { placeId ->
        val segments = placeId.split('/')
        require(segments.size == 4) { "个人日程 placeRefId 不是稳定地点图 ID" }
        PlaceRef(placeId, rowScope.schoolId, CampusId(segments[1]))
    }
    return PersonalEventRecord(
        id = TimelineEventId(id),
        scope = rowScope,
        kind = TimelineKind.valueOf(kind),
        title = title,
        start = Instant.ofEpochMilli(startAtEpochMs),
        end = endAtEpochMs?.let(Instant::ofEpochMilli),
        timeSemantics = TimelineTimeSemantics.valueOf(timeSemantics),
        zone = ZoneId.of(zoneId),
        place = place,
        status = EventStatus.valueOf(status),
        busy = BusyLevel.valueOf(busy),
        revision = revision,
        provenance = provenance,
        sourceEntity = sourceEntity,
        links = decodeEntityRefList(linksJson)
    )
}
