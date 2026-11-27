package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SourceRef
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

/**
 * 跨同步稳定的 Timeline 事件 ID，格式为 `<source>:<stableKey>`。
 * source 段是受限 sourceId；stableKey 可包含冒号，解析时只按第一个冒号分界。
 */
@JvmInline
value class TimelineEventId(val value: String) {
    init {
        require(value.length in 3..512) { "TimelineEventId 为空或过长" }
        require(value.none { it.isISOControl() || it.isWhitespace() }) { "TimelineEventId 不能包含空白或控制字符" }
        require(value.all { it.isLetterOrDigit() || it in "_-./:" }) { "TimelineEventId 包含非法字符" }
        val separator = value.indexOf(':')
        require(separator > 0 && separator < value.lastIndex) { "TimelineEventId 必须是 <source>:<stableKey>" }
        require(value.substring(0, separator).all { it.isLetterOrDigit() || it in "_-." }) {
            "TimelineEventId source 段包含非法字符"
        }
    }

    val sourceId: String
        get() = value.substringBefore(':')

    val stableKey: String
        get() = value.substringAfter(':')
}

enum class TimelineKind {
    COURSE,
    EXAM,
    PRACTICE,
    PERSONAL,
    TASK_DUE,
    GROUP_MEETING,
    REVIEW_BLOCK,
    FOCUS,
    RESERVATION,
    PROCEDURE_STEP,
    OPPORTUNITY_MILESTONE,
    DORM_DUTY
}

enum class EventStatus {
    SCHEDULED,
    CANCELLED_TODAY,
    RESCHEDULED,
    TENTATIVE,
    DONE
}

/**
 * 统一时间流事件。
 *
 * [scope]、[source] 与 [provenance] 都没有默认值。[zone] 必须是事件所在校区使用的时区，
 * 而不是设备默认时区。[end] 为 null 表示截止时间点；有 end 时必须是正时长半开区间。
 * RESCHEDULED 表示「生效的调入/改期事件」；被调出的原 occurrence 应以 CANCELLED_TODAY
 * 单独投影，因此 RESCHEDULED 默认仍参与忙闲，CANCELLED_TODAY 不参与。
 */
data class TimelineEvent(
    val id: TimelineEventId,
    val scope: SchoolAccountScope,
    val kind: TimelineKind,
    val title: String,
    val start: Instant,
    val end: Instant?,
    val zone: ZoneId,
    val place: PlaceRef?,
    val source: SourceRef,
    val provenance: DataProvenance,
    val status: EventStatus,
    val busy: BusyLevel,
    val revision: Long,
    val links: List<EntityRef> = emptyList()
) {
    init {
        require(title.isNotBlank() && title.length <= 256) { "事件标题为空或过长" }
        require(title.none { it.isISOControl() }) { "事件标题不能包含控制字符" }
        if (end != null) {
            require(end.isAfter(start)) { "定时事件 end 必须晚于 start；截止时间点应使用 null end" }
        }
        require(revision >= 0) { "事件 revision 不能为负" }
        require(provenance.schoolId == scope.schoolId) { "事件证据的学校与 scope 不一致" }
        require(provenance.accountId == scope.accountId) { "事件证据的账号与 scope 不一致" }
        require(source.entity.scope == scope) { "来源实体必须使用与事件相同的学校账号 scope" }
        require(place == null || place.schoolId == scope.schoolId) { "地点必须属于事件 scope 的学校" }
        require(links.distinct().size == links.size) { "事件 links 不能重复" }
        require(links.all { it.scope == scope }) { "事件 links 必须属于同一学校账号 scope" }
    }

    val isDeadline: Boolean
        get() = end == null

    val windowOrNull: TimeWindow?
        get() = end?.let { TimeWindow(start, it) }

    fun overlaps(window: TimeWindow): Boolean {
        val eventEnd = end
        return if (eventEnd == null) {
            window.contains(start)
        } else {
            start.isBefore(window.end) && eventEnd.isAfter(window.start)
        }
    }
}

/** 时间流过滤。空 kinds/statuses/sourceIds 都表示不限制；默认 [ALL] 不排除任何事件。 */
data class TimelineFilter(
    val kinds: Set<TimelineKind> = emptySet(),
    val statuses: Set<EventStatus> = emptySet(),
    val sourceIds: Set<String> = emptySet()
) {
    init {
        require(sourceIds.all { it.isNotBlank() && it.none { ch -> ch.isISOControl() || ch.isWhitespace() } }) {
            "TimelineFilter sourceId 为空或含空白/控制字符"
        }
    }

    fun includesKind(kind: TimelineKind): Boolean = kinds.isEmpty() || kind in kinds

    fun matches(event: TimelineEvent): Boolean =
        includesKind(event.kind) &&
            (statuses.isEmpty() || event.status in statuses) &&
            (sourceIds.isEmpty() || event.id.sourceId in sourceIds)

    companion object {
        val ALL: TimelineFilter = TimelineFilter()
    }
}

/**
 * Timeline 投影来源。
 *
 * 每个 emission 都是请求窗口内当前完整快照，首个 emission 必须尽快代表当前状态；
 * busyBlocks/freeSlots 依赖首个快照做 suspend 计算。source 只负责投影，不做跨领域迁移。
 */
interface TimelineSource {
    val id: String
    val kinds: Set<TimelineKind>
    fun observe(scope: SchoolAccountScope, window: TimeWindow): Flow<List<TimelineEvent>>
}

interface TimelineService {
    fun observe(
        scope: SchoolAccountScope,
        window: TimeWindow,
        filter: TimelineFilter = TimelineFilter.ALL
    ): Flow<List<TimelineEvent>>

    suspend fun busyBlocks(
        scope: SchoolAccountScope,
        window: TimeWindow,
        granularity: Duration = Duration.ofMinutes(30)
    ): List<BusyBlock>

    suspend fun freeSlots(
        scope: SchoolAccountScope,
        window: TimeWindow,
        minLength: Duration,
        constraints: SlotConstraints
    ): List<FreeSlot>
}
