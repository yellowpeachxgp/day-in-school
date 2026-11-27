package cn.edu.haut.campus.core.solver

import java.time.Duration

/**
 * 纯 JVM 选课求解器：位集编译、前向检查、学分界传播、MRV/degree、
 * 非负软成本分支定界；无解证明后用 QuickXplain 解释用户级约束。
 */
class BranchAndBoundTimetableSolver : TimetableSolver {

    override fun solve(problem: SelectionProblem, maxSolutions: Int, budget: Duration): SolveResult {
        require(maxSolutions in 1..10) { "最多返回方案数必须在 1..10" }
        val started = System.nanoTime()
        val budgetClock = SolverBudget(budget)
        val stats = MutableSolverStats()
        val userConstraints = UserConstraints.of(problem)
        val allActive = userConstraints.mapTo(linkedSetOf()) { it.ref.id }

        val outcome = SearchEngine.run(
            problem = problem,
            activeConstraintIds = allActive,
            optimize = true,
            maxSolutions = maxSolutions,
            budget = budgetClock,
            stats = stats
        )

        if (outcome.solutions.isNotEmpty()) {
            return SolveResult.Solutions(
                ranked = outcome.solutions,
                completion = if (outcome.exhaustive) SolveCompletion.PROVEN_COST_TOP_K else SolveCompletion.BEST_FOUND_BUDGET_EXHAUSTED,
                stats = stats.snapshot(started, problem.candidates.size, problem.candidates.map { it.offeringId }.distinct().size)
            )
        }

        if (!outcome.exhaustive) {
            return SolveResult.Solutions(
                ranked = emptyList(),
                completion = SolveCompletion.BEST_FOUND_BUDGET_EXHAUSTED,
                stats = stats.snapshot(started, problem.candidates.size, problem.candidates.map { it.offeringId }.distinct().size)
            )
        }

        val explainer = QuickXplainExplainer(userConstraints, ConsistencyOracle { active, oracleBudget, oracleStats ->
            consistencyOracle(problem, active, oracleBudget, oracleStats)
        })
        val explanation = explainer.explain(budgetClock, stats)
        return SolveResult.Infeasible(
            conflictCore = explanation.core,
            relaxations = explanation.relaxations,
            explanationStatus = explanation.status,
            stats = stats.snapshot(started, problem.candidates.size, problem.candidates.map { it.offeringId }.distinct().size)
        )
    }

    private fun consistencyOracle(
        problem: SelectionProblem,
        activeConstraintIds: Set<String>,
        budget: SolverBudget,
        stats: MutableSolverStats
    ): Consistency {
        if (budget.expired()) return Consistency.UNKNOWN
        val outcome = SearchEngine.run(
            problem = problem,
            activeConstraintIds = activeConstraintIds,
            optimize = false,
            maxSolutions = 1,
            budget = budget,
            stats = stats,
            nodeLimit = ORACLE_NODE_LIMIT
        )
        return when {
            outcome.solutions.isNotEmpty() -> Consistency.CONSISTENT
            outcome.exhaustive -> Consistency.INCONSISTENT
            else -> Consistency.UNKNOWN
        }
    }

    companion object {
        private const val ORACLE_NODE_LIMIT: Long = 100_000L

        fun create(): BranchAndBoundTimetableSolver = BranchAndBoundTimetableSolver()
    }
}
