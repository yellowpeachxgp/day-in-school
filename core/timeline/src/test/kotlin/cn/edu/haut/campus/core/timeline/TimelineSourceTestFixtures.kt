package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.VerificationLevel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal object TimelineSourceTestFixtures {
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-opaque"),
        profileId = ProfileId("profile-opaque"),
        campusId = CampusId("lianhuajie")
    )
    val term = Term(
        id = "2026-2027-1",
        academicYear = "2026-2027",
        termNumber = 1,
        startDate = LocalDate.of(2026, 8, 31),
        weekStartDay = DayOfWeek.MONDAY,
        weekCount = 20,
        timeTableId = "test-timetable",
        current = true
    )

    fun provenance(accountId: AccountId = scope.accountId): DataProvenance = DataProvenance(
        providerId = "official-academic",
        schoolId = scope.schoolId,
        accountId = accountId,
        sourceSystem = "academic-system",
        completeness = SnapshotCompleteness.COMPLETE,
        trust = VerificationLevel.MOCK_TESTED,
        schemaVersion = 1,
        contentRevision = "fixture-v1"
    )

    fun defaultPeriodTimes(): List<PeriodTime> = listOf(
        PeriodTime("pt-1", term.timeTableId, 1, LocalTime.of(8, 30), LocalTime.of(9, 15)),
        PeriodTime("pt-2", term.timeTableId, 2, LocalTime.of(9, 20), LocalTime.of(10, 5)),
        PeriodTime("pt-3", term.timeTableId, 3, LocalTime.of(10, 25), LocalTime.of(11, 10)),
        PeriodTime("pt-4", term.timeTableId, 4, LocalTime.of(11, 15), LocalTime.of(12, 0)),
        PeriodTime("pt-5", term.timeTableId, 5, LocalTime.of(14, 30), LocalTime.of(15, 15)),
        PeriodTime("pt-6", term.timeTableId, 6, LocalTime.of(15, 20), LocalTime.of(16, 5)),
        PeriodTime("pt-7", term.timeTableId, 7, LocalTime.of(16, 25), LocalTime.of(17, 10)),
        PeriodTime("pt-8", term.timeTableId, 8, LocalTime.of(17, 15), LocalTime.of(18, 0)),
        PeriodTime("pt-9", term.timeTableId, 9, LocalTime.of(19, 30), LocalTime.of(20, 15)),
        PeriodTime("pt-10", term.timeTableId, 10, LocalTime.of(20, 25), LocalTime.of(21, 10)),
        PeriodTime("pt-11", term.timeTableId, 11, LocalTime.of(21, 15), LocalTime.of(22, 0)),
        PeriodTime("pt-12", term.timeTableId, 12, LocalTime.of(22, 5), LocalTime.of(22, 50))
    )

    fun course(
        id: String = "c-math",
        externalId: String? = "math-1",
        name: String = "高等数学"
    ): Course = Course(
        id = id,
        externalId = externalId,
        name = name,
        teacher = "测试教师",
        colorArgb = 0xFF053968,
        source = CourseSource.OFFICIAL_JW
    )

    fun rule(
        id: String = "r-math",
        courseId: String = "c-math",
        weekday: DayOfWeek = DayOfWeek.MONDAY,
        startPeriod: Int = 1,
        endPeriod: Int = 2,
        weeks: Set<Int> = setOf(1, 3),
        rawRoom: String = "W111"
    ): CourseScheduleRule = CourseScheduleRule(
        id = id,
        courseId = courseId,
        termId = term.id,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        weeks = weeks,
        rawRoom = rawRoom
    )

    fun scheduleSnapshot(
        revision: Long = 1,
        terms: List<Term> = listOf(term),
        courses: List<Course> = listOf(course()),
        rules: List<CourseScheduleRule> = listOf(rule()),
        periodTimes: List<PeriodTime> = defaultPeriodTimes(),
        calendarOverrides: List<cn.edu.haut.campus.core.model.CalendarOverride> = emptyList(),
        occurrenceOverrides: List<cn.edu.haut.campus.core.model.OccurrenceOverride> = emptyList(),
        provenance: DataProvenance = provenance()
    ): CourseScheduleTimelineSnapshot = CourseScheduleTimelineSnapshot(
        zone = zone,
        provenance = provenance,
        revision = revision,
        terms = terms,
        courses = courses,
        rules = rules,
        periodTimes = periodTimes,
        calendarOverrides = calendarOverrides,
        occurrenceOverrides = occurrenceOverrides
    )

    fun dayWindow(date: LocalDate, zoneId: ZoneId = zone): TimeWindow = TimeWindow(
        date.atStartOfDay(zoneId).toInstant(),
        date.plusDays(1).atStartOfDay(zoneId).toInstant()
    )

    fun instant(date: LocalDate, time: LocalTime, zoneId: ZoneId = zone): Instant =
        date.atTime(time).atZone(zoneId).toInstant()
}

internal inline fun <reified T : Throwable> assertSourceFailure(block: () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw error
    }
    throw AssertionError("预期抛出 " + T::class.java.name + "，但操作成功完成")
}
