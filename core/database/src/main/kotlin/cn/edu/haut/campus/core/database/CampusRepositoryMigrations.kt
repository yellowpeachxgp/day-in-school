package cn.edu.haut.campus.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * CampusRepository 兼容外观保留的历史 Room 迁移对象。
 * SQL 逐字保留；companion 仅转发到本对象，避免巨型门面继续携带迁移实现。
 */
internal object CampusRepositoryMigrations {
    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `exam_schedules` (
                    `id` TEXT NOT NULL,
                    `termId` TEXT NOT NULL,
                    `courseCode` TEXT NOT NULL,
                    `courseName` TEXT NOT NULL,
                    `examBatchName` TEXT NOT NULL,
                    `examStageLabel` TEXT NOT NULL,
                    `examFormat` TEXT NOT NULL,
                    `rawTimeText` TEXT NOT NULL,
                    `examDateIso` TEXT,
                    `startTimeIso` TEXT,
                    `endTimeIso` TEXT,
                    `rawRoom` TEXT NOT NULL,
                    `seatNumber` TEXT,
                    `campusName` TEXT NOT NULL,
                    `isRetake` INTEGER NOT NULL,
                    `syncedAtEpochMilli` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
        }
    }

    internal val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reminder_tasks` (
                    `id` TEXT NOT NULL,
                    `taskKey` TEXT NOT NULL,
                    `eventKey` TEXT NOT NULL,
                    `eventType` TEXT NOT NULL,
                    `reminderKind` TEXT NOT NULL,
                    `eventRevision` TEXT NOT NULL,
                    `policyRevision` TEXT NOT NULL,
                    `eventStartAtEpochMs` INTEGER NOT NULL,
                    `originalScheduledAtEpochMs` INTEGER NOT NULL,
                    `scheduledAtEpochMs` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `schedulerState` TEXT NOT NULL,
                    `generation` INTEGER NOT NULL,
                    `snoozeCount` INTEGER NOT NULL,
                    `maxSnoozes` INTEGER NOT NULL,
                    `claimedAtEpochMs` INTEGER,
                    `deliveredAtEpochMs` INTEGER,
                    `lastActionAtEpochMs` INTEGER,
                    `scheduleMode` TEXT NOT NULL,
                    `lastErrorCode` TEXT,
                    `createdAtEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_reminder_tasks_taskKey` ON `reminder_tasks` (`taskKey`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reminder_tasks_status_scheduledAtEpochMs` ON `reminder_tasks` (`status`, `scheduledAtEpochMs`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reminder_tasks_eventKey_status` ON `reminder_tasks` (`eventKey`, `status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reminder_tasks_schedulerState_scheduledAtEpochMs` ON `reminder_tasks` (`schedulerState`, `scheduledAtEpochMs`)"
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reminder_event_prefs` (
                    `eventScopeKey` TEXT NOT NULL,
                    `enabledOverride` INTEGER,
                    `leadMinutesOverride` INTEGER,
                    `maxSnoozesOverride` INTEGER,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`eventScopeKey`)
                )
                """.trimIndent()
            )
        }
    }
}
