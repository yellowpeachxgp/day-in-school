package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.model.ReminderAccuracyMode
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderEventType
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderSchedulerState
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderDatabaseMigrationTest {

    @Test
    fun `reminder task entity and domain mapping preserves all fields`() {
        val taskKey = "CLASS:2025-2026-2:occ_1|CLASS_BEFORE"
        val record = ReminderTaskDomain(
            id = "task_occ_1",
            taskKey = taskKey,
            eventKey = "CLASS:2025-2026-2:occ_1",
            eventType = ReminderEventType.CLASS,
            reminderKind = ReminderKind.CLASS_BEFORE,
            eventRevision = "rev_evt_1",
            policyRevision = "rev_pol_1",
            eventStartAtEpochMs = 1775520000000L,
            originalScheduledAtEpochMs = 1775518800000L,
            scheduledAtEpochMs = 1775519400000L,
            status = ReminderTaskStatus.SNOOZED,
            schedulerState = ReminderSchedulerState.SCHEDULED,
            generation = 2,
            snoozeCount = 1,
            maxSnoozes = 2,
            claimedAtEpochMs = 1775518800000L,
            deliveredAtEpochMs = 1775518801000L,
            lastActionAtEpochMs = 1775518810000L,
            scheduleMode = ReminderAccuracyMode.EXACT,
            lastErrorCode = null,
            createdAtEpochMs = 1775500000000L,
            updatedAtEpochMs = 1775518810000L
        )
        val entity = record.toEntity()
        val roundTrip = entity.toDomain()
        assertEquals(record, roundTrip)
    }

    @Test
    fun `reminder event pref entity and domain mapping preserves overrides`() {
        val override = ReminderEventOverride(
            eventScopeKey = "COURSE:c_1",
            enabledOverride = true,
            leadMinutesOverride = 15,
            maxSnoozesOverride = 2,
            updatedAtEpochMs = 1775500000000L
        )
        val entity = override.toEntity()
        val roundTrip = entity.toDomain()
        assertEquals(override, roundTrip)
    }

    @Test
    fun `migration 1_2 and 2_3 versions are registered in sequence`() {
        assertEquals(1, CampusRepository.MIGRATION_1_2.startVersion)
        assertEquals(2, CampusRepository.MIGRATION_1_2.endVersion)
        assertEquals(2, CampusRepository.MIGRATION_2_3.startVersion)
        assertEquals(3, CampusRepository.MIGRATION_2_3.endVersion)
        assertTrue(CampusRepository.MIGRATION_2_3.endVersion == 3)
    }
}
