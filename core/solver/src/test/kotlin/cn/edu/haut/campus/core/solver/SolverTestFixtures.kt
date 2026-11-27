package cn.edu.haut.campus.core.solver

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.selection.ClassCapacity
import cn.edu.haut.campus.core.selection.ClassSlot
import cn.edu.haut.campus.core.selection.TeachingWeeks
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal object SolverTestFixtures {
    val schoolId = SchoolId("synthetic")
    val accountId = AccountId("bench-account")
    val scope = SchoolAccountScope(schoolId, accountId, ProfileId("bench-profile"), CampusId("north"))
    val north = CampusId("north")
    val south = CampusId("south")

    fun provenance(recordId: String): DataProvenance = DataProvenance(
        providerId = "synthetic-generator",
        schoolId = schoolId,
        accountId = accountId,
        sourceSystem = "solver-unit-test",
        sourceRecordId = recordId,
        fetchedAt = Instant.parse("2026-03-01T00:00:00Z"),
        trust = VerificationLevel.MOCK_TESTED
    )

    fun grid(): TeachingTimeGrid {
        val times = listOf(
            LocalTime.of(8, 0) to LocalTime.of(8, 45),
            LocalTime.of(8, 50) to LocalTime.of(9, 35),
            LocalTime.of(9, 55) to LocalTime.of(10, 40),
            LocalTime.of(10, 45) to LocalTime.of(11, 30),
            LocalTime.of(13, 0) to LocalTime.of(13, 45),
            LocalTime.of(13, 50) to LocalTime.of(14, 35),
            LocalTime.of(14, 55) to LocalTime.of(15, 40),
            LocalTime.of(15, 45) to LocalTime.of(16, 30),
            LocalTime.of(16, 40) to LocalTime.of(17, 25),
            LocalTime.of(18, 30) to LocalTime.of(19, 15),
            LocalTime.of(19, 30) to LocalTime.of(20, 15),
            LocalTime.of(20, 20) to LocalTime.of(21, 5)
        )
        return TeachingTimeGrid(
            weekCount = 20,
            sectionsPerDay = 12,
            firstWeekMonday = LocalDate.of(2026, 3, 2),
            zone = ZoneId.of("Asia/Shanghai"),
            periodTimes = times.mapIndexed { index, pair ->
                val section = index + 1
                section to PeriodTime(section, pair.first, pair.second)
            }.toMap()
        )
    }

    fun location(campus: CampusId = north, building: String = "A01"): SolverLocation =
        SolverLocation(campusId = campus, buildingId = building, room = "101")

    fun matrix(defaultMinutes: Int = 5): TravelTimeMatrix = TravelTimeMatrix { from, to ->
        when {
            from.buildingId == to.buildingId && from.campusId == to.campusId -> TravelTimeEstimate.Reachable(0)
            from.campusId != null && from.campusId == to.campusId -> TravelTimeEstimate.Reachable(defaultMinutes)
            else -> TravelTimeEstimate.Reachable(45)
        }
    }

    fun slot(
        weekday: Int,
        start: Int,
        end: Int,
        weeks: TeachingWeeks = TeachingWeeks.range(1, 16),
        location: SolverLocation? = location()
    ): SolverClassSlot = SolverClassSlot(
        time = ClassSlot(weekday, start, end, weeks, location?.room),
        location = location
    )

    fun option(
        id: String,
        offeringId: String,
        credits: String = "2",
        slots: List<SolverClassSlot> = listOf(slot(1, 1, 2)),
        capacity: ClassCapacity = ClassCapacity.Known(total = 30, selected = 10),
        courseName: String = "课程" + offeringId
    ): TeachingClassOption = TeachingClassOption(
        id = id,
        offeringId = offeringId,
        courseName = courseName,
        credits = BigDecimal(credits),
        slots = slots,
        capacity = capacity,
        provenance = provenance(id)
    )

    fun problem(
        candidates: List<TeachingClassOption>,
        hard: List<HardConstraint>,
        creditLower: String = "0",
        creditUpper: String = "30",
        soft: List<WeightedPreference> = emptyList(),
        fixed: List<FixedOccupancy> = emptyList(),
        travel: TravelTimeMatrix = matrix()
    ): SelectionProblem = SelectionProblem(
        candidates = candidates,
        hard = hard,
        soft = soft,
        travel = travel,
        creditRange = BigDecimal(creditLower)..BigDecimal(creditUpper),
        scope = scope,
        timeGrid = grid(),
        fixedOccupancy = fixed
    )
}
