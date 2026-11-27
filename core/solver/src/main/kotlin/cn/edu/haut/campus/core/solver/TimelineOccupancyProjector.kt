package cn.edu.haut.campus.core.solver

import cn.edu.haut.campus.core.selection.ClassSlot
import cn.edu.haut.campus.core.selection.TeachingWeeks
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.TimelineEvent

/** 把统一时间流事件投影到显式教学周网格；投影失败时返回空占用，绝不猜测日期或作息。 */
object TimelineOccupancyProjector {

    fun project(events: List<TimelineEvent>, grid: TeachingTimeGrid): List<FixedOccupancy> =
        events.mapNotNull { event -> projectOne(event, grid) }

    private fun projectOne(event: TimelineEvent, grid: TeachingTimeGrid): FixedOccupancy? {
        if (event.busy == BusyLevel.FREE || event.status == EventStatus.CANCELLED_TODAY) return null
        val slots = ArrayList<SolverClassSlot>()
        val location = event.place?.let { place ->
            SolverLocation(
                campusId = place.campusId,
                buildingId = place.id,
                room = null
            )
        }
        for (week in 1..grid.weekCount) {
            for (weekday in 1..7) {
                for (section in 1..grid.sectionsPerDay) {
                    val window = grid.windowFor(week, weekday, section)
                    if (event.overlaps(window)) {
                        slots += SolverClassSlot(
                            time = ClassSlot(
                                weekday = weekday,
                                startSection = section,
                                endSection = section,
                                weeks = TeachingWeeks.of(setOf(week)),
                                location = location?.room
                            ),
                            location = location
                        )
                    }
                }
            }
        }
        if (slots.isEmpty()) return null
        return FixedOccupancy(
            id = event.id.value,
            label = event.title,
            slots = mergeAdjacent(slots),
            provenance = event.provenance
        )
    }

    /** 把同一星期/周次/地点的连续单节合并，降低后续两两比较成本。 */
    private fun mergeAdjacent(input: List<SolverClassSlot>): List<SolverClassSlot> {
        val sorted = input.sortedWith(
            compareBy<SolverClassSlot> { it.weekday }
                .thenBy { it.weeks.weeks.firstOrNull() ?: 0 }
                .thenBy { it.startSection }
        )
        val output = ArrayList<SolverClassSlot>()
        for (slot in sorted) {
            val last = output.lastOrNull()
            val sameWeeks = last != null && last.weeks.isKnown && slot.weeks.isKnown && last.weeks.weeks == slot.weeks.weeks
            if (last != null && last.weekday == slot.weekday && sameWeeks &&
                last.endSection + 1 == slot.startSection && last.location == slot.location
            ) {
                output[output.lastIndex] = SolverClassSlot(
                    time = ClassSlot(
                        weekday = last.weekday,
                        startSection = last.startSection,
                        endSection = slot.endSection,
                        weeks = last.weeks,
                        location = last.time.location
                    ),
                    location = last.location
                )
            } else {
                output += slot
            }
        }
        return output
    }
}
