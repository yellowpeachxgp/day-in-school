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
 * personal_events 的写入草案。表由 W1-03 落地；本接口放在纯 JVM Timeline 内核，
 * 使用户日程、待办截止、群组会议与复习块共享同一稳定 TimelineEventId 语义。
 */
data class PersonalEventDraft(
    val id: TimelineEventId? = null,
    val scope: SchoolAccountScope,
    val kind: TimelineKind,
    val title: String,
    val note: String? = null,
    val start: Instant,
    val end: Instant? = null,
    val timeSemantics: TimelineTimeSemantics = if (end == null) {
        TimelineTimeSemantics.DEADLINE
    } else {
        TimelineTimeSemantics.TIMED
    },
    val zone: ZoneId,
    val placeText: String? = null,
    val place: PlaceRef? = null,
    val status: EventStatus = EventStatus.SCHEDULED,
    val busy: BusyLevel = BusyLevel.BUSY,
    val termId: String? = null,
    val provenance: DataProvenance,
    val sourceEntity: EntityRef? = null,
    val links: List<EntityRef> = emptyList()
) {
    init {
        require(kind in ALLOWED_KINDS) { "personal_events 不支持 TimelineKind " + kind }
        if (id != null) {
            require(id.sourceId == PERSONAL_TIMELINE_SOURCE) { "个人日程 TimelineEventId source 必须是 personal" }
        }
        require(title.isNotBlank() && title.length <= 256) { "个人日程标题为空或过长" }
        require(title.none { it.isISOControl() }) { "个人日程标题不能包含控制字符" }
        require(note == null || note.length <= 16 * 1024) { "个人日程备注过长" }
        if (end != null) require(end.isAfter(start)) { "定时个人日程 end 必须晚于 start" }
        when (timeSemantics) {
            TimelineTimeSemantics.TIMED -> require(end != null) { "TIMED 个人日程必须有 end" }
            TimelineTimeSemantics.DEADLINE -> require(end == null) { "DEADLINE 个人日程 end 必须为 null" }
            TimelineTimeSemantics.UNCERTAIN -> require(end == null) { "UNCERTAIN 个人日程不能伪造 end" }
        }
        require(timeSemantics != TimelineTimeSemantics.UNCERTAIN || busy == BusyLevel.FREE) {
            "不确定个人日程没有可信占用窗口，busy 必须为 FREE"
        }
        require(status != EventStatus.CANCELLED_TODAY || busy == BusyLevel.FREE) {
            "取消的个人日程不占忙，busy 必须为 FREE"
        }
        require(placeText == null || placeText.length <= 512) { "个人日程地点文本过长" }
        require(place == null || place.schoolId == scope.schoolId) { "个人日程地点必须属于同一学校" }
        require(termId == null || termId.isNotBlank()) { "termId 不能为空白" }
        require(provenance.schoolId == scope.schoolId) { "provenance 学校与 scope 不一致" }
        require(provenance.accountId == scope.accountId) { "provenance 账号与 scope 不一致" }
        require(provenance.providerId.isNotBlank() && provenance.sourceSystem.isNotBlank()) { "provenance 缺少 provider/sourceSystem" }
        require(sourceEntity == null || sourceEntity.scope == scope) { "sourceEntity 必须与个人日程同 scope" }
        require(links.distinct().size == links.size) { "个人日程 links 不能重复" }
        require(links.all { it.scope == scope }) { "个人日程 links 必须同 scope" }
    }

    companion object {
        const val PERSONAL_TIMELINE_SOURCE = "personal"
        val ALLOWED_KINDS: Set<TimelineKind> = setOf(
            TimelineKind.PERSONAL,
            TimelineKind.TASK_DUE,
            TimelineKind.GROUP_MEETING,
            TimelineKind.REVIEW_BLOCK
        )
    }
}

interface PersonalEventStore {
    suspend fun upsert(event: PersonalEventDraft, actor: Actor): TimelineEventId
    suspend fun delete(id: TimelineEventId, actor: Actor)
}

/**
 * personal_events 的纯 JVM 读取模型。id 是完整 TimelineEventId（含 personal: 前缀），
 * 与 W1-03 表行主键保持一致；TimelineSource 不再拼接或改写身份。timeSemantics
 * 显式区分真实截止点与时间证据不完整的锚点，避免 null end 被误读。
 */
data class PersonalEventRecord(
    val id: TimelineEventId,
    val scope: SchoolAccountScope,
    val kind: TimelineKind,
    val title: String,
    val start: Instant,
    val end: Instant?,
    val timeSemantics: TimelineTimeSemantics = if (end == null) {
        TimelineTimeSemantics.DEADLINE
    } else {
        TimelineTimeSemantics.TIMED
    },
    val zone: ZoneId,
    val place: PlaceRef?,
    val status: EventStatus,
    val busy: BusyLevel,
    val revision: Long,
    val provenance: DataProvenance,
    val sourceEntity: EntityRef? = null,
    val links: List<EntityRef> = emptyList()
) {
    init {
        require(id.sourceId == PersonalEventDraft.PERSONAL_TIMELINE_SOURCE) { "个人日程 TimelineEventId source 必须是 personal" }
        require(kind in PersonalEventDraft.ALLOWED_KINDS) { "personal_events 不支持 TimelineKind " + kind }
        require(title.isNotBlank() && title.length <= 256) { "个人日程标题为空或过长" }
        require(title.none { it.isISOControl() }) { "个人日程标题不能包含控制字符" }
        if (end != null) require(end.isAfter(start)) { "个人日程 end 必须晚于 start；截止时间点应使用 null end" }
        when (timeSemantics) {
            TimelineTimeSemantics.TIMED -> require(end != null) { "TIMED 个人日程必须有 end" }
            TimelineTimeSemantics.DEADLINE -> require(end == null) { "DEADLINE 个人日程 end 必须为 null" }
            TimelineTimeSemantics.UNCERTAIN -> require(end == null) { "UNCERTAIN 个人日程不能伪造 end" }
        }
        require(timeSemantics != TimelineTimeSemantics.UNCERTAIN || busy == BusyLevel.FREE) {
            "不确定个人日程没有可信占用窗口，busy 必须为 FREE"
        }
        require(status != EventStatus.CANCELLED_TODAY || busy == BusyLevel.FREE) {
            "取消的个人日程不占忙，busy 必须为 FREE"
        }
        require(revision >= 0) { "个人日程 revision 不能为负" }
        require(place == null || place.schoolId == scope.schoolId) { "个人日程地点必须属于同一学校" }
        require(provenance.schoolId == scope.schoolId) { "provenance 学校与 scope 不一致" }
        require(provenance.accountId == scope.accountId) { "provenance 账号与 scope 不一致" }
        require(sourceEntity == null || sourceEntity.scope == scope) { "sourceEntity 必须与个人日程同 scope" }
        require(links.distinct().size == links.size) { "个人日程 links 不能重复" }
        require(links.all { it.scope == scope }) { "个人日程 links 必须同 scope" }
    }
}

fun interface PersonalEventTimelineInputProvider {
    fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<PersonalEventRecord>>
}

/** 个人日程 TimelineSource；只投影，不承担 W1-03 才落地的 personal_events 写入职责。 */
class PersonalTimelineSource(
    private val inputProvider: PersonalEventTimelineInputProvider
) : TimelineSource {
    override val id: String = PersonalEventDraft.PERSONAL_TIMELINE_SOURCE
    override val kinds: Set<TimelineKind> = PersonalEventDraft.ALLOWED_KINDS

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

    private fun PersonalEventRecord.toTimelineEvent(requestScope: SchoolAccountScope): TimelineEvent {
        require(scope == requestScope) { "个人日程记录 scope 与请求 scope 不一致" }
        val selfRef = EntityRef(EntityType.PERSONAL_EVENT, id.value, scope)
        val eventProvenance = provenance.copy(sourceRecordId = provenance.sourceRecordId ?: id.value)
        return TimelineEvent(
            id = id,
            scope = scope,
            kind = kind,
            title = title,
            start = start,
            end = end,
            timeSemantics = timeSemantics,
            zone = zone,
            place = place,
            source = SourceRef(sourceEntity ?: selfRef, eventProvenance),
            provenance = eventProvenance,
            status = status,
            busy = busy,
            revision = revision,
            links = links
        )
    }
}
