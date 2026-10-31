package cn.edu.haut.campus.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
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

@Entity(tableName = "occurrence_overrides")
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
    val reason: String?
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
        reason = reason
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
    reason = reason
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
