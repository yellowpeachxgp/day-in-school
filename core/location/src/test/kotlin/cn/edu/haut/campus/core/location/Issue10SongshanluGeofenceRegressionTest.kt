package cn.edu.haut.campus.core.location

import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.CustomCampusZone
import cn.edu.haut.campus.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #10 「嵩山路校区电子围栏区划标定漂移」独立回归：
 * 锁定嵩山路校区围栏顶点、中心点与楼宇落点，防止围栏再次越过嵩山南路/伊河路
 * 或漂移到相邻街区；同时验证自动识别只吃进真实围栏内定位。
 */
class Issue10SongshanluGeofenceRegressionTest {

    private val polygon = ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON

    @Test
    fun geofenceVerticesStayCalibratedToSongshanRoadCampusBlock() {
        assertEquals(
            "嵩山路围栏顶点漂移会直接复现 #10，必须逐点锁定",
            listOf(
                GeoPoint(34.73930, 113.61730),
                GeoPoint(34.73930, 113.62035),
                GeoPoint(34.74150, 113.62035),
                GeoPoint(34.74150, 113.61730)
            ),
            polygon
        )
        assertEquals(4, polygon.distinct().size)
        assertTrue("东边界不得越过嵩山南路侧校准点", polygon.maxOf { it.longitude } <= 113.62035)
        assertTrue("北边界不得越过伊河路侧校准点", polygon.maxOf { it.latitude } <= 34.74150)
        assertTrue("围栏宽度异常说明坐标系或小数点漂移", polygon.maxOf { it.longitude } - polygon.minOf { it.longitude } in 0.0025..0.0035)
        assertTrue("围栏高度异常说明坐标系或小数点漂移", polygon.maxOf { it.latitude } - polygon.minOf { it.latitude } in 0.0018..0.0026)
    }

    @Test
    fun campusCenterAndEveryPublishedBuildingRemainInsideGeofence() {
        assertEquals(34.74045, CampusMapNavigator.SONGSHANLU_CENTER_LAT, 1e-5)
        assertEquals(113.61895, CampusMapNavigator.SONGSHANLU_CENTER_LNG, 1e-5)
        assertTrue(
            CustomCampusZone.isPointInPolygon(
                CampusMapNavigator.SONGSHANLU_CENTER_LAT,
                CampusMapNavigator.SONGSHANLU_CENTER_LNG,
                polygon
            )
        )

        val buildings = ClassroomBuildingResolver.HAUT_SONGSHANLU_BUILDINGS
        assertEquals("嵩山路公开楼宇目录必须保持 8 栋核心楼宇", 8, buildings.size)
        for (building in buildings) {
            assertEquals("songshanlu", building.campusId)
            assertTrue(
                "${building.name} 必须落在嵩山路围栏内",
                CustomCampusZone.isPointInPolygon(building.latitude, building.longitude, polygon)
            )
        }
    }

    @Test
    fun driftedRoadCrossingPointsStayOutsideAndDoNotAutoSelectSongshanlu() {
        val driftedPoints = listOf(
            34.74045 to 113.62120, // 向东越过嵩山南路
            34.74200 to 113.61895, // 向北越过伊河路
            34.73880 to 113.61895, // 南侧相邻街区
            34.74045 to 113.61680  // 西侧相邻街区
        )
        for ((lat, lon) in driftedPoints) {
            assertFalse(
                "漂移点 ($lat,$lon) 不得被识别进嵩山路校区",
                CustomCampusZone.isPointInPolygon(lat, lon, polygon)
            )
            val detected = CampusGeofenceRecognizer.detectActiveCampus(
                userLocation = UserGeoLocation(
                    latitude = lat,
                    longitude = lon,
                    accuracyMeters = 10f,
                    bearingDegrees = null,
                    provider = "test",
                    timestampMillis = 1_700_000_000_000L,
                    isInsideLianhuajieCampus = false,
                    distanceToCampusCenterMeters = Int.MAX_VALUE,
                    isInsideSongshanluCampus = false,
                    distanceToSongshanluCenterMeters = Int.MAX_VALUE
                ),
                customZones = emptyList()
            )
            assertFalse(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value == detected.campusId)
        }
    }

    @Test
    fun accuratePointInsideFenceAutoSelectsSongshanluWithLiveGpsEvidence() {
        val detected = CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = UserGeoLocation(
                latitude = 34.74045,
                longitude = 113.61895,
                accuracyMeters = 12f,
                bearingDegrees = null,
                provider = "test",
                timestampMillis = 1_700_000_000_000L,
                isInsideLianhuajieCampus = false,
                distanceToCampusCenterMeters = Int.MAX_VALUE,
                isInsideSongshanluCampus = true,
                distanceToSongshanluCenterMeters = 0
            ),
            customZones = emptyList()
        )
        assertEquals(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value, detected.campusId)
        assertEquals("嵩山路校区", detected.campusName)
        assertEquals(CampusDetectionSource.LIVE_GPS_GEOFENCE, detected.source)
        assertEquals(CampusMapNavigator.SONGSHANLU_CENTER_LAT, detected.centerLatitude, 1e-9)
        assertEquals(CampusMapNavigator.SONGSHANLU_CENTER_LNG, detected.centerLongitude, 1e-9)
        assertFalse(detected.isLowAccuracyGps)
    }
}
