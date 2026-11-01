package cn.edu.haut.campus.core.database

import androidx.room.Entity
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.edu.haut.campus.core.academic.UnscheduledAcademicCourse
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** 未提供时间的官方课程独立保存，不进入排课规则、下一节课和提醒查询。 */
@Entity(tableName = "unscheduled_academic_courses", primaryKeys = ["termId", "sourceKey"])
data class UnscheduledAcademicCourseEntity(
    val termId: String,
    val sourceKey: String,
    val sourceId: String?,
    val name: String,
    val teacher: String?,
    val creditPlain: String?,
    val rawWeeks: String,
    val category: String,
    val syncedAtEpochMilli: Long
) {
    fun toDomain() = UnscheduledAcademicCourse(
        sourceId = sourceId,
        name = name,
        teacher = teacher,
        credit = creditPlain?.let(::BigDecimal),
        rawWeeks = rawWeeks,
        category = category
    )
}

internal fun List<UnscheduledAcademicCourse>.toUnscheduledEntities(
    termId: String,
    syncedAt: Instant = Instant.now()
): List<UnscheduledAcademicCourseEntity> {
    require(termId.isNotBlank()) { "待排课程必须归属明确学期" }
    val rows = map { course ->
        require(course.name.isNotBlank()) { "待排课程缺少名称" }
        // 官方课程代码不能唯一定位同课的分段实践安排，因此仍保留教师、周次、类别区分。
        val identity = listOf(
            course.sourceId.orEmpty(), course.name, course.teacher.orEmpty(),
            course.rawWeeks, course.category
        ).joinToString("") { "${it.length}:$it" }
        val sourceKey = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        UnscheduledAcademicCourseEntity(
            termId = termId,
            sourceKey = sourceKey,
            sourceId = course.sourceId,
            name = course.name,
            teacher = course.teacher,
            creditPlain = course.credit?.toPlainString(),
            rawWeeks = course.rawWeeks,
            category = course.category,
            syncedAtEpochMilli = syncedAt.toEpochMilli()
        )
    }
    // 内容矛盾时保留旧完整快照，避免 REPLACE 的最后一条静默胜出。
    require(rows.groupBy { it.sourceKey }.values.none { group -> group.distinct().size > 1 }) {
        "待排课程存在无法区分的冲突记录，已保留本地数据"
    }
    return rows.distinctBy { it.sourceKey }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `unscheduled_academic_courses` (
                `termId` TEXT NOT NULL,
                `sourceKey` TEXT NOT NULL,
                `sourceId` TEXT,
                `name` TEXT NOT NULL,
                `teacher` TEXT,
                `creditPlain` TEXT,
                `rawWeeks` TEXT NOT NULL,
                `category` TEXT NOT NULL,
                `syncedAtEpochMilli` INTEGER NOT NULL,
                PRIMARY KEY(`termId`, `sourceKey`)
            )
            """.trimIndent()
        )
    }
}
