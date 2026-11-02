package cn.edu.haut.campus.core.campusservices

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class BookingExecutionMode(
    val wireName: String,
    val label: String,
    val subtitle: String,
    val requiresXpeachSso: Boolean,
    val priceMicropeach: Long
) {
    LOCAL_DEVICE(
        wireName = "LOCAL_DEVICE",
        label = "手机自动抢（免费）",
        subtitle = "完全免费，无需登录云账号，保持手机开机联网即可到点自动帮您抢座",
        requiresXpeachSso = false,
        priceMicropeach = 0L
    ),
    CLOUD_HOSTED(
        wireName = "CLOUD_HOSTED",
        label = "云端关机代抢（0.1 🍑）",
        subtitle = "手机关机或没网也能由服务器帮您抢，抢到座位才扣 0.1 🍑，没抢到不收费",
        requiresXpeachSso = true,
        priceMicropeach = 1_000L
    ),
    MANUAL_REMINDER(
        wireName = "MANUAL_REMINDER",
        label = "仅到点提醒我",
        subtitle = "到点前发送通知提醒您自己去选座",
        requiresXpeachSso = false,
        priceMicropeach = 0L
    );

    val pricePeach: Double
        get() = priceMicropeach.toDouble() / 10_000.0

    companion object {
        fun fromWire(raw: String?): BookingExecutionMode =
            entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) } ?: LOCAL_DEVICE
    }
}

enum class TripleScheduleStage(val wireName: String, val leadSeconds: Long, val label: String) {
    PRECHECK("PRECHECK", 1800L, "开抢前30分钟自动保活"),
    FINAL_CHECK("FINAL_CHECK", 300L, "开抢前5分钟就绪检查"),
    FIRE("FIRE", 0L, "准点自动抢座")
}

data class LocalAlarmIntentSpec(
    val planId: String,
    val stage: TripleScheduleStage,
    val generation: Int,
    val requestCode: Int,
    val triggerAtEpochMillis: Long,
    val action: String
)

data class ComputedTripleSchedule(
    val targetLocalDate: LocalDate,
    val targetLocalTime: LocalTime,
    val zoneId: ZoneId,
    val precheckAtEpochMillis: Long,
    val finalCheckAtEpochMillis: Long,
    val fireAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val precheckInitialStatus: String,
    val finalCheckInitialStatus: String,
    val fireInitialStatus: String,
    val alarmSpecs: List<LocalAlarmIntentSpec>
)

object DualEngineReservationPlanner {
    val HAUT_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    val WINDOW_START: LocalTime = LocalTime.of(7, 0, 0)
    val WINDOW_END_EXCLUSIVE: LocalTime = LocalTime.of(21, 30, 0)
    const val PRECHECK_LEAD_SECONDS: Long = 1800L
    const val FINAL_CHECK_LEAD_SECONDS: Long = 300L
    const val ACTION_TRIPLE_SCHEDULE_ALARM = "cn.edu.haut.campus.action.LIBRARY_TRIPLE_SCHEDULE_ALARM"

    private val TIME_FORMATTER_HMS = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val TIME_FORMATTER_HM = DateTimeFormatter.ofPattern("HH:mm")

    fun parseTargetLocalTime(raw: String): LocalTime {
        val trimmed = raw.trim()
        return runCatching {
            if (trimmed.count { it == ':' } == 1) {
                LocalTime.parse(trimmed, TIME_FORMATTER_HM)
            } else {
                LocalTime.parse(trimmed, TIME_FORMATTER_HMS)
            }
        }.getOrElse {
            throw IllegalArgumentException("无效的目标时刻格式：$raw（请使用 HH:mm 或 HH:mm:ss）")
        }
    }

    fun formatLocalTimeHms(time: LocalTime): String = time.format(TIME_FORMATTER_HMS)

    fun computeSchedule(
        planId: String,
        targetDate: LocalDate,
        targetTime: LocalTime,
        generation: Int = 1,
        maxLatenessSeconds: Long = 0L,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = HAUT_ZONE
    ): ComputedTripleSchedule {
        if (targetTime.isBefore(WINDOW_START) || !targetTime.isBefore(WINDOW_END_EXCLUSIVE)) {
            throw IllegalArgumentException(
                "目标时刻 ${formatLocalTimeHms(targetTime)} 超出学校官方允许窗口 [07:00:00, 21:30:00)"
            )
        }

        val targetZdt = ZonedDateTime.of(targetDate, targetTime, zoneId)
        val windowEndZdt = ZonedDateTime.of(targetDate, WINDOW_END_EXCLUSIVE, zoneId)
        val fireAtMs = targetZdt.toInstant().toEpochMilli()
        val precheckAtMs = fireAtMs - PRECHECK_LEAD_SECONDS * 1000L
        val finalCheckAtMs = fireAtMs - FINAL_CHECK_LEAD_SECONDS * 1000L
        val clampedLatenessMs = maxLatenessSeconds.coerceIn(0L, 1800L) * 1000L
        val expiresAtMs = minOf(fireAtMs + clampedLatenessMs, windowEndZdt.toInstant().toEpochMilli())

        if (nowEpochMillis > expiresAtMs) {
            throw IllegalArgumentException("目标预约时刻 T 已过有效期窗口 (MISSED_WINDOW)")
        }

        val diffMs = fireAtMs - nowEpochMillis
        val precheckStatus: String
        val finalCheckStatus: String
        when {
            diffMs in 1L..(5 * 60 * 1000L) -> {
                precheckStatus = "PRECHECK_LATE_OK"
                finalCheckStatus = "FINAL_CHECK_LATE_OK"
            }
            diffMs in (5 * 60 * 1000L + 1L) until (30 * 60 * 1000L) -> {
                precheckStatus = "PRECHECK_LATE_OK"
                finalCheckStatus = "ARMED"
            }
            else -> {
                precheckStatus = "ARMED"
                finalCheckStatus = "ARMED"
            }
        }

        val alarmSpecs = listOf(
            LocalAlarmIntentSpec(
                planId = planId,
                stage = TripleScheduleStage.PRECHECK,
                generation = generation,
                requestCode = computeDeterministicRequestCode(planId, TripleScheduleStage.PRECHECK, generation),
                triggerAtEpochMillis = maxOf(precheckAtMs, nowEpochMillis),
                action = ACTION_TRIPLE_SCHEDULE_ALARM
            ),
            LocalAlarmIntentSpec(
                planId = planId,
                stage = TripleScheduleStage.FINAL_CHECK,
                generation = generation,
                requestCode = computeDeterministicRequestCode(planId, TripleScheduleStage.FINAL_CHECK, generation),
                triggerAtEpochMillis = maxOf(finalCheckAtMs, nowEpochMillis),
                action = ACTION_TRIPLE_SCHEDULE_ALARM
            ),
            LocalAlarmIntentSpec(
                planId = planId,
                stage = TripleScheduleStage.FIRE,
                generation = generation,
                requestCode = computeDeterministicRequestCode(planId, TripleScheduleStage.FIRE, generation),
                triggerAtEpochMillis = fireAtMs,
                action = ACTION_TRIPLE_SCHEDULE_ALARM
            )
        )

        return ComputedTripleSchedule(
            targetLocalDate = targetDate,
            targetLocalTime = targetTime,
            zoneId = zoneId,
            precheckAtEpochMillis = precheckAtMs,
            finalCheckAtEpochMillis = finalCheckAtMs,
            fireAtEpochMillis = fireAtMs,
            expiresAtEpochMillis = expiresAtMs,
            precheckInitialStatus = precheckStatus,
            finalCheckInitialStatus = finalCheckStatus,
            fireInitialStatus = "ARMED",
            alarmSpecs = alarmSpecs
        )
    }

    fun computeDeterministicRequestCode(
        planId: String,
        stage: TripleScheduleStage,
        generation: Int
    ): Int {
        val raw = "$planId|${stage.wireName}|$generation"
        return (raw.hashCode() and 0x7FFFFFFF).coerceAtLeast(1000)
    }

    fun formatEpochToShanghaiHms(epochMillis: Long): String {
        return Instant.ofEpochMilli(epochMillis)
            .atZone(HAUT_ZONE)
            .toLocalTime()
            .format(TIME_FORMATTER_HMS)
    }
}
