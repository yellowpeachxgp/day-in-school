package cn.edu.haut.campus.core.solver

import java.io.File
import java.time.Duration
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticBenchmarkTest {
    private val solver = BranchAndBoundTimetableSolver()

    @Test
    fun generatorCoversRequiredMatrixAndIsDeterministic() {
        for (size in SyntheticBenchmarkGenerator.sizes) {
            for (family in BenchmarkFamily.entries) {
                val seed = SyntheticBenchmarkGenerator.seeds.first()
                val first = SyntheticBenchmarkGenerator.generate(size, family, seed)
                val second = SyntheticBenchmarkGenerator.generate(size, family, seed)
                assertEquals(size, first.problem.candidates.size)
                assertEquals(first.canonicalJson, second.canonicalJson)
                assertEquals(first.sha256, second.sha256)
                assertTrue(first.sha256.matches(Regex("[0-9a-f]{64}")))
                assertTrue(first.problem.candidates.all { candidate ->
                    candidate.slots.all { it.startSection in 1..12 && it.endSection in 1..12 }
                })
                if (family == BenchmarkFamily.UNCERTAIN_DATA) {
                    assertTrue(first.problem.candidates.any { it.slots.any { slot -> !slot.weeks.isKnown } })
                    assertTrue(first.problem.candidates.any { it.slots.any { slot -> slot.location == null } })
                    assertTrue(first.problem.candidates.any { it.capacity == cn.edu.haut.campus.core.selection.ClassCapacity.Unknown })
                }
                if (family == BenchmarkFamily.CROSS_CAMPUS) {
                    val campuses = first.problem.candidates.flatMap { it.slots }.mapNotNull { it.location?.campusId }.toSet()
                    assertTrue(campuses.size >= 2)
                }
            }
        }
    }

    @Test
    fun mediumPlantedReferenceSolutionsPassIndependentVerifier() {
        for (size in SyntheticBenchmarkGenerator.sizes) {
            for (seed in SyntheticBenchmarkGenerator.seeds) {
                val instance = SyntheticBenchmarkGenerator.generate(size, BenchmarkFamily.MEDIUM_PLANTED, seed)
                val planted = instance.plantedSolutionIds
                assertNotNull(planted)
                val plantedIds = planted!!
                val byId = instance.problem.candidates.associateBy { it.id }
                val selected = plantedIds.map { byId.getValue(it) }
                val evaluated = SolutionCostEvaluator.evaluate(instance.problem, selected)
                val solution = ScheduleSolution(
                    selections = selected.map { SelectedTeachingClass(it) },
                    totalCostMilli = evaluated.totalCostMilli,
                    costBreakdown = evaluated.breakdown,
                    creditTotal = selected.fold(java.math.BigDecimal.ZERO) { total, option -> total.add(option.credits) },
                    uncertainties = evaluated.uncertainties,
                    tradeoffs = emptyList(),
                    signature = plantedIds.sorted().joinToString("|")
                )
                val report = ScheduleVerifier.verify(instance.problem, solution)
                assertTrue(instance.fixtureId + " planted violations: " + report.violations, report.isValid)
            }
        }
    }

    @Test
    fun quickGateMatrixProducesMachineReadableEvidence() {
        for ((size, family, seed) in SyntheticBenchmarkGenerator.matrixCases(quickOnly = true)) {
            val instance = SyntheticBenchmarkGenerator.generate(size, family, seed)
            val started = System.nanoTime()
            val result = solver.solve(instance.problem, maxSolutions = 5, budget = Duration.ofMillis(1900))
            val elapsed = System.nanoTime() - started
            when (family) {
                BenchmarkFamily.NEAR_INFEASIBLE -> {
                    assertTrue(result is SolveResult.Infeasible)
                    result as SolveResult.Infeasible
                    assertEquals(ExplanationStatus.MINIMAL_VERIFIED, result.explanationStatus)
                    assertEquals(instance.expectedCoreKinds, result.conflictCore.map { it.kind }.toSet())
                }
                else -> {
                    assertTrue(result is SolveResult.Solutions)
                    result as SolveResult.Solutions
                    assertTrue(result.ranked.isNotEmpty())
                    val report = ScheduleVerifier.verify(instance.problem, result.ranked.first())
                    assertTrue(instance.fixtureId + " violations: " + report.violations, report.isValid)
                }
            }
            recordBenchmark(
                "CIS_SOLVER_BENCH fixture=" + instance.fixtureId +
                    " sha256=" + instance.sha256 +
                    " elapsedMs=" + formatMillis(elapsed) +
                    " completion=" + completionOf(result) +
                    " nodes=" + statsOf(result).nodes +
                    " propagations=" + statsOf(result).propagations +
                    " oracleCalls=" + statsOf(result).oracleCalls
            )
        }
    }

    @Test
    fun s50ColdAndSteadyStatePerformanceIsUnderTwoSeconds() {
        val families = listOf(
            BenchmarkFamily.MEDIUM_PLANTED,
            BenchmarkFamily.SPARSE_MANY_SOLUTIONS,
            BenchmarkFamily.DENSE_CONFLICT
        )
        for (family in families) {
            val instance = SyntheticBenchmarkGenerator.generate(50, family, SyntheticBenchmarkGenerator.seeds.first())
            val cold = measure(instance)
            assertTrue(
                instance.fixtureId + " cold " + formatMillis(cold.elapsedNanos) + "ms",
                cold.elapsedNanos < 2_000_000_000L
            )
            assertTrue(cold.result is SolveResult.Solutions)
            assertEquals(SolveCompletion.PROVEN_COST_TOP_K, (cold.result as SolveResult.Solutions).completion)

            repeat(3) { solver.solve(instance.problem, maxSolutions = 5, budget = Duration.ofMillis(1900)) }
            val measurements = ArrayList<Long>()
            repeat(5) { measurements += measure(instance).elapsedNanos }
            val sorted = measurements.sorted()
            val p50 = percentile(sorted, 0.50)
            val p95 = percentile(sorted, 0.95)
            val max = sorted.last()
            assertTrue(instance.fixtureId + " steady max " + formatMillis(max) + "ms", max < 2_000_000_000L)
            recordBenchmark(
                "CIS_SOLVER_PERF fixture=" + instance.fixtureId +
                    " sha256=" + instance.sha256 +
                    " coldMs=" + formatMillis(cold.elapsedNanos) +
                    " steadyMs=" + measurements.joinToString(prefix = "[", postfix = "]") { formatMillis(it) } +
                    " p50Ms=" + formatMillis(p50) +
                    " p95Ms=" + formatMillis(p95) +
                    " maxMs=" + formatMillis(max) +
                    " nodes=" + statsOf(cold.result).nodes +
                    " propagations=" + statsOf(cold.result).propagations
            )
        }

        val infeasible = SyntheticBenchmarkGenerator.generate(50, BenchmarkFamily.NEAR_INFEASIBLE, SyntheticBenchmarkGenerator.seeds.first())
        val explained = measure(infeasible)
        assertTrue(explained.result is SolveResult.Infeasible)
        assertTrue(
            infeasible.fixtureId + " solve+explain " + formatMillis(explained.elapsedNanos) + "ms",
            explained.elapsedNanos < 2_000_000_000L
        )
        explained.result as SolveResult.Infeasible
        assertEquals(ExplanationStatus.MINIMAL_VERIFIED, explained.result.explanationStatus)
        assertEquals(infeasible.expectedCoreKinds, explained.result.conflictCore.map { it.kind }.toSet())
        recordBenchmark(
            "CIS_SOLVER_PERF fixture=" + infeasible.fixtureId +
                " sha256=" + infeasible.sha256 +
                " coldMs=" + formatMillis(explained.elapsedNanos) +
                " completion=INFEASIBLE_EXPLAINED" +
                " nodes=" + explained.result.stats.nodes +
                " oracleCalls=" + explained.result.stats.oracleCalls
        )
    }

    private data class Measurement(val result: SolveResult, val elapsedNanos: Long)

    private fun measure(instance: SyntheticBenchmarkInstance): Measurement {
        val started = System.nanoTime()
        val result = solver.solve(instance.problem, maxSolutions = 5, budget = Duration.ofMillis(1900))
        return Measurement(result, System.nanoTime() - started)
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        val index = kotlin.math.ceil(sorted.size * fraction).toInt().coerceIn(1, sorted.size) - 1
        return sorted[index]
    }

    private fun statsOf(result: SolveResult): SolverStats = when (result) {
        is SolveResult.Solutions -> result.stats
        is SolveResult.Infeasible -> result.stats
    }

    private fun completionOf(result: SolveResult): String = when (result) {
        is SolveResult.Solutions -> result.completion.name
        is SolveResult.Infeasible -> "INFEASIBLE_" + result.explanationStatus.name
    }

    private fun formatMillis(nanos: Long): String = String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)

    private fun recordBenchmark(line: String) {
        println(line)
        val output = File("build/reports/solver-benchmark/benchmark-evidence.txt")
        output.parentFile.mkdirs()
        output.appendText(line + "
")
    }
}
