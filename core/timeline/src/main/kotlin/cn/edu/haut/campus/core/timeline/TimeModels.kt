package cn.edu.haut.campus.core.timeline

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 半开时间窗 [start, end)。统一使用半开区间，避免相邻事件在边界上重复计算。 */
data class TimeWindow(
    val start: Instant,
    val end: Instant
) {
    init {
        require(end.isAfter(start)) { "时间窗 end 必须晚于 start" }
    }

    val duration: Duration
        get() = Duration.between(start, end)

    operator fun contains(instant: Instant): Boolean = !instant.isBefore(start) && instant.isBefore(end)

    fun overlaps(other: TimeWindow): Boolean = start.isBefore(other.end) && other.start.isBefore(end)

    fun intersection(other: TimeWindow): TimeWindow? {
        val start = if (this.start.isAfter(other.start)) this.start else other.start
        val end = if (this.end.isBefore(other.end)) this.end else other.end
        return if (end.isAfter(start)) TimeWindow(start, end) else null
    }
}

/**
 * 校区本地一天内的可用时间窗，end 为开区间。
 * start 晚于 end 时表示跨午夜窗口（例如 22:00–次日 06:00）；二者相等非法，
 * 因为那会混淆「零长度」和「全天」。
 */
data class LocalTimeWindow(
    val start: LocalTime,
    val end: LocalTime
) {
    init {
        require(end != start) { "本地时间窗 start 与 end 不能相同" }
    }

    val crossesMidnight: Boolean
        get() = !end.isAfter(start)

    fun toTimeWindow(startDate: LocalDate, zone: ZoneId): TimeWindow {
        val endDate = if (crossesMidnight) startDate.plusDays(1) else startDate
        val startAt = startDate.atTime(start).atZone(zone).toInstant()
        val endAt = endDate.atTime(end).atZone(zone).toInstant()
        return TimeWindow(startAt, endAt)
    }
}

/** 时间参与忙闲计算的级别；FREE 事件会出现在时间流中，但不占用可用时间。 */
enum class BusyLevel {
    BUSY,
    FREE,
    TENTATIVE
}

/**
 * 隐私最小化的占用时间块：只有起止和占用级别，不携带事件 ID、标题、地点或来源。
 * TENTATIVE 与 BUSY 分开，调用方可决定 tentative 是否占用空闲；FREE 不允许成为块。
 */
data class BusyBlock(
    val start: Instant,
    val end: Instant,
    val level: BusyLevel
) {
    init {
        require(end.isAfter(start)) { "忙闲块 end 必须晚于 start" }
        require(level != BusyLevel.FREE) { "FREE 事件不参与忙闲块" }
    }

    val window: TimeWindow
        get() = TimeWindow(start, end)
}

/** 满足最小时长和约束的一段空闲时间，同样只含时间，不含事件元数据。 */
data class FreeSlot(
    val start: Instant,
    val end: Instant
) {
    init {
        require(end.isAfter(start)) { "空闲块 end 必须晚于 start" }
    }

    val duration: Duration
        get() = Duration.between(start, end)
}

/**
 * freeSlots 的本地约束。
 *
 * [zone] 必须显式传入校区时区，禁止回落到设备默认时区。[allowedDays] 为空表示每周
 * 任意日；[dailyWindows] 为空表示全天。tentative 默认按占用处理，这是更保守的可用性
 * 语义，避免把暂不确定的时间误报成可约空闲。
 */
data class SlotConstraints(
    val zone: ZoneId,
    val allowedDays: Set<DayOfWeek> = emptySet(),
    val dailyWindows: List<LocalTimeWindow> = emptyList(),
    val tentativeCountsAsBusy: Boolean = true
) {
    init {
        require(dailyWindows.distinct().size == dailyWindows.size) { "每日可用时间窗不能重复" }
    }
}
