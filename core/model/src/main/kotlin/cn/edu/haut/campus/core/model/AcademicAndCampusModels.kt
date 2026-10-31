package cn.edu.haut.campus.core.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

enum class AttemptType(val label: String) {
    NORMAL("正常修读"),
    MAKEUP("补考"),
    RETAKE("重修"),
    EXEMPT("免修/免考"),
    DEFERRED("缓考"),
    PASS_FAIL("通过制"),
    FIVE_LEVEL("五级制")
}

enum class CourseCategory(val label: String) {
    COMPULSORY("必修"),
    ELECTIVE("专业选修"),
    PUBLIC_ELECTIVE("通识选修"),
    PRACTICE("实践环节")
}

data class GradeAttempt(
    val id: String,
    val termId: String,
    val academicYear: String,
    val termNumber: Int,
    val courseKey: String,
    val name: String,
    val category: CourseCategory = CourseCategory.COMPULSORY,
    val credits: BigDecimal,
    val rawGrade: String,
    val numericScore: BigDecimal? = null,
    val gradePoint: BigDecimal? = null,
    val attemptType: AttemptType = AttemptType.NORMAL,
    val passed: Boolean = true,
    val isCourseReplacement: Boolean = false,
    val source: CourseSource = CourseSource.OFFICIAL_JW,
    val syncedAt: Instant
)

enum class GpaSourceType(val badgeLabel: String) {
    OFFICIAL_JW("来自教务官方汇总"),
    LOCAL_WEIGHTED_FROM_JW_POINTS("本地加权计算（教务课程绩点）"),
    POLICY_FORMULA_ESTIMATE("按已验证学校规则换算"),
    INSUFFICIENT_RULE("不足以可靠计算")
}

enum class RetakeInclusionStrategy {
    /** 取最高有效修读记录（重修/补考覆盖原记录） */
    HIGHEST_ATTEMPT,
    /** 取最新一次有效修读记录 */
    LATEST_ATTEMPT,
    /** 仅首次正考 */
    FIRST_ATTEMPT_ONLY
}

data class GpaPolicy(
    val id: String,
    val source: String,
    val appliesTo: String,
    val policyVersion: String,
    val retakeStrategy: RetakeInclusionStrategy = RetakeInclusionStrategy.HIGHEST_ATTEMPT,
    val includeZeroCreditCourses: Boolean = false,
    val includePassFailCoursesInGpa: Boolean = false,
    val allowRawScoreFallbackFormula: Boolean = false,
    val scoreToPointRules: List<ScoreBandRule> = emptyList()
)

data class ScoreBandRule(
    val minScoreInclusive: BigDecimal,
    val maxScoreInclusive: BigDecimal,
    val gradePoint: BigDecimal
)

data class OfficialGpaSnapshot(
    val cumulativeGpa: BigDecimal?,
    val termGpas: Map<String, BigDecimal> = emptyMap(),
    val academicYearGpas: Map<String, BigDecimal> = emptyMap(),
    val fetchedAt: Instant,
    val fieldDefinitionNote: String
)

data class TermGpaTrendPoint(
    val termId: String,
    val termLabel: String,
    val gpa: BigDecimal?,
    val weightedAverageScore: BigDecimal?,
    val earnedCredits: BigDecimal,
    val sourceType: GpaSourceType
)

data class GradeDistribution(
    val excellentCount: Int, // >= 90 or GPA >= 3.7 / 优秀
    val goodCount: Int,      // 80-89 / 良好
    val mediumCount: Int,    // 70-79 / 中等
    val passCount: Int,      // 60-69 / 及格 / 通过
    val failOrOtherCount: Int // < 60 / 未通过 / 缓考
)

data class CourseGradeAuditItem(
    val courseKey: String,
    val courseName: String,
    val selectedAttempt: GradeAttempt?,
    val allAttempts: List<GradeAttempt>,
    val includedInGpaDenominator: Boolean,
    val exclusionOrChangeReason: String
)

data class GpaReport(
    val cumulativeGpa: BigDecimal?,
    val currentTermGpa: BigDecimal?,
    val currentAcademicYearGpa: BigDecimal?,
    val totalEarnedCredits: BigDecimal,
    val gpaIncludedCredits: BigDecimal,
    val primarySourceType: GpaSourceType,
    val sourceExplanation: String,
    val termTrends: List<TermGpaTrendPoint>,
    val distribution: GradeDistribution,
    val auditItems: List<CourseGradeAuditItem>,
    val diagnosticNotes: List<String>
)

enum class CampusCategory(val label: String) {
    TEACHING_BUILDING("教学楼"),
    LIBRARY("图书馆"),
    CANTEEN("食堂"),
    GATE("校门"),
    DORMITORY("宿舍区"),
    EXPRESS("快递/生活"),
    LAB("实验楼/学院楼"),
    SPORTS("体育场馆")
}

data class Building(
    val id: String,
    val campusId: String = "lianhuajie",
    val name: String,
    val shortName: String,
    val latitude: Double,
    val longitude: Double,
    val category: CampusCategory,
    val entrances: List<String>,
    val description: String,
    val verifiedAt: LocalDate
)

data class BuildingAlias(
    val id: String,
    val buildingId: String,
    val rawNameOrPattern: String,
    val confidence: Float,
    val verified: Boolean
)

enum class BuildingMatchStatus {
    EXACT_VERIFIED,
    FUZZY_NEEDS_CONFIRMATION,
    UNRESOLVED
}

data class ResolvedClassroomLocation(
    val rawRoom: String,
    val status: BuildingMatchStatus,
    val building: Building? = null,
    val roomNumberHint: String? = null,
    val confidence: Float = 0f,
    val matchedAlias: String? = null,
    val candidateBuildings: List<Building> = emptyList()
)

enum class WidgetType(val label: String, val gridSpec: String) {
    NEXT_CLASS_2X2("下一节课 Mini", "2×2"),
    TODAY_4X2("今日课程 Today", "4×2"),
    TIMELINE_4X4("日程时间轴 Timeline", "4×4"),
    WEEK_4X4("周课表 Week", "4×4")
}

enum class WidgetThemeStyle(val label: String) {
    DYNAMIC_SYSTEM("系统动态色 (Android 12+)"),
    WARM_PEACH("暖橙/桃色强调"),
    NEUTRAL_RATIONAL("极简理性灰")
}

data class WidgetInstanceConfig(
    val appWidgetId: Int,
    val widgetType: WidgetType,
    val termId: String? = null,
    val themeStyle: WidgetThemeStyle = WidgetThemeStyle.DYNAMIC_SYSTEM,
    val compact: Boolean = false,
    val showTeacher: Boolean = true,
    val showRoom: Boolean = true,
    val privacyMode: Boolean = false
)

enum class ImportChangeType(val label: String) {
    ADDED("新增课程"),
    MODIFIED("教务更新"),
    CONFLICT_USER_MODIFIED("冲突（保留用户修改）"),
    REMOVED_FROM_REMOTE("教务已移除"),
    UNCHANGED("无变化")
}

data class ImportCourseCandidate(
    val externalId: String?,
    val courseName: String,
    val teacher: String?,
    val credit: BigDecimal?,
    val weekday: java.time.DayOfWeek,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: Set<Int>,
    val rawRoom: String
)

data class ImportDiffEntry(
    val changeType: ImportChangeType,
    val incoming: ImportCourseCandidate?,
    val existingCourse: Course?,
    val existingRule: CourseScheduleRule?,
    val summary: String,
    val keepUserEditByDefault: Boolean = changeType == ImportChangeType.CONFLICT_USER_MODIFIED
)

data class ImportDiffPreview(
    val termId: String,
    val source: CourseSource,
    val entries: List<ImportDiffEntry>,
    val addedCount: Int,
    val modifiedCount: Int,
    val conflictUserEditCount: Int,
    val unchangedCount: Int
)

data class ImportAudit(
    val id: String,
    val termId: String,
    val importSource: CourseSource,
    val importedAt: Instant,
    val rowCount: Int,
    val conflictCount: Int,
    val outcome: String
)

/**
 * 正方教务系统学生考试安排模型（兼容期初补缓考、期中随堂分散考试、期末集中考试）。
 */
data class ExamScheduleItem(
    val id: String,
    val termId: String,
    val courseCode: String,
    val courseName: String,
    val examBatchName: String,
    val examStageLabel: String,
    val examFormat: String,
    val rawTimeText: String,
    val examDate: LocalDate? = null,
    val startTime: LocalTime? = null,
    val endTime: LocalTime? = null,
    val rawRoom: String,
    val seatNumber: String? = null,
    val campusName: String = "莲花街校区",
    val isRetake: Boolean = false,
    val syncedAt: Instant = Instant.now()
)
