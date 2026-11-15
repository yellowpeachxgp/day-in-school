package cn.edu.haut.campus.core.location

import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.model.CustomCampusZone
import cn.edu.haut.campus.core.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #10 嵩山路/莲花街电子围栏纯函数回归：
 * 多边形内外/边界/顶点判定、WGS84<->GCJ02 往返误差、双校区自动识别、
 * 低精度定位显式标记、用户手绘电子区划优先于内置围栏。
 */
class CampusGeofenceRecognizerTest {

    private val songshanPolygon = ClassroomBuildingResolver.HAUT_SONGSHANLU_POLYGON
    private val lianhuajiePolygon = ClassroomBuildingResolver.HAUT_LIANHUAJIE_POLYGON

    private fun location(
        lat: Double,
        lng: Double,
        accuracy: Float = 15f,
        insideLhj: Boolean = false
    ) = UserGeoLocation(
        latitude = lat,
        longitude = lng,
        accuracyMeters = accuracy,
        bearingDegrees = null,
        provider = "test",
        timestampMillis = 1_700_000_000_000L,
        isInsideLianhuajieCampus = insideLhj,
        distanceToCampusCenterMeters = 0,
        isInsideSongshanluCampus = CustomCampusZone.isPointInPolygon(lat, lng, songshanPolygon),
        distanceToSongshanluCenterMeters = 0
    )

    @Test
    fun songshanluPolygonInsideOutsideBoundaryAndVertexClassification() {
        // 中心点与内部点
        assertTrue(CustomCampusZone.isPointInPolygon(34.74045, 113.61895, songshanPolygon))
        assertTrue(CustomCampusZone.isPointInPolygon(34.74000, 113.61850, songshanPolygon))
        // 明显外部：东侧过嵩山南路、西侧、南侧、北侧
        assertFalse(CustomCampusZone.isPointInPolygon(34.74045, 113.62120, songshanPolygon))
        assertFalse(CustomCampusZone.isPointInPolygon(34.74045, 113.61600, songshanPolygon))
        assertFalse(CustomCampusZone.isPointInPolygon(34.73800, 113.61895, songshanPolygon))
        assertFalse(CustomCampusZone.isPointInPolygon(34.74300, 113.61895, songshanPolygon))
        // 紧贴边界外侧（1e-5 ≈ 1m 之外）必须在外
        assertFalse(CustomCampusZone.isPointInPolygon(34.73929, 113.61900, songshanPolygon))
        assertFalse(CustomCampusZone.isPointInPolygon(34.74151, 113.61900, songshanPolygon))
        assertFalse(CustomCampusZone.isPointInPolygon(34.74040, 113.61729, songshanPolygon))
        assertFalse(CustomCampusZone.isPointInPolygon(34.74040, 113.62036, songshanPolygon))
        // 射线法半开边界语义（测量事实）：南/西边缘与西南顶点判定为内，
        // 北/东边缘与其余顶点判定为外——断言实现的真实行为而非理想化假设
        assertTrue(CustomCampusZone.isPointInPolygon(34.73930, 113.61900, songshanPolygon))
        assertTrue(CustomCampusZone.isPointInPolygon(34.74040, 113.61730, songshanPolygon))
        assertTrue(CustomCampusZone.isPointInPolygon(34.73930, 113.61730, songshanPolygon))
        assertFalse(CustomCampusZone.isPointInPolygon(34.74150, 113.61900, songshanPolygon))
        assertFalse(CustomCampusZone.isPointInPolygon(34.74040, 113.62035, songshanPolygon))
        assertFalse(CustomCampusZone.isPointInPolygon(34.74150, 113.62035, songshanPolygon))
        // 顶点数不足 3 个时多边形判定为 false（调用方须回退圆形围栏）
        assertFalse(CustomCampusZone.isPointInPolygon(34.74045, 113.61895, songshanPolygon.take(2)))
    }

    @Test
    fun wgs84Gcj02RoundtripErrorIsBoundedUnder50m() {
        val points = listOf(
            34.74045 to 113.61895,  // 嵩山路中心
            34.83120 to 113.54420,  // 莲花街中心
            34.73930 to 113.61730,  // 围栏顶点
            34.74150 to 113.62035,
            39.90000 to 116.40000,  // 中国境内其他点
            34.00000 to 113.60000
        )
        var maxErr = 0
        for ((lat, lng) in points) {
            val (gLat, gLng) = CampusMapNavigator.wgs84ToGcj02(lat, lng)
            // GCJ-02 必须产生非平凡偏移（中国境内 WGS->GCJ 不可为恒等）
            assertNotEquals(lat, gLat, 1e-9)
            assertNotEquals(lng, gLng, 1e-9)
            val (backLat, backLng) = CampusMapNavigator.gcj02ToWgs84(gLat, gLng)
            val err = CampusMapNavigator.calculateDistanceMeters(lat, lng, backLat, backLng)
            maxErr = maxOf(maxErr, err)
        }
        assertTrue("Roundtrip error must stay well under 50m bound (measured $maxErr m)", maxErr < 50)
        // 实测 ~2m；若算法退化为恒等或大幅漂移，此更紧的约束会立刻暴露
        assertTrue("Roundtrip error regression bound (measured $maxErr m)", maxErr < 25)
        // 中国境外坐标恒等转换（不出错也不伪造）
        val (oLat, oLng) = CampusMapNavigator.wgs84ToGcj02(10.0, 20.0)
        assertEquals(10.0, oLat, 1e-9)
        assertEquals(20.0, oLng, 1e-9)
    }

    @Test
    fun bothCampusesAutoResolveFromGpsAndClassroomHints() {
        // GPS 多边形命中 -> 对应校区 LIVE_GPS_GEOFENCE
        val ssResult = CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = location(34.74045, 113.61895),
            customZones = emptyList()
        )
        assertEquals(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value, ssResult.campusId)
        assertEquals(CampusDetectionSource.LIVE_GPS_GEOFENCE, ssResult.source)
        assertEquals(CampusMapNavigator.SONGSHANLU_CENTER_LAT, ssResult.centerLatitude, 1e-9)

        val lhjResult = CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = location(34.83120, 113.54420, insideLhj = true),
            customZones = emptyList()
        )
        assertEquals(SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value, lhjResult.campusId)
        assertEquals(CampusDetectionSource.LIVE_GPS_GEOFENCE, lhjResult.source)

        // 无 GPS 时课表教室文本识别校区
        val hintSs = CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = null,
            customZones = emptyList(),
            classroomHints = listOf("嵩山路校区1号教学楼101", "嵩山2教202")
        )
        assertEquals(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value, hintSs.campusId)
        assertEquals(CampusDetectionSource.COURSE_CLASSROOM_HINT, hintSs.source)

        val hintLhj = CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = null,
            customZones = emptyList(),
            classroomHints = listOf("莲花街校区3号教学楼3101")
        )
        assertEquals(SchoolDirectory.HAUT_LIANHUAJIE_CAMPUS_ID.value, hintLhj.campusId)
    }

    @Test
    fun lowAccuracyGpsIsFlaggedNotSilentlyAccepted() {
        val coarse = location(34.74045, 113.61895, accuracy = 150f)
        val flagged = CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = coarse,
            customZones = emptyList()
        )
        assertEquals(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value, flagged.campusId)
        assertTrue("Accuracy >90m must be flagged, not silently accepted", flagged.isLowAccuracyGps)
        assertEquals(150f, flagged.gpsAccuracyMeters!!, 0.01f)

        val precise = CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = location(34.74045, 113.61895, accuracy = 20f),
            customZones = emptyList()
        )
        assertFalse(precise.isLowAccuracyGps)
    }

    @Test
    fun userDrawnFenceTakesPrecedenceOverBuiltInPolygons() {
        // 用户手绘区划覆盖嵩山路同一点位 -> 命中用户区划而非内置围栏
        val userZone = CustomCampusZone(
            campusId = "custom_my_campus",
            campusName = "我的自定义校区",
            centerLatitude = 34.74045,
            centerLongitude = 113.61895,
            polygonVertices = listOf(
                GeoPoint(34.73900, 113.61700),
                GeoPoint(34.73900, 113.62100),
                GeoPoint(34.74200, 113.62100),
                GeoPoint(34.74200, 113.61700)
            )
        )
        val result = CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = location(34.74045, 113.61895),
            customZones = listOf(userZone)
        )
        assertEquals("custom_my_campus", result.campusId)
        assertEquals(CampusDetectionSource.LIVE_GPS_CUSTOM_POLYGON, result.source)
        assertEquals(userZone, result.customZone)

        // 手动选择校区优先级最高，盖过 GPS 命中
        val manual = CampusGeofenceRecognizer.detectActiveCampus(
            userLocation = location(34.83120, 113.54420, insideLhj = true),
            customZones = listOf(userZone),
            manualCampusId = SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value
        )
        assertEquals(SchoolDirectory.HAUT_SONGSHANLU_CAMPUS_ID.value, manual.campusId)
        assertEquals(CampusDetectionSource.MANUAL_SELECTION, manual.source)
        assertNull(manual.customZone)

        // 顶点不足的用户区划回退圆形围栏判定
        val circleZone = CustomCampusZone(
            campusId = "custom_circle",
            campusName = "圆形围栏校区",
            centerLatitude = 34.74045,
            centerLongitude = 113.61895,
            polygonVertices = listOf(GeoPoint(34.73900, 113.61700)),
            geofenceRadiusMeters = 500
        )
        assertTrue(circleZone.containsWgs84Point(34.74045, 113.61895))
        assertFalse(circleZone.containsWgs84Point(34.76000, 113.61895))
    }
}
