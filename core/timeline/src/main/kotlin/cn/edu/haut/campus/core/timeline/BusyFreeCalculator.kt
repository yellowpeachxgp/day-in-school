package cn.edu.haut.campus.core.timeline

import java.time.Duration
import java.time.Instant

/**
 * 忙闲与空闲的纯函数计算器。
 *
 * 公开 busyBlocks 会先把占用裁剪到请求窗口，再按 granularity 向外取整并重新合并。
 * 向外取整是刻意的隐私与可用性保守语义：宁可少报空隙，也不泄漏分钟级课程边界或
 * 把过窄空隙当成可约时间。freeSlots 使用未取整的本地占用计算，供本机精确排程。
 */
object BusyFreeCalculator {

    fun busyBlocks(
        window: TimeWindow,
        intervals: List<BusyBlock>,
        granularity: Duration
    ): List<BusyBlock> {
        requirePositiveWholeSeconds(granularity, "granularity")
        val clipped = intervals.mapNotNull { block ->
            window.intersection(block.window)?.let { BusyBlock(it.start, it.end, block.level) }
        }
        val exact = normalize(clipped)
        val step = granularity.seconds
        val rounded = exact.mapNotNull { block ->
            val roundedWindow = TimeWindow(roundDown(block.start, step), roundUp(block.end, step))
            window.intersection(roundedWindow)?.let { BusyBlock(it.start, it.end, block.level) }
        }
        return normalize(rounded)
    }

    fun freeSlots(
        window: TimeWindow,
        intervals: List<BusyBlock>,
        minLength: Duration,
        constraints: SlotConstraints
    ): List<FreeSlot> {
        require(!minLength.isZero && !minLength.isNegative) { "minLength 必须为正时长" }
        val clipped = intervals.mapNotNull { block ->
            if (!constraints.tentativeCountsAsBusy && block.level == BusyLevel.TENTATIVE) {
                null
            } else {
                window.intersection(block.window)?.let { BusyBlock(it.start, it.end, block.level) }
            }
        }
        val occupied = normalize(clipped)
        val exactGaps = subtract(window, occupied)
        val constrained = applyConstraints(exactGaps, window, constraints)
        return constrained
            .filter { it.duration.compareTo(minLength) >= 0 }
            .map { FreeSlot(it.start, it.end) }
    }

    /**
     * 将重叠块规范化为互不重叠的块。BUSY 优先于 TENTATIVE；完全重叠的 TENTATIVE
     * 不会重复输出，邻接且同级的块会合并。
     */
    internal fun normalize(blocks: List<BusyBlock>): List<BusyBlock> {
        if (blocks.isEmpty()) return emptyList()
        val deltas = HashMap<Instant, IntArray>()
        for (block in blocks) {
            val startDelta = deltas.getOrPut(block.start) { intArrayOf(0, 0) }
            val endDelta = deltas.getOrPut(block.end) { intArrayOf(0, 0) }
            when (block.level) {
                BusyLevel.BUSY -> {
                    startDelta[0] += 1
                    endDelta[0] -= 1
                }
                BusyLevel.TENTATIVE -> {
                    startDelta[1] += 1
                    endDelta[1] -= 1
                }
                BusyLevel.FREE -> throw IllegalArgumentException("FREE 事件不参与忙闲块")
            }
        }

        val segments = mutableListOf<BusyBlock>()
        var busyCount = 0
        var tentativeCount = 0
        var activeStart: Instant? = null
        var activeLevel: BusyLevel? = null
        for (time in deltas.keys.sorted()) {
            val start = activeStart
            val level = activeLevel
            if (start != null && level != null && time.isAfter(start)) {
                segments += BusyBlock(start, time, level)
            }

            val delta = deltas.getValue(time)
            busyCount += delta[0]
            tentativeCount += delta[1]
            activeLevel = when {
                busyCount > 0 -> BusyLevel.BUSY
                tentativeCount > 0 -> BusyLevel.TENTATIVE
                else -> null
            }
            activeStart = if (activeLevel == null) null else time
        }
        return mergeAdjacentSameLevel(segments)
    }

    private fun subtract(window: TimeWindow, occupied: List<BusyBlock>): List<TimeWindow> {
        var gaps = listOf(window)
        for (block in occupied) {
            val next = mutableListOf<TimeWindow>()
            for (gap in gaps) {
                val overlap = gap.intersection(block.window)
                if (overlap == null) {
                    next += gap
                    continue
                }
                if (overlap.start.isAfter(gap.start)) {
                    next += TimeWindow(gap.start, overlap.start)
                }
                if (overlap.end.isBefore(gap.end)) {
                    next += TimeWindow(overlap.end, gap.end)
                }
            }
            gaps = next
        }
        return gaps
    }

    private fun applyConstraints(
        gaps: List<TimeWindow>,
        window: TimeWindow,
        constraints: SlotConstraints
    ): List<TimeWindow> {
        if (gaps.isEmpty()) return emptyList()
        if (constraints.allowedDays.isEmpty() && constraints.dailyWindows.isEmpty()) return gaps

        val zone = constraints.zone
        val firstDate = window.start.atZone(zone).toLocalDate().minusDays(1)
        val lastDate = window.end.minusNanos(1).atZone(zone).toLocalDate()
        val constraintWindows = mutableListOf<TimeWindow>()
        var date = firstDate
        while (!date.isAfter(lastDate)) {
            val dayAllowed = constraints.allowedDays.isEmpty() || constraints.allowedDays.contains(date.dayOfWeek)
            if (dayAllowed) {
                if (constraints.dailyWindows.isEmpty()) {
                    constraintWindows += TimeWindow(
                        date.atStartOfDay(zone).toInstant(),
                        date.plusDays(1).atStartOfDay(zone).toInstant()
                    )
                } else {
                    constraints.dailyWindows.forEach { localWindow ->
                        constraintWindows += localWindow.toTimeWindow(date, zone)
                    }
                }
            }
            date = date.plusDays(1)
        }

        val intersections = mutableListOf<TimeWindow>()
        for (gap in gaps) {
            for (constraint in constraintWindows) {
                gap.intersection(constraint)?.let { intersections += it }
            }
        }
        return mergeWindows(intersections)
    }

    private fun mergeWindows(windows: List<TimeWindow>): List<TimeWindow> {
        val sorted = windows.sortedWith(compareBy<TimeWindow> { it.start }.thenBy { it.end })
        val merged = mutableListOf<TimeWindow>()
        for (window in sorted) {
            val last = merged.lastOrNull()
            if (last != null && !window.start.isAfter(last.end)) {
                val end = if (window.end.isAfter(last.end)) window.end else last.end
                merged[merged.lastIndex] = TimeWindow(last.start, end)
            } else {
                merged += window
            }
        }
        return merged
    }

    private fun mergeAdjacentSameLevel(segments: List<BusyBlock>): List<BusyBlock> {
        val merged = mutableListOf<BusyBlock>()
        for (segment in segments) {
            val last = merged.lastOrNull()
            if (last != null && last.level == segment.level && last.end == segment.start) {
                merged[merged.lastIndex] = BusyBlock(last.start, segment.end, last.level)
            } else {
                merged += segment
            }
        }
        return merged
    }

    private fun requirePositiveWholeSeconds(duration: Duration, name: String) {
        require(!duration.isZero && !duration.isNegative) { "$name 必须为正时长" }
        require(duration.nano == 0) { "$name 必须是整秒粒度" }
    }

    private fun roundDown(instant: Instant, stepSeconds: Long): Instant {
        val aligned = Math.floorDiv(instant.epochSecond, stepSeconds) * stepSeconds
        return Instant.ofEpochSecond(aligned)
    }

    private fun roundUp(instant: Instant, stepSeconds: Long): Instant {
        val aligned = Math.floorDiv(instant.epochSecond, stepSeconds) * stepSeconds
        return if (instant.epochSecond == aligned && instant.nano == 0) {
            Instant.ofEpochSecond(aligned)
        } else {
            Instant.ofEpochSecond(aligned + stepSeconds)
        }
    }
}
