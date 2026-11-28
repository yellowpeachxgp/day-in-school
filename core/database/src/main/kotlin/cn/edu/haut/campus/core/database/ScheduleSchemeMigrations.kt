package cn.edu.haut.campus.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room 10 -> 11（W0-04，主代理分配版本号）：
 * - period_times 追加可空 schemeId/termId/生效日期列，以及用户修改与更新时间元数据；
 *   既有行全部保持 schemeId=NULL，继续作为学期默认参考作息，绝不猜测改写用户时间。
 * - 新建 period_time_schemes 元数据表，用日期区间承载同一学期多套作息方案。
 * 迁移只增列/增表/增索引，不重建表、不删除或回填任何既有 period_times 语义。
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `period_times` ADD COLUMN `schemeId` TEXT")
        db.execSQL("ALTER TABLE `period_times` ADD COLUMN `termId` TEXT")
        db.execSQL("ALTER TABLE `period_times` ADD COLUMN `validStartDateIso` TEXT")
        db.execSQL("ALTER TABLE `period_times` ADD COLUMN `validEndDateIso` TEXT")
        db.execSQL("ALTER TABLE `period_times` ADD COLUMN `userModified` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `period_times` ADD COLUMN `updatedAtEpochMillis` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_period_times_schemeId` ON `period_times` (`schemeId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_period_times_termId` ON `period_times` (`termId`)"
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `period_time_schemes` (
                `id` TEXT NOT NULL,
                `termId` TEXT NOT NULL,
                `timeTableId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `startDateIso` TEXT NOT NULL,
                `endDateIso` TEXT NOT NULL,
                `userModified` INTEGER NOT NULL,
                `createdAtEpochMilli` INTEGER NOT NULL,
                `updatedAtEpochMilli` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_period_time_schemes_termId` ON `period_time_schemes` (`termId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_period_time_schemes_timeTableId` ON `period_time_schemes` (`timeTableId`)"
        )
    }
}
