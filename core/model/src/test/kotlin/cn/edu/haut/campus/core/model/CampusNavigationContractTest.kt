package cn.edu.haut.campus.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CampusNavigationContractTest {
    @Test
    fun geoJsonWestOfNinetyDegreesStillUsesLongitudeFirst() {
        val zone = CustomCampusZoneCodec.decodeSingleOrList(
            """{"coordinates":[[87.6,43.8],[87.7,43.8],[87.7,43.9]],"campusName":"test"}"""
        ).single()
        assertEquals(43.8, zone.polygonVertices.first().latitude, 0.00001)
        assertEquals(87.6, zone.polygonVertices.first().longitude, 0.00001)
    }


    @Test
    fun `bottom tab routes resolve from index and route id`() {
        assertEquals(CampusBottomTabRoute.TODAY, CampusBottomTabRoute.fromIndex(0))
        assertEquals(CampusBottomTabRoute.TIMETABLE, CampusBottomTabRoute.fromIndex(1))
        assertEquals(CampusBottomTabRoute.GRADES, CampusBottomTabRoute.fromIndex(2))
        assertEquals(CampusBottomTabRoute.MAP, CampusBottomTabRoute.fromIndex(3))
        assertEquals(CampusBottomTabRoute.SETTINGS, CampusBottomTabRoute.fromIndex(4))
        assertEquals(CampusBottomTabRoute.TODAY, CampusBottomTabRoute.fromIndex(99))

        assertEquals(CampusBottomTabRoute.MAP, CampusBottomTabRoute.fromRouteId("map"))
        assertEquals(null, CampusBottomTabRoute.fromRouteId("unknown"))
    }

    @Test
    fun `home card module normalizes raw order and appends missing defaults`() {
        val normalized = HomeCardModule.normalizeOrderIds(listOf("PARCEL_HUB", "invalid_id", "DORM_ELECTRICITY", "PARCEL_HUB"))
        assertEquals("PARCEL_HUB", normalized[0])
        assertEquals("DORM_ELECTRICITY", normalized[1])
        assertEquals(HomeCardModule.defaultOrderIds.size, normalized.size)

        val fallback = HomeCardModule.normalizeOrderIds(emptyList())
        assertEquals(HomeCardModule.defaultOrderIds, fallback)
        assertEquals(HomeCardModule.COMPETITION_GUIDE, HomeCardModule.fromId("COMPETITION_GUIDE"))
        assertEquals("合作社团", HomeCardModule.CAMPUS_COMMUNITY.title)
    }

    @Test
    fun `theme visual defaults clamp opacity and blur radius safely`() {
        assertEquals(ThemeVisualDefaults.MIN_GLASS_OPACITY, ThemeVisualDefaults.clampGlassOpacity(0.05f), 1e-4f)
        assertEquals(ThemeVisualDefaults.MAX_GLASS_OPACITY, ThemeVisualDefaults.clampGlassOpacity(1.5f), 1e-4f)
        assertEquals(0.72f, ThemeVisualDefaults.clampGlassOpacity(0.72f), 1e-4f)

        assertEquals(ThemeVisualDefaults.MIN_BLUR_RADIUS_DP, ThemeVisualDefaults.clampBlurRadiusDp(-3f), 1e-4f)
        assertEquals(ThemeVisualDefaults.MAX_BLUR_RADIUS_DP, ThemeVisualDefaults.clampBlurRadiusDp(99f), 1e-4f)
        assertEquals(20f, ThemeVisualDefaults.clampBlurRadiusDp(20f), 1e-4f)
    }

    @Test
    fun `academic term resolver computes autumn and spring terms`() {
        val autumnDate = LocalDate.of(2026, 10, 15)
        assertEquals("2026", AcademicTermResolver.defaultAcademicYearCode(autumnDate))
        assertEquals("3", AcademicTermResolver.defaultSemesterCode(autumnDate))
        assertEquals("2026-2027-1", AcademicTermResolver.defaultTermId(autumnDate))

        val springDate = LocalDate.of(2026, 4, 15)
        assertEquals("2025", AcademicTermResolver.defaultAcademicYearCode(springDate))
        assertEquals("12", AcademicTermResolver.defaultSemesterCode(springDate))
        assertEquals("2025-2026-2", AcademicTermResolver.defaultTermId(springDate))

        assertEquals(listOf("2026", "2025", "2024", "2023"), AcademicTermResolver.recentAcademicYears(4, autumnDate))
    }

    @Test
    fun `focus session aggregation computes today and total minutes`() {
        val targetDate = LocalDate.of(2026, 11, 6)
        val targetEpochMs = targetDate.atStartOfDay(HAUT_ZONE_ID).plusHours(10).toInstant().toEpochMilli()
        val prevDayEpochMs = targetDate.minusDays(1).atStartOfDay(HAUT_ZONE_ID).plusHours(15).toInstant().toEpochMilli()

        val sessions = listOf(
            FocusSessionDomain(
                id = "s1",
                generation = 1,
                durationMinutes = 25,
                startedAtEpochMs = targetEpochMs - 1_500_000L,
                expectedEndAtEpochMs = targetEpochMs,
                completedAtEpochMs = targetEpochMs,
                status = FocusSessionStatus.COMPLETED
            ),
            FocusSessionDomain(
                id = "s2",
                generation = 2,
                durationMinutes = 45,
                startedAtEpochMs = prevDayEpochMs - 2_700_000L,
                expectedEndAtEpochMs = prevDayEpochMs,
                completedAtEpochMs = prevDayEpochMs,
                status = FocusSessionStatus.COMPLETED
            )
        )

        assertEquals(25, sessions.todayFocusMinutes(targetDate))
        assertEquals(70, sessions.totalFocusMinutes())
        assertTrue(sessions.todayFocusMinutes(targetDate) < sessions.totalFocusMinutes())
        assertFalse(sessions.isEmpty())
    }
}
