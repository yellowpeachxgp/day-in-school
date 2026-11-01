package cn.edu.haut.campus.core.plan

import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPA 模拟测试。
 *
 * UNVERIFIED：成绩与官方汇总值都是合成夹具，不代表任何真实学校的 GPA 口径；
 * 这里只验证「模拟值只能是 ESTIMATED、官方记录不被改写、缺少绩点时不猜公式」的隔离契约。
 */
class GpaSimulatorTest {

    private val math = PlanFixtures.attempt(
        "MATH101", name = "高等数学", credits = "3.0",
        rawGrade = "85", numericScore = "85", gradePoint = "3.0"
    )
    private val physics = PlanFixtures.attempt(
        "PHYS101", name = "大学物理", credits = "4.0",
        rawGrade = "75", numericScore = "75", gradePoint = "2.5"
    )
    private val snapshot = OfficialGpaSnapshot(
        cumulativeGpa = BigDecimal("3.12"),
        fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
        fieldDefinitionNote = "合成官方汇总字段（UNVERIFIED）"
    )

    private fun newCourse(gradePoint: String = "4.0", credits: String = "2.0") = SimulatedCourse(
        courseKey = "NEW101",
        courseName = "新生研讨课",
        credits = BigDecimal(credits),
        expectedGradePoint = BigDecimal(gradePoint)
    )

    @Test
    fun simulationNeverMutatesOfficialRecords() {
        val attempts = mutableListOf(math, physics)
        val before = attempts.toList()
        val simulation = GpaSimulator.simulate(attempts, listOf(newCourse()), officialSnapshot = snapshot)
        assertEquals("UNVERIFIED：模拟不得增删或改写官方成绩记录", before, attempts.toList())
        assertEquals("UNVERIFIED：官方汇总值必须原样透传", Provenance.OFFICIAL, simulation.official.provenance)
        PlanFixtures.assertAmount("3.12", simulation.official.value!!)
        assertTrue(
            "UNVERIFIED：必须明示模拟不写回官方记录，实际为 ${simulation.notes}",
            simulation.notes.any { it.contains("不写回") }
        )
    }

    @Test
    fun simulatedIsEstimatedAndBaselineIsDerived() {
        val simulation = GpaSimulator.simulate(listOf(math, physics), listOf(newCourse()))
        assertEquals("UNVERIFIED：模拟结果只能是估算", Provenance.ESTIMATED, simulation.simulated.provenance)
        assertEquals("UNVERIFIED：基线来自官方记录的本地加权", Provenance.DERIVED, simulation.baseline.provenance)
        assertTrue("UNVERIFIED：基线必须说明计算依据", simulation.baseline.basis.contains("本地加权"))
    }

    @Test
    fun deltaComparesSimulatedToBaseline() {
        val simulation = GpaSimulator.simulate(listOf(math, physics), listOf(newCourse()))
        PlanFixtures.assertAmount("2.71", simulation.baseline.value!!, "UNVERIFIED：19/7 应得 2.71")
        PlanFixtures.assertAmount("3.00", simulation.simulated.value!!, "UNVERIFIED：27/9 应得 3.00")
        PlanFixtures.assertAmount("0.29", simulation.delta!!)
    }

    @Test
    fun officialSnapshotIsNotUsedAsSimulationBaseline() {
        val simulation = GpaSimulator.simulate(listOf(math, physics), emptyList(), officialSnapshot = snapshot)
        PlanFixtures.assertAmount("3.12", simulation.official.value!!)
        PlanFixtures.assertAmount(
            "2.71",
            simulation.baseline.value!!,
            "UNVERIFIED：基线必须与模拟同口径，不能把官方汇总值当作基线"
        )
        assertTrue(
            "UNVERIFIED：官方值与基线必须分开保存",
            simulation.official.value!!.compareTo(simulation.baseline.value!!) != 0
        )
    }

    @Test
    fun rawScoreWithoutConfirmedPolicyIsExcluded() {
        val planned = SimulatedCourse(
            courseKey = "ART101",
            courseName = "艺术鉴赏",
            credits = BigDecimal("2.0"),
            expectedRawGrade = "优秀",
            gradeSystem = GradeSystem.FIVE_LEVEL
        )
        val simulation = GpaSimulator.simulate(listOf(math, physics), listOf(planned))
        assertEquals("UNVERIFIED：政策未确认的五级制成绩不得参与模拟", 1, simulation.excludedCourses.size)
        assertTrue("UNVERIFIED：被排除的课程不得进入模拟明细", simulation.appliedCourses.isEmpty())
        PlanFixtures.assertAmount("2.71", simulation.simulated.value!!, "UNVERIFIED：模拟值应与基线一致")
        PlanFixtures.assertAmount("0.00", simulation.delta!!)
        assertTrue(
            "UNVERIFIED：必须说明排除原因，实际为 ${simulation.notes}",
            simulation.notes.any { it.contains("被排除") }
        )
    }

    @Test
    fun userConfirmedFiveLevelPolicyIsUsableForSimulation() {
        val policy = GradeConversionPolicy(
            id = "fixture-user-five-level",
            label = "用户确认五级制（UNVERIFIED）",
            confirmation = PolicyConfirmation.USER_CONFIRMED,
            fiveLevelRules = listOf(GradeConversionRule("优秀", BigDecimal("95"), BigDecimal("4.0")))
        )
        val planned = SimulatedCourse(
            courseKey = "ART101",
            courseName = "艺术鉴赏",
            credits = BigDecimal("2.0"),
            expectedRawGrade = "优秀",
            gradeSystem = GradeSystem.FIVE_LEVEL,
            conversionPolicy = policy
        )
        val simulation = GpaSimulator.simulate(listOf(math, physics), listOf(planned))
        assertEquals("UNVERIFIED：已确认政策的五级制成绩应可参与模拟", 1, simulation.appliedCourses.size)
        PlanFixtures.assertAmount("3.00", simulation.simulated.value!!, "UNVERIFIED：(19 + 2×4.0)/9 = 3.00")
    }

    @Test
    fun worseSimulatedAttemptDoesNotOverwriteExistingBetterRecord() {
        val planned = SimulatedCourse(
            courseKey = "MATH101",
            courseName = "高等数学",
            credits = BigDecimal("3.0"),
            expectedGradePoint = BigDecimal("1.0")
        )
        val simulation = GpaSimulator.simulate(listOf(math, physics), listOf(planned))
        assertEquals("UNVERIFIED：模拟课程确实参与了计算", 1, simulation.appliedCourses.size)
        PlanFixtures.assertAmount("0.00", simulation.delta!!, "UNVERIFIED：更差的模拟成绩不得覆盖已有更高记录")
        assertTrue(
            "UNVERIFIED：必须说明结果与基线相同，实际为 ${simulation.notes}",
            simulation.notes.any { it.contains("与基线相同") }
        )
    }

    @Test
    fun noPlannedCoursesKeepsBaseline() {
        val simulation = GpaSimulator.simulate(listOf(math, physics), emptyList())
        assertTrue("UNVERIFIED：没有模拟课程时不应有参与明细", simulation.appliedCourses.isEmpty())
        PlanFixtures.assertAmount("0.00", simulation.delta!!)
        assertTrue(
            "UNVERIFIED：必须说明没有提供模拟课程，实际为 ${simulation.notes}",
            simulation.notes.any { it.contains("没有提供模拟课程") }
        )
        assertNull("UNVERIFIED：没有官方快照时官方值必须为空而不是 0", simulation.official.value)
    }

    @Test
    fun missingGradePointDataYieldsUnavailableSimulation() {
        val noPoint = PlanFixtures.attempt(
            "CHEM101", name = "应用化学", credits = "2.0",
            rawGrade = "88", numericScore = "88", gradePoint = null
        )
        val simulation = GpaSimulator.simulate(listOf(noPoint), listOf(newCourse()))
        assertNull("UNVERIFIED：缺少课程绩点时基线不可用", simulation.baseline.value)
        assertNull("UNVERIFIED：缺少课程绩点时模拟值不可用", simulation.simulated.value)
        assertNull("UNVERIFIED：不可用时不得给出差值", simulation.delta)
        assertTrue(
            "UNVERIFIED：必须说明不猜测换算公式，实际为 ${simulation.notes}",
            simulation.notes.any { it.contains("不猜测换算公式") }
        )
    }

    @Test
    fun simulatedCourseRequiresPositiveCredits() {
        var thrown: IllegalArgumentException? = null
        try {
            SimulatedCourse("X101", "测试课", BigDecimal.ZERO, expectedGradePoint = BigDecimal("4.0"))
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：0 学分模拟课程必须被拒绝", thrown != null)
    }

    @Test
    fun simulatedCourseRequiresExpectedGrade() {
        var thrown: IllegalArgumentException? = null
        try {
            SimulatedCourse("X101", "测试课", BigDecimal("2.0"))
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：既无绩点又无成绩的模拟课程必须被拒绝", thrown != null)
    }
}
