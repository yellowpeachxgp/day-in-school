package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SourceRef
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * personal_events 的纯 JVM 读取模型。W1-03 建表前，本卡只定义接口与投影；
 * 记录自带证据、版本与时区，Timeline 不为个人日程伪造来源。
 */
data class PersonalEventRecord(
    val id: String,
    val title: String,
    val start: Instant,
    val end: Instant?,
    val zone: ZoneId,
    val place: PlaceRef?,
    val status: EventStatus,
    val busy: BusyLevel,
    val revision: Long,
    val provenance: DataProvenance,
    val links: List<EntityRef> = emptyList()
) {
    init {
        require(title.isNotBlank() && title.length <= 256) { "个人日程标题为空或过长" }
        require(title.none { it.isISOControl() }) { "个人日程标题不能包含控制字符" }
        if (end != null) require(end.isAfter(start)) { "个人日程 end 必须晚于 start；截止时间点应使用 null end" }
        require(revision >= 0) { "个人日程 revision 不能为负" }
        require(links.distinct().size == links.size) { "个人日程 links 不能重复" }
    }
}

/** personal_events 写入草案；W1-03 的 Room 实现负责分配/校验稳定 ID、revision 与 journal。 */
data class PersonalEventDraft(
    val id: TimelineEventId? = null,
    val title: String,
    val start: Instant,
    val end: Instant?,
    val zone: ZoneId,
    val place: PlaceRef? = null,
    val status: EventStatus = EventStatus.SCHEDULED,
    val busy: BusyLevel = BusyLevel.BUSY,
    val links: List<EntityRef> = emptyList()
) {
    init {
        require(id == null || id.sourceId == PERSONAL_TIMELINE_SOURCE_ID) { "个人日程 ID 必须使用 personal source 前缀" }
        require(title.isNotBlank() && title.length <= 256) { "个人日程标题为空或过长" }
        require(title.none { it.isISOControl() }) { "个人日程标题不能包含控制字符" }
        if (end != null) require(end.isAfter(start)) { "个人日程 end 必须晚于 start；截止时间点应使用 null end" }
        require(links.distinct().size == links.size) { "个人日程 links 不能重复" }
    }
}

interface PersonalEventStore {
    suspend fun upsert(event: PersonalEventDraft, actor: Actor): TimelineEventId
    suspend fun delete(id: TimelineEventId, actor: Actor)
}

fun interface PersonalEventTimelineInputProvider {
    fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<PersonalEventRecord>>
}

/** 个人日程 TimelineSource；只投影，不承担 W1-03 才落地的 personal_events 写入职责。 */
class PersonalTimelineSource(
    private val inputProvider: PersonalEventTimelineInputProvider
) : TimelineSource {
    override val id: String = PERSONAL_TIMELINE_SOURCE_ID
    override val kinds: Set<TimelineKind> = setOf(TimelineKind.PERSONAL)

    override fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<TimelineEvent>> =
        inputProvider.observe(scope, window).map { records ->
            require(records.map { it.id }.distinct().size == records.size) { "个人日程 ID 重复，Timeline 拒绝歧义投影" }
            records.map { it.toTimelineEvent(scope) }
                .filter { it.overlaps(window) }
                .sortedWith(
                    compareBy<TimelineEvent> { it.start }
                        .thenBy { it.end ?: it.start }
                        .thenBy { it.id.value }
                )
        }

    private fun PersonalEventRecord.toTimelineEvent(scope: SchoolAccountScope): TimelineEvent {
        require(provenance.schoolId == scope.schoolId) { "个人日程证据学校与 scope 不一致" }
        require(provenance.accountId == scope.accountId) { "个人日程证据账号与 scope 不一致" }
        val personalEntity = EntityRef(EntityType.PERSONAL_EVENT, id, scope)
        val eventProvenance = provenance.copy(sourceRecordId = provenance.sourceRecordId ?: id)
        return TimelineEvent(
            id = TimelineEventId(PERSONAL_TIMELINE_SOURCE_ID + ":" + id),
            scope = scope,
            kind = TimelineKind.PERSONAL,
            title = title,
            start = start,
            end = end,
            zone = zone,
            place = place,
            source = SourceRef(personalEntity, eventProvenance),
            provenance = eventProvenance,
            status = status,
            busy = busy,
            revision = revision,
            links = links
        )
    }
}

const val PERSONAL_TIMELINE_SOURCE_ID = "personal"
