package cn.edu.haut.campus.core.plan

import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.RetakeInclusionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重修/补考策略测试。
 *
 * UNVERIFIED：成绩与学分是合成夹具，不代表任何真实学校的重修认定口径；
 * 这里验证的是「每门课程只选一次记录、学分只入账一次、选择理由可解释」的行为契约。
 */
class AttemptAggregatorTest {

    private val failedOriginal = PlanFixtures.attempt(
        courseKey = "MATH101",
        name = "高等数学",
        academicYear = "2024-2025",
        termNumber = 1,
        rawGrade = "55",
        numericScore = "55",
        gradePoint = "0.0",
        passed = false
    )

    private val passedRetake = PlanFixtures.attempt(
        courseKey = "MATH101",
        name = "高等数学",
        academicYear = "2025-2026",
        termNumber = 1,
        rawGrade = "78",
        numericScore = "78",
        gradePoint = "2.8",
        attemptType = AttemptType.RETAKE
    )

    @Test
    fun highestStrategyCountsCreditsOnlyOnce() {
        val result = AttemptAggregator.aggregate(
            listOf(failedOriginal, passedRetake),
            RetakeInclusionStrategy.HIGHEST_ATTEMPT
        )
        PlanFixtures.assertAmount("3.0", result.totalCreditedCredits, "UNVERIFIED：重修学分不得重复累加")
        val decision = result.decisionFor("MATH101")!!
        assertEquals("UNVERIFIED：最高策略应选择通过的重修记录", AttemptType.RETAKE, decision.selected!!.attemptType)
        assertTrue("UNVERIFIED：通过记录必须标记为通过", decision.passed)
        assertEquals("UNVERIFIED：全部修读记录必须保留", 2, decision.attempts.size)
    }

    @Test
    fun latestStrategyPicksNewestAttemptEvenIfWorse() {
        val older = PlanFixtures.attempt(
            courseKey = "PHYS101", academicYear = "2024-2025", termNumber = 2,
            rawGrade = "92", numericScore = "92", gradePoint = "3.9"
        )
        val newer = PlanFixtures.attempt(
            courseKey = "PHYS101", academicYear = "2025-2026", termNumber = 1,
            rawGrade = "70", numericScore = "70", gradePoint = "2.0"
        )
        val decision = AttemptAggregator.aggregate(listOf(older, newer), RetakeInclusionStrategy.LATEST_ATTEMPT)
            .decisionFor("PHYS101")!!
        assertEquals("UNVERIFIED：最新策略必须选择最新学期记录", "2025-2026", decision.selected!!.academicYear)
    }

    @Test
    fun firstAttemptOnlySkipsRetake() {
        val first = PlanFixtures.attempt(
            courseKey = "CHEM101", academicYear = "2024-2025", termNumber = 1,
            rawGrade = "88", numericScore = "88", gradePoint = "3.7"
        )
        val retake = PlanFixtures.attempt(
            courseKey = "CHEM101", academicYear = "2025-2026", termNumber = 1,
            rawGrade = "95", numericScore = "95", gradePoint = "4.5", attemptType = AttemptType.RETAKE
        )
        val decision = AttemptAggregator.aggregate(listOf(first, retake), RetakeInclusionStrategy.FIRST_ATTEMPT_ONLY)
            .decisionFor("CHEM101")!!
        assertEquals("UNVERIFIED：首次策略必须忽略重修记录", AttemptType.NORMAL, decision.selected!!.attemptType)
        PlanFixtures.assertAmount("88", decision.selected!!.numericScore!!, "UNVERIFIED：首次策略应取正考成绩")
    }

    @Test
    fun highestStrategyPrefersPassedAttemptOverHigherRawScoreFailure() {
        val failingHigh = PlanFixtures.attempt(
            courseKey = "ENG101", rawGrade = "59", numericScore = "59", gradePoint = "0.0", passed = false
        )
        val passingLow = PlanFixtures.attempt(
            courseKey = "ENG101", academicYear = "2025-2026", rawGrade = "60", numericScore = "60",
            gradePoint = "1.0", attemptType = AttemptType.MAKEUP
        )
        val decision = AttemptAggregator.aggregate(listOf(failingHigh, passingLow), RetakeInclusionStrategy.HIGHEST_ATTEMPT)
            .decisionFor("ENG101")!!
        assertEquals("UNVERIFIED：最高策略必须先比较是否通过，再比较分数", "60", decision.selected!!.rawGrade)
    }

    @Test
    fun multiAttemptReasonExplainsStrategyAndTypes() {
        val decision = AttemptAggregator.aggregate(
            listOf(failedOriginal, passedRetake),
            RetakeInclusionStrategy.HIGHEST_ATTEMPT
        ).decisionFor("MATH101")!!
        assertTrue("UNVERIFIED：选择理由必须包含策略名", decision.reason.contains("HIGHEST_ATTEMPT"))
        assertTrue("UNVERIFIED：选择理由必须列出修读类型", decision.reason.contains("重修"))
        assertTrue("UNVERIFIED：选择理由必须说明学分只入账一次", decision.reason.contains("只入账一次"))
    }

    @Test
    fun singleAttemptReasonSaysOnlyAttempt() {
        val decision = AttemptAggregator.aggregate(
            listOf(PlanFixtures.attempt("BIO101", rawGrade = "84", numericScore = "84")),
            RetakeInclusionStrategy.HIGHEST_ATTEMPT
        ).decisionFor("BIO101")!!
        assertTrue("UNVERIFIED：单次修读应说明是唯一记录，实际为 ${decision.reason}", decision.reason.contains("唯一"))
    }

    @Test
    fun differentCreditsAcrossAttemptsUseSelectedAttemptCredits() {
        val original = PlanFixtures.attempt(
            courseKey = "CS101", credits = "2.0", rawGrade = "70", numericScore = "70", gradePoint = "2.0"
        )
        val retake = PlanFixtures.attempt(
            courseKey = "CS101", credits = "3.0", academicYear = "2025-2026",
            rawGrade = "90", numericScore = "90", gradePoint = "4.0", attemptType = AttemptType.RETAKE
        )
        val result = AttemptAggregator.aggregate(listOf(original, retake), RetakeInclusionStrategy.HIGHEST_ATTEMPT)
        PlanFixtures.assertAmount("3.0", result.totalCreditedCredits, "UNVERIFIED：应按所选记录的学分入账")
        assertTrue(
            "UNVERIFIED：学分不一致必须写进说明，实际为 ${result.notes}",
            result.notes.any { it.contains("学分不一致") }
        )
    }

    @Test
    fun emptyInputYieldsEmptyAggregation() {
        val result = AttemptAggregator.aggregate(emptyList(), RetakeInclusionStrategy.HIGHEST_ATTEMPT)
        assertTrue("UNVERIFIED：空输入不应产生课程结论", result.decisions.isEmpty())
        PlanFixtures.assertAmount("0", result.totalCreditedCredits)
        assertEquals("UNVERIFIED：空输入不应统计多次修读课程", 0, result.multiAttemptCourseCount)
    }

    @Test
    fun failedOnlyAttemptCreditsNothing() {
        val decision = AttemptAggregator.aggregate(listOf(failedOriginal), RetakeInclusionStrategy.HIGHEST_ATTEMPT)
            .decisionFor("MATH101")!!
        PlanFixtures.assertAmount("0", decision.creditedCredits, "UNVERIFIED：未通过的课程不得入账学分")
        assertTrue("UNVERIFIED：未通过必须反映在结论里", !decision.passed)
        assertTrue("UNVERIFIED：未通过原因必须可解释，实际为 ${decision.reason}", decision.reason.contains("未通过"))
    }

    @Test
    fun zeroCreditCourseCreditsNothing() {
        val decision = AttemptAggregator.aggregate(
            listOf(PlanFixtures.attempt("PE101", credits = "0", rawGrade = "通过", numericScore = null, gradePoint = null)),
            RetakeInclusionStrategy.HIGHEST_ATTEMPT
        ).decisionFor("PE101")!!
        PlanFixtures.assertAmount("0", decision.creditedCredits)
        assertTrue("UNVERIFIED：0 学分课程必须在理由中说明，实际为 ${decision.reason}", decision.reason.contains("0 学分"))
    }

    @Test
    fun notesCountCoursesWithMultipleAttempts() {
        val single = PlanFixtures.attempt("BIO101")
        val result = AttemptAggregator.aggregate(
            listOf(single, failedOriginal, passedRetake),
            RetakeInclusionStrategy.HIGHEST_ATTEMPT
        )
        assertEquals("UNVERIFIED：应统计存在多次修读的课程数", 1, result.multiAttemptCourseCount)
        assertTrue(
            "UNVERIFIED：说明里必须提示去重结论，实际为 ${result.notes}",
            result.notes.any { it.contains("1 门课程") && it.contains("未重复累加") }
        )
    }

    @Test
    fun decisionLookupReturnsNullForUnknownCourse() {
        val result = AttemptAggregator.aggregate(listOf(singleCourse()), RetakeInclusionStrategy.LATEST_ATTEMPT)
        assertNull("UNVERIFIED：未知课程不应返回结论", result.decisionFor("NOPE999"))
    }

    private fun singleCourse() = PlanFixtures.attempt("ART101")
}
