package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.CourseGradeAuditItem
import cn.edu.haut.campus.core.model.GpaPolicy
import cn.edu.haut.campus.core.model.GpaReport
import cn.edu.haut.campus.core.model.GpaSourceType
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.GradeDistribution
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.RetakeInclusionStrategy
import cn.edu.haut.campus.core.model.TermGpaTrendPoint
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object GpaCalculator {

    private val CALC_MC = MathContext.DECIMAL128
    private const val DISPLAY_SCALE = 2

    val DEFAULT_HAUT_UNDERGRAD_POLICY = GpaPolicy(
        id = "haut_undergrad_strict_v1",
        source = "教务原始课程绩点优先（不默认套用国际学生 5.0 换算办法）",
        appliesTo = "河南工业大学普通本科生（本地加权模式）",
        policyVersion = "2026.1",
        retakeStrategy = RetakeInclusionStrategy.HIGHEST_ATTEMPT,
        includeZeroCreditCourses = false,
        includePassFailCoursesInGpa = false,
        allowRawScoreFallbackFormula = false
    )

    /**
     * 严格遵循 PRD 4.2 优先级计算 GPA：
     * 1. 若教务返回总体/学期官方 GPA 快照 -> 标记为 OFFICIAL_JW 原样展示
     * 2. 若课程有教务返回的课程级绩点 (gradePoint) 与有效学分 -> 按 Σ(学分×课程绩点)/Σ学分 精确 BigDecimal 加权计算，标记为 LOCAL_WEIGHTED_FROM_JW_POINTS
     * 3. 若仅有原始百分制/五级制成绩且无已核实的学校换算规则 -> 返回 null 并标记为 INSUFFICIENT_RULE，绝不臆造 score/25 或套用国际学生 5.0 规则
     */
    fun calculateReport(
        attempts: List<GradeAttempt>,
        currentTermId: String?,
        currentAcademicYear: String?,
        policy: GpaPolicy = DEFAULT_HAUT_UNDERGRAD_POLICY,
        officialSnapshot: OfficialGpaSnapshot? = null
    ): GpaReport {
        val groupedByCourse = attempts.groupBy { it.courseKey }
        val auditItems = mutableListOf<CourseGradeAuditItem>()
        val selectedForGpa = mutableListOf<Pair<GradeAttempt, BigDecimal>>()
        val diagnosticNotes = mutableListOf<String>()

        var totalEarnedCredits = BigDecimal.ZERO
        var missingGradePointCourses = 0

        for ((courseKey, courseAttempts) in groupedByCourse) {
            val sortedAttempts = courseAttempts.sortedWith(
                compareBy<GradeAttempt> { it.academicYear }
                    .thenBy { it.termNumber }
                    .thenBy { it.syncedAt }
            )
            val chosen = selectRepresentativeAttempt(sortedAttempts, policy.retakeStrategy)

            if (chosen != null && chosen.passed && chosen.credits > BigDecimal.ZERO) {
                totalEarnedCredits = totalEarnedCredits.add(chosen.credits)
            }

            if (chosen == null) {
                auditItems.add(
                    CourseGradeAuditItem(
                        courseKey = courseKey,
                        courseName = courseAttempts.first().name,
                        selectedAttempt = null,
                        allAttempts = sortedAttempts,
                        includedInGpaDenominator = false,
                        exclusionOrChangeReason = "无有效成绩记录"
                    )
                )
                continue
            }

            // 判断是否纳入 GPA 分母
            val zeroCreditExcluded = chosen.credits <= BigDecimal.ZERO && !policy.includeZeroCreditCourses
            val exemptOrDeferred = chosen.attemptType == AttemptType.EXEMPT || chosen.attemptType == AttemptType.DEFERRED
            val passFailExcluded = chosen.attemptType == AttemptType.PASS_FAIL && !policy.includePassFailCoursesInGpa
            val invalidRaw = chosen.rawGrade.isBlank() || chosen.rawGrade.contains("未评教") || chosen.rawGrade.contains("无效")

            if (zeroCreditExcluded || exemptOrDeferred || passFailExcluded || invalidRaw) {
                val reason = when {
                    invalidRaw -> "成绩未录入或无效（${chosen.rawGrade}），不计入 GPA 分母"
                    zeroCreditExcluded -> "0 学分课程，不计入 GPA 分母"
                    exemptOrDeferred -> "${chosen.attemptType.label}记录，不计入 GPA 分母"
                    else -> "通过制课程，按策略不计入 GPA 分母"
                }
                auditItems.add(
                    CourseGradeAuditItem(
                        courseKey = courseKey,
                        courseName = chosen.name,
                        selectedAttempt = chosen,
                        allAttempts = sortedAttempts,
                        includedInGpaDenominator = false,
                        exclusionOrChangeReason = reason
                    )
                )
                continue
            }

            val resolvedPoint = resolveCourseGradePoint(chosen, policy)
            if (resolvedPoint == null) {
                missingGradePointCourses++
                auditItems.add(
                    CourseGradeAuditItem(
                        courseKey = courseKey,
                        courseName = chosen.name,
                        selectedAttempt = chosen,
                        allAttempts = sortedAttempts,
                        includedInGpaDenominator = false,
                        exclusionOrChangeReason = "缺少教务课程级绩点且未配置适用换算规则（不猜测公式）"
                    )
                )
            } else {
                selectedForGpa.add(chosen to resolvedPoint)
                val multiAttemptNote = if (sortedAttempts.size > 1) {
                    "共 ${sortedAttempts.size} 次修读（含${sortedAttempts.map { it.attemptType.label }.distinct().joinToString("/")}），按策略采用「${chosen.attemptType.label}: ${chosen.rawGrade} (绩点 ${resolvedPoint.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)})」"
                } else {
                    "正常纳入计算（学分 ${chosen.credits} × 绩点 ${resolvedPoint.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)}）"
                }
                auditItems.add(
                    CourseGradeAuditItem(
                        courseKey = courseKey,
                        courseName = chosen.name,
                        selectedAttempt = chosen,
                        allAttempts = sortedAttempts,
                        includedInGpaDenominator = true,
                        exclusionOrChangeReason = multiAttemptNote
                    )
                )
            }
        }

        val gpaIncludedCredits = selectedForGpa.fold(BigDecimal.ZERO) { acc, (attempt, _) ->
            acc.add(attempt.credits)
        }

        val hasUnresolvedScoredCourses = missingGradePointCourses > 0
        val weightedAllGpa = if (!hasUnresolvedScoredCourses && selectedForGpa.isNotEmpty()) {
            computeWeightedGpa(selectedForGpa)
        } else {
            null
        }

        val primarySource = when {
            officialSnapshot?.cumulativeGpa != null -> GpaSourceType.OFFICIAL_JW
            hasUnresolvedScoredCourses || selectedForGpa.isEmpty() -> GpaSourceType.INSUFFICIENT_RULE
            policy.allowRawScoreFallbackFormula && selectedForGpa.any { it.first.gradePoint == null } -> GpaSourceType.POLICY_FORMULA_ESTIMATE
            else -> GpaSourceType.LOCAL_WEIGHTED_FROM_JW_POINTS
        }

        val cumulativeGpa = officialSnapshot?.cumulativeGpa?.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)
            ?: weightedAllGpa

        val currentTermGpa = if (currentTermId != null) {
            officialSnapshot?.termGpas?.get(currentTermId)?.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)
                ?: computeWeightedGpa(selectedForGpa.filter { it.first.termId == currentTermId })
        } else null

        val currentAcademicYearGpa = if (currentAcademicYear != null) {
            officialSnapshot?.academicYearGpas?.get(currentAcademicYear)?.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)
                ?: computeWeightedGpa(selectedForGpa.filter { it.first.academicYear == currentAcademicYear })
        } else null

        // 生成学期趋势点
        val termGroups = attempts.groupBy { it.termId }
        val termTrends = termGroups.entries.sortedWith(
            compareBy<Map.Entry<String, List<GradeAttempt>>> { it.value.first().academicYear }
                .thenBy { it.value.first().termNumber }
        ).map { (termId, termAttempts) ->
            val first = termAttempts.first()
            val label = "${first.academicYear} (${first.termNumber})"
            val termSelected = selectedForGpa.filter { it.first.termId == termId }
            val officialTermGpa = officialSnapshot?.termGpas?.get(termId)?.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)
            val termGpa = officialTermGpa ?: computeWeightedGpa(termSelected)
            val termSource = when {
                officialTermGpa != null -> GpaSourceType.OFFICIAL_JW
                termGpa != null -> GpaSourceType.LOCAL_WEIGHTED_FROM_JW_POINTS
                else -> GpaSourceType.INSUFFICIENT_RULE
            }
            val avgScore = computeWeightedAverageScore(termAttempts)
            val termCredits = termAttempts
                .filter { it.passed && it.credits > BigDecimal.ZERO }
                .distinctBy { it.courseKey }
                .fold(BigDecimal.ZERO) { acc, item -> acc.add(item.credits) }

            TermGpaTrendPoint(
                termId = termId,
                termLabel = label,
                gpa = termGpa,
                weightedAverageScore = avgScore,
                earnedCredits = termCredits,
                sourceType = termSource
            )
        }

        // 统计分布與低绩点诊断
        val representativeAttempts = auditItems.mapNotNull { it.selectedAttempt }
        val distribution = computeDistribution(representativeAttempts)

        val lowDragCourses = selectedForGpa
            .filter { (_, point) -> point < BigDecimal("2.50") }
            .sortedBy { (_, point) -> point }
        if (lowDragCourses.isNotEmpty()) {
            val summary = lowDragCourses.take(3).joinToString("、") { (att, pt) ->
                "《${att.name}》(${att.credits}学分/绩点${pt.setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)})"
            }
            diagnosticNotes.add("低绩点拉低项提示：$summary 对加权 GPA 影响较大。")
        }

        val retakeCourses = auditItems.filter { it.allAttempts.size > 1 }
        if (retakeCourses.isNotEmpty()) {
            diagnosticNotes.add("检测到 ${retakeCourses.size} 门课程存在补考/重修多次记录，已按策略去重，未重复累加分母。")
        }

        if (hasUnresolvedScoredCourses) {
            diagnosticNotes.add("有 $missingGradePointCourses 门课程仅有原始分数而缺少教务课程绩点；依据规范未套用未经核实的公式（国际学生 5.0 规定不适用于普通本科），故总 GPA 标为「不足以可靠计算」。")
        }

        val sourceExplanation = when (primarySource) {
            GpaSourceType.OFFICIAL_JW -> "直接采用河南工业大学教务系统返回的汇总 GPA（${officialSnapshot?.fieldDefinitionNote ?: "官方字段"}）。"
            GpaSourceType.LOCAL_WEIGHTED_FROM_JW_POINTS -> "基于教务返回的每门课程绩点与学分，按 Σ(课程学分×教务课程绩点)/Σ纳入课程学分 本地精确计算。"
            GpaSourceType.POLICY_FORMULA_ESTIMATE -> "部分课程按已配置的学校适用规则换算绩点。"
            GpaSourceType.INSUFFICIENT_RULE -> "部分课程缺少教务返回的课程级绩点，且未配置适用的普通本科换算规则，拒绝猜测公式。"
        }

        return GpaReport(
            cumulativeGpa = cumulativeGpa,
            currentTermGpa = currentTermGpa,
            currentAcademicYearGpa = currentAcademicYearGpa,
            totalEarnedCredits = totalEarnedCredits.setScale(1, RoundingMode.HALF_UP),
            gpaIncludedCredits = gpaIncludedCredits.setScale(1, RoundingMode.HALF_UP),
            primarySourceType = primarySource,
            sourceExplanation = sourceExplanation,
            termTrends = termTrends,
            distribution = distribution,
            auditItems = auditItems,
            diagnosticNotes = diagnosticNotes
        )
    }

    private fun selectRepresentativeAttempt(
        attempts: List<GradeAttempt>,
        strategy: RetakeInclusionStrategy
    ): GradeAttempt? {
        if (attempts.isEmpty()) return null
        if (attempts.size == 1) return attempts.first()

        return when (strategy) {
            RetakeInclusionStrategy.HIGHEST_ATTEMPT -> {
                attempts.maxWithOrNull(
                    compareBy<GradeAttempt> { it.passed }
                        .thenBy { it.gradePoint ?: BigDecimal("-1") }
                        .thenBy { it.numericScore ?: BigDecimal("-1") }
                        .thenBy { it.academicYear }
                        .thenBy { it.termNumber }
                        .thenBy { it.syncedAt }
                )
            }
            RetakeInclusionStrategy.LATEST_ATTEMPT -> {
                attempts.maxWithOrNull(
                    compareBy<GradeAttempt> { it.academicYear }
                        .thenBy { it.termNumber }
                        .thenBy { it.syncedAt }
                )
            }
            RetakeInclusionStrategy.FIRST_ATTEMPT_ONLY -> {
                attempts.firstOrNull { it.attemptType == AttemptType.NORMAL } ?: attempts.first()
            }
        }
    }

    private fun resolveCourseGradePoint(attempt: GradeAttempt, policy: GpaPolicy): BigDecimal? {
        if (attempt.gradePoint != null) {
            return attempt.gradePoint
        }
        if (!policy.allowRawScoreFallbackFormula || policy.scoreToPointRules.isEmpty()) {
            return null
        }
        val score = attempt.numericScore ?: attempt.rawGrade.toBigDecimalOrNull() ?: return null
        return policy.scoreToPointRules.firstOrNull { rule ->
            score >= rule.minScoreInclusive && score <= rule.maxScoreInclusive
        }?.gradePoint
    }

    private fun computeWeightedGpa(items: List<Pair<GradeAttempt, BigDecimal>>): BigDecimal? {
        if (items.isEmpty()) return null
        var weightedSum = BigDecimal.ZERO
        var creditSum = BigDecimal.ZERO
        for ((attempt, point) in items) {
            if (attempt.credits <= BigDecimal.ZERO) continue
            weightedSum = weightedSum.add(attempt.credits.multiply(point, CALC_MC), CALC_MC)
            creditSum = creditSum.add(attempt.credits, CALC_MC)
        }
        if (creditSum <= BigDecimal.ZERO) return null
        return weightedSum.divide(creditSum, CALC_MC).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)
    }

    private fun computeWeightedAverageScore(attempts: List<GradeAttempt>): BigDecimal? {
        val valid = attempts.mapNotNull { att ->
            val score = att.numericScore ?: att.rawGrade.toBigDecimalOrNull()
            if (score != null && att.credits > BigDecimal.ZERO) att.credits to score else null
        }
        if (valid.isEmpty()) return null
        var sum = BigDecimal.ZERO
        var credits = BigDecimal.ZERO
        for ((c, s) in valid) {
            sum = sum.add(c.multiply(s, CALC_MC), CALC_MC)
            credits = credits.add(c, CALC_MC)
        }
        if (credits <= BigDecimal.ZERO) return null
        return sum.divide(credits, CALC_MC).setScale(DISPLAY_SCALE, RoundingMode.HALF_UP)
    }

    private fun computeDistribution(attempts: List<GradeAttempt>): GradeDistribution {
        var excellent = 0
        var good = 0
        var medium = 0
        var pass = 0
        var failOrOther = 0

        for (att in attempts) {
            val score = att.numericScore ?: att.rawGrade.toBigDecimalOrNull()
            when {
                !att.passed -> failOrOther++
                score != null -> when {
                    score >= BigDecimal("90") -> excellent++
                    score >= BigDecimal("80") -> good++
                    score >= BigDecimal("70") -> medium++
                    score >= BigDecimal("60") -> pass++
                    else -> failOrOther++
                }
                att.rawGrade.contains("优") -> excellent++
                att.rawGrade.contains("良") -> good++
                att.rawGrade.contains("中") -> medium++
                att.rawGrade.contains("及格") || att.rawGrade.contains("通过") || att.rawGrade.contains("免") -> pass++
                else -> failOrOther++
            }
        }

        return GradeDistribution(
            excellentCount = excellent,
            goodCount = good,
            mediumCount = medium,
            passCount = pass,
            failOrOtherCount = failOrOther
        )
    }
}
