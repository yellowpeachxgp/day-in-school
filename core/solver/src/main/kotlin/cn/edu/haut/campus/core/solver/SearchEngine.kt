package cn.edu.haut.campus.core.solver

import java.time.Duration
import java.util.BitSet

internal class SolverBudget(budget: Duration) {
    private val deadlineNanos: Long

    init {
        require(!budget.isZero && !budget.isNegative) { "求解预算必须为正时长" }
        val now = System.nanoTime()
        val nanos = budget.toNanos()
        deadlineNanos = if (Long.MAX_VALUE - now < nanos) Long.MAX_VALUE else now + nanos
    }

    fun expired(): Boolean = System.nanoTime() - deadlineNanos >= 0L

    fun remainingNanos(): Long = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
}

internal class MutableSolverStats {
    var nodes: Long = 0
    var propagations: Long = 0
    var prunedBranches: Long = 0
    var oracleCalls: Long = 0
    var oracleMemoHits: Long = 0

    fun snapshot(startedNanos: Long, candidateCount: Int, variableCount: Int): SolverStats = SolverStats(
        elapsedNanos = (System.nanoTime() - startedNanos).coerceAtLeast(0L),
        nodes = nodes,
        propagations = propagations,
        prunedBranches = prunedBranches,
        oracleCalls = oracleCalls,
        oracleMemoHits = oracleMemoHits,
        candidateCount = candidateCount,
        variableCount = variableCount
    )
}

internal enum class Consistency {
    CONSISTENT,
    INCONSISTENT,
    UNKNOWN
}

internal data class SearchOutcome(
    val solutions: List<ScheduleSolution>,
    val exhaustive: Boolean
)

internal object SearchEngine {

    fun run(
        problem: SelectionProblem,
        activeConstraintIds: Set<String>,
        optimize: Boolean,
        maxSolutions: Int,
        budget: SolverBudget,
        stats: MutableSolverStats,
        nodeLimit: Long? = null
    ): SearchOutcome {
        val compiled = ProblemCompiler.compile(problem, activeConstraintIds)
        if (compiled.immediateInfeasible) return SearchOutcome(emptyList(), exhaustive = true)
        val run = SingleSearchRun(compiled, optimize, maxSolutions, budget, stats, nodeLimit)
        return run.search()
    }

    private class SingleSearchRun(
        private val compiled: CompiledProblem,
        private val optimize: Boolean,
        private val maxSolutions: Int,
        private val budget: SolverBudget,
        private val stats: MutableSolverStats,
        private val nodeLimit: Long?
    ) {
        private val variableCount = compiled.variables.size
        private val incumbents = ArrayList<ScheduleSolution>()
        private val signatures = HashSet<String>()
        private var timedOut = false

        fun search(): SearchOutcome {
            val root = initialNode()
            if (!propagate(root)) {
                return SearchOutcome(emptyList(), exhaustive = true)
            }
            searchNode(root)
            val ranked = incumbents.sortedWith(compareBy<ScheduleSolution> { it.totalCostMilli }.thenBy { it.signature })
            return SearchOutcome(ranked, exhaustive = !timedOut)
        }

        private inner class Node(
            val domains: Array<IntArray>,
            val assignments: IntArray,
            var assignedCount: Int,
            var assignedCreditsMilli: Long,
            var assignedUnaryCostMilli: Long
        ) {
            fun deepCopy(): Node = Node(
                domains = Array(domains.size) { domains[it].copyOf() },
                assignments = assignments.copyOf(),
                assignedCount = assignedCount,
                assignedCreditsMilli = assignedCreditsMilli,
                assignedUnaryCostMilli = assignedUnaryCostMilli
            )

        }

        private fun initialNode(): Node {
            val domains = Array(compiled.variables.size) { variableIndex ->
                val variable = compiled.variables[variableIndex]
                val candidates = variable.candidateIndices.sortedWith(
                    compareBy<Int> { compiled.options[it].unaryCostMilli }.thenBy { compiled.options[it].source.id }
                ).toIntArray()
                if (variable.required) {
                    candidates
                } else {
                    intArrayOf(SKIP_VALUE) + candidates
                }
            }
            return Node(
                domains = domains,
                assignments = IntArray(compiled.variables.size) { Int.MIN_VALUE },
                assignedCount = 0,
                assignedCreditsMilli = 0L,
                assignedUnaryCostMilli = 0L
            )
        }

        private fun searchNode(node: Node) {
            if (budget.expired() || (nodeLimit != null && stats.nodes >= nodeLimit)) {
                timedOut = true
                return
            }
            stats.nodes++
            if (node.assignedCount == variableCount) {
                considerSolution(node)
                return
            }
            if (optimize && incumbents.size >= maxSolutions) {
                val worst = incumbents.maxOf { it.totalCostMilli }
                if (lowerBound(node) >= worst) {
                    stats.prunedBranches++
                    return
                }
            }

            val variableIndex = selectVariable(node) ?: return
            for (value in orderedValues(node, variableIndex)) {
                if (budget.expired() || (nodeLimit != null && stats.nodes >= nodeLimit)) {
                    timedOut = true
                    return
                }
                val child = node.deepCopy()
                assign(child, variableIndex, value)
                if (propagate(child)) {
                    searchNode(child)
                } else {
                    stats.prunedBranches++
                }
                if (timedOut) return
            }
        }

        private fun propagate(node: Node): Boolean {
            while (true) {
                var changed = false

                for (variableIndex in compiled.variables.indices) {
                    val assigned = node.assignments[variableIndex]
                    if (assigned == Int.MIN_VALUE || assigned == SKIP_VALUE) continue
                    val blocking = compiled.incompatible[assigned]
                    for (otherIndex in compiled.variables.indices) {
                        if (node.assignments[otherIndex] != Int.MIN_VALUE) continue
                        val before = node.domains[otherIndex]
                        val filtered = before.filter { value ->
                            value == SKIP_VALUE || !blocking.get(value)
                        }.toIntArray()
                        if (filtered.isEmpty()) return false
                        if (filtered.size != before.size) {
                            node.domains[otherIndex] = filtered
                            stats.propagations++
                            changed = true
                        }
                    }
                }

                val bounds = creditBounds(node, excludedVariable = -1)
                if (compiled.creditUpperActive && bounds.first > compiled.maxCreditMilli) return false
                if (compiled.creditLowerActive && bounds.second < compiled.minCreditMilli) return false

                for (variableIndex in compiled.variables.indices) {
                    if (node.assignments[variableIndex] != Int.MIN_VALUE) continue
                    val variable = compiled.variables[variableIndex]
                    val before = node.domains[variableIndex]
                    val filtered = before.filter { value ->
                        val valueCredits = if (value == SKIP_VALUE) 0L else variable.creditsMilli
                        val others = creditBounds(node, variableIndex)
                        val otherMin = others.first - node.assignedCreditsMilli
                        val otherMax = others.second - node.assignedCreditsMilli
                        val after = node.assignedCreditsMilli + valueCredits
                        (!compiled.creditUpperActive || after + otherMin <= compiled.maxCreditMilli) &&
                            (!compiled.creditLowerActive || after + otherMax >= compiled.minCreditMilli)
                    }.toIntArray()
                    if (filtered.isEmpty()) return false
                    if (filtered.size != before.size) {
                        node.domains[variableIndex] = filtered
                        stats.propagations++
                        changed = true
                    }
                }

                val mandatoryFutureCredits = compiled.variables.indices
                    .filter { node.assignments[it] == Int.MIN_VALUE && compiled.variables[it].required }
                    .sumOf { compiled.variables[it].creditsMilli }
                val lowerAlreadyGuaranteed = !compiled.creditLowerActive ||
                    node.assignedCreditsMilli + mandatoryFutureCredits >= compiled.minCreditMilli
                if (lowerAlreadyGuaranteed) {
                    for (variableIndex in compiled.variables.indices) {
                        val variable = compiled.variables[variableIndex]
                        if (variable.required || node.assignments[variableIndex] != Int.MIN_VALUE) continue
                        val before = node.domains[variableIndex]
                        if (before.size > 1 && before.contains(SKIP_VALUE)) {
                            node.domains[variableIndex] = intArrayOf(SKIP_VALUE)
                            stats.propagations++
                            changed = true
                        }
                    }
                }

                var assignedSingleton = false
                for (variableIndex in compiled.variables.indices) {
                    if (node.assignments[variableIndex] == Int.MIN_VALUE && node.domains[variableIndex].size == 1) {
                        assign(node, variableIndex, node.domains[variableIndex][0])
                        assignedSingleton = true
                        changed = true
                        break
                    }
                }
                if (!changed && !assignedSingleton) return true
            }
        }

        private fun creditBounds(node: Node, excludedVariable: Int): Pair<Long, Long> {
            var min = node.assignedCreditsMilli
            var max = node.assignedCreditsMilli
            for (variableIndex in compiled.variables.indices) {
                if (variableIndex == excludedVariable || node.assignments[variableIndex] != Int.MIN_VALUE) continue
                val variable = compiled.variables[variableIndex]
                val domain = node.domains[variableIndex]
                val hasSkip = domain.contains(SKIP_VALUE)
                val hasCandidate = domain.any { it != SKIP_VALUE }
                if (!hasSkip && !hasCandidate) return Long.MAX_VALUE to Long.MIN_VALUE
                if (!hasSkip) min += variable.creditsMilli
                if (hasCandidate) max += variable.creditsMilli
            }
            return min to max
        }

        private fun assign(node: Node, variableIndex: Int, value: Int) {
            check(node.assignments[variableIndex] == Int.MIN_VALUE) { "变量重复赋值" }
            check(node.domains[variableIndex].contains(value)) { "值不在当前值域内" }
            node.assignments[variableIndex] = value
            node.domains[variableIndex] = intArrayOf(value)
            node.assignedCount++
            if (value != SKIP_VALUE) {
                val option = compiled.options[value]
                node.assignedCreditsMilli = Math.addExact(node.assignedCreditsMilli, option.creditsMilli)
                node.assignedUnaryCostMilli = Math.addExact(node.assignedUnaryCostMilli, option.unaryCostMilli)
            }
        }

        private fun selectVariable(node: Node): Int? {
            var best: Int? = null
            var bestSize = Int.MAX_VALUE
            var bestDegree = -1
            var bestCredits = -1L
            for (variableIndex in compiled.variables.indices) {
                if (node.assignments[variableIndex] != Int.MIN_VALUE) continue
                val size = node.domains[variableIndex].size
                val variable = compiled.variables[variableIndex]
                val better = best == null ||
                    size < bestSize ||
                    (size == bestSize && variable.degree > bestDegree) ||
                    (size == bestSize && variable.degree == bestDegree && variable.creditsMilli > bestCredits)
                if (better) {
                    best = variableIndex
                    bestSize = size
                    bestDegree = variable.degree
                    bestCredits = variable.creditsMilli
                }
            }
            return best
        }

        private fun orderedValues(node: Node, variableIndex: Int): List<Int> {
            val domain = node.domains[variableIndex]
            val candidates = domain.filter { it != SKIP_VALUE }.sortedWith(
                compareBy<Int> { compiled.options[it].unaryCostMilli }.thenBy { compiled.options[it].source.id }
            )
            if (!domain.contains(SKIP_VALUE)) return candidates
            val deficit = creditDeficit(node)
            return if (deficit > 0L) candidates + SKIP_VALUE else listOf(SKIP_VALUE) + candidates
        }

        private fun creditDeficit(node: Node): Long {
            if (!compiled.creditLowerActive) return 0L
            var guaranteed = node.assignedCreditsMilli
            for (variableIndex in compiled.variables.indices) {
                if (node.assignments[variableIndex] == Int.MIN_VALUE && compiled.variables[variableIndex].required) {
                    guaranteed += compiled.variables[variableIndex].creditsMilli
                }
            }
            return (compiled.minCreditMilli - guaranteed).coerceAtLeast(0L)
        }

        /** 可接纳下界：已选一元成本 + 必选最小一元成本 + 学分缺口的分数背包松弛。成对/全局成本安全取 0。 */
        private fun lowerBound(node: Node): Long {
            var bound = node.assignedUnaryCostMilli
            val optionalItems = ArrayList<Pair<Long, Long>>()
            for (variableIndex in compiled.variables.indices) {
                if (node.assignments[variableIndex] != Int.MIN_VALUE) continue
                val variable = compiled.variables[variableIndex]
                val candidateCosts = node.domains[variableIndex]
                    .filter { it != SKIP_VALUE }
                    .map { compiled.options[it].unaryCostMilli }
                val minCandidateCost = candidateCosts.minOrNull() ?: 0L
                if (variable.required) {
                    bound = Math.addExact(bound, minCandidateCost)
                } else if (candidateCosts.isNotEmpty() && variable.creditsMilli > 0) {
                    optionalItems += variable.creditsMilli to minCandidateCost
                }
            }
            var deficit = creditDeficit(node)
            if (deficit > 0L && optionalItems.isNotEmpty()) {
                val sorted = optionalItems.sortedWith(compareBy<Pair<Long, Long>> { it.second.toDouble() / it.first.toDouble() })
                for ((credits, cost) in sorted) {
                    if (deficit <= 0L) break
                    val used = minOf(deficit, credits)
                    val fractionCost = ceilDiv(cost * used, credits)
                    bound = Math.addExact(bound, fractionCost)
                    deficit -= used
                }
            }
            return bound
        }

        private fun considerSolution(node: Node) {
            val selectedIndices = compiled.variables.indices
                .map { node.assignments[it] }
                .filter { it != SKIP_VALUE && it != Int.MIN_VALUE }
            val selected = selectedIndices
                .map { compiled.options[it].source }
                .sortedWith(compareBy<TeachingClassOption> { it.offeringId }.thenBy { it.id })
            val creditsMilli = selected.sumOf { creditsToMilli(it.credits) }
            if (compiled.creditLowerActive && creditsMilli < compiled.minCreditMilli) return
            if (compiled.creditUpperActive && creditsMilli > compiled.maxCreditMilli) return
            val signature = selected.joinToString(separator = "|") { it.id }
            if (!signatures.add(signature)) return
            val evaluated = SolutionCostEvaluator.evaluate(compiled.problem, selected)
            if (incumbents.size >= maxSolutions) {
                val worst = incumbents.maxOf { it.totalCostMilli }
                if (evaluated.totalCostMilli >= worst) {
                    signatures.remove(signature)
                    return
                }
            }
            val breakdown = evaluated.breakdown.sortedWith(compareBy<CostBreakdownItem> { it.preferenceId })
            val tradeoffs = breakdown.filter { it.costMilli > 0L }.map { it.label + "：" + it.detail }
            val solution = ScheduleSolution(
                selections = selected.map { SelectedTeachingClass(it) },
                totalCostMilli = evaluated.totalCostMilli,
                costBreakdown = breakdown,
                creditTotal = milliToCredits(creditsMilli),
                uncertainties = evaluated.uncertainties,
                tradeoffs = tradeoffs,
                signature = signature
            )
            incumbents += solution
            incumbents.sortWith(compareBy<ScheduleSolution> { it.totalCostMilli }.thenBy { it.signature })
            while (incumbents.size > maxSolutions) incumbents.removeAt(incumbents.lastIndex)
        }

        private fun ceilDiv(value: Long, divisor: Long): Long = if (value <= 0L) 0L else ((value - 1L) / divisor) + 1L
    }
}
