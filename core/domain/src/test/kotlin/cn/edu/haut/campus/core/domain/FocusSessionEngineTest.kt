package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.ClassOccurrence
import cn.edu.haut.campus.core.model.FocusCompletionSource
import cn.edu.haut.campus.core.model.FocusPreClassAdvisoryKind
import cn.edu.haut.campus.core.model.FocusSessionStatus
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class FocusSessionEngineTest {

    @Test
    fun startCompleteAndIdempotency_preventDuplicateCompletion() {
        val t0 = 1_760_000_000_000L
        val started = FocusSessionEngine.startSession(
            currentRunningSession = null,
            durationMinutes = 25,
            nowEpochMs = t0,
            newSessionId = "focus-1"
        )
        val running = started.updatedSession
        assertEquals(FocusSessionStatus.RUNNING, running.status)
        assertEquals(t0 + 25 * 60_000L, running.expectedEndAtEpochMs)
        assertTrue(started.shouldScheduleAlarm)

        // Early completion attempt before expectedEndAtEpochMs -> Ignored
        val earlyAttempt = FocusSessionEngine.completeSession(
            session = running,
            expectedSessionId = "focus-1",
            expectedGeneration = running.generation,
            expectedEndAtEpochMs = running.expectedEndAtEpochMs,
            nowEpochMs = running.expectedEndAtEpochMs - 5_000L
        )
        assertTrue(earlyAttempt is FocusSessionEngine.TransitionResult.Ignored)
        assertEquals(
            FocusSessionEngine.IgnoreReason.EARLY_BEFORE_EXPECTED_END,
            (earlyAttempt as FocusSessionEngine.TransitionResult.Ignored).reason
        )

        // Exact alarm completion -> Updated
        val completedRes = FocusSessionEngine.completeSession(
            session = running,
            expectedSessionId = "focus-1",
            expectedGeneration = running.generation,
            expectedEndAtEpochMs = running.expectedEndAtEpochMs,
            nowEpochMs = running.expectedEndAtEpochMs + 500L
        )
        assertTrue(completedRes is FocusSessionEngine.TransitionResult.Updated)
        val completed = (completedRes as FocusSessionEngine.TransitionResult.Updated).updatedSession
        assertEquals(FocusSessionStatus.COMPLETED, completed.status)
        assertEquals(FocusCompletionSource.ALARM_RECEIVER, completed.completionSource)
        assertTrue(completedRes.shouldPostCompletionNotification)

        // Second completion attempt on already completed session -> Ignored (idempotent)
        val duplicateAttempt = FocusSessionEngine.completeSession(
            session = completed,
            expectedSessionId = "focus-1",
            expectedGeneration = running.generation,
            expectedEndAtEpochMs = running.expectedEndAtEpochMs,
            nowEpochMs = running.expectedEndAtEpochMs + 1_500L
        )
        assertTrue(duplicateAttempt is FocusSessionEngine.TransitionResult.Ignored)
        assertEquals(
            FocusSessionEngine.IgnoreReason.ALREADY_COMPLETED,
            (duplicateAttempt as FocusSessionEngine.TransitionResult.Ignored).reason
        )
    }

    @Test
    fun restoreDecision_enforcesFifteenMinuteNotificationGraceWindow() {
        val t0 = 1_760_000_000_000L
        val running = FocusSessionEngine.startSession(
            currentRunningSession = null,
            durationMinutes = 25,
            nowEpochMs = t0,
            newSessionId = "focus-grace"
        ).updatedSession

        // 5 minutes after expectedEnd -> within 15m grace window -> shouldNotify = true
        val restoreWithinGrace = FocusSessionEngine.evaluateRestoreAction(
            runningSession = running,
            nowEpochMs = running.expectedEndAtEpochMs + 5 * 60_000L
        )
        assertTrue(restoreWithinGrace is FocusSessionEngine.RestoreDecision.CompleteExpired)
        val withinDecision = restoreWithinGrace as FocusSessionEngine.RestoreDecision.CompleteExpired
        assertTrue(withinDecision.shouldNotify)
        assertEquals(
            FocusCompletionSource.RESTORE_WITHIN_GRACE_WINDOW,
            withinDecision.completedSession.completionSource
        )

        // 20 minutes after expectedEnd -> past 15m grace window -> shouldNotify = false (silent completion)
        val restorePastGrace = FocusSessionEngine.evaluateRestoreAction(
            runningSession = running,
            nowEpochMs = running.expectedEndAtEpochMs + 20 * 60_000L
        )
        assertTrue(restorePastGrace is FocusSessionEngine.RestoreDecision.CompleteExpired)
        val pastDecision = restorePastGrace as FocusSessionEngine.RestoreDecision.CompleteExpired
        assertFalse(pastDecision.shouldNotify)
        assertEquals(
            FocusCompletionSource.RESTORE_SILENT_PAST_GRACE_WINDOW,
            pastDecision.completedSession.completionSource
        )
    }

    @Test
    fun evaluatePreClassAdvisory_providesNonMandatoryWarningForUpcomingClass() {
        val date = LocalDate.of(2026, 11, 2)
        val occurrence = ClassOccurrence(
            occurrenceId = "occ-1",
            courseId = "c1",
            ruleId = "r1",
            overrideId = null,
            courseName = "数据结构",
            teacher = "王老师",
            colorArgb = 0xFF3D7EFFL,
            date = date,
            teachingWeek = 10,
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            startTime = LocalTime.of(8, 0),
            endTime = LocalTime.of(9, 40),
            rawRoom = "6203",
            buildingId = "6",
            buildingName = "6号教学楼",
            isMakeupOrOverridden = false,
            overrideReason = null,
            userModified = false
        )

        // At 07:30 (30 min before class), planning a 25-min focus ends at 07:55 (intrudes 20-min pre-class window)
        val now0730 = ZonedDateTime.of(date, LocalTime.of(7, 30), HAUT_ZONE_ID).toInstant()
        val advisory = FocusSessionEngine.evaluatePreClassAdvisory(
            occurrences = listOf(occurrence),
            now = now0730,
            plannedFocusMinutes = 25
        )
        assertNotNull(advisory)
        assertEquals(FocusPreClassAdvisoryKind.FOCUS_INTRUDES_PRE_CLASS_WINDOW, advisory!!.kind)
        assertEquals("数据结构", advisory.courseName)
        assertEquals(10, advisory.recommendedMaxFocusMinutes)

        // At 06:30 (90 min before class), planning a 25-min focus -> no advisory needed
        val now0630 = ZonedDateTime.of(date, LocalTime.of(6, 30), HAUT_ZONE_ID).toInstant()
        val earlyAdvisory = FocusSessionEngine.evaluatePreClassAdvisory(
            occurrences = listOf(occurrence),
            now = now0630,
            plannedFocusMinutes = 25
        )
        assertNull(earlyAdvisory)
    }
}
