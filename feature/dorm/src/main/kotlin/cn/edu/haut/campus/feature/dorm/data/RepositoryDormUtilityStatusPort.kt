package cn.edu.haut.campus.feature.dorm.data

import cn.edu.haut.campus.core.campusservices.UtilityMeterCategory
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.DormElectricityNotificationSubsystem
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.feature.dorm.DormUtilityReminder
import cn.edu.haut.campus.feature.dorm.DormUtilityStatusPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 只读取当前 Profile 已授权保存的本机水电缓存/手工台账；不触发网络刷新、自动化、支付或通知。 */
class RepositoryDormUtilityStatusPort(
    private val repository: CampusRepository,
) : DormUtilityStatusPort {
    override fun reminders(): Flow<List<DormUtilityReminder>> = combine(
        repository.utilityOnlineRoomsFlow,
        repository.utilityMetersFlow,
        repository.utilityReadingsFlow,
        repository.utilityDailySamplesFlow,
        repository.preferencesFlow,
    ) { onlineRooms, meters, readings, dailySamples, prefs ->
        val threshold = prefs.utilityLowBalanceThreshold.toDouble()
        val online = onlineRooms.flatMap { room ->
            runCatching { decodeOnlineDevicesJson(room.devicesJson) }.getOrDefault(emptyList()).map { device ->
                val balanceNumber = DormElectricityNotificationSubsystem.parseBalanceNumber(device.balanceRaw)
                val deviceName = device.name.trim().ifBlank { device.category.displayLabel + "表" }
                val usage = dailySamples
                    .filter { it.roomId == room.roomId && it.deviceName == deviceName && it.usedValue > 0.0 }
                    .sortedByDescending { it.dateText }
                    .take(7)
                val estimate = if (balanceNumber != null && usage.isNotEmpty()) {
                    val average = usage.map { it.usedValue }.average()
                    if (average > 0.0) {
                        val days = (balanceNumber / average).toInt().coerceAtLeast(0)
                        "；按近 " + usage.size + " 个采样日平均 " + "%.2f".format(average) + usage.first().unit + "/日，约可用 " + days + " 天（估计）"
                    } else {
                        ""
                    }
                } else {
                    ""
                }
                DormUtilityReminder(
                    meterLabel = room.roomName + " · " + deviceName,
                    utilityKind = if (device.category == UtilityMeterCategory.WATER) "WATER" else "ELECTRICITY",
                    balanceText = DormElectricityNotificationSubsystem.formatMetricWithDefaultUnit(
                        device.balanceRaw,
                        if (device.category == UtilityMeterCategory.WATER) "吨" else "度",
                    ),
                    lowBalance = balanceNumber != null && balanceNumber <= threshold,
                    observedAtEpochMillis = room.updatedAtEpochMs,
                    detail = "本机水电缓存；低余额只作为记账/充值提醒，不执行缴费" + estimate,
                )
            }
        }
        if (online.isNotEmpty()) return@combine online.sortedBy { it.meterLabel }

        meters.mapNotNull { meter ->
            val latest = readings.filter { it.meterId == meter.id }.maxByOrNull { it.recordedAtEpochMs }
                ?: return@mapNotNull null
            val effectiveThreshold = if (meter.lowThreshold > 0.0) minOf(threshold, meter.lowThreshold) else threshold
            DormUtilityReminder(
                meterLabel = meter.roomName + " · " + meter.name,
                utilityKind = if (meter.unit.contains("吨")) "WATER" else "ELECTRICITY",
                balanceText = "%.2f".format(latest.value) + meter.unit,
                lowBalance = latest.value <= effectiveThreshold,
                observedAtEpochMillis = latest.recordedAtEpochMs,
                detail = "手工水电台账；请先在宿舍水电页核对读数",
            )
        }.sortedBy { it.meterLabel }
    }
}
