package cn.edu.haut.campus.core.plan

import cn.edu.haut.campus.core.domain.GpaCalculator
import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.GpaPolicy
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.RetakeInclusionStrategy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * 模拟用课程：只在本地参与计算，绝不写入官方成绩。
 * 绩点优先取 [expectedGradePoint]；否则按 [gradeSystem] 与 [conversionPolicy] 换算，
 * 换算失败（政策未确认/档位未定义）时该课程不会进入模拟结果。
 */
data class SimulatedCourse(
    val courseKey: String,
    val courseName: String,
    val credits: BigDecimal,
    val expectedGradePoint: BigDecimal? = null,
    val expectedRawGrade: String? = null,
    val gradeSystem: GradeSystem = GradeSystem.PERCENTAGE,
    val conversionPolicy: GradeConversionPolicy = GradeConversionPolicy.UNVERIFIED
) {
    init {
        require(courseKey.isNotBlank() && courseName.isNotBlank()) { "模拟课程标识与名称不能为空" }
        require(credits > BigDecimal.ZERO) { "模拟课程学分必须为正数" }
        require(expectedGradePoint == null || expectedGradePoint >= BigDecimal.ZERO) { "模拟课程绩点不能为负数" }
        require(expectedGradePoint != null || expectedRawGrade != null) { "模拟课程必须给出预期绩点或预期成绩" }
    }
}

/** 因缺少可用绩点而被排除的模拟课程。 */
data class ExcludedSimulation(val courseKey: String, val reason: String)

/**
 * GPA 模拟结果。四个数字分开保存，界面可以并排展示且永不混淆：
 * [official] 官方汇总值（可能为空，原样透传）、[baseline] 现有记录的本地加权值、
 * [simulated] 加入模拟课程后的估算值、[delta] 两者差值。
 */
data class GpaSimulation(
    val scenarioLabel: String,
    val official: ProvenValue<BigDecimal?>,
    val baseline: ProvenValue<BigDecimal?>,
    val simulated: ProvenValue<BigDecimal?>,
    val delta: BigDecimal?,
    val appliedCourses: List<SimulatedCourse>,
    val excludedCourses: List<ExcludedSimulation>,
    val notes: List<String>
)

/**
 * GPA 模拟（政策层包装）。
 *
 * 加权公式复用 :core:domain 的 [GpaCalculator]，这里只负责三件事：
 * 把模拟课程转换成临时成绩记录、把官方汇总值隔离在旁路、生成可解释的对比结论。
 * 模拟结果一律标记 ESTIMATED，且不会修改任何官方记录（输入列表只读，输出为独立对象）。
 */
object GpaSimulator {

    private const val SIMULATED_TERM_ID = "SIMULATED"
    private const val SIMULATED_ACADEMIC_YEAR = "9999-9999"

    /** 模拟专用政策：只使用教务返回的课程绩点，不套用任何分数换算公式。 */
    val DEFAULT_POLICY = GpaPolicy(
        id = "plan-simulation-v1",
        source = "本地加权（仅使用教务课程绩点）",
        appliesTo = "GPA 模拟场景",
        policyVersion = "1.0",
        retakeStrategy = RetakeInclusionStrategy.HIGHEST_ATTEMPT,
        includeZeroCreditCourses = false,
        includePassFailCoursesInGpa = false,
        allowRawScoreFallbackFormula = false
    )

    fun simulate(
        officialAttempts: List<GradeAttempt>,
        planned: List<SimulatedCourse>,
        policy: GpaPolicy = DEFAULT_POLICY,
        officialSnapshot: OfficialGpaSnapshot? = null,
        scenarioLabel: String = "未命名模拟"
    ): GpaSimulation {
        val applied = mutableListOf<SimulatedCourse>()
        val excluded = mutableListOf<ExcludedSimulation>()
        val simulatedAttempts = mutableListOf<GradeAttempt>()

        planned.forEach { course ->
            when (val outcome = resolve(course)) {
                is ConversionOutcome.NotConvertible ->
                    excluded += ExcludedSimulation(course.courseKey, outcome.reason)
                is ConversionOutcome.Converted -> {
                    val point = outcome.gradePoint
                    if (point == null) {
                        val score = outcome.score?.toPlainString() ?: "未知"
                        excluded += ExcludedSimulation(
                            course.courseKey,
                            "只换算出分数 $score，没有可用课程绩点（未配置已确认的换算政策）"
                        )
                    } else {
                        applied += course
                        simulatedAttempts += syntheticAttempt(course, point, outcome.score)
                    }
                }
            }
        }

        val baselineValue = reportOf(officialAttempts, policy).cumulativeGpa
        val simulatedValue = reportOf(officialAttempts + simulatedAttempts, policy).cumulativeGpa
        val delta = if (baselineValue != null && simulatedValue != null) {
            simulatedValue.subtract(baselineValue).setScale(2, RoundingMode.HALF_UP)
        } else null

        val officialValue = officialSnapshot?.cumulativeGpa
        val notes = mutableListOf<String>()
        notes += "模拟只读取官方记录，不写回、不覆盖任何官方成绩或官方 GPA"
        when {
            planned.isEmpty() -> notes += "没有提供模拟课程，模拟结果与基线相同"
            applied.isEmpty() -> notes += "所有模拟课程都被排除，模拟结果与基线相同"
            simulatedValue != null && baselineValue != null && simulatedValue.compareTo(baselineValue) == 0 ->
                notes += "加入模拟课程后加权结果与基线相同（该课程按策略未被采用或分数相同）"
        }
        if (excluded.isNotEmpty()) {
            notes += "有 ${excluded.size} 门模拟课程因缺少可用绩点被排除：" +
                excluded.joinToString("；") { "${it.courseKey}（${it.reason}）" }
        }
        if (simulatedValue == null) {
            notes += "现有或模拟课程缺少课程绩点，模拟 GPA 不可用（不猜测换算公式）"
        }

        return GpaSimulation(
            scenarioLabel = scenarioLabel,
            official = if (officialValue != null) {
                ProvenValue.official(officialValue, "教务官方汇总 GPA（原样透传，未参与模拟计算）")
            } else {
                ProvenValue.official<BigDecimal?>(
                    null,
                    "本次未获取官方汇总 GPA 快照；模拟值不是官方值"
                )
            },
            baseline = ProvenValue.derived(
                baselineValue,
                "基于现有官方记录按课程绩点本地加权（基线，刻意不使用官方汇总值，保证与模拟值同口径）"
            ),
            simulated = ProvenValue.estimated(
                simulatedValue,
                "在基线记录上加入 ${applied.size} 门模拟课程后的本地加权估算；不写回官方记录"
            ),
            delta = delta,
            appliedCourses = applied,
            excludedCourses = excluded,
            notes = notes
        )
    }

    private fun reportOf(attempts: List<GradeAttempt>, policy: GpaPolicy) = GpaCalculator.calculateReport(
        attempts = attempts,
        currentTermId = null,
        currentAcademicYear = null,
        policy = policy,
        officialSnapshot = null
    )

    private fun resolve(course: SimulatedCourse): ConversionOutcome {
        course.expectedGradePoint?.let { point ->
            return ConversionOutcome.Converted(
                score = null,
                gradePoint = point,
                matchedToken = "用户输入的预期绩点",
                policyId = null,
                provenance = Provenance.ESTIMATED
            )
        }
        val raw = course.expectedRawGrade ?: return ConversionOutcome.NotConvertible("模拟课程缺少预期成绩")
        return GradeScaleConverter.convert(raw, course.gradeSystem, course.conversionPolicy)
    }

    /**
     * 把模拟课程变成临时成绩记录：来源标记为非教务、学期标记为 SIMULATED，
     * 便于下游审计区分「原始记录」与「模拟记录」。
     */
    private fun syntheticAttempt(
        course: SimulatedCourse,
        gradePoint: BigDecimal,
        numericScore: BigDecimal?
    ): GradeAttempt = GradeAttempt(
        id = "simulation-${course.courseKey}",
        termId = SIMULATED_TERM_ID,
        academicYear = SIMULATED_ACADEMIC_YEAR,
        termNumber = 9,
        courseKey = course.courseKey,
        name = course.courseName,
        credits = course.credits,
        rawGrade = course.expectedRawGrade ?: gradePoint.toPlainString(),
        numericScore = numericScore,
        gradePoint = gradePoint,
        attemptType = AttemptType.NORMAL,
        passed = true,
        isCourseReplacement = false,
        source = CourseSource.MANUAL,
        syncedAt = Instant.EPOCH
    )
}
