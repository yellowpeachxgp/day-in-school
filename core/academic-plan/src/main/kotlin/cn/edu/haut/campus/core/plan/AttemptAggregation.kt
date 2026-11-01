package cn.edu.haut.campus.core.plan

import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.RetakeInclusionStrategy
import java.math.BigDecimal

/**
 * 一门课程的修读结论：选了哪一次记录、可入账多少学分，以及为什么这样选。
 * [attempts] 是按学期与同步时间稳定排序后的全部记录，界面的「成绩审计」直接用它。
 */
data class AttemptDecision(
    val courseKey: String,
    val courseName: String,
    val selected: GradeAttempt?,
    val attempts: List<GradeAttempt>,
    val creditedCredits: BigDecimal,
    val passed: Boolean,
    val reason: String
)

/** 全部课程的修读结论；[totalCreditedCredits] 保证同门课程只入账一次。 */
data class AttemptAggregation(
    val strategy: RetakeInclusionStrategy,
    val decisions: List<AttemptDecision>,
    val totalCreditedCredits: BigDecimal,
    val multiAttemptCourseCount: Int,
    val notes: List<String>
) {
    fun decisionFor(courseKey: String): AttemptDecision? = decisions.firstOrNull { it.courseKey == courseKey }
}

/**
 * 重修/补考策略层。
 *
 * 职责只有两件：为每门课程选出「代表修读记录」并给出理由；把学分按课程只入账一次。
 * GPA 数值本身仍由 :core:domain 的 GpaCalculator 计算，本对象不重复实现加权公式。
 */
object AttemptAggregator {

    fun aggregate(
        attempts: List<GradeAttempt>,
        strategy: RetakeInclusionStrategy
    ): AttemptAggregation {
        val decisions = attempts.groupBy { it.courseKey }
            .toSortedMap()
            .map { (courseKey, records) -> decide(courseKey, stableOrder(records), strategy) }

        val total = decisions.fold(BigDecimal.ZERO) { acc, decision -> acc.add(decision.creditedCredits) }
        val multi = decisions.count { it.attempts.size > 1 }
        val notes = mutableListOf<String>()
        if (multi > 0) {
            notes += "共有 $multi 门课程存在补考/重修多次记录，已按 ${strategy.name} 策略去重，学分未重复累加"
        }
        decisions.forEach { decision ->
            val distinctCredits = decision.attempts.map { it.credits.stripTrailingZeros().toPlainString() }.distinct()
            if (distinctCredits.size > 1) {
                notes += "课程「${decision.courseName}」多次修读的学分不一致（${distinctCredits.joinToString("/")}），" +
                    "按所选记录的 ${decision.creditedCredits.toPlainString()} 学分入账"
            }
            if (decision.selected == null) {
                notes += "课程「${decision.courseName}」没有可用的修读记录"
            }
        }
        return AttemptAggregation(
            strategy = strategy,
            decisions = decisions,
            totalCreditedCredits = total,
            multiAttemptCourseCount = multi,
            notes = notes
        )
    }

    private fun decide(
        courseKey: String,
        ordered: List<GradeAttempt>,
        strategy: RetakeInclusionStrategy
    ): AttemptDecision {
        val selected = select(ordered, strategy)
        val credited = selected
            ?.takeIf { it.passed && it.credits > BigDecimal.ZERO }
            ?.credits ?: BigDecimal.ZERO
        return AttemptDecision(
            courseKey = courseKey,
            courseName = ordered.first().name,
            selected = selected,
            attempts = ordered,
            creditedCredits = credited,
            passed = selected?.passed == true,
            reason = describe(ordered, selected, strategy, credited)
        )
    }

    private fun select(ordered: List<GradeAttempt>, strategy: RetakeInclusionStrategy): GradeAttempt? =
        when (strategy) {
            RetakeInclusionStrategy.HIGHEST_ATTEMPT -> ordered.maxWithOrNull(
                compareBy<GradeAttempt> { it.passed }
                    .thenBy { it.gradePoint ?: BigDecimal("-1") }
                    .thenBy { it.numericScore ?: BigDecimal("-1") }
            )
            RetakeInclusionStrategy.LATEST_ATTEMPT -> ordered.lastOrNull()
            RetakeInclusionStrategy.FIRST_ATTEMPT_ONLY ->
                ordered.firstOrNull { it.attemptType == AttemptType.NORMAL } ?: ordered.firstOrNull()
        }

    /** 稳定排序：学年、学期、同步时间、ID，保证同一份数据每次得到同一结论。 */
    private fun stableOrder(records: List<GradeAttempt>): List<GradeAttempt> = records.sortedWith(
        compareBy<GradeAttempt> { it.academicYear }
            .thenBy { it.termNumber }
            .thenBy { it.syncedAt }
            .thenBy { it.id }
    )

    private fun describe(
        ordered: List<GradeAttempt>,
        selected: GradeAttempt?,
        strategy: RetakeInclusionStrategy,
        credited: BigDecimal
    ): String {
        if (selected == null) return "没有可用的修读记录"
        val creditText = when {
            credited > BigDecimal.ZERO -> "学分 ${credited.toPlainString()} 只入账一次"
            selected.credits <= BigDecimal.ZERO -> "0 学分课程，不计入学分"
            else -> "未通过，学分不计入"
        }
        return if (ordered.size == 1) {
            "唯一修读记录（${selected.attemptType.label}，成绩 ${selected.rawGrade.take(16)}），$creditText"
        } else {
            val types = ordered.map { it.attemptType.label }.distinct().joinToString("/")
            "共 ${ordered.size} 次修读（$types），按 ${strategy.name} 策略采用" +
                "「${selected.attemptType.label} ${selected.rawGrade.take(16)}」；$creditText"
        }
    }
}
