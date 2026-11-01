package cn.edu.haut.campus.core.protocol

import cn.edu.haut.campus.core.model.AcademicProtocolFamily
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** 协议层只负责把校方返回变成结构化事实，不携带任何 UI 或数据库语义。 */
data class RemoteTerm(
    val sourceId: String,
    val label: String,
    val academicYear: Int? = null,
    val ordinal: Int? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null
) {
    init { require(sourceId.isNotBlank()) { "来源学期 ID 不能为空" } }
}

/** 一次上课安排；周次使用 1 起始的绝对教学周，未解析出周次时保持空集而不是猜测。 */
data class RemoteMeeting(
    val weekday: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: Set<Int> = emptySet(),
    val location: String? = null,
    val teacher: String? = null
) {
    init {
        require(weekday in 1..7) { "星期必须在 1..7 之间" }
        require(startSection in 1..60 && endSection in 1..60 && startSection <= endSection) {
            "节次范围无效"
        }
        require(weeks.all { it in 1..80 }) { "教学周超出允许范围" }
    }

    /** 半开区间比较：结束节次的下一个单位才是下一节课的起点。 */
    fun overlaps(other: RemoteMeeting): Boolean =
        weekday == other.weekday &&
            startSection <= other.endSection &&
            other.startSection <= endSection &&
            (weeks.isEmpty() || other.weeks.isEmpty() || weeks.any { it in other.weeks })
}

data class RemoteCourse(
    val sourceId: String,
    val name: String,
    val code: String? = null,
    val credits: BigDecimal? = null,
    val teacher: String? = null,
    val meetings: List<RemoteMeeting> = emptyList()
) {
    init {
        require(sourceId.isNotBlank()) { "课程来源 ID 不能为空" }
        require(name.isNotBlank()) { "课程名称不能为空" }
    }
}

data class RemoteGrade(
    val sourceId: String,
    val courseName: String,
    val credits: BigDecimal? = null,
    val scoreText: String? = null,
    val gradePoint: BigDecimal? = null,
    val attempt: String? = null,
    val termSourceId: String? = null,
    val category: String? = null
) {
    init {
        require(sourceId.isNotBlank()) { "成绩来源 ID 不能为空" }
        require(courseName.isNotBlank()) { "成绩课程名称不能为空" }
    }
}

data class RemoteExam(
    val sourceId: String,
    val courseName: String,
    val date: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val location: String? = null,
    val seat: String? = null,
    val kind: String? = null
) {
    init {
        require(sourceId.isNotBlank()) { "考试来源 ID 不能为空" }
        require(courseName.isNotBlank()) { "考试课程名称不能为空" }
    }
}

data class TimetableSnapshot(
    val termSourceId: String,
    val courses: List<RemoteCourse>,
    val completeness: SnapshotCompleteness
)

data class GradeSnapshot(
    val termSourceId: String?,
    val grades: List<RemoteGrade>,
    val completeness: SnapshotCompleteness
)

data class ExamSnapshot(
    val termSourceId: String?,
    val exams: List<RemoteExam>,
    val completeness: SnapshotCompleteness
)

data class GatewayDescriptor(
    val id: String,
    val protocol: AcademicProtocolFamily,
    val displayName: String,
    val authMode: AuthMode,
    val readCapabilities: Set<GatewayCapability> = GatewayCapability.readOnly(),
    val writeCapabilities: Set<GatewayCapability> = emptySet(),
    val requiresJavaScript: Boolean = false,
    val notes: String? = null
) {
    init {
        require(id.isNotBlank()) { "Gateway ID 不能为空" }
        require(displayName.isNotBlank()) { "Gateway 名称不能为空" }
        require(writeCapabilities.all { it.writable }) { "写能力必须声明为可写能力" }
    }
}

enum class AuthMode { FORM_CREDENTIALS, CAS_REDIRECT, SSO_REDIRECT, WEBVIEW_MANUAL }

enum class GatewayCapability(val writable: Boolean = false) {
    TERMS_READ,
    TIMETABLE_READ,
    GRADES_READ,
    EXAMS_READ,
    CALENDAR_READ,
    SELECTION_QUERY,
    SELECTION_WRITE(writable = true);

    companion object {
        fun readOnly(): Set<GatewayCapability> =
            setOf(TERMS_READ, TIMETABLE_READ, GRADES_READ, EXAMS_READ)
    }
}
