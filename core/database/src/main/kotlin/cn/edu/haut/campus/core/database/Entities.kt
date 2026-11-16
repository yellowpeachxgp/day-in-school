package cn.edu.haut.campus.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.edu.haut.campus.core.domain.WeeksSetParser
import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.ImportAudit
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import cn.edu.haut.campus.core.model.WidgetThemeStyle
import cn.edu.haut.campus.core.model.WidgetType
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity(tableName = "student_profile")
data class StudentProfileEntity(
    @PrimaryKey val id: String,
    val schoolId: String,
    val displayName: String?,
    val major: String?,
    val enrollmentYear: Int?
) {
    fun toDomain() = StudentProfile(id, schoolId, displayName, major, enrollmentYear)
}

@Entity(tableName = "terms")
data class TermEntity(
    @PrimaryKey val id: String,
    val academicYear: String,
    val termNumber: Int,
    val startDateIso: String,
    val weekStartDayIso: Int,
    val weekCount: Int,
    val timeTableId: String,
    val current: Boolean
) {
    fun toDomain() = Term(
        id = id,
        academicYear = academicYear,
        termNumber = termNumber,
        startDate = LocalDate.parse(startDateIso),
        weekStartDay = DayOfWeek.of(weekStartDayIso),
        weekCount = weekCount,
        timeTableId = timeTableId,
        current = current
    )
}

@Entity(tableName = "period_times")
data class PeriodTimeEntity(
    @PrimaryKey val id: String,
    val timeTableId: String,
    val periodNo: Int,
    val startLocalTimeIso: String,
    val endLocalTimeIso: String,
    val sectionLabel: String
) {
    fun toDomain() = PeriodTime(
        id = id,
        timeTableId = timeTableId,
        periodNo = periodNo,
        startLocalTime = LocalTime.parse(startLocalTimeIso),
        endLocalTime = LocalTime.parse(endLocalTimeIso),
        sectionLabel = sectionLabel
    )
}

@Entity(tableName = "calendar_overrides")
data class CalendarOverrideEntity(
    @PrimaryKey val id: String,
    val termId: String,
    val dateIso: String,
    val overrideType: String,
    val followDateIso: String?,
    val customTeachingWeek: Int?,
    val title: String
) {
    fun toDomain() = CalendarOverride(
        id = id,
        termId = termId,
        date = LocalDate.parse(dateIso),
        overrideType = CalendarOverrideType.valueOf(overrideType),
        followDate = followDateIso?.let(LocalDate::parse),
        customTeachingWeek = customTeachingWeek,
        title = title
    )
}

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val externalId: String?,
    val name: String,
    val teacher: String?,
    val colorArgb: Long,
    val creditPlain: String?,
    val source: String,
    val userModified: Boolean,
    val lastSyncEpochMilli: Long?
) {
    fun toDomain() = Course(
        id = id,
        externalId = externalId,
        name = name,
        teacher = teacher,
        colorArgb = colorArgb,
        credit = creditPlain?.let(::BigDecimal),
        source = CourseSource.valueOf(source),
        userModified = userModified,
        lastSyncAt = lastSyncEpochMilli?.let(Instant::ofEpochMilli)
    )
}

@Entity(tableName = "course_schedule_rules")
data class CourseScheduleRuleEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val termId: String,
    val weekdayIso: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeksCsv: String,
    val rawRoom: String,
    val buildingId: String?,
    val customStartTimeIso: String?,
    val customEndTimeIso: String?,
    val userModified: Boolean
) {
    fun toDomain() = CourseScheduleRule(
        id = id,
        courseId = courseId,
        termId = termId,
        weekday = DayOfWeek.of(weekdayIso),
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = weeksCsv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet(),
        rawRoom = rawRoom,
        buildingId = buildingId,
        customStartTime = customStartTimeIso?.let(LocalTime::parse),
        customEndTime = customEndTimeIso?.let(LocalTime::parse),
        userModified = userModified
    )
}

@Entity(
    tableName = "occurrence_overrides",
    indices = [Index(value = ["termId", "moveId", "moveRole"], unique = false)]
)
data class OccurrenceOverrideEntity(
    @PrimaryKey val id: String,
    val termId: String,
    val courseRuleId: String?,
    val courseId: String?,
    val dateIso: String,
    val startPeriod: Int?,
    val endPeriod: Int?,
    val startAtIso: String?,
    val endAtIso: String?,
    val room: String?,
    val buildingId: String?,
    val canceled: Boolean,
    val reason: String?,
    /** 跨日调课稳定关联 ID：同一次调课的全部源停课行与调入目标行共享，普通停课/旧数据为 null。 */
    val moveId: String?,
    /** 调课行角色（受控值 SOURCE/TARGET）；null 表示与调课无关。 */
    val moveRole: String?,
    /**
     * 不可复用行版本令牌：任何写入路径每次真实写入都盖印新的随机 UUID，
     * 精确恢复命令以点击时捕获的令牌逐行断言身份；旧行由迁移一次性分配稳定且不重复。
     */
    val mutationToken: String = ""
) {
    fun toDomain() = OccurrenceOverride(
        id = id,
        termId = termId,
        courseRuleId = courseRuleId,
        courseId = courseId,
        date = LocalDate.parse(dateIso),
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        startAt = startAtIso?.let(LocalTime::parse),
        endAt = endAtIso?.let(LocalTime::parse),
        room = room,
        buildingId = buildingId,
        canceled = canceled,
        reason = reason,
        moveId = moveId,
        moveRole = moveRole,
        mutationToken = mutationToken
    )
}

@Entity(tableName = "grade_attempts")
data class GradeAttemptEntity(
    @PrimaryKey val id: String,
    val termId: String,
    val academicYear: String,
    val termNumber: Int,
    val courseKey: String,
    val name: String,
    val category: String,
    val creditsPlain: String,
    val rawGrade: String,
    val numericScorePlain: String?,
    val gradePointPlain: String?,
    val attemptType: String,
    val passed: Boolean,
    val isCourseReplacement: Boolean,
    val source: String,
    val syncedAtEpochMilli: Long
) {
    fun toDomain() = GradeAttempt(
        id = id,
        termId = termId,
        academicYear = academicYear,
        termNumber = termNumber,
        courseKey = courseKey,
        name = name,
        category = CourseCategory.valueOf(category),
        credits = BigDecimal(creditsPlain),
        rawGrade = rawGrade,
        numericScore = numericScorePlain?.let(::BigDecimal),
        gradePoint = gradePointPlain?.let(::BigDecimal),
        attemptType = AttemptType.valueOf(attemptType),
        passed = passed,
        isCourseReplacement = isCourseReplacement,
        source = CourseSource.valueOf(source),
        syncedAt = Instant.ofEpochMilli(syncedAtEpochMilli)
    )
}

@Entity(tableName = "gpa_policies")
data class GpaPolicyEntity(
    @PrimaryKey val id: String,
    val source: String,
    val appliesTo: String,
    val policyVersion: String,
    val includeRulesJson: String,
    val scoreToPointRulesJson: String?
)

@Entity(tableName = "buildings")
data class BuildingEntity(
    @PrimaryKey val id: String,
    val campusId: String,
    val name: String,
    val shortName: String,
    val latitude: Double,
    val longitude: Double,
    val category: String,
    val entrancesCsv: String,
    val description: String,
    val verifiedAtIso: String
) {
    fun toDomain() = Building(
        id = id,
        campusId = campusId,
        name = name,
        shortName = shortName,
        latitude = latitude,
        longitude = longitude,
        category = CampusCategory.valueOf(category),
        entrances = entrancesCsv.split("|").filter { it.isNotBlank() },
        description = description,
        verifiedAt = LocalDate.parse(verifiedAtIso)
    )
}

@Entity(tableName = "building_aliases")
data class BuildingAliasEntity(
    @PrimaryKey val id: String,
    val buildingId: String,
    val rawNameOrPattern: String,
    val confidence: Float,
    val verified: Boolean
) {
    fun toDomain() = BuildingAlias(id, buildingId, rawNameOrPattern, confidence, verified)
}

@Entity(tableName = "widget_instance_configs")
data class WidgetInstanceConfigEntity(
    @PrimaryKey val appWidgetId: Int,
    val widgetType: String,
    val termId: String?,
    val themeStyle: String,
    val compact: Boolean,
    val showTeacher: Boolean,
    val showRoom: Boolean,
    val privacyMode: Boolean
) {
    fun toDomain() = WidgetInstanceConfig(
        appWidgetId = appWidgetId,
        widgetType = WidgetType.valueOf(widgetType),
        termId = termId,
        themeStyle = WidgetThemeStyle.valueOf(themeStyle),
        compact = compact,
        showTeacher = showTeacher,
        showRoom = showRoom,
        privacyMode = privacyMode
    )
}

@Entity(tableName = "import_audits")
data class ImportAuditEntity(
    @PrimaryKey val id: String,
    val termId: String,
    val importSource: String,
    val importedAtEpochMilli: Long,
    val rowCount: Int,
    val conflictCount: Int,
    val outcome: String
) {
    fun toDomain() = ImportAudit(
        id = id,
        termId = termId,
        importSource = CourseSource.valueOf(importSource),
        importedAt = Instant.ofEpochMilli(importedAtEpochMilli),
        rowCount = rowCount,
        conflictCount = conflictCount,
        outcome = outcome
    )
}

@Entity(tableName = "exam_schedules")
data class ExamScheduleEntity(
    @PrimaryKey val id: String,
    val termId: String,
    val courseCode: String,
    val courseName: String,
    val examBatchName: String,
    val examStageLabel: String,
    val examFormat: String,
    val rawTimeText: String,
    val examDateIso: String?,
    val startTimeIso: String?,
    val endTimeIso: String?,
    val rawRoom: String,
    val seatNumber: String?,
    val campusName: String,
    val isRetake: Boolean,
    val syncedAtEpochMilli: Long,
    /** 可信同步管线写入时记录的原始学期（权威来源证明）；旧库行或手工写入为 null。 */
    val originTermId: String?
) {
    fun toDomain() = ExamScheduleItem(
        id = id,
        termId = termId,
        courseCode = courseCode,
        courseName = courseName,
        examBatchName = examBatchName,
        examStageLabel = examStageLabel,
        examFormat = examFormat,
        rawTimeText = rawTimeText,
        examDate = examDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        startTime = startTimeIso?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        endTime = endTimeIso?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        rawRoom = rawRoom,
        seatNumber = seatNumber,
        campusName = campusName,
        isRetake = isRetake,
        syncedAt = Instant.ofEpochMilli(syncedAtEpochMilli),
        originTermId = originTermId
    )
}

/**
 * 考试学期修复逐条审计：记录每次自动修复/待核对判定的原始学期、目标学期、证据引用与规则版本，
 * 保证修复可审计、可重跑幂等；绝不使用单个布尔标志冒充“全库已修”。
 */
@Entity(
    tableName = "exam_term_repair_audits",
    indices = [Index(value = ["examRowId"], unique = false)]
)
data class ExamTermRepairAuditEntity(
    /** 稳定主键：ruleVersion + examRowId，重复运行同一规则只保留一条审计。 */
    @PrimaryKey val id: String,
    val examRowId: String,
    val previousTermId: String,
    val newTermId: String?,
    val evidenceRef: String,
    val ruleVersion: String,
    val disposition: String,
    val repairedAtEpochMilli: Long
) {
    companion object {
        const val DISPOSITION_REPAIRED = "REPAIRED"
        const val DISPOSITION_REVIEW_REQUIRED = "REVIEW_REQUIRED"
        const val DISPOSITION_RESOLVED_BY_RESYNC = "RESOLVED_BY_RESYNC"
        const val DISPOSITION_MERGED_DUPLICATE = "MERGED_DUPLICATE"
    }
}

fun StudentProfile.toEntity() = StudentProfileEntity(
    id = id,
    schoolId = schoolId,
    displayName = displayName,
    major = major,
    enrollmentYear = enrollmentYear
)

fun Term.toEntity() = TermEntity(
    id = id,
    academicYear = academicYear,
    termNumber = termNumber,
    startDateIso = startDate.toString(),
    weekStartDayIso = weekStartDay.value,
    weekCount = weekCount,
    timeTableId = timeTableId,
    current = current
)

fun PeriodTime.toEntity() = PeriodTimeEntity(
    id = id,
    timeTableId = timeTableId,
    periodNo = periodNo,
    startLocalTimeIso = startLocalTime.toString(),
    endLocalTimeIso = endLocalTime.toString(),
    sectionLabel = sectionLabel
)

fun CalendarOverride.toEntity() = CalendarOverrideEntity(
    id = id,
    termId = termId,
    dateIso = date.toString(),
    overrideType = overrideType.name,
    followDateIso = followDate?.toString(),
    customTeachingWeek = customTeachingWeek,
    title = title
)

fun Course.toEntity() = CourseEntity(
    id = id,
    externalId = externalId,
    name = name,
    teacher = teacher,
    colorArgb = colorArgb,
    creditPlain = credit?.toPlainString(),
    source = source.name,
    userModified = userModified,
    lastSyncEpochMilli = lastSyncAt?.toEpochMilli()
)

fun CourseScheduleRule.toEntity() = CourseScheduleRuleEntity(
    id = id,
    courseId = courseId,
    termId = termId,
    weekdayIso = weekday.value,
    startPeriod = startPeriod,
    endPeriod = endPeriod,
    weeksCsv = weeks.sorted().joinToString(","),
    rawRoom = rawRoom,
    buildingId = buildingId,
    customStartTimeIso = customStartTime?.toString(),
    customEndTimeIso = customEndTime?.toString(),
    userModified = userModified
)

fun OccurrenceOverride.toEntity() = OccurrenceOverrideEntity(
    id = id,
    termId = termId,
    courseRuleId = courseRuleId,
    courseId = courseId,
    dateIso = date.toString(),
    startPeriod = startPeriod,
    endPeriod = endPeriod,
    startAtIso = startAt?.toString(),
    endAtIso = endAt?.toString(),
    room = room,
    buildingId = buildingId,
    canceled = canceled,
    reason = reason,
    moveId = moveId,
    moveRole = moveRole,
    // 未盖印的领域对象（空令牌）在落库瞬间补齐随机令牌：持久化行永远持有非空令牌。
    mutationToken = mutationToken.ifBlank { UUID.randomUUID().toString() }
)

fun GradeAttempt.toEntity() = GradeAttemptEntity(
    id = id,
    termId = termId,
    academicYear = academicYear,
    termNumber = termNumber,
    courseKey = courseKey,
    name = name,
    category = category.name,
    creditsPlain = credits.toPlainString(),
    rawGrade = rawGrade,
    numericScorePlain = numericScore?.toPlainString(),
    gradePointPlain = gradePoint?.toPlainString(),
    attemptType = attemptType.name,
    passed = passed,
    isCourseReplacement = isCourseReplacement,
    source = source.name,
    syncedAtEpochMilli = syncedAt.toEpochMilli()
)

fun Building.toEntity() = BuildingEntity(
    id = id,
    campusId = campusId,
    name = name,
    shortName = shortName,
    latitude = latitude,
    longitude = longitude,
    category = category.name,
    entrancesCsv = entrances.joinToString("|"),
    description = description,
    verifiedAtIso = verifiedAt.toString()
)

fun BuildingAlias.toEntity() = BuildingAliasEntity(
    id = id,
    buildingId = buildingId,
    rawNameOrPattern = rawNameOrPattern,
    confidence = confidence,
    verified = verified
)

fun WidgetInstanceConfig.toEntity() = WidgetInstanceConfigEntity(
    appWidgetId = appWidgetId,
    widgetType = widgetType.name,
    termId = termId,
    themeStyle = themeStyle.name,
    compact = compact,
    showTeacher = showTeacher,
    showRoom = showRoom,
    privacyMode = privacyMode
)

fun ImportAudit.toEntity() = ImportAuditEntity(
    id = id,
    termId = termId,
    importSource = importSource.name,
    importedAtEpochMilli = importedAt.toEpochMilli(),
    rowCount = rowCount,
    conflictCount = conflictCount,
    outcome = outcome
)

fun ExamScheduleItem.toEntity() = ExamScheduleEntity(
    id = id,
    termId = termId,
    courseCode = courseCode,
    courseName = courseName,
    examBatchName = examBatchName,
    examStageLabel = examStageLabel,
    examFormat = examFormat,
    rawTimeText = rawTimeText,
    examDateIso = examDate?.toString(),
    startTimeIso = startTime?.toString(),
    endTimeIso = endTime?.toString(),
    rawRoom = rawRoom,
    seatNumber = seatNumber,
    campusName = campusName,
    isRetake = isRetake,
    syncedAtEpochMilli = syncedAt.toEpochMilli(),
    originTermId = originTermId
)

/**
 * Room 8 -> 9：
 * - occurrence_overrides 追加可空 moveId/moveRole 两列与非唯一索引 (termId, moveId, moveRole)，
 *   支撑按稳定调课关联精确恢复；旧行保持 NULL，绝不按 reason 文本回填配对、绝不删表重建。
 * - exam_schedules 追加可空 originTermId 列，记录可信同步管线写入时的原始学期证据。
 * - 新建 exam_term_repair_audits 逐条修复审计表（原/新学期、证据引用、规则版本、时间、行 ID）。
 * 迁移不猜测学期：无任何权威来源的旧行一律保留原值并交由运行时 V2 修复流程判定。
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `occurrence_overrides` ADD COLUMN `moveId` TEXT")
        db.execSQL("ALTER TABLE `occurrence_overrides` ADD COLUMN `moveRole` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_occurrence_overrides_termId_moveId_moveRole` ON `occurrence_overrides` (`termId`, `moveId`, `moveRole`)"
        )
        db.execSQL("ALTER TABLE `exam_schedules` ADD COLUMN `originTermId` TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `exam_term_repair_audits` (
                `id` TEXT NOT NULL,
                `examRowId` TEXT NOT NULL,
                `previousTermId` TEXT NOT NULL,
                `newTermId` TEXT,
                `evidenceRef` TEXT NOT NULL,
                `ruleVersion` TEXT NOT NULL,
                `disposition` TEXT NOT NULL,
                `repairedAtEpochMilli` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exam_term_repair_audits_examRowId` ON `exam_term_repair_audits` (`examRowId`)"
        )
    }
}

/**
 * Room 9 -> 10：
 * - occurrence_overrides 追加非空 mutationToken 列（不可复用行版本令牌）。
 *   迁移为每条既有行独立分配随机的 UUID 形态令牌：randomblob 按行求值，保证稳定且不重复；
 *   普通停课行、调课 SOURCE/TARGET、暂停目标、同 ID 历史行与旧版模糊调课行全部原样保留，
 *   绝不按 reason/moveId 文本猜测改写任何语义字段，绝不删表重建。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `occurrence_overrides` ADD COLUMN `mutationToken` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            UPDATE `occurrence_overrides` SET `mutationToken` =
                lower(hex(randomblob(4))) || '-' ||
                lower(hex(randomblob(2))) || '-' ||
                lower(hex(randomblob(2))) || '-' ||
                lower(hex(randomblob(2))) || '-' ||
                lower(hex(randomblob(6)))
            WHERE `mutationToken` = ''
            """.trimIndent()
        )
    }
}
