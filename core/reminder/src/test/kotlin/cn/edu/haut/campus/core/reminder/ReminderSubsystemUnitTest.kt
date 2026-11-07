package cn.edu.haut.campus.core.reminder

import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import cn.edu.haut.campus.core.model.ReminderEventType
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderSchedulerState
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import cn.edu.haut.campus.core.model.SnoozeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSubsystemUnitTest {
    @Test
    fun disablingClassChannelDoesNotDisableExamPermission() {
        val permissions = ReminderPermissionSnapshot(true, true, true, false, true)
        assertTrue(permissions.canPostNotifications)
        assertTrue(permissions.examChannelEnabled)
        assertFalse(permissions.classChannelEnabled)
    }


    @Test
    fun `background scope requires both profile and generation to remain current`() {
        val scope = AccountBackgroundScope(profileId = "profile-a", epoch = 12L)

        assertTrue(scope.matches("profile-a", 12L))
        assertFalse(scope.matches("profile-b", 12L))
        assertFalse(scope.matches("profile-a", 13L))
    }

    @Test
    fun `snooze choice action codes round-trip cleanly for receiver intents`() {
        val allChoices = listOf(
            SnoozeChoice.Plus5Minutes,
            SnoozeChoice.Plus10Minutes,
            SnoozeChoice.BeforeClass5Minutes,
            SnoozeChoice.ExamPlus15Minutes,
            SnoozeChoice.ExamPlus30Minutes,
            SnoozeChoice.ExamPlus60Minutes
        )
        for (choice in allChoices) {
            val decoded = SnoozeChoice.fromActionCode(choice.actionCode)
            assertNotNull(decoded)
            assertEquals(choice, decoded)
        }
    }

    @Test
    fun `fake alarm scheduler records generation-scoped schedule and cancel operations`() {
        val scheduledLog = mutableListOf<Pair<String, Int>>()
        val cancelledLog = mutableListOf<Pair<String, Int>>()

        val fakeScheduler = object : AlarmScheduler {
            override fun schedule(task: ReminderTaskDomain, preferExact: Boolean): ScheduleOutcome {
                scheduledLog.add(task.id to task.generation)
                return ScheduleOutcome.Scheduled(
                    mode = if (preferExact) ReminderAccuracyMode.EXACT else ReminderAccuracyMode.INEXACT,
                    schedulerState = ReminderSchedulerState.SCHEDULED
                )
            }

            override fun cancel(taskId: String, generation: Int) {
                cancelledLog.add(taskId to generation)
            }
        }

        val taskGen0 = ReminderTaskDomain(
            id = "task-001",
            taskKey = "CLASS:2026-2027-1:occ1|CLASS_BEFORE",
            eventKey = "CLASS:2026-2027-1:occ1",
            eventType = ReminderEventType.CLASS,
            reminderKind = ReminderKind.CLASS_BEFORE,
            eventRevision = "rev1",
            policyRevision = "pol1",
            eventStartAtEpochMs = 1775520000000L,
            originalScheduledAtEpochMs = 1775518800000L,
            scheduledAtEpochMs = 1775518800000L,
            status = ReminderTaskStatus.PENDING,
            schedulerState = ReminderSchedulerState.NEEDS_SCHEDULE,
            generation = 0,
            snoozeCount = 0,
            maxSnoozes = 2,
            createdAtEpochMs = 1775500000000L,
            updatedAtEpochMs = 1775500000000L
        )

        val out0 = fakeScheduler.schedule(taskGen0, preferExact = true)
        assertEquals(ScheduleOutcome.Scheduled(ReminderAccuracyMode.EXACT), out0)
        assertEquals(listOf("task-001" to 0), scheduledLog)

        fakeScheduler.cancel("task-001", 0)
        val taskGen1 = taskGen0.copy(status = ReminderTaskStatus.SNOOZED, generation = 1, snoozeCount = 1)
        val out1 = fakeScheduler.schedule(taskGen1, preferExact = false)
        assertEquals(ScheduleOutcome.Scheduled(ReminderAccuracyMode.INEXACT), out1)
        assertEquals(listOf("task-001" to 0), cancelledLog)
        assertEquals(listOf("task-001" to 0, "task-001" to 1), scheduledLog)
    }
}
