package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.selection.ClassSlot
import cn.edu.haut.campus.core.selection.TeachingWeeks
import cn.edu.haut.campus.core.solver.BranchAndBoundTimetableSolver
import cn.edu.haut.campus.core.solver.HardConstraint
import cn.edu.haut.campus.core.solver.PeriodTime
import cn.edu.haut.campus.core.solver.PrecomputedTravelTimeMatrix
import cn.edu.haut.campus.core.solver.ScheduleSolution
import cn.edu.haut.campus.core.solver.ScheduleVerificationReport
import cn.edu.haut.campus.core.solver.ScheduleVerifier
import cn.edu.haut.campus.core.solver.SelectionProblem
import cn.edu.haut.campus.core.solver.SolveResult
import cn.edu.haut.campus.core.solver.SolverClassSlot
import cn.edu.haut.campus.core.solver.TeachingClassOption
import cn.edu.haut.campus.core.solver.TeachingTimeGrid
import cn.edu.haut.campus.core.solver.TimetableSolver
import cn.edu.haut.campus.core.solver.WeightedPreference
import cn.edu.haut.campus.core.timeline.FreeSlot
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineEvent
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

internal data class ReviewTaskSchedulingSpec(
    val task: ReviewTask,
    /** 真实的任务/考试截止；null 表示只有调用方给出的排程窗口终点。 */
    val deadlineAt: Instant?,
    /** 候选必须不晚于该时间；已经按 window.end 裁剪。 */
    val schedulingEnd: Instant,
    val overdue: Boolean
)

internal data class PlannedReviewBlockSpec(
    val repetitionIndex: Int,
    val targetStart: Instant,
    val start: Instant,
    val end: Instant,
    val startAtom: Int,
    val atomCount: Int
)

internal data class GeneratedReviewPlan(
    val spec: ReviewTaskSchedulingSpec,
    val blocks: List<PlannedReviewBlockSpec>,
    val costMilli: Long,
    val candidateId: String = ""
)

internal data class ReviewCandidateGeneration(
    val plansByTaskId: Map<String, List<GeneratedReviewPlan>>,
    val failures: Map<String, String>,
    val grid: TeachingTimeGrid
)

internal data class ReviewSolveOutcome(
    val problem: SelectionProblem,
    val result: SolveResult,
    val solution: ScheduleSolution?,
    val verification: ScheduleVerificationReport?,
    val selectedPlans: Map<String, GeneratedReviewPlan>
)

/** 把复习任务合成“完整间隔重复方案”的候选值，再复用 W3-02 分支定界求解器解决跨任务时间冲突。 */
internal class ReviewSchedulePlanner(
    private val solver: TimetableSolver = BranchAndBoundTimetableSolver.create()
) {
    fun generateCandidates(
        specs: List<ReviewTaskSchedulingSpec>,
        freeSlots: List<FreeSlot>,
        window: TimeWindow,
        zone: ZoneId,
        maxCandidatesPerTask: Int
    ): ReviewCandidateGeneration {
        val grid = ReviewTimeGridFactory.create(window, zone)
        val plans = LinkedHashMap<String, List<GeneratedReviewPlan>>()
        val failures = LinkedHashMap<String, String>()
        val sortedSlots = freeSlots.sortedWith(compareBy<FreeSlot> { it.start }.thenBy { it.end })
        for (spec in specs) {
            val generated = generateForTask(spec, sortedSlots, window, grid, maxCandidatesPerTask)
            if (generated.isEmpty()) {
                failures[spec.task.id] = "截止前没有满足估时与间隔要求的空闲块"
            } else {
                plans[spec.task.id] = generated
            }
        }
        return ReviewCandidateGeneration(plans, failures, grid)
    }

    fun solve(
        activeSpecs: List<ReviewTaskSchedulingSpec>,
        generation: ReviewCandidateGeneration,
        fixedEvents: List<TimelineEvent>,
        scope: SchoolAccountScope,
        budget: Duration
    ): ReviewSolveOutcome? {
        if (activeSpecs.isEmpty()) return null
        val plans = activeSpecs.flatMap { spec -> generation.plansByTaskId[spec.task.id].orEmpty() }
        if (activeSpecs.any { spec -> generation.plansByTaskId[spec.task.id].isNullOrEmpty() }) return null
        val options = plans.map { plan -> plan.toTeachingClassOption(generation.grid, scope) }
        val candidatePenalties = plans.filter { it.costMilli > 0L }.map { plan ->
            WeightedPreference.CandidatePenalty(
                candidateIds = setOf(plan.candidateId),
                costPerOccurrenceMilli = plan.costMilli,
                id = "review-cost-" + plan.candidateId,
                label = "复习目标时间偏离成本"
            )
        }
        val soft = candidatePenalties + WeightedPreference.DailyLoad(
            maxSectionsPerDay = REVIEW_SECTION_SOFT_CAP_PER_DAY,
            costPerExtraSectionMilli = 200L,
            id = "review-daily-load",
            label = "每日复习负担"
        )
        val requiredCredits = BigDecimal(activeSpecs.size)
        val problem = SelectionProblem(
            candidates = options,
            hard = activeSpecs.map { spec ->
                HardConstraint.RequireOffering(
                    offeringId = reviewOfferingId(spec.task.id),
                    courseName = spec.task.title
                )
            },
            soft = soft,
            travel = PrecomputedTravelTimeMatrix(emptyMap()),
            creditRange = requiredCredits..requiredCredits,
            scope = scope,
            timeGrid = generation.grid,
            fixedEvents = fixedEvents
        )
        val result = solver.solve(problem, maxSolutions = 1, budget = budget)
        val solution = (result as? SolveResult.Solutions)?.ranked?.firstOrNull()
        val verification = solution?.let { ScheduleVerifier.verify(problem, it) }
        val planByCandidateId = plans.associateBy { it.candidateId }
        val selectedPlans = solution?.selections.orEmpty()
            .mapNotNull { selection -> planByCandidateId[selection.option.id] }
            .associateBy { it.spec.task.id }
        return ReviewSolveOutcome(problem, result, solution, verification, selectedPlans)
    }

    private fun generateForTask(
        spec: ReviewTaskSchedulingSpec,
        freeSlots: List<FreeSlot>,
        window: TimeWindow,
        grid: TeachingTimeGrid,
        maxCandidatesPerTask: Int
    ): List<GeneratedReviewPlan> {
        val targets = SpacedRepetitionPolicy.targetInstants(spec.task, window.start, spec.schedulingEnd)
            .mapIndexed { index, target -> SessionTarget(index + 1, target) }
        if (targets.isEmpty()) return emptyList()
        val sessionMinutes = SpacedRepetitionPolicy.roundedSessionMinutes(spec.task.estimatedMinutes, targets.size)
        val placementsBySession = targets.map { target ->
            target to selectDiversePlacements(
                allPlacements(target, sessionMinutes, freeSlots, window, spec.schedulingEnd, grid),
                target.idealStart
            )
        }
        if (placementsBySession.any { it.second.isEmpty() }) return emptyList()

        val complete = ArrayList<GeneratedReviewPlan>()
        fun enumerate(index: Int, previous: Placement?, chosen: List<Placement>) {
            if (index == placementsBySession.size) {
                val blocks = chosen.mapIndexed { blockIndex, placement ->
                    val target = targets[blockIndex]
                    PlannedReviewBlockSpec(
                        repetitionIndex = target.repetitionIndex,
                        targetStart = target.idealStart,
                        start = placement.start,
                        end = placement.end,
                        startAtom = placement.startAtom,
                        atomCount = placement.atomCount
                    )
                }
                complete += GeneratedReviewPlan(
                    spec = spec,
                    blocks = blocks,
                    costMilli = planCostMilli(spec.task, targets, chosen)
                )
                return
            }
            val target = placementsBySession[index].first
            for (placement in placementsBySession[index].second) {
                val last = previous
                if (last != null) {
                    if (placement.start.isBefore(last.end)) continue
                    val idealGap = Duration.between(placementsBySession[index - 1].first.idealStart, target.idealStart)
                    val requiredGap = SpacedRepetitionPolicy.minimumGapAfter(idealGap)
                    val actualGap = Duration.between(last.end, placement.start)
                    if (actualGap < requiredGap) continue
                }
                enumerate(index + 1, placement, chosen + placement)
            }
        }
        enumerate(0, null, emptyList())
        return complete
            .sortedWith(
                compareBy<GeneratedReviewPlan> { it.costMilli }
                    .thenBy { fingerprint(it.blocks) }
            )
            .take(maxCandidatesPerTask)
            .mapIndexed { index, plan -> plan.copy(candidateId = reviewCandidateId(spec.task.id, index)) }
    }

    private fun allPlacements(
        target: SessionTarget,
        sessionMinutes: Int,
        freeSlots: List<FreeSlot>,
        window: TimeWindow,
        deadline: Instant,
        grid: TeachingTimeGrid
    ): List<Placement> {
        val quantumMinutes = SpacedRepetitionPolicy.QUANTUM.toMinutes().toInt()
        val atomCount = ceilDiv(sessionMinutes, quantumMinutes)
        val result = ArrayList<Placement>()
        for (startAtom in 0 until grid.atomCount) {
            val startSection = startAtom % grid.sectionsPerDay + 1
            if (startSection + atomCount - 1 > grid.sectionsPerDay) continue
            val blockWindow = ReviewTimeGridFactory.windowForAtoms(grid, startAtom, atomCount)
            if (blockWindow.start.isBefore(window.start) || blockWindow.end.isAfter(window.end)) continue
            if (blockWindow.end.isAfter(deadline)) continue
            val contained = freeSlots.any { slot ->
                !slot.start.isAfter(blockWindow.start) && !slot.end.isBefore(blockWindow.end)
            }
            if (!contained) continue
            result += Placement(
                start = blockWindow.start,
                end = blockWindow.end,
                startAtom = startAtom,
                atomCount = atomCount,
                deviationMinutes = abs(Duration.between(target.idealStart, blockWindow.start).toMinutes())
            )
        }
        return result
    }

    private fun selectDiversePlacements(all: List<Placement>, target: Instant): List<Placement> {
        if (all.isEmpty()) return emptyList()
        val sorted = all.sortedWith(compareBy<Placement> { it.deviationMinutes }.thenBy { it.start }.thenBy { it.end })
        val selected = LinkedHashSet<Placement>()
        fun add(placement: Placement?) {
            if (placement != null) selected += placement
        }
        sorted.take(4).forEach(::add)
        add(sorted.minWithOrNull(compareBy<Placement> { it.start }.thenBy { it.end }))
        add(sorted.maxWithOrNull(compareBy<Placement> { it.start }.thenBy { it.end }))
        add(sorted.firstOrNull { !it.start.isBefore(target) })
        add(sorted.lastOrNull { !it.start.isAfter(target) })
        for (placement in sorted) {
            if (selected.size >= PER_SESSION_PLACEMENT_LIMIT) break
            add(placement)
        }
        return selected.take(PER_SESSION_PLACEMENT_LIMIT)
    }

    private fun planCostMilli(
        task: ReviewTask,
        targets: List<SessionTarget>,
        placements: List<Placement>
    ): Long {
        val masteryWeightPermille = 1000L + (100L - SpacedRepetitionPolicy.effectiveMasteryPercent(task)) * 20L
        var weightedMinutes = 0L
        placements.forEach { placement ->
            weightedMinutes += placement.deviationMinutes * masteryWeightPermille
        }
        for (index in 1 until placements.size) {
            val idealGap = Duration.between(targets[index - 1].idealStart, targets[index].idealStart).toMinutes()
            val actualGap = Duration.between(placements[index - 1].start, placements[index].start).toMinutes()
            weightedMinutes += abs(actualGap - idealGap) * masteryWeightPermille / 2L
        }
        return weightedMinutes.coerceAtMost(Long.MAX_VALUE / 4L)
    }

    private fun fingerprint(blocks: List<PlannedReviewBlockSpec>): String =
        blocks.joinToString("|") { it.start.toString() + "/" + it.end.toString() }

    private fun ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor

    private data class SessionTarget(
        val repetitionIndex: Int,
        val idealStart: Instant
    )

    private data class Placement(
        val start: Instant,
        val end: Instant,
        val startAtom: Int,
        val atomCount: Int,
        val deviationMinutes: Long
    )

    companion object {
        /** 每个复习 session 参与笛卡尔组合的多样化候选数；完整方案数仍由 maxCandidatesPerTask 限制。 */
        private const val PER_SESSION_PLACEMENT_LIMIT = 6
        private const val REVIEW_SECTION_SOFT_CAP_PER_DAY = 8
    }
}

internal object ReviewTimeGridFactory {
    const val SECTIONS_PER_DAY: Int = 48

    /** 30 分钟量子覆盖全天；最后一节以 LocalTime.MAX 收尾，绝不把跨午夜占用误报为空闲。 */
    fun create(window: TimeWindow, zone: ZoneId): TeachingTimeGrid {
        val startDate = window.start.atZone(zone).toLocalDate()
        val endDate = window.end.minusNanos(1).atZone(zone).toLocalDate()
        val firstMonday = startDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val lastMonday = endDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val weekCount = (ChronoUnit.WEEKS.between(firstMonday, lastMonday) + 1L).toInt()
        val periods = (1..SECTIONS_PER_DAY).associateWith { section ->
            val start = LocalTime.MIDNIGHT.plusMinutes((section - 1) * 30L)
            val end = if (section == SECTIONS_PER_DAY) {
                LocalTime.MAX
            } else {
                LocalTime.MIDNIGHT.plusMinutes(section * 30L)
            }
            PeriodTime(section, start, end)
        }
        return TeachingTimeGrid(
            weekCount = weekCount,
            sectionsPerDay = SECTIONS_PER_DAY,
            firstWeekMonday = firstMonday,
            zone = zone,
            periodTimes = periods
        )
    }

    fun windowForAtoms(grid: TeachingTimeGrid, startAtom: Int, atomCount: Int): TimeWindow {
        require(atomCount > 0) { "atomCount 必须为正" }
        val start = atomComponents(grid, startAtom)
        val end = atomComponents(grid, startAtom + atomCount - 1)
        require(start.week == end.week && start.weekday == end.weekday) { "复习块不能跨午夜" }
        return TimeWindow(
            grid.windowFor(start.week, start.weekday, start.section).start,
            grid.windowFor(end.week, end.weekday, end.section).end
        )
    }

    fun solverSlotForAtoms(grid: TeachingTimeGrid, startAtom: Int, atomCount: Int): SolverClassSlot {
        val start = atomComponents(grid, startAtom)
        val end = atomComponents(grid, startAtom + atomCount - 1)
        require(start.week == end.week && start.weekday == end.weekday) { "复习块不能跨午夜" }
        return SolverClassSlot(
            time = ClassSlot(
                weekday = start.weekday,
                startSection = start.section,
                endSection = end.section,
                weeks = TeachingWeeks.of(setOf(start.week))
            ),
            location = null
        )
    }

    private fun atomComponents(grid: TeachingTimeGrid, atom: Int): AtomComponents {
        require(atom in 0 until grid.atomCount) { "时间网格 atom 超出范围" }
        val dayIndex = atom / grid.sectionsPerDay
        return AtomComponents(
            week = dayIndex / 7 + 1,
            weekday = dayIndex % 7 + 1,
            section = atom % grid.sectionsPerDay + 1
        )
    }

    internal data class AtomComponents(val week: Int, val weekday: Int, val section: Int)
}

private fun GeneratedReviewPlan.toTeachingClassOption(grid: TeachingTimeGrid, scope: SchoolAccountScope): TeachingClassOption {
    return TeachingClassOption(
        id = candidateId,
        offeringId = reviewOfferingId(spec.task.id),
        courseName = spec.task.title,
        credits = BigDecimal.ONE,
        slots = blocks.map { block -> ReviewTimeGridFactory.solverSlotForAtoms(grid, block.startAtom, block.atomCount) },
        provenance = DataProvenance(
            providerId = "local-review-scheduler",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "spaced-repetition-solver",
            sourceRecordId = spec.task.id,
            completeness = SnapshotCompleteness.COMPLETE,
            trust = VerificationLevel.UNVERIFIED,
            schemaVersion = 1
        )
    )
}

private fun reviewOfferingId(taskId: String): String =
    "ro-" + sha256Hex(taskId.toByteArray(Charsets.UTF_8)).take(24)

private fun reviewCandidateId(taskId: String, index: Int): String =
    "rc-" + sha256Hex((taskId + "|" + index).toByteArray(Charsets.UTF_8)).take(24)
