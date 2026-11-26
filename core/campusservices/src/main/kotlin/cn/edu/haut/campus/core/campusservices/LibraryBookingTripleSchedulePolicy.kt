package cn.edu.haut.campus.core.campusservices

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * T−30/T−5/T 三阶段预约窗口的纯 JVM 语义模型。
 *
 * 所有窗口都是半开区间 [opensAt, closesAt)：到达 closesAt 的同一毫秒即视为窗口关闭。
 * FIRE 的 closesAt 就是 expiresAt；这样 21:30:00 这个校方窗口 exclusive end 不会被误执行。
 * 本模型只决定“本地执行门禁”；CLOUD_HOSTED 的权威执行在云端，永不因本地闹钟触发写操作。
 */
data class LibraryBookingTripleScheduleFacts(
    val executionMode: BookingExecutionMode,
    val targetLocalDate: LocalDate,
    val targetLocalTime: LocalTime,
    val timezoneId: ZoneId,
    val fireAtEpochMillis: Long
)

data class LibraryBookingStageWindow(
    val stage: TripleScheduleStage,
    val opensAtEpochMillis: Long,
    val closesAtEpochMillis: Long
) {
    fun contains(atEpochMillis: Long): Boolean =
        atEpochMillis >= opensAtEpochMillis && atEpochMillis < closesAtEpochMillis
}

data class LibraryBookingTripleScheduleWindows(
    val precheck: LibraryBookingStageWindow,
    val finalCheck: LibraryBookingStageWindow,
    val fire: LibraryBookingStageWindow,
    val expiresAtEpochMillis: Long
) {
    fun windowFor(stage: TripleScheduleStage): LibraryBookingStageWindow = when (stage) {
        TripleScheduleStage.PRECHECK -> precheck
        TripleScheduleStage.FINAL_CHECK -> finalCheck
        TripleScheduleStage.FIRE -> fire
    }
}

enum class LibraryBookingStageGateDecision {
    DUE,
    NOT_DUE,
    STAGE_WINDOW_CLOSED,
    FIRE_WINDOW_CLOSED,
    TARGET_OUTSIDE_SCHOOL_WINDOW,
    CLOUD_HOSTED_NOT_LOCAL
}

data class LibraryBookingStageGateEvaluation(
    val stage: TripleScheduleStage,
    val decision: LibraryBookingStageGateDecision,
    val window: LibraryBookingStageWindow,
    val expiresAtEpochMillis: Long
)

object LibraryBookingTripleSchedulePolicy {
    const val FIRE_MAX_LATENESS_MILLIS: Long = 30L * 60L * 1000L

    fun isWithinSchoolTargetWindow(targetLocalTime: LocalTime): Boolean =
        !targetLocalTime.isBefore(DualEngineReservationPlanner.WINDOW_START) &&
            targetLocalTime.isBefore(DualEngineReservationPlanner.WINDOW_END_EXCLUSIVE)

    fun schoolWindowEndAtEpochMillis(
        targetLocalDate: LocalDate,
        timezoneId: ZoneId
    ): Long = ZonedDateTime.of(
        targetLocalDate,
        DualEngineReservationPlanner.WINDOW_END_EXCLUSIVE,
        timezoneId
    ).toInstant().toEpochMilli()

    /**
     * 从持久化事实推导 FIRE 过期点：min(fireAt + maxLateness, 当日 21:30)。
     * 生产默认 maxLateness 为 30 分钟；显式参数仅供 planner 保持既有自定义测试/兼容入口。
     */
    fun expiresAtEpochMillis(
        targetLocalDate: LocalDate,
        timezoneId: ZoneId,
        fireAtEpochMillis: Long,
        maxLatenessMillis: Long = FIRE_MAX_LATENESS_MILLIS
    ): Long {
        val lateness = maxLatenessMillis.coerceAtLeast(0L)
        val fireExpiry = saturatingAdd(fireAtEpochMillis, lateness)
        return minOf(fireExpiry, schoolWindowEndAtEpochMillis(targetLocalDate, timezoneId))
    }

    fun deriveWindows(facts: LibraryBookingTripleScheduleFacts): LibraryBookingTripleScheduleWindows {
        val fireAt = facts.fireAtEpochMillis
        val precheckAt = fireAt - TripleScheduleStage.PRECHECK.leadSeconds * 1000L
        val finalCheckAt = fireAt - TripleScheduleStage.FINAL_CHECK.leadSeconds * 1000L
        val expiresAt = expiresAtEpochMillis(
            targetLocalDate = facts.targetLocalDate,
            timezoneId = facts.timezoneId,
            fireAtEpochMillis = fireAt
        )
        return LibraryBookingTripleScheduleWindows(
            precheck = LibraryBookingStageWindow(
                stage = TripleScheduleStage.PRECHECK,
                opensAtEpochMillis = precheckAt,
                closesAtEpochMillis = finalCheckAt
            ),
            finalCheck = LibraryBookingStageWindow(
                stage = TripleScheduleStage.FINAL_CHECK,
                opensAtEpochMillis = finalCheckAt,
                closesAtEpochMillis = fireAt
            ),
            fire = LibraryBookingStageWindow(
                stage = TripleScheduleStage.FIRE,
                opensAtEpochMillis = fireAt,
                closesAtEpochMillis = expiresAt
            ),
            expiresAtEpochMillis = expiresAt
        )
    }

    fun evaluateLocalStage(
        facts: LibraryBookingTripleScheduleFacts,
        stage: TripleScheduleStage,
        atEpochMillis: Long
    ): LibraryBookingStageGateEvaluation {
        val windows = deriveWindows(facts)
        val window = windows.windowFor(stage)
        val decision = when {
            !isWithinSchoolTargetWindow(facts.targetLocalTime) ->
                LibraryBookingStageGateDecision.TARGET_OUTSIDE_SCHOOL_WINDOW
            facts.executionMode == BookingExecutionMode.CLOUD_HOSTED ->
                LibraryBookingStageGateDecision.CLOUD_HOSTED_NOT_LOCAL
            atEpochMillis < window.opensAtEpochMillis ->
                LibraryBookingStageGateDecision.NOT_DUE
            atEpochMillis >= window.closesAtEpochMillis ->
                if (stage == TripleScheduleStage.FIRE) {
                    LibraryBookingStageGateDecision.FIRE_WINDOW_CLOSED
                } else {
                    LibraryBookingStageGateDecision.STAGE_WINDOW_CLOSED
                }
            else -> LibraryBookingStageGateDecision.DUE
        }
        return LibraryBookingStageGateEvaluation(
            stage = stage,
            decision = decision,
            window = window,
            expiresAtEpochMillis = windows.expiresAtEpochMillis
        )
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE
        return left + right
    }
}
