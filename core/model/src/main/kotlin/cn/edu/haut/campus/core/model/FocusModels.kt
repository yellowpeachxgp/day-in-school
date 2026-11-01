/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.model

import java.time.Instant

/**
 * 自习专注外部可观测四态状态机：
 * - IDLE：当前无正在运行的专注会话
 * - RUNNING：专注倒计时进行中
 * - COMPLETED：专注正常到点完成（计入累计专注时长）
 * - CANCELLED：用户中途主动结束或被新会话替换（不计入完成时长）
 */
enum class FocusSessionStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    CANCELLED;

    companion object {
        fun fromName(raw: String): FocusSessionStatus =
            entries.firstOrNull { it.name == raw } ?: IDLE
    }
}

/**
 * 专注完成的触发来源，用于审计与迟到静默判据。
 */
enum class FocusCompletionSource {
    FOREGROUND_TIMER,
    ALARM_RECEIVER,
    RESTORE_WITHIN_GRACE_WINDOW,
    RESTORE_SILENT_PAST_GRACE_WINDOW;

    companion object {
        fun fromName(raw: String?): FocusCompletionSource? =
            raw?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

/**
 * 单次自习专注会话领域模型（对应独立表 `focus_sessions`）。
 */
data class FocusSessionDomain(
    val id: String,
    val generation: Int,
    val durationMinutes: Int,
    val startedAtEpochMs: Long,
    val expectedEndAtEpochMs: Long,
    val status: FocusSessionStatus,
    val scheduleMode: ReminderAccuracyMode = ReminderAccuracyMode.NONE,
    val completedAtEpochMs: Long? = null,
    val cancelledAtEpochMs: Long? = null,
    val completionSource: FocusCompletionSource? = null,
    val notified: Boolean = false,
    val cancelReason: String? = null,
    val updatedAtEpochMs: Long = startedAtEpochMs
) {
    val isRunning: Boolean
        get() = status == FocusSessionStatus.RUNNING

    fun remainingMillis(nowEpochMs: Long): Long =
        if (status == FocusSessionStatus.RUNNING) {
            (expectedEndAtEpochMs - nowEpochMs).coerceAtLeast(0L)
        } else {
            0L
        }
}

/**
 * 课前 20 分钟非强制提示等级与详情。
 */
enum class FocusPreClassAdvisoryKind {
    IN_CLASS_NOW,
    CLASS_STARTING_WITHIN_20_MINUTES,
    FOCUS_INTRUDES_PRE_CLASS_WINDOW
}

data class FocusPreClassAdvisory(
    val kind: FocusPreClassAdvisoryKind,
    val courseName: String,
    val roomText: String,
    val classStartAt: Instant,
    val minutesUntilClassStart: Long,
    val recommendedMaxFocusMinutes: Int?,
    val message: String,
    val isNonMandatory: Boolean = true
)

/**
 * 专注提醒送达诊断数据（对应上游 FocusDelivery）。
 */
data class FocusDeliveryTelemetry(
    val kind: String,
    val plannedAtEpochMs: Long = 0L,
    val exact: Boolean = false,
    val receivedAtEpochMs: Long = 0L,
    val receivedInBackground: Boolean? = null,
    val submittedAtEpochMs: Long = 0L,
    val errorText: String? = null
)

/**
 * 手工宿舍水电表计领域模型（对应 `utility_meters` 表）。
 */
data class UtilityMeterDomain(
    val id: String,
    val name: String,
    val campusName: String,
    val roomName: String,
    val unit: String,
    val lowThreshold: Double,
    val createdAtEpochMs: Long
)

/**
 * 手工宿舍水电读数记录领域模型（对应 `utility_readings` 表）。
 */
data class UtilityReadingDomain(
    val id: String,
    val meterId: String,
    val value: Double,
    val topUpValue: Double,
    val recordedAtEpochMs: Long,
    val note: String
)

/**
 * 在线水电房间缓存领域模型（对应 `utility_online_rooms` 表）。
 */
data class UtilityOnlineRoomDomain(
    val roomId: String,
    val roomName: String,
    val campusCode: String?,
    val devicesJson: String,
    val updatedAtEpochMs: Long,
    val failed: Boolean
)
