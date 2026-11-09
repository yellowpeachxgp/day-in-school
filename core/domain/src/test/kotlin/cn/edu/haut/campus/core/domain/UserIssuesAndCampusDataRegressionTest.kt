package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.Course
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.CustomCampusZone
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.Term
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class UserIssuesAndCampusDataRegressionTest {

    @Test
    fun `Issue 5 - closed 2nd and 3rd canteens are excluded from operating recommendations`() {
        val operatingIds = CampusDiningRecommender.OPERATING_CANTEENS.map { it.buildingId }.toSet()
        assertFalse("2nd canteen must not be in operating canteens", "b_canteen2" in operatingIds)
        assertFalse("3rd canteen must not be in operating canteens", "b_canteen3" in operatingIds)
        assertTrue("1st canteen must remain operating", "b_canteen1" in operatingIds)
        assertTrue("4th canteen must remain operating", "b_canteen4" in operatingIds)
        assertTrue("Small canteen must remain operating", "b_canteen_small" in operatingIds)

        val rec = CampusDiningRecommender.buildRecommendation(
            date = LocalDate.of(2026, 11, 9),
            now = Instant.parse("2026-11-09T03:30:00Z"),
            occurrences = emptyList(),
            buildings = emptyList()
        )
        assertTrue(rec.allCandidates.none { it.profile.buildingId in setOf("b_canteen2", "b_canteen3") })
    }

    @Test
    fun `Issue 7 - single-day canceled occurrence is recoverable via resolveCanceledOccurrencesForDate`() {
        val term = Term(
            id = "2026-2027-1",
            academicYear = "2026-2027",
            termNumber = 1,
            startDate = LocalDate.of(2026, 8, 31),
            weekStartDay = DayOfWeek.MONDAY,
            weekCount = 20,
            timeTableId = "tt_default",
            current = true
        )
        val course = Course(
            id = "c_alg",
            externalId = "JW_01",
            name = "算法设计与分析",
            teacher = "王教授",
            colorArgb = 0xFF053968,
            credit = null,
            source = CourseSource.OFFICIAL_JW,
            userModified = false,
            lastSyncAt = Instant.now()
        )
        val rule = CourseScheduleRule(
            id = "r_alg_mon",
            courseId = "c_alg",
            termId = "2026-2027-1",
            weekday = DayOfWeek.MONDAY,
            startPeriod = 1,
            endPeriod = 2,
            weeks = (1..16).toSet(),
            rawRoom = "31号楼31101",
            buildingId = "b_t1",
            userModified = false
        )
        val periods = listOf(
            PeriodTime("pt_1", "tt_default", 1, LocalTime.of(8, 0), LocalTime.of(8, 45), "上午"),
            PeriodTime("pt_2", "tt_default", 2, LocalTime.of(8, 55), LocalTime.of(9, 40), "上午")
        )
        val targetDate = LocalDate.of(2026, 9, 7) // Week 2 Monday
        val cancelOverride = OccurrenceOverride(
            id = "ov_cancel_1",
            termId = "2026-2027-1",
            courseRuleId = "r_alg_mon",
            courseId = "c_alg",
            date = targetDate,
            canceled = true,
            reason = "单次停课"
        )

        val activeOnDate = ScheduleEngine.resolveOccurrencesForDate(
            date = targetDate,
            term = term,
            courses = listOf(course),
            rules = listOf(rule),
            periodTimes = periods,
            occurrenceOverrides = listOf(cancelOverride)
        )
        assertTrue("Canceled class must be hidden from active occurrences", activeOnDate.isEmpty())

        val canceledOnDate = ScheduleEngine.resolveCanceledOccurrencesForDate(
            date = targetDate,
            term = term,
            courses = listOf(course),
            rules = listOf(rule),
            periodTimes = periods,
            occurrenceOverrides = listOf(cancelOverride)
        )
        assertEquals(1, canceledOnDate.size)
        assertEquals("算法设计与分析", canceledOnDate.first().courseName)
        assertEquals("ov_cancel_1", canceledOnDate.first().overrideId)
    }

    @Test
    fun `Issue 10 and 16 - Songshan Road Campus geofence and Computer Service Club F01 W111 venue`() {
        // #10: Songshan Road Campus WGS-84 center (34.741905, 113.620066) must be inside HAUT_SONGSHANLU_POLYGON
        assertTrue(
            CustomCampusZone.isPointInPolygon(
                34.741905,
                113.620066,
                ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON
            )
        )
        for (b in ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS) {
            assertTrue(
                "Building ${b.name} must lie inside Songshanlu geofence polygon",
                CustomCampusZone.isPointInPolygon(
                    b.latitude,
                    b.longitude,
                    ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON
                )
            )
        }

        // #16: Computer Service Club structured venue
        val compClub = PresetCampusCommunityCatalog.defaultCampusClubs.firstOrNull {
            it.id == "club_partner_computer_service"
        }
        assertNotNull(compClub)
        assertEquals("莲花街校区 学生宿舍F01 W111", compClub!!.activityVenue)
        assertEquals("学生宿舍F01", compClub.buildingName)
        assertEquals("W111", compClub.roomNumber)

        val dormBuilding = Building(
            id = "b_dorm_ef",
            campusId = "lianhuajie",
            name = "学生公寓E/F区",
            shortName = "E/F公寓",
            latitude = 34.833150,
            longitude = 113.537750,
            category = CampusCategory.DORMITORY,
            entrances = listOf("东侧门禁"),
            description = "学生公寓E/F区",
            verifiedAt = LocalDate.of(2026, 11, 9)
        )
        val dormAliases = listOf(
            BuildingAlias("al_dorm_f01_a", "b_dorm_ef", "学生宿舍F01", 1.0f, true)
        )
        val resolved = ClassroomBuildingResolver.resolve(
            rawRoom = compClub.activityVenue,
            buildings = listOf(dormBuilding),
            aliases = dormAliases
        )
        assertEquals(BuildingMatchStatus.EXACT_VERIFIED, resolved.status)
        assertEquals("b_dorm_ef", resolved.building?.id)
        assertEquals("W111", resolved.roomNumberHint)
    }

    @Test
    fun `Issue 12 and 13 - AcademicTermResolver and Songshanlu study rooms catalog`() {
        assertEquals("2026-2027-1", AcademicTermResolver.defaultTermId(LocalDate.of(2026, 11, 9)))
        assertEquals("2026", AcademicTermResolver.defaultAcademicYearCode(LocalDate.of(2026, 11, 9)))
        assertEquals("3", AcademicTermResolver.defaultSemesterCode(LocalDate.of(2026, 11, 9)))

        val songshanMap = CampusStudyRoomCatalog.studyRoomsMapForCampus("songshanlu")
        assertTrue(songshanMap.containsKey("嵩山路1号教学楼"))
        assertTrue(songshanMap.containsKey("嵩山路2号教学楼"))
        assertTrue(songshanMap.values.flatten().isNotEmpty())
    }

    @Test
    fun `Issue 17 - CampusHospitalPharmacyCatalog has 202 verified items and contributor attribution`() {
        assertEquals("Lollipop2233", CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE)
        assertEquals("https://github.com/Lollipop2233", CampusHospitalPharmacyCatalog.CONTRIBUTOR_GITHUB_URL)
        assertEquals(202, CampusHospitalPharmacyCatalog.ITEMS.size)

        val ibuprofen = CampusHospitalPharmacyCatalog.search("布洛芬")
        assertEquals(2, ibuprofen.size)
        val tcmItems = CampusHospitalPharmacyCatalog.search("", categoryFilter = HospitalMedicineCategory.TRADITIONAL_CHINESE)
        assertTrue(tcmItems.size >= 40)
    }
}
