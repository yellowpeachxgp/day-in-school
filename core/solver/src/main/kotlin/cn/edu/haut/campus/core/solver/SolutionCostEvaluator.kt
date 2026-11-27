package cn.edu.haut.campus.core.solver

import cn.edu.haut.campus.core.selection.CapacityVerdict
import java.time.Duration
import java.time.LocalTime

internal data class EvaluatedCost(
    val totalCostMilli: Long,
    val breakdown: List<CostBreakdownItem>,
    val uncertainties: List<SolverUncertainty>
)

/** 独立成本计算器：完整方案验收时重算，搜索中的下界只使用其中可接纳的一元部分。 */
internal object SolutionCostEvaluator {

    fun evaluate(problem: SelectionProblem, selected: List<TeachingClassOption>): EvaluatedCost {
        val items = ArrayList<CostBreakdownItem>()
        var total = 0L
        val occurrences = expandOccurrences(problem, selected)

        for (preference in problem.soft) {
            when (preference) {
                is WeightedPreference.EarlySection -> {
                    val count = selected.sumOf { option -> option.slots.count { it.startSection <= preference.latestEarlySection } }
                    val cost = Math.multiplyExact(count.toLong(), preference.costPerOccurrenceMilli)
                    total = Math.addExact(total, cost)
                    items += CostBreakdownItem(preference.id, preference.label, cost, "早课时段 $count 次")
                }
                is WeightedPreference.LateSection -> {
                    val count = selected.sumOf { option -> option.slots.count { it.endSection >= preference.firstLateSection } }
                    val cost = Math.multiplyExact(count.toLong(), preference.costPerOccurrenceMilli)
                    total = Math.addExact(total, cost)
                    items += CostBreakdownItem(preference.id, preference.label, cost, "晚课时段 $count 次")
                }
                is WeightedPreference.GapTime -> {
                    val minutes = gapMinutes(occurrences)
                    val cost = ceilDiv(Math.multiplyExact(minutes, preference.costPerHourMilli), 60L)
                    total = Math.addExact(total, cost)
                    items += CostBreakdownItem(preference.id, preference.label, cost, "空档 $minutes 分钟")
                }
                is WeightedPreference.TravelTime -> {
                    val minutes = travelMinutes(problem, occurrences)
                    val cost = Math.multiplyExact(minutes, preference.costPerMinuteMilli)
                    total = Math.addExact(total, cost)
                    items += CostBreakdownItem(preference.id, preference.label, cost, "已知通勤 $minutes 分钟")
                }
                is WeightedPreference.DailyLoad -> {
                    val extra = extraSections(problem, occurrences, preference.maxSectionsPerDay)
                    val cost = Math.multiplyExact(extra, preference.costPerExtraSectionMilli)
                    total = Math.addExact(total, cost)
                    items += CostBreakdownItem(preference.id, preference.label, cost, "超过每日 " + preference.maxSectionsPerDay + " 节共 " + extra + " 节")
                }
                is WeightedPreference.UnknownCapacityRisk -> {
                    val count = selected.count { it.capacityVerdict() == CapacityVerdict.UNKNOWN }
                    val cost = Math.multiplyExact(count.toLong(), preference.costPerClassMilli)
                    total = Math.addExact(total, cost)
                    items += CostBreakdownItem(preference.id, preference.label, cost, "容量未知教学班 $count 个")
                }
                is WeightedPreference.UnknownWeeksRisk -> {
                    val count = selected.count { option -> option.slots.any { !it.weeks.isKnown } }
                    val cost = Math.multiplyExact(count.toLong(), preference.costPerClassMilli)
                    total = Math.addExact(total, cost)
                    items += CostBreakdownItem(preference.id, preference.label, cost, "周次未知教学班 $count 个")
                }
                is WeightedPreference.CandidatePenalty -> {
                    val count = selected.count { it.id in preference.candidateIds }
                    val cost = Math.multiplyExact(count.toLong(), preference.costPerOccurrenceMilli)
                    total = Math.addExact(total, cost)
                    items += CostBreakdownItem(preference.id, preference.label, cost, "受惩罚候选 $count 个")
                }
            }
        }
        return EvaluatedCost(total, items, uncertainties(problem, selected, occurrences))
    }

    private data class Occurrence(
        val week: Int,
        val weekday: Int,
        val startSection: Int,
        val endSection: Int,
        val startMinute: Long,
        val endMinute: Long,
        val option: TeachingClassOption,
        val slot: SolverClassSlot
    )

    private fun expandOccurrences(problem: SelectionProblem, selected: List<TeachingClassOption>): List<Occurrence> {
        val result = ArrayList<Occurrence>()
        for (option in selected) {
            for (slot in option.slots) {
                val weeks = if (slot.weeks.isKnown) slot.weeks.weeks else (1..problem.timeGrid.weekCount).toSet()
                for (week in weeks) {
                    val start = minuteOf(problem.timeGrid.period(slot.startSection).start)
                    val end = minuteOf(problem.timeGrid.period(slot.endSection).end)
                    result += Occurrence(week, slot.weekday, slot.startSection, slot.endSection, start, end, option, slot)
                }
            }
        }
        return result.sortedWith(compareBy<Occurrence> { it.week }.thenBy { it.weekday }.thenBy { it.startMinute }.thenBy { it.option.id })
    }

    private fun minuteOf(time: LocalTime): Long = Duration.between(LocalTime.MIDNIGHT, time).toMinutes()

    private fun adjacentPairs(occurrences: List<Occurrence>): List<Pair<Occurrence, Occurrence>> {
        val grouped = occurrences.groupBy { it.week to it.weekday }
        val result = ArrayList<Pair<Occurrence, Occurrence>>()
        for ((_, dayOccurrences) in grouped) {
            val sorted = dayOccurrences.sortedWith(compareBy<Occurrence> { it.startMinute }.thenBy { it.endMinute }.thenBy { it.option.id })
            var previous: Occurrence? = null
            for (current in sorted) {
                val last = previous
                if (last != null && current.startMinute >= last.endMinute) {
                    result += last to current
                    previous = current
                } else if (last != null && current.endMinute > last.endMinute) {
                    previous = current
                } else if (last == null) {
                    previous = current
                }
            }
        }
        return result
    }

    private fun gapMinutes(occurrences: List<Occurrence>): Long =
        adjacentPairs(occurrences).sumOf { (left, right) -> (right.startMinute - left.endMinute).coerceAtLeast(0L) }

    private fun travelMinutes(problem: SelectionProblem, occurrences: List<Occurrence>): Long {
        var total = 0L
        for ((left, right) in adjacentPairs(occurrences)) {
            val from = left.slot.location
            val to = right.slot.location
            if (from == null || to == null) continue
            val estimate = problem.travel.estimate(from, to)
            if (estimate is TravelTimeEstimate.Reachable) total = Math.addExact(total, estimate.minutes.toLong())
        }
        return total
    }

    private fun extraSections(problem: SelectionProblem, occurrences: List<Occurrence>, maxSections: Int): Long {
        var total = 0L
        val grouped = occurrences.groupBy { it.week to it.weekday }
        for ((_, dayOccurrences) in grouped) {
            val used = BooleanArray(problem.timeGrid.sectionsPerDay + 1)
            for (occurrence in dayOccurrences) {
                for (section in occurrence.startSection..occurrence.endSection) used[section] = true
            }
            val count = used.count { it }
            if (count > maxSections) total = Math.addExact(total, (count - maxSections).toLong())
        }
        return total
    }

    private fun uncertainties(
        problem: SelectionProblem,
        selected: List<TeachingClassOption>,
        occurrences: List<Occurrence>
    ): List<SolverUncertainty> {
        val result = ArrayList<SolverUncertainty>()
        val unknownCapacity = selected.filter { it.capacityVerdict() == CapacityVerdict.UNKNOWN }.map { it.id }
        if (unknownCapacity.isNotEmpty()) {
            result += SolverUncertainty(UncertaintyKind.CAPACITY, "容量未知，未被改写成有余量", unknownCapacity)
        }
        val unknownWeeks = selected.filter { option -> option.slots.any { !it.weeks.isKnown } }.map { it.id }
        if (unknownWeeks.isNotEmpty()) {
            result += SolverUncertainty(UncertaintyKind.WEEKS, "周次未知，冲突检查已按可能重叠保守处理", unknownWeeks)
        }
        val unknownLocation = selected.filter { option -> option.slots.any { it.location?.hasCampusAndBuilding != true } }.map { it.id }
        if (unknownLocation.isNotEmpty()) {
            result += SolverUncertainty(UncertaintyKind.LOCATION, "楼宇或校区未知，通勤证据不足", unknownLocation)
        }
        val missingProvenance = selected.filter { it.provenance == null }.map { it.id }
        if (missingProvenance.isNotEmpty()) {
            result += SolverUncertainty(UncertaintyKind.PROVENANCE, "候选缺少 DataProvenance 来源证据", missingProvenance)
        }
        val commutePolicy = problem.hard.filterIsInstance<HardConstraint.CommutePolicy>().firstOrNull()
        if (commutePolicy?.unknownTravel == UnknownTravelPolicy.ALLOW_WITH_WARNING) {
            val unknownPairs = adjacentPairs(occurrences).count { (left, right) ->
                val from = left.slot.location
                val to = right.slot.location
                from == null || to == null || problem.travel.estimate(from, to) == TravelTimeEstimate.Unknown
            }
            if (unknownPairs > 0) {
                result += SolverUncertainty(
                    UncertaintyKind.TRAVEL,
                    "有 " + unknownPairs + " 个相邻课程通勤估计未知；用户已显式允许降级",
                    selected.map { it.id }
                )
            }
        }
        return result.distinct()
    }

    private fun ceilDiv(value: Long, divisor: Long): Long = if (value == 0L) 0L else ((value - 1L) / divisor) + 1L
}
