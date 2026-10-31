package cn.edu.haut.campus.core.model

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

val HAUT_ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")

data class StudentProfile(
    val id: String,
    val schoolId: String,
    val displayName: String? = null,
    val major: String? = null,
    val enrollmentYear: Int? = null
)

data class Term(
    val id: String,
    val academicYear: String,
    val termNumber: Int,
    val startDate: LocalDate,
    val weekStartDay: DayOfWeek = DayOfWeek.MONDAY,
    val weekCount: Int = 20,
    val timeTableId: String = "haut_lianhuajie_default",
    val current: Boolean = false
) {
    val displayName: String
        get() = "${academicYear} 第 $termNumber 学期"
}

data class PeriodTime(
    val id: String,
    val timeTableId: String,
    val periodNo: Int,
    val startLocalTime: LocalTime,
    val endLocalTime: LocalTime,
    val sectionLabel: String = when {
        periodNo <= 4 -> "上午"
        periodNo <= 8 -> "下午"
        else -> "晚间"
    }
)

enum class CalendarOverrideType {
    /** 节假日停课 */
    HOLIDAY_NO_CLASS,
    /** 调休补课：当天按照指定日期（followDate）的周次和星期排课 */
    MAKEUP_FOLLOW_DATE,
    /** 特殊教学周覆盖 */
    CUSTOM_TEACHING_WEEK
}

data class CalendarOverride(
    val id: String,
    val termId: String,
    val date: LocalDate,
    val overrideType: CalendarOverrideType,
    val followDate: LocalDate? = null,
    val customTeachingWeek: Int? = null,
    val title: String
)

enum class CourseSource {
    OFFICIAL_JW,
    MANUAL,
    CSV_ICS,
    OCR
}

data class Course(
    val id: String,
    val externalId: String? = null,
    val name: String,
    val teacher: String? = null,
    val colorArgb: Long,
    val credit: BigDecimal? = null,
    val source: CourseSource = CourseSource.OFFICIAL_JW,
    val userModified: Boolean = false,
    val lastSyncAt: Instant? = null
)

/**
 * 课程排课规则模板。
 * 有课周次精确存储为 [weeks]: Set<Int>，不依赖模糊的“单/双周”字符串。
 */
data class CourseScheduleRule(
    val id: String,
    val courseId: String,
    val termId: String,
    val weekday: DayOfWeek,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: Set<Int>,
    val rawRoom: String,
    val buildingId: String? = null,
    val customStartTime: LocalTime? = null,
    val customEndTime: LocalTime? = null,
    val userModified: Boolean = false
)

/**
 * 单日课程覆盖（停课、改时间、改教室、或单次新增补课），不污染原始 [CourseScheduleRule]。
 */
data class OccurrenceOverride(
    val id: String,
    val termId: String,
    val courseRuleId: String? = null,
    val courseId: String? = null,
    val date: LocalDate,
    val startPeriod: Int? = null,
    val endPeriod: Int? = null,
    val startAt: LocalTime? = null,
    val endAt: LocalTime? = null,
    val room: String? = null,
    val buildingId: String? = null,
    val canceled: Boolean = false,
    val reason: String? = null
)

enum class ClassStatus {
    UPCOMING,
    IN_PROGRESS,
    FINISHED
}

data class ClassOccurrence(
    val occurrenceId: String,
    val courseId: String,
    val ruleId: String? = null,
    val overrideId: String? = null,
    val courseName: String,
    val teacher: String? = null,
    val colorArgb: Long,
    val date: LocalDate,
    val teachingWeek: Int?,
    val weekday: DayOfWeek,
    val startPeriod: Int,
    val endPeriod: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val rawRoom: String,
    val buildingId: String? = null,
    val buildingName: String? = null,
    val isMakeupOrOverridden: Boolean = false,
    val overrideReason: String? = null,
    val userModified: Boolean = false,
    val conflictColumnIndex: Int = 0,
    val conflictColumnCount: Int = 1
) {
    val periodRangeText: String
        get() = if (startPeriod == endPeriod) "第${startPeriod}节" else "${startPeriod}-${endPeriod}节"
}

enum class TermDateStatus {
    BEFORE_TERM,
    IN_TERM,
    AFTER_TERM,
    HOLIDAY_NO_CLASS,
    MAKEUP_RESCHEDULED
}

data class TimelineOccurrenceState(
    val occurrence: ClassOccurrence,
    val status: ClassStatus,
    val topPx: Float,
    val heightPx: Float,
    val minutesUntilStart: Long? = null,
    val minutesElapsed: Long? = null,
    val minutesRemaining: Long? = null,
    val statusText: String
)

data class NextClassDayHint(
    val nextDate: LocalDate,
    val daysUntil: Long,
    val firstCourseName: String,
    val firstCourseStartTime: LocalTime,
    val firstCourseRoom: String
)

data class DayTimelineSnapshot(
    val date: LocalDate,
    val now: ZonedDateTime,
    val term: Term?,
    val teachingWeek: Int?,
    val termStatus: TermDateStatus,
    val calendarNotice: String? = null,
    val timelineStart: LocalTime,
    val timelineEnd: LocalTime,
    val pxPerMinute: Float,
    val totalHeightPx: Float,
    val currentTimeLinePx: Float?,
    val activeOrNextClass: TimelineOccurrenceState?,
    val items: List<TimelineOccurrenceState>,
    val nextClassDayHint: NextClassDayHint? = null
)
