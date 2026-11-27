package cn.edu.haut.campus.core.solver

import cn.edu.haut.campus.core.selection.ClassCapacity
import cn.edu.haut.campus.core.selection.TeachingWeeks
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableSolverTest {
    private val solver = BranchAndBoundTimetableSolver()

    @Test
    fun adjacentSectionsDoNotConflictButSharedSectionDoes() {
        val requiredA = HardConstraint.RequireOffering("A")
        val requiredB = HardConstraint.RequireOffering("B")
        val adjacent = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("a1", "A", slots = listOf(SolverTestFixtures.slot(1, 1, 2))),
                SolverTestFixtures.option("b1", "B", slots = listOf(SolverTestFixtures.slot(1, 3, 4)))
            ),
            hard = listOf(requiredA, requiredB)
        )
        val adjacentResult = solver.solve(adjacent, budget = Duration.ofSeconds(2))
        assertTrue(adjacentResult is SolveResult.Solutions)
        val solution = (adjacentResult as SolveResult.Solutions).ranked.single()
        assertTrue(ScheduleVerifier.verify(adjacent, solution).isValid)

        val overlapping = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("a1", "A", slots = listOf(SolverTestFixtures.slot(1, 1, 2))),
                SolverTestFixtures.option("b1", "B", slots = listOf(SolverTestFixtures.slot(1, 2, 3)))
            ),
            hard = listOf(requiredA, requiredB)
        )
        val overlappingResult = solver.solve(overlapping, budget = Duration.ofSeconds(2))
        assertTrue(overlappingResult is SolveResult.Infeasible)
    }

    @Test
    fun oddAndEvenWeeksDoNotConflictButUnknownWeeksAreConservative() {
        val hard = listOf(HardConstraint.RequireOffering("A"), HardConstraint.RequireOffering("B"))
        val disjoint = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("a1", "A", slots = listOf(SolverTestFixtures.slot(1, 1, 2, TeachingWeeks.odd(1, 15)))),
                SolverTestFixtures.option("b1", "B", slots = listOf(SolverTestFixtures.slot(1, 1, 2, TeachingWeeks.even(2, 16))))
            ),
            hard = hard
        )
        assertTrue(solver.solve(disjoint, budget = Duration.ofSeconds(2)) is SolveResult.Solutions)

        val uncertain = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("a1", "A", slots = listOf(SolverTestFixtures.slot(1, 1, 2, TeachingWeeks.odd(1, 15)))),
                SolverTestFixtures.option("b1", "B", slots = listOf(SolverTestFixtures.slot(1, 1, 2, TeachingWeeks.UNKNOWN)))
            ),
            hard = hard
        )
        assertTrue(solver.solve(uncertain, budget = Duration.ofSeconds(2)) is SolveResult.Infeasible)
    }

    @Test
    fun creditLowerBoundForcesOptionalSelectionAndUpperBoundIsExact() {
        val problem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("req1", "REQ", credits = "2", slots = listOf(SolverTestFixtures.slot(1, 1, 2))),
                SolverTestFixtures.option("opt1", "OPT", credits = "2.5", slots = listOf(SolverTestFixtures.slot(2, 1, 2)))
            ),
            hard = listOf(HardConstraint.RequireOffering("REQ")),
            creditLower = "4.5",
            creditUpper = "4.5"
        )
        val result = solver.solve(problem, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Solutions)
        val solution = (result as SolveResult.Solutions).ranked.single()
        assertEquals(setOf("req1", "opt1"), solution.selections.map { it.option.id }.toSet())
        assertTrue(ScheduleVerifier.verify(problem, solution).isValid)

        val impossibleUpper = SolverTestFixtures.problem(
            candidates = listOf(SolverTestFixtures.option("req1", "REQ", credits = "2")),
            hard = listOf(HardConstraint.RequireOffering("REQ")),
            creditLower = "0",
            creditUpper = "1.9"
        )
        assertTrue(solver.solve(impossibleUpper, budget = Duration.ofSeconds(2)) is SolveResult.Infeasible)
    }

    @Test
    fun capacityAndMutualExclusionAreHardConstraints() {
        val capacityProblem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("u1", "U", capacity = ClassCapacity.Unknown)
            ),
            hard = listOf(
                HardConstraint.RequireOffering("U"),
                HardConstraint.CapacityPolicy(excludeFull = true, allowUnknown = false)
            )
        )
        assertTrue(solver.solve(capacityProblem, budget = Duration.ofSeconds(2)) is SolveResult.Infeasible)

        val mutexProblem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("a1", "A", slots = listOf(SolverTestFixtures.slot(1, 1, 2))),
                SolverTestFixtures.option("b1", "B", slots = listOf(SolverTestFixtures.slot(2, 1, 2)))
            ),
            hard = listOf(
                HardConstraint.RequireOffering("A"),
                HardConstraint.RequireOffering("B"),
                HardConstraint.MutualExclusion(setOf("A", "B"))
            )
        )
        val result = solver.solve(mutexProblem, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Infeasible)
    }

    @Test
    fun crossCampusAdjacentCoursesAreRejectedWhenBreakIsInsufficient() {
        val cross = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option(
                    "a1", "A",
                    slots = listOf(SolverTestFixtures.slot(1, 1, 2, location = SolverTestFixtures.location(SolverTestFixtures.north, "A01")))
                ),
                SolverTestFixtures.option(
                    "b1", "B",
                    slots = listOf(SolverTestFixtures.slot(1, 3, 4, location = SolverTestFixtures.location(SolverTestFixtures.south, "B01")))
                )
            ),
            hard = listOf(
                HardConstraint.RequireOffering("A"),
                HardConstraint.RequireOffering("B"),
                HardConstraint.CommutePolicy(bufferMinutes = 0)
            )
        )
        assertTrue(solver.solve(cross, budget = Duration.ofSeconds(2)) is SolveResult.Infeasible)

        val enoughGap = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option(
                    "a1", "A",
                    slots = listOf(SolverTestFixtures.slot(1, 1, 2, location = SolverTestFixtures.location(SolverTestFixtures.north, "A01")))
                ),
                SolverTestFixtures.option(
                    "b1", "B",
                    slots = listOf(SolverTestFixtures.slot(1, 5, 6, location = SolverTestFixtures.location(SolverTestFixtures.south, "B01")))
                )
            ),
            hard = listOf(
                HardConstraint.RequireOffering("A"),
                HardConstraint.RequireOffering("B"),
                HardConstraint.CommutePolicy(bufferMinutes = 0)
            )
        )
        assertTrue(solver.solve(enoughGap, budget = Duration.ofSeconds(2)) is SolveResult.Solutions)
    }

    @Test
    fun fixedOccupancyConflictsAreNotRelaxableBackgroundFacts() {
        val fixed = FixedOccupancy(
            id = "fixed-1",
            label = "已有课程",
            slots = listOf(SolverTestFixtures.slot(1, 1, 2)),
            provenance = SolverTestFixtures.provenance("fixed-1")
        )
        val problem = SolverTestFixtures.problem(
            candidates = listOf(SolverTestFixtures.option("a1", "A", slots = listOf(SolverTestFixtures.slot(1, 1, 2)))),
            hard = listOf(HardConstraint.RequireOffering("A")),
            fixed = listOf(fixed)
        )
        val result = solver.solve(problem, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Infeasible)
        result as SolveResult.Infeasible
        assertEquals(listOf(ConstraintKind.REQUIRED_OFFERING), result.conflictCore.map { it.kind })
    }

    @Test
    fun repeatedRunsAreDeterministic() {
        val problem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("a1", "A", slots = listOf(SolverTestFixtures.slot(1, 1, 2))),
                SolverTestFixtures.option("a2", "A", slots = listOf(SolverTestFixtures.slot(2, 1, 2))),
                SolverTestFixtures.option("b1", "B", slots = listOf(SolverTestFixtures.slot(3, 1, 2)))
            ),
            hard = listOf(HardConstraint.RequireOffering("A")),
            creditLower = "2",
            creditUpper = "4"
        )
        val first = solver.solve(problem, budget = Duration.ofSeconds(2))
        val second = solver.solve(problem, budget = Duration.ofSeconds(2))
        assertTrue(first is SolveResult.Solutions)
        assertTrue(second is SolveResult.Solutions)
        first as SolveResult.Solutions
        second as SolveResult.Solutions
        assertEquals(first.ranked.map { it.signature }, second.ranked.map { it.signature })
        assertEquals(first.ranked.map { it.totalCostMilli }, second.ranked.map { it.totalCostMilli })
    }

    @Test
    fun tinyBudgetIsNotReportedAsInfeasible() {
        val problem = SolverTestFixtures.problem(
            candidates = listOf(SolverTestFixtures.option("a1", "A")),
            hard = listOf(HardConstraint.RequireOffering("A"))
        )
        val result = solver.solve(problem, budget = Duration.ofNanos(1))
        assertTrue(result is SolveResult.Solutions)
        result as SolveResult.Solutions
        assertEquals(SolveCompletion.BEST_FOUND_BUDGET_EXHAUSTED, result.completion)
        assertTrue(result.ranked.isEmpty())
    }
    @Test
    fun campusRestrictionAndTimeExclusionAreEnforcedConservatively() {
        val southOnly = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option(
                    "s1", "S",
                    slots = listOf(SolverTestFixtures.slot(1, 1, 2, location = SolverTestFixtures.location(SolverTestFixtures.south, "B01")))
                )
            ),
            hard = listOf(
                HardConstraint.RequireOffering("S"),
                HardConstraint.CampusRestriction(setOf(SolverTestFixtures.north))
            )
        )
        assertTrue(solver.solve(southOnly, budget = Duration.ofSeconds(2)) is SolveResult.Infeasible)

        val excludedMonday = SolverTestFixtures.slot(1, 1, 2)
        val filtered = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("m1", "M", slots = listOf(excludedMonday)),
                SolverTestFixtures.option("m2", "M", slots = listOf(SolverTestFixtures.slot(2, 1, 2)))
            ),
            hard = listOf(
                HardConstraint.RequireOffering("M"),
                HardConstraint.TimeExclusion(listOf(excludedMonday), ConstraintRef("no-monday-first", ConstraintKind.TIME_EXCLUSION, "排除周一一二节"))
            )
        )
        val result = solver.solve(filtered, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Solutions)
        result as SolveResult.Solutions
        assertEquals(listOf("m2"), result.ranked.single().selections.map { it.option.id })
    }

    @Test
    fun allowedUnknownCapacityProducesSolutionWithUncertainty() {
        val problem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("u1", "U", capacity = ClassCapacity.Unknown)
            ),
            hard = listOf(
                HardConstraint.RequireOffering("U"),
                HardConstraint.CapacityPolicy(excludeFull = true, allowUnknown = true)
            )
        )
        val result = solver.solve(problem, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Solutions)
        result as SolveResult.Solutions
        assertTrue(result.ranked.single().uncertainties.any { it.kind == UncertaintyKind.CAPACITY })
    }

    @Test
    fun timelineEventsAreProjectedIntoFixedOccupancy() {
        val grid = SolverTestFixtures.grid()
        val window = grid.windowFor(week = 1, weekday = 1, section = 1)
        val entity = cn.edu.haut.campus.core.model.EntityRef(
            cn.edu.haut.campus.core.model.EntityType.PERSONAL_EVENT,
            "event-1",
            SolverTestFixtures.scope
        )
        val provenance = SolverTestFixtures.provenance("event-1")
        val event = cn.edu.haut.campus.core.timeline.TimelineEvent(
            id = cn.edu.haut.campus.core.timeline.TimelineEventId("test:event-1"),
            scope = SolverTestFixtures.scope,
            kind = cn.edu.haut.campus.core.timeline.TimelineKind.PERSONAL,
            title = "个人安排",
            start = window.start,
            end = window.end,
            zone = grid.zone,
            place = null,
            source = cn.edu.haut.campus.core.model.SourceRef(entity, provenance),
            provenance = provenance,
            status = cn.edu.haut.campus.core.timeline.EventStatus.SCHEDULED,
            busy = cn.edu.haut.campus.core.timeline.BusyLevel.BUSY,
            revision = 0
        )
        val problem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("m1", "M", slots = listOf(SolverTestFixtures.slot(1, 1, 2))),
                SolverTestFixtures.option("m2", "M", slots = listOf(SolverTestFixtures.slot(2, 1, 2)))
            ),
            hard = listOf(HardConstraint.RequireOffering("M"))
        ).copy(fixedEvents = listOf(event))
        val result = solver.solve(problem, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Solutions)
        result as SolveResult.Solutions
        assertEquals(listOf("m2"), result.ranked.single().selections.map { it.option.id })
    }

    @Test
    fun commuteToFixedOccupancyIsAlsoChecked() {
        val fixed = FixedOccupancy(
            id = "fixed-commute",
            label = "已有跨校区课程",
            slots = listOf(
                SolverTestFixtures.slot(1, 1, 2, location = SolverTestFixtures.location(SolverTestFixtures.south, "B01"))
            ),
            provenance = SolverTestFixtures.provenance("fixed-commute")
        )
        val problem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option(
                    "near1", "M",
                    slots = listOf(SolverTestFixtures.slot(1, 3, 4, location = SolverTestFixtures.location(SolverTestFixtures.north, "A01")))
                ),
                SolverTestFixtures.option(
                    "safe1", "M",
                    slots = listOf(SolverTestFixtures.slot(2, 5, 6, location = SolverTestFixtures.location(SolverTestFixtures.north, "A01")))
                )
            ),
            hard = listOf(
                HardConstraint.RequireOffering("M"),
                HardConstraint.CommutePolicy(bufferMinutes = 0)
            ),
            fixed = listOf(fixed)
        )
        val result = solver.solve(problem, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Solutions)
        result as SolveResult.Solutions
        assertEquals(listOf("safe1"), result.ranked.single().selections.map { it.option.id })
        assertTrue(ScheduleVerifier.verify(problem, result.ranked.single()).isValid)
    }

    @Test
    fun topKRetainsUsefulExtraOptionalSolutionAfterMinimum() {
        val penalty = WeightedPreference.CandidatePenalty(
            candidateIds = setOf("opt1"),
            costPerOccurrenceMilli = 10,
            id = "penalize-opt1",
            label = "额外选修成本"
        )
        val problem = SolverTestFixtures.problem(
            candidates = listOf(
                SolverTestFixtures.option("req1", "REQ", credits = "1", slots = listOf(SolverTestFixtures.slot(1, 1, 2))),
                SolverTestFixtures.option("opt1", "OPT", credits = "1", slots = listOf(SolverTestFixtures.slot(2, 1, 2)))
            ),
            hard = listOf(HardConstraint.RequireOffering("REQ")),
            creditLower = "1",
            creditUpper = "2",
            soft = listOf(penalty)
        )
        val result = solver.solve(problem, maxSolutions = 5, budget = Duration.ofSeconds(2))
        assertTrue(result is SolveResult.Solutions)
        result as SolveResult.Solutions
        assertEquals(2, result.ranked.size)
        assertEquals(listOf("req1"), result.ranked[0].selections.map { it.option.id })
        assertEquals(setOf("req1", "opt1"), result.ranked[1].selections.map { it.option.id }.toSet())
        assertTrue(result.ranked[1].totalCostMilli > result.ranked[0].totalCostMilli)
    }

}
