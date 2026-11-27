package cn.edu.haut.campus.core.solver

import java.time.Duration

/** 课间通勤二元规则；搜索编译与独立 verifier 共用，避免两套语义漂移。 */
internal object CommuteRules {

    fun violates(
        grid: TeachingTimeGrid,
        left: SolverClassSlot,
        right: SolverClassSlot,
        policy: HardConstraint.CommutePolicy,
        travel: TravelTimeMatrix
    ): Boolean {
        if (left.weekday != right.weekday || !left.weeks.intersects(right.weeks)) return false
        if (left.sharesSectionWith(right)) return false
        val earlier = if (left.endSection < right.startSection) left else right
        val later = if (earlier === left) right else left
        if (earlier.endSection >= later.startSection) return false
        val end = grid.period(earlier.endSection).end
        val start = grid.period(later.startSection).start
        val gap = Duration.between(end, start).toMinutes()
        val from = earlier.location
        val to = later.location
        val estimate = if (from == null || to == null) TravelTimeEstimate.Unknown else travel.estimate(from, to)
        return when (estimate) {
            is TravelTimeEstimate.Reachable -> gap < estimate.minutes.toLong() + policy.bufferMinutes
            TravelTimeEstimate.NotWithinBreak -> true
            TravelTimeEstimate.Unknown -> policy.unknownTravel == UnknownTravelPolicy.REJECT
        }
    }
}
