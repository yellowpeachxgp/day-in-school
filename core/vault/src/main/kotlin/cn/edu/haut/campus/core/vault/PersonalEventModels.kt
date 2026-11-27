package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineKind
import java.time.Instant
import java.time.ZoneId

/** personal_events 的用户/自动化/收件箱写入草稿。id 为 null 时由 store 生成 personal:<uuid>。 */
data class PersonalEventDraft(
    val id: TimelineEventId? = null,
    val scope: SchoolAccountScope,
    val kind: TimelineKind,
    val title: String,
    val note: String? = null,
    val start: Instant,
    val end: Instant? = null,
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
        require(placeText == null || placeText.length <= 512) { "个人日程地点文本过长" }
        require(place == null || place.schoolId == scope.schoolId) { "个人日程地点必须属于同一学校" }
        require(place == null || scope.campusId == place.campusId) { "个人日程地点必须属于 scope 校区" }
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

class PersonalEventNotFoundException(id: TimelineEventId) :
    IllegalArgumentException("personal event 不存在: " + id.value)
