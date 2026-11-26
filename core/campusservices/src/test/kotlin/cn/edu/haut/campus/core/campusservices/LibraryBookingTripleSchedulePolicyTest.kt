package cn.edu.haut.campus.core.campusservices

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryBookingTripleSchedulePolicyTest {
    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun plannerAccepts0700And212959ButRejects065959And2130() {
        val targetDate = LocalDate.of(2026, 11, 3)
        val now = epochMs(targetDate.minusDays(1), LocalTime.of(20, 0), shanghai)

        listOf("07:00:00", "21:29:59").forEach { target ->
            val targetTime = DualEngineReservationPlanner.parseTargetLocalTime(target)
            val schedule = DualEngineReservationPlanner.computeSchedule(
                planId = "plan_boundary_$target",
                targetDate = targetDate,
                targetTime = targetTime,
                nowEpochMillis = now
            )
            assertEquals(
                epochMs(targetDate, targetTime, shanghai),
                schedule.fireAtEpochMillis
            )
            assertEquals(
                LibraryBookingTripleSchedulePolicy.expiresAtEpochMillis(
                    targetLocalDate = targetDate,
                    timezoneId = shanghai,
                    fireAtEpochMillis = schedule.fireAtEpochMillis
                ),
                schedule.expiresAtEpochMillis
            )
        }

        listOf("06:59:59", "21:30:00").forEach { invalid ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                DualEngineReservationPlanner.computeSchedule(
                    planId = "plan_invalid_$invalid",
                    targetDate = targetDate,
                    targetTime = DualEngineReservationPlanner.parseTargetLocalTime(invalid),
                    nowEpochMillis = now
                )
            }
            assertTrue(error.message.orEmpty().contains("窗口"))
        }
    }

    @Test
    fun derivedStageWindowsHandOffAndFireExpiryIsInclusive() {
        val targetDate = LocalDate.of(2026, 11, 3)
        val targetTime = LocalTime.of(8, 0)
        val fireAt = epochMs(targetDate, targetTime, shanghai)
        val facts = facts(
            executionMode = BookingExecutionMode.LOCAL_DEVICE,
            targetLocalDate = targetDate,
            targetLocalTime = targetTime,
            timezoneId = shanghai,
            fireAtEpochMillis = fireAt
        )
        val precheckAt = fireAt - 30 * 60_000L
        val finalCheckAt = fireAt - 5 * 60_000L
        val expiresAt = fireAt + 30 * 60_000L

        val windows = LibraryBookingTripleSchedulePolicy.deriveWindows(facts)
        assertEquals(precheckAt, windows.precheck.opensAtEpochMillis)
        assertEquals(finalCheckAt, windows.precheck.closesAtEpochMillis)
        assertEquals(finalCheckAt, windows.finalCheck.opensAtEpochMillis)
        assertEquals(fireAt, windows.finalCheck.closesAtEpochMillis)
        assertEquals(fireAt, windows.fire.opensAtEpochMillis)
        assertEquals(expiresAt, windows.fire.closesAtEpochMillis)
        assertEquals(expiresAt, windows.expiresAtEpochMillis)

        assertDecision(
            LibraryBookingStageGateDecision.NOT_DUE,
            facts, TripleScheduleStage.PRECHECK, precheckAt - 1
        )
        assertDecision(
            LibraryBookingStageGateDecision.DUE,
            facts, TripleScheduleStage.PRECHECK, precheckAt
        )
        assertDecision(
            LibraryBookingStageGateDecision.DUE,
            facts, TripleScheduleStage.PRECHECK, finalCheckAt - 1
        )
        assertDecision(
            LibraryBookingStageGateDecision.STAGE_WINDOW_CLOSED,
            facts, TripleScheduleStage.PRECHECK, finalCheckAt
        )

        assertDecision(
            LibraryBookingStageGateDecision.NOT_DUE,
            facts, TripleScheduleStage.FINAL_CHECK, finalCheckAt - 1
        )
        assertDecision(
            LibraryBookingStageGateDecision.DUE,
            facts, TripleScheduleStage.FINAL_CHECK, finalCheckAt
        )
        assertDecision(
            LibraryBookingStageGateDecision.DUE,
            facts, TripleScheduleStage.FINAL_CHECK, fireAt - 1
        )
        assertDecision(
            LibraryBookingStageGateDecision.STAGE_WINDOW_CLOSED,
            facts, TripleScheduleStage.FINAL_CHECK, fireAt
        )

        assertDecision(
            LibraryBookingStageGateDecision.NOT_DUE,
            facts, TripleScheduleStage.FIRE, fireAt - 1
        )
        assertDecision(
            LibraryBookingStageGateDecision.DUE,
            facts, TripleScheduleStage.FIRE, fireAt
        )
        assertDecision(
            LibraryBookingStageGateDecision.DUE,
            facts, TripleScheduleStage.FIRE, expiresAt - 1
        )
        assertDecision(
            LibraryBookingStageGateDecision.DUE,
            facts, TripleScheduleStage.FIRE, expiresAt
        )
        assertDecision(
            LibraryBookingStageGateDecision.FIRE_WINDOW_CLOSED,
            facts, TripleScheduleStage.FIRE, expiresAt + 1
        )
    }

    @Test
    fun expiryIsClampedAtSchoolWindowEndAndCutoffIsInclusive() {
        val targetDate = LocalDate.of(2026, 11, 3)
        val targetTime = LocalTime.of(21, 29, 59)
        val fireAt = epochMs(targetDate, targetTime, shanghai)
        val schoolEndAt = epochMs(targetDate, LocalTime.of(21, 30), shanghai)
        val facts = facts(
            executionMode = BookingExecutionMode.LOCAL_DEVICE,
            targetLocalDate = targetDate,
            targetLocalTime = targetTime,
            timezoneId = shanghai,
            fireAtEpochMillis = fireAt
        )

        val windows = LibraryBookingTripleSchedulePolicy.deriveWindows(facts)
        assertEquals(schoolEndAt, windows.expiresAtEpochMillis)
        assertEquals(schoolEndAt, windows.fire.closesAtEpochMillis)
        assertDecision(
            LibraryBookingStageGateDecision.DUE,
            facts, TripleScheduleStage.FIRE, schoolEndAt - 1
        )
        assertDecision(
            LibraryBookingStageGateDecision.DUE,
            facts, TripleScheduleStage.FIRE, schoolEndAt
        )
        assertDecision(
            LibraryBookingStageGateDecision.FIRE_WINDOW_CLOSED,
            facts, TripleScheduleStage.FIRE, schoolEndAt + 1
        )
    }

    @Test
    fun zoneAwareInstantsCrossUtcDatesWithoutUsingSystemDefaultZone() {
        val shanghaiDate = LocalDate.of(2026, 11, 3)
        val shanghaiFire = epochMs(shanghaiDate, LocalTime.of(7, 0), shanghai)
        assertEquals("2026-11-02T23:00:00Z", Instant.ofEpochMilli(shanghaiFire).toString())
        assertEquals(
            "2026-11-02T23:30:00Z",
            Instant.ofEpochMilli(
                LibraryBookingTripleSchedulePolicy.expiresAtEpochMillis(
                    targetLocalDate = shanghaiDate,
                    timezoneId = shanghai,
                    fireAtEpochMillis = shanghaiFire
                )
            ).toString()
        )

        val newYork = ZoneId.of("America/New_York")
        val newYorkDate = LocalDate.of(2026, 11, 3)
        val newYorkFire = epochMs(newYorkDate, LocalTime.of(21, 0), newYork)
        assertEquals("2026-11-04T02:00:00Z", Instant.ofEpochMilli(newYorkFire).toString())
        assertEquals(
            "2026-11-04T02:30:00Z",
            Instant.ofEpochMilli(
                LibraryBookingTripleSchedulePolicy.expiresAtEpochMillis(
                    targetLocalDate = newYorkDate,
                    timezoneId = newYork,
                    fireAtEpochMillis = newYorkFire
                )
            ).toString()
        )
    }

    @Test
    fun cloudHostedIsNeverDueForLocalExecutionEvenInsideFireWindow() {
        val targetDate = LocalDate.of(2026, 11, 3)
        val targetTime = LocalTime.of(8, 0)
        val fireAt = epochMs(targetDate, targetTime, shanghai)
        val cloudFacts = facts(
            executionMode = BookingExecutionMode.CLOUD_HOSTED,
            targetLocalDate = targetDate,
            targetLocalTime = targetTime,
            timezoneId = shanghai,
            fireAtEpochMillis = fireAt
        )

        for (stage in TripleScheduleStage.entries) {
            val window = LibraryBookingTripleSchedulePolicy.deriveWindows(cloudFacts).windowFor(stage)
            assertDecision(
                LibraryBookingStageGateDecision.CLOUD_HOSTED_NOT_LOCAL,
                cloudFacts, stage, window.opensAtEpochMillis
            )
        }

        val localFacts = cloudFacts.copy(executionMode = BookingExecutionMode.LOCAL_DEVICE)
        val manualFacts = cloudFacts.copy(executionMode = BookingExecutionMode.MANUAL_REMINDER)
        assertDecision(LibraryBookingStageGateDecision.DUE, localFacts, TripleScheduleStage.FIRE, fireAt)
        assertDecision(LibraryBookingStageGateDecision.DUE, manualFacts, TripleScheduleStage.FIRE, fireAt)
    }

    @Test
    fun gateRejectsDirectlyConstructedFactsOutsideSchoolWindow() {
        val targetDate = LocalDate.of(2026, 11, 3)
        listOf(LocalTime.of(6, 59, 59), LocalTime.of(21, 30, 0)).forEach { targetTime ->
            val fireAt = epochMs(targetDate, targetTime, shanghai)
            val facts = facts(
                executionMode = BookingExecutionMode.LOCAL_DEVICE,
                targetLocalDate = targetDate,
                targetLocalTime = targetTime,
                timezoneId = shanghai,
                fireAtEpochMillis = fireAt
            )
            assertDecision(
                LibraryBookingStageGateDecision.TARGET_OUTSIDE_SCHOOL_WINDOW,
                facts, TripleScheduleStage.FIRE, fireAt
            )
        }
    }

    private fun assertDecision(
        expected: LibraryBookingStageGateDecision,
        facts: LibraryBookingTripleScheduleFacts,
        stage: TripleScheduleStage,
        atEpochMillis: Long
    ) {
        assertEquals(
            "stage=$stage at=$atEpochMillis",
            expected,
            LibraryBookingTripleSchedulePolicy.evaluateLocalStage(facts, stage, atEpochMillis).decision
        )
    }

    private fun facts(
        executionMode: BookingExecutionMode,
        targetLocalDate: LocalDate,
        targetLocalTime: LocalTime,
        timezoneId: ZoneId,
        fireAtEpochMillis: Long
    ) = LibraryBookingTripleScheduleFacts(
        executionMode = executionMode,
        targetLocalDate = targetLocalDate,
        targetLocalTime = targetLocalTime,
        timezoneId = timezoneId,
        fireAtEpochMillis = fireAtEpochMillis
    )

    private fun epochMs(date: LocalDate, time: LocalTime, zoneId: ZoneId): Long =
        ZonedDateTime.of(date, time, zoneId).toInstant().toEpochMilli()
}
