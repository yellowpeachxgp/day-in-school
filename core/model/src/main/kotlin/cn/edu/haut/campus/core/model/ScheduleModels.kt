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

/**
 * 连堂课程内部的小课间（或跨大节连排课内部的课间）。
 * 例如 1-2 节（08:30–10:05）内部的 09:15–09:20（5 分钟小课间）。
 */
data class InternalPeriodBreak(
    val afterPeriod: Int,
    val beforePeriod: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val durationMinutes: Long
) {
    val label: String
        get() = if (durationMinutes <= 10) {
            "小课间 ${durationMinutes}分钟"
        } else {
            "大课间 ${durationMinutes}分钟"
        }
}

/**
 * 跨课程课间类型（自动根据两门课起止时刻与节次识别）。
 */
enum class BreakType {
    /** 课间小休息（<= 10 分钟） */
    SMALL_BREAK,
    /** 跨大节大课间（11..45 分钟，如 2-3 节间 10:05–10:25 的 20 分钟大课间） */
    BIG_BREAK,
    /** 午间休息（上午课与下午课之间，如 12:00–14:30） */
    NOON_BREAK,
    /** 傍晚/晚餐休息（下午课与晚课之间，如 18:00–19:30） */
    EVENING_BREAK,
    /** 跨大节空堂间歇（> 45 分钟的非午休/晚休空档） */
    FREE_PERIOD_GAP
}

/**
 * 同一天相邻两门课程之间的课间区间（含时长、类型、纵轴比例位置、以及跨教室/跨楼栋换教室提醒）。
 */
data class ScheduleBreakInterval(
    val previousCourseName: String,
    val nextCourseName: String,
    val afterPeriod: Int,
    val beforePeriod: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val durationMinutes: Long,
    val breakType: BreakType,
    val label: String,
    val fromRoom: String,
    val toRoom: String,
    val fromBuildingId: String? = null,
    val toBuildingId: String? = null,
    val requiresRoomChange: Boolean = fromRoom != toRoom,
    val requiresBuildingChange: Boolean = fromBuildingId != null && toBuildingId != null && fromBuildingId != toBuildingId,
    val topPx: Float = 0f,
    val heightPx: Float = 0f,
    val isActiveNow: Boolean = false,
    val minutesRemainingInBreak: Long? = null
) {
    val roomTransferText: String?
        get() = when {
            requiresBuildingChange -> "跨楼换教室：$fromRoom → $toRoom"
            requiresRoomChange -> "换教室：$fromRoom → $toRoom"
            else -> null
        }
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
    val conflictColumnCount: Int = 1,
    val internalBreaks: List<InternalPeriodBreak> = emptyList(),
    val mergedRuleIds: List<String> = listOfNotNull(ruleId),
    val hasResolvedTime: Boolean = true
) {
    val periodRangeText: String
        get() = if (startPeriod == endPeriod) "第${startPeriod}节" else "${startPeriod}-${endPeriod}节"

    fun formatTimeOrPeriodRange(formatter: java.time.format.DateTimeFormatter): String =
        if (hasResolvedTime) {
            "${startTime.format(formatter)}–${endTime.format(formatter)}"
        } else {
            "$periodRangeText（作息待设）"
        }

    val internalBreaksSummary: String?
        get() {
            if (internalBreaks.isEmpty()) return null
            return internalBreaks.joinToString(" / ") { brk ->
                "${brk.startTime}–${brk.endTime} (${brk.label})"
            }
        }
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
    val activeInternalBreak: InternalPeriodBreak? = null,
    val currentSubPeriodNo: Int? = null,
    val statusText: String
)

data class NextClassDayHint(
    val nextDate: LocalDate,
    val daysUntil: Long,
    val firstCourseName: String,
    val firstCourseStartTime: LocalTime,
    val firstCourseRoom: String,
    val hasResolvedStartTime: Boolean = true,
    val firstCoursePeriodRangeText: String = ""
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
    val breakIntervals: List<ScheduleBreakInterval> = emptyList(),
    val activeBreakInterval: ScheduleBreakInterval? = null,
    val nextClassDayHint: NextClassDayHint? = null
)
