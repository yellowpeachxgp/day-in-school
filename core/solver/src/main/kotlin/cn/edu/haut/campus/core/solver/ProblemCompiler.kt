package cn.edu.haut.campus.core.solver

import cn.edu.haut.campus.core.selection.CapacityVerdict
import java.time.Duration
import java.util.BitSet

internal const val SKIP_VALUE: Int = -1

internal data class UserConstraint(
    val ref: ConstraintRef,
    val hard: HardConstraint? = null,
    val priority: Int
)

internal object UserConstraints {
    fun of(problem: SelectionProblem): List<UserConstraint> {
        val result = ArrayList<UserConstraint>()
        for (constraint in problem.hard) {
            val priority = when (constraint) {
                is HardConstraint.RequireOffering -> 10
                is HardConstraint.CapacityPolicy -> 30
                is HardConstraint.TimeExclusion -> 40
                is HardConstraint.CampusRestriction -> 50
                is HardConstraint.MutualExclusion -> 60
                is HardConstraint.CommutePolicy -> 70
            }
            result += UserConstraint(constraint.ref, constraint, priority)
        }
        result += UserConstraint(ConstraintRef.creditLowerBound(), null, 20)
        result += UserConstraint(ConstraintRef.creditUpperBound(), null, 21)
        return result.sortedWith(compareBy<UserConstraint> { it.priority }.thenBy { it.ref.id })
    }
}

internal data class CompiledOption(
    val index: Int,
    val source: TeachingClassOption,
    val timeBits: BitSet,
    val unaryCostMilli: Long,
    val creditsMilli: Long,
    val weeksUncertain: Boolean,
    val locationUncertain: Boolean,
    val capacityVerdict: CapacityVerdict
)

internal data class CompiledVariable(
    val index: Int,
    val offeringId: String,
    val courseName: String,
    val required: Boolean,
    val creditsMilli: Long,
    val candidateIndices: List<Int>,
    val degree: Int
)

internal data class CompiledProblem(
    val problem: SelectionProblem,
    val activeConstraintIds: Set<String>,
    val options: List<CompiledOption>,
    val variables: List<CompiledVariable>,
    val incompatible: List<BitSet>,
    val fixedBusyBits: BitSet,
    val minCreditMilli: Long,
    val maxCreditMilli: Long,
    val creditLowerActive: Boolean,
    val creditUpperActive: Boolean,
    val immediateInfeasible: Boolean
)

internal object ProblemCompiler {

    fun compile(problem: SelectionProblem, activeConstraintIds: Set<String>): CompiledProblem {
        validateWeeksWithinGrid(problem)
        val hardById = problem.hard.associateBy { it.ref.id }
        val activeHard = problem.hard.filter { it.ref.id in activeConstraintIds }
        val requiredIds = activeHard.filterIsInstance<HardConstraint.RequireOffering>()
            .mapTo(linkedSetOf()) { it.offeringId }
        val capacityPolicy = activeHard.filterIsInstance<HardConstraint.CapacityPolicy>().firstOrNull()
        val commutePolicy = activeHard.filterIsInstance<HardConstraint.CommutePolicy>().firstOrNull()
        val timeExclusions = activeHard.filterIsInstance<HardConstraint.TimeExclusion>()
        val campusRestrictions = activeHard.filterIsInstance<HardConstraint.CampusRestriction>()
        val mutualExclusions = activeHard.filterIsInstance<HardConstraint.MutualExclusion>()
        val creditLowerActive = ConstraintRef.creditLowerBound().id in activeConstraintIds
        val creditUpperActive = ConstraintRef.creditUpperBound().id in activeConstraintIds

        val sortedCandidates = problem.candidates.sortedWith(compareBy({ it.offeringId }, { it.id }))
        val fixedProjected = problem.fixedOccupancy + TimelineOccupancyProjector.project(problem.fixedEvents, problem.timeGrid)
        val fixedBusy = BitSet(problem.timeGrid.atomCount)
        for (fixed in fixedProjected) {
            for (slot in fixed.slots) {
                fixedBusy.or(bitsFor(slot, problem.timeGrid))
            }
        }

        val options = sortedCandidates.mapIndexed { index, source ->
            val bits = BitSet(problem.timeGrid.atomCount)
            source.slots.forEach { bits.or(bitsFor(it, problem.timeGrid)) }
            CompiledOption(
                index = index,
                source = source,
                timeBits = bits,
                unaryCostMilli = unaryCost(source, problem.soft),
                creditsMilli = creditsToMilli(source.credits),
                weeksUncertain = source.slots.any { !it.weeks.isKnown },
                locationUncertain = source.slots.any { it.location?.hasCampusAndBuilding != true },
                capacityVerdict = source.capacityVerdict()
            )
        }

        val excluded = HashMap<Int, MutableList<String>>()
        for (option in options) {
            val reasons = ArrayList<String>()
            if (option.timeBits.intersects(fixedBusy)) reasons += "fixed-occupancy"
            if (capacityPolicy != null) {
                if (capacityPolicy.excludeFull && option.capacityVerdict == CapacityVerdict.FULL) reasons += capacityPolicy.ref.id
                if (!capacityPolicy.allowUnknown && option.capacityVerdict == CapacityVerdict.UNKNOWN) reasons += capacityPolicy.ref.id
            }
            for (exclusion in timeExclusions) {
                if (overlapsAny(option.source.slots, exclusion.slots)) reasons += exclusion.ref.id
            }
            for (restriction in campusRestrictions) {
                val violates = option.source.slots.any { slot ->
                    val campus = slot.location?.campusId
                    campus == null || campus !in restriction.allowedCampusIds
                }
                if (violates) reasons += restriction.ref.id
            }
            if (reasons.isNotEmpty()) excluded[option.index] = reasons
        }

        val incompatible = List(options.size) { BitSet(options.size) }
        val travel = CachingTravelTimeMatrix(problem.travel)
        for (left in options) {
            for (right in options) {
                if (left.index >= right.index) continue
                val conflict = left.timeBits.intersects(right.timeBits) ||
                    mutuallyExclusive(left.source.offeringId, right.source.offeringId, mutualExclusions) ||
                    (commutePolicy != null && commuteIncompatible(left, right, commutePolicy, problem, travel))
                if (conflict) {
                    incompatible[left.index].set(right.index)
                    incompatible[right.index].set(left.index)
                }
            }
        }

        val byOffering = options.groupBy { it.source.offeringId }
        val offeringIds = byOffering.keys.sorted()
        var immediateInfeasible = false
        val variables = offeringIds.mapIndexed { variableIndex, offeringId ->
            val members = byOffering.getValue(offeringId)
            val creditValues = members.map { it.creditsMilli }.distinct()
            require(creditValues.size == 1) { "同一 offering 的教学班学分必须一致: " + offeringId }
            val available = members.filter { it.index !in excluded }.map { it.index }
            val required = offeringId in requiredIds
            if (required && available.isEmpty()) immediateInfeasible = true
            val degree = members.sumOf { member ->
                val other = incompatible[member.index].clone() as BitSet
                other.andNot(bitsForIndices(members.map { it.index }))
                other.cardinality()
            }
            CompiledVariable(
                index = variableIndex,
                offeringId = offeringId,
                courseName = members.first().source.courseName,
                required = required,
                creditsMilli = creditValues.single(),
                candidateIndices = available,
                degree = degree
            )
        }.sortedBy { it.offeringId }.mapIndexed { index, variable -> variable.copy(index = index) }

        for (requiredId in requiredIds) {
            if (requiredId !in byOffering.keys) immediateInfeasible = true
        }

        return CompiledProblem(
            problem = problem,
            activeConstraintIds = activeConstraintIds,
            options = options,
            variables = variables,
            incompatible = incompatible,
            fixedBusyBits = fixedBusy,
            minCreditMilli = creditsToMilli(problem.creditRange.start),
            maxCreditMilli = creditsToMilli(problem.creditRange.endInclusive),
            creditLowerActive = creditLowerActive,
            creditUpperActive = creditUpperActive,
            immediateInfeasible = immediateInfeasible
        )
    }

    internal fun bitsFor(slot: SolverClassSlot, grid: TeachingTimeGrid): BitSet {
        val bits = BitSet(grid.atomCount)
        val weeks = if (slot.weeks.isKnown) slot.weeks.weeks else (1..grid.weekCount).toSet()
        for (week in weeks) {
            require(week in 1..grid.weekCount) { "教学周超出显式网格: " + week }
            for (section in slot.startSection..slot.endSection) {
                require(section in 1..grid.sectionsPerDay) { "节次超出显式网格: " + section }
                bits.set(grid.atomIndex(week, slot.weekday, section))
            }
        }
        return bits
    }

    private fun bitsForIndices(indices: List<Int>): BitSet {
        val bits = BitSet()
        indices.forEach { bits.set(it) }
        return bits
    }

    private fun validateWeeksWithinGrid(problem: SelectionProblem) {
        fun validate(slot: SolverClassSlot, owner: String) {
            if (slot.weeks.isKnown) {
                val max = slot.weeks.weeks.maxOrNull() ?: 0
                require(max <= problem.timeGrid.weekCount) { owner + " 的教学周超出显式网格" }
            }
            require(slot.endSection <= problem.timeGrid.sectionsPerDay) { owner + " 的节次超出显式网格" }
        }
        problem.candidates.forEach { candidate -> candidate.slots.forEach { validate(it, "候选 " + candidate.id) } }
        problem.fixedOccupancy.forEach { fixed -> fixed.slots.forEach { validate(it, "固定占用 " + fixed.id) } }
        problem.hard.filterIsInstance<HardConstraint.TimeExclusion>().forEach { exclusion ->
            exclusion.slots.forEach { validate(it, "时间排除 " + exclusion.ref.id) }
        }
    }

    private fun unaryCost(option: TeachingClassOption, preferences: List<WeightedPreference>): Long {
        var total = 0L
        for (preference in preferences) {
            val cost = when (preference) {
                is WeightedPreference.EarlySection ->
                    option.slots.count { it.startSection <= preference.latestEarlySection } * preference.costPerOccurrenceMilli
                is WeightedPreference.LateSection ->
                    option.slots.count { it.endSection >= preference.firstLateSection } * preference.costPerOccurrenceMilli
                is WeightedPreference.UnknownCapacityRisk ->
                    if (option.capacityVerdict() == CapacityVerdict.UNKNOWN) preference.costPerClassMilli else 0L
                is WeightedPreference.UnknownWeeksRisk ->
                    if (option.slots.any { !it.weeks.isKnown }) preference.costPerClassMilli else 0L
                is WeightedPreference.CandidatePenalty ->
                    if (option.id in preference.candidateIds) preference.costPerOccurrenceMilli else 0L
                else -> 0L
            }
            total = Math.addExact(total, cost)
        }
        return total
    }

    private fun overlapsAny(left: List<SolverClassSlot>, right: List<SolverClassSlot>): Boolean {
        for (a in left) {
            for (b in right) {
                if (a.sharesSectionWith(b) && a.weeks.intersects(b.weeks)) return true
            }
        }
        return false
    }

    private fun mutuallyExclusive(
        leftOffering: String,
        rightOffering: String,
        exclusions: List<HardConstraint.MutualExclusion>
    ): Boolean {
        if (leftOffering == rightOffering) return false
        return exclusions.any { leftOffering in it.offeringIds && rightOffering in it.offeringIds }
    }

    private fun commuteIncompatible(
        left: CompiledOption,
        right: CompiledOption,
        policy: HardConstraint.CommutePolicy,
        problem: SelectionProblem,
        travel: TravelTimeMatrix
    ): Boolean {
        if (left.source.offeringId == right.source.offeringId) return false
        for (a in left.source.slots) {
            for (b in right.source.slots) {
                if (a.weekday != b.weekday || !a.weeks.intersects(b.weeks)) continue
                if (a.sharesSectionWith(b)) continue
                val earlier = if (a.endSection < b.startSection) a else b
                val later = if (earlier === a) b else a
                if (earlier.endSection >= later.startSection) continue
                val gap = gapMinutes(problem.timeGrid, earlier.endSection, later.startSection)
                val estimate = estimateFor(earlier, later, travel)
                when (estimate) {
                    is TravelTimeEstimate.Reachable -> {
                        if (gap < estimate.minutes.toLong() + policy.bufferMinutes) return true
                    }
                    TravelTimeEstimate.NotWithinBreak -> return true
                    TravelTimeEstimate.Unknown -> {
                        if (policy.unknownTravel == UnknownTravelPolicy.REJECT) return true
                    }
                }
            }
        }
        return false
    }

    private fun estimateFor(
        earlier: SolverClassSlot,
        later: SolverClassSlot,
        travel: TravelTimeMatrix
    ): TravelTimeEstimate {
        val from = earlier.location
        val to = later.location
        if (from == null || to == null) return TravelTimeEstimate.Unknown
        return travel.estimate(from, to)
    }

    private fun gapMinutes(grid: TeachingTimeGrid, endSection: Int, startSection: Int): Long {
        val end = grid.period(endSection).end
        val start = grid.period(startSection).start
        return Duration.between(end, start).toMinutes()
    }
}

internal fun TeachingClassOption.capacityVerdict(): CapacityVerdict = when (val value = capacity) {
    cn.edu.haut.campus.core.selection.ClassCapacity.Unknown -> CapacityVerdict.UNKNOWN
    is cn.edu.haut.campus.core.selection.ClassCapacity.Known -> if (value.isFull) CapacityVerdict.FULL else CapacityVerdict.AVAILABLE
}
