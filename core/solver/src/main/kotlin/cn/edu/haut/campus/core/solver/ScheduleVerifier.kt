package cn.edu.haut.campus.core.solver

import cn.edu.haut.campus.core.selection.CapacityVerdict

data class ScheduleVerificationReport(
    val violations: List<String>
) {
    val isValid: Boolean get() = violations.isEmpty()
}

/** 独立 verifier：不复用搜索状态或传播结果，直接按公开输入重算硬约束与成本。 */
object ScheduleVerifier {

    fun verify(problem: SelectionProblem, solution: ScheduleSolution): ScheduleVerificationReport {
        val violations = ArrayList<String>()
        val selected = solution.selections.map { it.option }
        val byId = problem.candidates.associateBy { it.id }

        for (option in selected) {
            val canonical = byId[option.id]
            if (canonical == null) {
                violations += "方案包含输入中不存在的教学班 " + option.id
            } else if (canonical != option) {
                violations += "方案教学班快照与输入不一致 " + option.id
            }
        }

        val byOffering = selected.groupBy { it.offeringId }
        for ((offeringId, members) in byOffering) {
            if (members.size > 1) violations += "同一 offering 被选择多个教学班: " + offeringId
        }
        for (required in problem.hard.filterIsInstance<HardConstraint.RequireOffering>()) {
            if (!byOffering.containsKey(required.offeringId)) violations += "必选 offering 未选择: " + required.offeringId
        }

        val creditsMilli = selected.sumOf { creditsToMilli(it.credits) }
        if (creditsMilli < creditsToMilli(problem.creditRange.start)) violations += "总学分低于下限"
        if (creditsMilli > creditsToMilli(problem.creditRange.endInclusive)) violations += "总学分高于上限"
        if (milliToCredits(creditsMilli).compareTo(solution.creditTotal) != 0) violations += "方案学分合计与重算结果不一致"

        val capacityPolicy = problem.hard.filterIsInstance<HardConstraint.CapacityPolicy>().firstOrNull()
        if (capacityPolicy != null) {
            for (option in selected) {
                val verdict = option.capacityVerdict()
                if (capacityPolicy.excludeFull && verdict == CapacityVerdict.FULL) violations += "选择了已知满员教学班 " + option.id
                if (!capacityPolicy.allowUnknown && verdict == CapacityVerdict.UNKNOWN) violations += "选择了容量未知教学班 " + option.id
            }
        }

        for (exclusion in problem.hard.filterIsInstance<HardConstraint.TimeExclusion>()) {
            for (option in selected) {
                if (overlapsAny(option.slots, exclusion.slots)) violations += "教学班 " + option.id + " 违反时间排除 " + exclusion.ref.id
            }
        }

        for (restriction in problem.hard.filterIsInstance<HardConstraint.CampusRestriction>()) {
            for (option in selected) {
                val bad = option.slots.any { slot ->
                    val campus = slot.location?.campusId
                    campus == null || campus !in restriction.allowedCampusIds
                }
                if (bad) violations += "教学班 " + option.id + " 违反校区限制 " + restriction.ref.id
            }
        }

        for (mutex in problem.hard.filterIsInstance<HardConstraint.MutualExclusion>()) {
            val count = mutex.offeringIds.count { it in byOffering.keys }
            if (count > 1) violations += "违反互斥 " + mutex.ref.id + "，同时选择了 " + count + " 门"
        }

        val fixed = problem.fixedOccupancy + TimelineOccupancyProjector.project(problem.fixedEvents, problem.timeGrid)
        val commutePolicy = problem.hard.filterIsInstance<HardConstraint.CommutePolicy>().firstOrNull()
        for (option in selected) {
            for (occupancy in fixed) {
                if (overlapsAny(option.slots, occupancy.slots)) {
                    violations += "教学班 " + option.id + " 与固定占用 " + occupancy.label + " 时间冲突"
                }
                if (commutePolicy != null) {
                    val badCommute = option.slots.any { candidateSlot ->
                        occupancy.slots.any { fixedSlot ->
                            CommuteRules.violates(problem.timeGrid, candidateSlot, fixedSlot, commutePolicy, problem.travel)
                        }
                    }
                    if (badCommute) violations += "教学班 " + option.id + " 与固定占用 " + occupancy.label + " 课间通勤不可行或证据不足"
                }
            }
        }

        for (leftIndex in selected.indices) {
            for (rightIndex in leftIndex + 1 until selected.size) {
                val left = selected[leftIndex]
                val right = selected[rightIndex]
                if (overlapsAny(left.slots, right.slots)) {
                    violations += "教学班 " + left.id + " 与 " + right.id + " 时间重叠"
                }
                if (commutePolicy != null && commuteViolation(problem, left, right, commutePolicy)) {
                    violations += "教学班 " + left.id + " 与 " + right.id + " 课间通勤不可行或证据不足"
                }
            }
        }

        val evaluated = SolutionCostEvaluator.evaluate(problem, selected)
        if (evaluated.totalCostMilli != solution.totalCostMilli) violations += "方案成本与独立重算不一致"
        return ScheduleVerificationReport(violations.distinct())
    }

    private fun overlapsAny(left: List<SolverClassSlot>, right: List<SolverClassSlot>): Boolean {
        for (a in left) {
            for (b in right) {
                if (a.sharesSectionWith(b) && a.weeks.intersects(b.weeks)) return true
            }
        }
        return false
    }

    private fun commuteViolation(
        problem: SelectionProblem,
        left: TeachingClassOption,
        right: TeachingClassOption,
        policy: HardConstraint.CommutePolicy
    ): Boolean {
        for (a in left.slots) {
            for (b in right.slots) {
                if (CommuteRules.violates(problem.timeGrid, a, b, policy, problem.travel)) return true
            }
        }
        return false
    }
}
