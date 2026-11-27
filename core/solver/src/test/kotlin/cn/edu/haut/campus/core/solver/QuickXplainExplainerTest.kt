package cn.edu.haut.campus.core.solver

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickXplainExplainerTest {
    private val solver = BranchAndBoundTimetableSolver()

    @Test
    fun conflictingRequiredOfferingsProduceOnlyTheTwoRequiredConstraints() {
        val problem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("a1", "A", slots = listOf(SolverTestFixtures.slot(1, 1, 2))),
                SolverTestFixtures.option("b1", "B", slots = listOf(SolverTestFixtures.slot(1, 1, 2))),
                SolverTestFixtures.option("u1", "U", slots = listOf(SolverTestFixtures.slot(2, 1, 2)))
            ),
            hard = listOf(
                HardConstraint.RequireOffering("A"),
                HardConstraint.RequireOffering("B"),
                HardConstraint.TimeExclusion(
                    slots = listOf(SolverTestFixtures.slot(7, 11, 12)),
                    ref = ConstraintRef("no-weekend-late", ConstraintKind.TIME_EXCLUSION, "不选周末晚课")
                )
            )
        )
        val result = solver.solve(problem, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Infeasible)
        result as SolveResult.Infeasible
        assertEquals(ExplanationStatus.MINIMAL_VERIFIED, result.explanationStatus)
        assertEquals(
            listOf(ConstraintKind.REQUIRED_OFFERING, ConstraintKind.REQUIRED_OFFERING),
            result.conflictCore.map { it.kind }
        )
        assertEquals(2, result.conflictCore.size)
        assertEquals(2, result.relaxations.size)
        assertTrue(result.relaxations.all { it.action == RelaxationAction.REMOVE_REQUIRED_OFFERING })
    }

    @Test
    fun impossibleCreditLowerBoundProducesCreditRelaxationWithoutUnrelatedFilters() {
        val problem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("o1", "O", credits = "2", slots = listOf(SolverTestFixtures.slot(1, 1, 2)))
            ),
            hard = listOf(
                HardConstraint.TimeExclusion(
                    slots = listOf(SolverTestFixtures.slot(7, 11, 12)),
                    ref = ConstraintRef("no-weekend-late", ConstraintKind.TIME_EXCLUSION, "不选周末晚课")
                )
            ),
            creditLower = "4",
            creditUpper = "6"
        )
        val result = solver.solve(problem, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Infeasible)
        result as SolveResult.Infeasible
        assertEquals(ExplanationStatus.MINIMAL_VERIFIED, result.explanationStatus)
        assertEquals(listOf(ConstraintKind.CREDIT_LOWER_BOUND), result.conflictCore.map { it.kind })
        assertEquals(listOf(RelaxationAction.LOWER_CREDIT_MINIMUM), result.relaxations.map { it.action })
    }
}
