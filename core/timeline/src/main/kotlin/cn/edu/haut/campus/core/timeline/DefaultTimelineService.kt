package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** source 投影违反命名、隔离或幂等约束时 fail-closed，而不是静默合并错误数据。 */
class TimelineConsistencyException(message: String) : IllegalStateException(message)

/**
 * 纯 JVM 的 TimelineSource 聚合实现。
 *
 * 服务不信任 source 已经正确裁剪窗口：所有事件都会重新按半开窗口过滤。跨 scope、
 * ID source 前缀与 emitting source 不符、同 ID 同 revision 但内容冲突都会 fail-closed；
 * 完全相同的重复投影幂等去重，较高 revision 替换较低 revision。
 */
class DefaultTimelineService(
    sources: List<TimelineSource>
) : TimelineService {
    private val sources = sources.toList()

    init {
        require(sources.map { it.id }.distinct().size == sources.size) { "TimelineSource id 不能重复" }
        sources.forEach { source ->
            requireValidSourceId(source.id)
            require(source.kinds.isNotEmpty()) { "TimelineSource ${source.id} 至少声明一种 TimelineKind" }
        }
    }

    constructor(vararg sources: TimelineSource) : this(sources.toList())

    override fun observe(
        scope: SchoolAccountScope,
        window: TimeWindow,
        filter: TimelineFilter
    ): Flow<List<TimelineEvent>> {
        val selected = sources.filter { source ->
            source.kinds.any(filter::includesKind) && (filter.sourceIds.isEmpty() || source.id in filter.sourceIds)
        }
        if (selected.isEmpty()) return flowOf(emptyList())

        val flows = selected.map { source ->
            source.observe(scope, window).map { events -> SourceSnapshot(source.id, source.kinds, events) }
        }
        return combine(flows) { snapshots: Array<SourceSnapshot> ->
            mergeSnapshots(scope, window, filter, snapshots.toList())
        }
    }

    override suspend fun busyBlocks(
        scope: SchoolAccountScope,
        window: TimeWindow,
        granularity: Duration
    ): List<BusyBlock> {
        val events = observe(scope, window, TimelineFilter.ALL).first()
        return BusyFreeCalculator.busyBlocks(window, busyIntervals(events), granularity)
    }

    override suspend fun freeSlots(
        scope: SchoolAccountScope,
        window: TimeWindow,
        minLength: Duration,
        constraints: SlotConstraints
    ): List<FreeSlot> {
        val events = observe(scope, window, TimelineFilter.ALL).first()
        return BusyFreeCalculator.freeSlots(window, busyIntervals(events), minLength, constraints)
    }

    private fun mergeSnapshots(
        scope: SchoolAccountScope,
        window: TimeWindow,
        filter: TimelineFilter,
        snapshots: List<SourceSnapshot>
    ): List<TimelineEvent> {
        // 先按 (id, revision) 做全量一致性检查，再选择最高 revision，最后才应用窗口和
        // 调用方过滤。这样新 revision 取消或移出窗口时不会让旧 revision 复活；同
        // revision 冲突也不会因高 revision 先出现而被顺序依赖地吞掉。
        val revisionsById = LinkedHashMap<TimelineEventId, MutableMap<Long, TimelineEvent>>()
        for (snapshot in snapshots) {
            for (event in snapshot.events) {
                if (event.id.sourceId != snapshot.sourceId) {
                    throw TimelineConsistencyException(
                        "事件 ${event.id.value} 的 source 前缀与 emitting source ${snapshot.sourceId} 不一致"
                    )
                }
                if (event.scope != scope) {
                    throw TimelineConsistencyException("事件 ${event.id.value} 跨学校账号 scope，已拒绝合并")
                }
                if (event.kind !in snapshot.kinds) {
                    throw TimelineConsistencyException(
                        "事件 ${event.id.value} kind ${event.kind} 不在 source ${snapshot.sourceId} 声明的 kinds 内"
                    )
                }

                val byRevision = revisionsById.getOrPut(event.id) { sortedMapOf() }
                val sameRevision = byRevision[event.revision]
                if (sameRevision == null) {
                    byRevision[event.revision] = event
                } else if (sameRevision != event) {
                    throw TimelineConsistencyException("事件 ${event.id.value} 同 revision 内容冲突")
                }
            }
        }
        return revisionsById.values
            .mapNotNull { byRevision -> byRevision.maxByOrNull { it.key }?.value }
            .filter { event -> event.overlaps(window) && filter.matches(event) }
            .sortedWith(
                compareBy<TimelineEvent> { it.start }
                    .thenBy { it.end ?: it.start }
                    .thenBy { it.id.value }
            )
    }

    private fun busyIntervals(events: List<TimelineEvent>): List<BusyBlock> =
        events.mapNotNull { event ->
            // DONE 历史上仍占用时间；RESCHEDULED 是生效的调入事件；CANCELLED_TODAY 不占忙。
            if (event.busy == BusyLevel.FREE || event.status == EventStatus.CANCELLED_TODAY) {
                null
            } else {
                event.windowOrNull?.let { BusyBlock(it.start, it.end, event.busy) }
            }
        }

    private fun requireValidSourceId(id: String) {
        require(id.length in 1..128) { "TimelineSource id 为空或过长" }
        require(id.all { it.isLetterOrDigit() || it in "_-." }) { "TimelineSource id 只能包含字母数字、下划线、短横线或点" }
    }

    private data class SourceSnapshot(
        val sourceId: String,
        val kinds: Set<TimelineKind>,
        val events: List<TimelineEvent>
    )
}
