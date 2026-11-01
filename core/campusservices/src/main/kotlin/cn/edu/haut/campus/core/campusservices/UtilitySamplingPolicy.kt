/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.campusservices

import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 每日电表采样点领域模型。
 */
data class UtilityDailySample(
    val id: String,
    val roomId: String,
    val roomName: String,
    val deviceName: String,
    val dateText: String,
    val usedValue: Double,
    val unit: String,
    val sampledAtEpochMs: Long
)

/**
 * 折线图趋势点模型（支持通过 segmentBreak 表达缺测日或未知购电分段）。
 */
data class UtilityTrendPoint(
    val label: String,
    val value: Double,
    val unit: String = "kWh",
    val subtitle: String = "",
    val segmentBreakBefore: Boolean = false
)

/**
 * 智能水电每日采样策略（北京时间 Asia/Shanghai 自然日归档）。
 *
 * 核心规则：
 * 1. 白昼（23:00 前）采样最小间隔 60 分钟；23:00–23:59 冲刺窗口最小间隔 15 分钟。
 * 2. 若请求发起时刻 `startedAtEpochMs` 与完成时刻 `finishedAtEpochMs` 跨越了北京时间零点（`!sameDay`），
 *    直接丢弃本次采样，绝不将前一日累计值错记到次日或覆盖前一日快照。
 * 3. 同一房间内若存在同名电表设备（`deviceName` 重复），跳过整间房采样，防止后写覆盖前写。
 * 4. 仅提取纯数字或带 `度` / `kWh` 单位的电表 `todayuse`，遇到 `元` 等货币单位或负数立即跳过。
 * 5. 同一 `(roomId, deviceName, dateText)` 仅当 `finishedAtEpochMs >= existing.sampledAtEpochMs` 时单调更新。
 */
object UtilitySamplingPolicy {

    const val DAYTIME_INTERVAL_MS = 60 * 60_000L
    const val LATE_NIGHT_INTERVAL_MS = 15 * 60_000L
    const val MAX_RETAINED_SAMPLES = 730

    fun localDateInShanghai(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(HAUT_ZONE_ID).toLocalDate()

    fun dateKey(epochMs: Long): String =
        localDateInShanghai(epochMs).toString()

    fun sameDay(firstEpochMs: Long, secondEpochMs: Long): Boolean =
        localDateInShanghai(firstEpochMs) == localDateInShanghai(secondEpochMs)

    /**
     * 判断当前时刻是否到达周期采样窗口。
     */
    fun shouldSample(lastSampledAtEpochMs: Long, nowEpochMs: Long): Boolean {
        if (lastSampledAtEpochMs <= 0L) return true
        if (!sameDay(lastSampledAtEpochMs, nowEpochMs)) return true
        val shanghaiHour = Instant.ofEpochMilli(nowEpochMs).atZone(HAUT_ZONE_ID).hour
        val minInterval = if (shanghaiHour >= 23) LATE_NIGHT_INTERVAL_MS else DAYTIME_INTERVAL_MS
        return nowEpochMs - lastSampledAtEpochMs >= minInterval
    }

    /**
     * 将单次在线查询结果合并进每日电表采样历史。
     */
    fun mergeDailySamples(
        existingSamples: List<UtilityDailySample>,
        rooms: List<OnlineUtilityRoomSnapshot>,
        startedAtEpochMs: Long,
        finishedAtEpochMs: Long = startedAtEpochMs
    ): List<UtilityDailySample> {
        if (startedAtEpochMs <= 0L || finishedAtEpochMs < startedAtEpochMs) {
            return existingSamples
        }
        if (!sameDay(startedAtEpochMs, finishedAtEpochMs)) {
            return existingSamples
        }

        val currentDateText = dateKey(finishedAtEpochMs)
        val mergedByKey = existingSamples
            .associateBy { Triple(it.roomId, it.deviceName, it.dateText) }
            .toMutableMap()

        for (room in rooms) {
            if (room.failed || room.roomId.isBlank()) continue
            val electricDevices = room.devices.filter {
                it.category == UtilityMeterCategory.ELECTRICITY
            }
            val cleanNames = electricDevices.map { it.name.trim() }
            if (cleanNames.any { it.isBlank() || it == WanxiaoProtocolParser.MISSING_PLACEHOLDER }) {
                continue
            }
            if (cleanNames.distinct().size != cleanNames.size) {
                // 同房间存在重名电表，拒绝覆盖采样
                continue
            }

            for (device in electricDevices) {
                val (usedKwh, unit) = WanxiaoProtocolParser.parseElectricTodayUseKwh(device.todayUseRaw)
                    ?: continue
                val sampleTimeMs = room.fetchedAtEpochMs.takeIf {
                    it in startedAtEpochMs..finishedAtEpochMs
                } ?: finishedAtEpochMs

                val key = Triple(room.roomId, device.name.trim(), currentDateText)
                val previous = mergedByKey[key]
                if (previous != null && sampleTimeMs < previous.sampledAtEpochMs) {
                    continue
                }
                val sampleId = previous?.id ?: "${room.roomId}:${device.name.trim()}:$currentDateText"
                mergedByKey[key] = UtilityDailySample(
                    id = sampleId,
                    roomId = room.roomId,
                    roomName = room.roomName,
                    deviceName = device.name.trim(),
                    dateText = currentDateText,
                    usedValue = usedKwh,
                    unit = unit,
                    sampledAtEpochMs = sampleTimeMs
                )
            }
        }

        return mergedByKey.values
            .sortedWith(compareBy({ it.dateText }, { it.sampledAtEpochMs }, { it.roomId }, { it.deviceName }))
            .takeLast(MAX_RETAINED_SAMPLES)
    }

    /**
     * 构建指定房间与电表的每日趋势点列表：
     * 当相邻两条采样日期间隔超过 1 天（缺测日）时，自动将后一点标记为 `segmentBreakBefore = true`，
     * 使折线图在缺测处断开，不绘制虚假跨日直线。
     */
    fun buildDailyTrendPoints(
        samples: List<UtilityDailySample>,
        roomId: String,
        deviceName: String,
        maxPoints: Int = 14
    ): List<UtilityTrendPoint> {
        val filtered = samples
            .filter { it.roomId == roomId && it.deviceName == deviceName }
            .sortedBy { it.dateText }
            .takeLast(maxPoints)

        var previousDate: LocalDate? = null
        return filtered.map { sample ->
            val currentDate = runCatching { LocalDate.parse(sample.dateText) }.getOrNull()
            val gapDays = if (previousDate != null && currentDate != null) {
                ChronoUnit.DAYS.between(previousDate, currentDate)
            } else {
                1L
            }
            previousDate = currentDate
            val shortLabel = sample.dateText.takeLast(5) // MM-dd
            UtilityTrendPoint(
                label = shortLabel,
                value = sample.usedValue,
                unit = sample.unit,
                subtitle = "${sample.dateText} · ${"%.2f".format(sample.usedValue)} ${sample.unit}",
                segmentBreakBefore = gapDays != 1L
            )
        }
    }
}
