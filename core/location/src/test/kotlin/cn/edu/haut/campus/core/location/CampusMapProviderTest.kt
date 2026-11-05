package cn.edu.haut.campus.core.location

import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityState
import cn.edu.haut.campus.core.model.SchoolId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampusMapProviderTest {

    @Test
    fun registry_resolvesHautLianhuajieAndReturnsUnsupportedForOtherSchools() {
        val registry = CampusMapProviderRegistry.Default
        val haut = registry.resolve(
            SchoolDirectory.HAUT_SCHOOL_ID,
            SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID
        )
        assertNotNull(haut)
        assertEquals("haut-lianhuajie-gis", haut!!.providerId)

        assertNull(registry.resolve(SchoolDirectory.HAUT_SCHOOL_ID, CampusId("unknown-campus")))
        assertNull(registry.resolve(SchoolId("other-school")))

        val unsupported = registry.capabilityStatus(SchoolId("other-school"))
        assertFalse(unsupported.providerSupports)
        assertEquals(CapabilityState.UNSUPPORTED, unsupported.state)

        val originBuilding = Building(
            id = "b_t1",
            name = "1号教学楼（莲花街校区）",
            shortName = "1教",
            latitude = 34.82975,
            longitude = 113.54500,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门主入口"),
            description = "1号教学楼",
            verifiedAt = LocalDate.of(2026, 10, 1)
        )
        val destinationBuilding = Building(
            id = "b_lib",
            name = "图书馆（莲花街校区）",
            shortName = "图书馆",
            latitude = 34.83080,
            longitude = 113.54685,
            category = CampusCategory.LIBRARY,
            entrances = listOf("东门主入口"),
            description = "图书馆",
            verifiedAt = LocalDate.of(2026, 10, 1)
        )
        val plan = haut.buildWalkingRoute(
            userLocation = null,
            fallbackOriginBuilding = originBuilding,
            destinationBuilding = destinationBuilding,
            allBuildings = listOf(originBuilding, destinationBuilding),
            rawClassroom = "1104"
        )
        assertTrue(plan.routePoints.size >= 2)
        assertTrue(plan.estimatedWalkDistanceMeters > 0)
    }

    @Test
    fun officialGisClient_infersFloorIndexAndMatchesBuildingsAccurately() {
        assertEquals(0, HautOfficialGisClient.inferFloorIndexFromRoomQuery("3101"))
        assertEquals(3, HautOfficialGisClient.inferFloorIndexFromRoomQuery("莲花街 3号楼3402"))
        assertEquals(1, HautOfficialGisClient.inferFloorIndexFromRoomQuery("31205"))
        assertEquals(2, HautOfficialGisClient.inferFloorIndexFromRoomQuery("5号楼 3层 5308"))
        assertEquals("3104", HautOfficialGisClient.extractCleanRoomCode("莲花街校区 3号楼3104"))
        assertEquals("31205", HautOfficialGisClient.extractCleanRoomCode("31号楼31205"))

        val b3 = Building(
            id = "b_t3",
            name = "3号楼（文科楼）",
            shortName = "3号楼",
            latitude = 34.83013,
            longitude = 113.54798,
            category = CampusCategory.TEACHING_BUILDING,
            entrances = listOf("南门"),
            description = "文科楼",
            verifiedAt = LocalDate.of(2026, 11, 5)
        )
        val resolved = HautOfficialGisClient.resolveFeatureIdForBuilding(b3, null)
        assertNotNull(resolved)
        assertEquals("12866", resolved!!.first)
        assertEquals(5, resolved.second)
        assertEquals(GisIndoorRoomCategory.CLASSROOM, HautOfficialGisClient.classifyRoomCategory("classroom", "3101"))
        assertEquals(GisIndoorRoomCategory.LABORATORY, HautOfficialGisClient.classifyRoomCategory("laboratory", "机房5204"))
    }
}
