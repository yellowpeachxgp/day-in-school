/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.FocusCompletionSource
import cn.edu.haut.campus.core.model.FocusPreClassAdvisory
import cn.edu.haut.campus.core.model.FocusPreClassAdvisoryKind
import cn.edu.haut.campus.core.model.FocusSessionDomain
import cn.edu.haut.campus.core.model.FocusSessionStatus
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 自习专注会话纯领域引擎：
 * 1. 管理 `IDLE -> RUNNING -> COMPLETED / CANCELLED` 显式状态机与世代 (generation) 演进；
 * 2. 提供与上游 `FocusCompletion.kt` 等价且更严格的完成幂等保障（防旧闹钟、防早到、防重复记账）；
 * 3. 实现 15 分钟迟到恢复静默窗口（超过 15 分钟仅标记 COMPLETED，不发送扰民通知）；
 * 4. 实现课前 20 分钟非强制提示评估（纯只读计算，不阻断用户操作，不触碰 `:core:reminder`）。
 */
object FocusSessionEngine {

    const val MIN_FOCUS_MINUTES: Int = 1
    const val MAX_FOCUS_MINUTES: Int = 180
    const val DEFAULT_FOCUS_MINUTES: Int = 25

    /** 重启或恢复时，过期超过 15 分钟的专注仅静默标记完成，不再补发通知 */
    const val LATE_RESTORE_NOTIFICATION_WINDOW_MS: Long = 15 * 60_000L

    /** 课前非强制提示窗口：20 分钟 */
    const val PRE_CLASS_ADVISORY_LEAD_MINUTES: Long = 20L

    sealed interface TransitionResult {
        data class Updated(
            val updatedSession: FocusSessionDomain,
            val cancelledPreviousSession: FocusSessionDomain? = null,
            val shouldScheduleAlarm: Boolean = false,
            val alarmToCancel: Pair<String, Int>? = null,
            val shouldPostCompletionNotification: Boolean = false
        ) : TransitionResult

        data class Ignored(
            val reason: IgnoreReason,
            val currentSession: FocusSessionDomain?
        ) : TransitionResult
    }

    enum class IgnoreReason {
        SESSION_NOT_FOUND,
        NOT_RUNNING,
        GENERATION_MISMATCH,
        EXPECTED_END_MISMATCH,
        EARLY_BEFORE_EXPECTED_END,
        ALREADY_COMPLETED
    }

    fun startSession(
        currentRunningSession: FocusSessionDomain?,
        durationMinutes: Int,
        nowEpochMs: Long,
        newSessionId: String = UUID.randomUUID().toString()
    ): TransitionResult.Updated {
        require(durationMinutes in MIN_FOCUS_MINUTES..MAX_FOCUS_MINUTES) {
            "专注时长必须在 $MIN_FOCUS_MINUTES..$MAX_FOCUS_MINUTES 分钟之间"
        }

        val cancelledPrevious = if (currentRunningSession != null &&
            currentRunningSession.status == FocusSessionStatus.RUNNING
        ) {
            currentRunningSession.copy(
                status = FocusSessionStatus.CANCELLED,
                generation = currentRunningSession.generation + 1,
                cancelledAtEpochMs = nowEpochMs,
                cancelReason = "REPLACED_BY_NEW_SESSION",
                updatedAtEpochMs = nowEpochMs
            )
        } else {
            null
        }

        val alarmToCancel = if (currentRunningSession != null &&
            currentRunningSession.status == FocusSessionStatus.RUNNING
        ) {
            currentRunningSession.id to currentRunningSession.generation
        } else {
            null
        }

        val expectedEnd = nowEpochMs + durationMinutes * 60_000L
        val freshSession = FocusSessionDomain(
            id = newSessionId,
            generation = 1,
            durationMinutes = durationMinutes,
            startedAtEpochMs = nowEpochMs,
            expectedEndAtEpochMs = expectedEnd,
            status = FocusSessionStatus.RUNNING,
            updatedAtEpochMs = nowEpochMs
        )

        return TransitionResult.Updated(
            updatedSession = freshSession,
            cancelledPreviousSession = cancelledPrevious,
            shouldScheduleAlarm = true,
            alarmToCancel = alarmToCancel,
            shouldPostCompletionNotification = false
        )
    }

    fun cancelSession(
        session: FocusSessionDomain?,
        nowEpochMs: Long,
        reason: String = "USER_STOPPED"
    ): TransitionResult {
        if (session == null) {
            return TransitionResult.Ignored(IgnoreReason.SESSION_NOT_FOUND, null)
        }
        if (session.status != FocusSessionStatus.RUNNING) {
            return TransitionResult.Ignored(IgnoreReason.NOT_RUNNING, session)
        }
        val cancelled = session.copy(
            status = FocusSessionStatus.CANCELLED,
            generation = session.generation + 1,
            cancelledAtEpochMs = nowEpochMs,
            cancelReason = reason,
            updatedAtEpochMs = nowEpochMs
        )
        return TransitionResult.Updated(
            updatedSession = cancelled,
            shouldScheduleAlarm = false,
            alarmToCancel = session.id to session.generation,
            shouldPostCompletionNotification = false
        )
    }

    fun completeSession(
        session: FocusSessionDomain?,
        expectedSessionId: String,
        expectedGeneration: Int? = null,
        expectedEndAtEpochMs: Long? = null,
        nowEpochMs: Long,
        isRestorePath: Boolean = false
    ): TransitionResult {
        if (session == null || session.id != expectedSessionId) {
            return TransitionResult.Ignored(IgnoreReason.SESSION_NOT_FOUND, session)
        }
        if (session.status == FocusSessionStatus.COMPLETED) {
            return TransitionResult.Ignored(IgnoreReason.ALREADY_COMPLETED, session)
        }
        if (session.status != FocusSessionStatus.RUNNING) {
            return TransitionResult.Ignored(IgnoreReason.NOT_RUNNING, session)
        }
        if (expectedGeneration != null && session.generation != expectedGeneration) {
            return TransitionResult.Ignored(IgnoreReason.GENERATION_MISMATCH, session)
        }
        if (expectedEndAtEpochMs != null &&
            (expectedEndAtEpochMs <= 0L || session.expectedEndAtEpochMs != expectedEndAtEpochMs)
        ) {
            return TransitionResult.Ignored(IgnoreReason.EXPECTED_END_MISMATCH, session)
        }
        if (nowEpochMs < session.expectedEndAtEpochMs) {
            return TransitionResult.Ignored(IgnoreReason.EARLY_BEFORE_EXPECTED_END, session)
        }

        val latenessMs = nowEpochMs - session.expectedEndAtEpochMs
        val withinGraceWindow = latenessMs <= LATE_RESTORE_NOTIFICATION_WINDOW_MS
        val shouldNotify = if (isRestorePath) withinGraceWindow else true
        val source = when {
            !isRestorePath && expectedGeneration != null -> FocusCompletionSource.ALARM_RECEIVER
            !isRestorePath -> FocusCompletionSource.FOREGROUND_TIMER
            withinGraceWindow -> FocusCompletionSource.RESTORE_WITHIN_GRACE_WINDOW
            else -> FocusCompletionSource.RESTORE_SILENT_PAST_GRACE_WINDOW
        }

        val completed = session.copy(
            status = FocusSessionStatus.COMPLETED,
            generation = session.generation + 1,
            completedAtEpochMs = session.expectedEndAtEpochMs,
            completionSource = source,
            notified = shouldNotify,
            updatedAtEpochMs = nowEpochMs
        )

        return TransitionResult.Updated(
            updatedSession = completed,
            shouldScheduleAlarm = false,
            alarmToCancel = session.id to session.generation,
            shouldPostCompletionNotification = shouldNotify
        )
    }

    fun evaluateRestoreAction(
        runningSession: FocusSessionDomain?,
        nowEpochMs: Long
    ): RestoreDecision {
        if (runningSession == null || runningSession.status != FocusSessionStatus.RUNNING) {
            return RestoreDecision.NoOp
        }
        return if (nowEpochMs >= runningSession.expectedEndAtEpochMs) {
            when (val res = completeSession(
                session = runningSession,
                expectedSessionId = runningSession.id,
                expectedGeneration = runningSession.generation,
                expectedEndAtEpochMs = runningSession.expectedEndAtEpochMs,
                nowEpochMs = nowEpochMs,
                isRestorePath = true
            )) {
                is TransitionResult.Updated -> RestoreDecision.CompleteExpired(
                    completedSession = res.updatedSession,
                    shouldNotify = res.shouldPostCompletionNotification,
                    alarmToCancel = res.alarmToCancel
                )
                is TransitionResult.Ignored -> RestoreDecision.NoOp
            }
        } else {
            RestoreDecision.RescheduleActive(runningSession)
        }
    }

    sealed interface RestoreDecision {
        data object NoOp : RestoreDecision
        data class RescheduleActive(val session: FocusSessionDomain) : RestoreDecision
        data class CompleteExpired(
            val completedSession: FocusSessionDomain,
            val shouldNotify: Boolean,
            val alarmToCancel: Pair<String, Int>?
        ) : RestoreDecision
    }

    fun evaluatePreClassAdvisory(
        occurrences: List<ClassOccurrence>,
        now: Instant,
        plannedFocusMinutes: Int,
        leadMinutes: Long = PRE_CLASS_ADVISORY_LEAD_MINUTES
    ): FocusPreClassAdvisory? {
        if (occurrences.isEmpty()) return null
        val plannedEnd = now.plus(Duration.ofMinutes(plannedFocusMinutes.toLong().coerceAtLeast(1L)))

        val sorted = occurrences.map { occ ->
            val startInstant = occ.date.atTime(occ.startTime).atZone(HAUT_ZONE_ID).toInstant()
            val endInstant = occ.date.atTime(occ.endTime).atZone(HAUT_ZONE_ID).toInstant()
            Triple(occ, startInstant, endInstant)
        }.sortedBy { it.second }

        val inProgress = sorted.firstOrNull { (_, startAt, endAt) ->
            !now.isBefore(startAt) && now.isBefore(endAt)
        }
        if (inProgress != null) {
            val (occ, startAt, _) = inProgress
            val room = occ.rawRoom.ifBlank { "教室待定" }
            return FocusPreClassAdvisory(
                kind = FocusPreClassAdvisoryKind.IN_CLASS_NOW,
                courseName = occ.courseName,
                roomText = room,
                classStartAt = startAt,
                minutesUntilClassStart = 0L,
                recommendedMaxFocusMinutes = null,
                message = "当前正处于《${occ.courseName}》（$room）上课时段，仍可自由开启自习专注。"
            )
        }

        val nextUpcoming = sorted.firstOrNull { (_, startAt, _) ->
            startAt.isAfter(now)
        } ?: return null

        val (nextOcc, nextStartAt, _) = nextUpcoming
        val minutesUntilStart = Duration.between(now, nextStartAt).toMinutes().coerceAtLeast(0L)
        val room = nextOcc.rawRoom.ifBlank { "教室待定" }
        val preClassWindowStart = nextStartAt.minus(Duration.ofMinutes(leadMinutes))

        if (minutesUntilStart <= leadMinutes) {
            return FocusPreClassAdvisory(
                kind = FocusPreClassAdvisoryKind.CLASS_STARTING_WITHIN_20_MINUTES,
                courseName = nextOcc.courseName,
                roomText = room,
                classStartAt = nextStartAt,
                minutesUntilClassStart = minutesUntilStart,
                recommendedMaxFocusMinutes = null,
                message = "距《${nextOcc.courseName}》（$room）上课还有 $minutesUntilStart 分钟，建议留意出发时间；你仍可按需开始专注。"
            )
        }

        if (plannedEnd.isAfter(preClassWindowStart)) {
            val safeMinutesBeforeWindow = Duration.between(now, preClassWindowStart)
                .toMinutes()
                .toInt()
                .coerceAtLeast(MIN_FOCUS_MINUTES)
            val overlapsClassStart = !plannedEnd.isBefore(nextStartAt)
            val detail = if (overlapsClassStart) {
                "本次 ${plannedFocusMinutes} 分钟专注将与 ${nextOcc.startTime} 开始的《${nextOcc.courseName}》（$room）时间重叠"
            } else {
                "本次 ${plannedFocusMinutes} 分钟专注结束时距《${nextOcc.courseName}》（$room）上课不足 $leadMinutes 分钟"
            }
            return FocusPreClassAdvisory(
                kind = FocusPreClassAdvisoryKind.FOCUS_INTRUDES_PRE_CLASS_WINDOW,
                courseName = nextOcc.courseName,
                roomText = room,
                classStartAt = nextStartAt,
                minutesUntilClassStart = minutesUntilStart,
                recommendedMaxFocusMinutes = safeMinutesBeforeWindow,
                message = "$detail（距上课还有 $minutesUntilStart 分钟，建议缩短至 $safeMinutesBeforeWindow 分钟以内，或直接开始）。"
            )
        }

        return null
    }
}
