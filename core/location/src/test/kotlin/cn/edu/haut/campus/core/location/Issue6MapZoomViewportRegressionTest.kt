package cn.edu.haut.campus.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * #6 校园 2.5D 地图缩放回归：双指捏合必须能真正缩小，按钮步进不得回到旧版
 * 15.x/13.8 下限，且缩放手势要围绕质心保持地理焦点，避免「能缩放但画面乱跳」。
 */
class Issue6MapZoomViewportRegressionTest {

    @Test
    fun zoomLimitsAndButtonsAllowRealZoomOutWithoutCrossingBounds() {
        assertEquals(12.6f, CampusMapViewportMath.MIN_ZOOM, 1e-5f)
        assertEquals(18.8f, CampusMapViewportMath.MAX_ZOOM, 1e-5f)

        assertEquals(15.75f, CampusMapViewportMath.zoomOutByButton(16.3f), 1e-4f)
        assertEquals(16.85f, CampusMapViewportMath.zoomInByButton(16.3f), 1e-4f)

        var zoom = 16.3f
        repeat(20) { zoom = CampusMapViewportMath.zoomOutByButton(zoom) }
        assertEquals("连续缩小必须停在 12.6，不得回退到旧版 15.6/13.8 下限", 12.6f, zoom, 1e-5f)
        repeat(40) { zoom = CampusMapViewportMath.zoomInByButton(zoom) }
        assertEquals(18.8f, zoom, 1e-5f)
    }

    @Test
    fun pinchZoomFactorMapsToZoomLevelInCorrectDirectionAndRejectsBadEvents() {
        // Compose calculateZoom < 1 表示双指捏合（手指靠近）：地图必须缩小（zoom level 下降）。
        assertEquals(15.3f, CampusMapViewportMath.zoomAfterPinch(16.3f, 0.5f), 1e-4f)
        // calculateZoom > 1 表示双指张开：地图放大（zoom level 上升）。
        assertEquals(17.3f, CampusMapViewportMath.zoomAfterPinch(16.3f, 2.0f), 1e-4f)

        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -0.5f)) {
            assertEquals(16.3f, CampusMapViewportMath.zoomAfterPinch(16.3f, bad), 1e-6f)
        }
        // 极端事件每次最多按 4x/0.25x 折算为 ±2 级，并仍受全局边界钳制。
        assertEquals(18.3f, CampusMapViewportMath.zoomAfterPinch(16.3f, 100f), 1e-4f)
        assertEquals(12.6f, CampusMapViewportMath.zoomAfterPinch(13.0f, 0.0001f), 1e-5f)
    }

    @Test
    fun tileZoomTracksLowerZoomOutRangeForBoth25dAndStreetTiles() {
        assertEquals(13, CampusMapViewportMath.tileZoomFor(12.6f, official25d = true))
        assertEquals(12, CampusMapViewportMath.tileZoomFor(12.4f, official25d = true))
        assertEquals(17, CampusMapViewportMath.tileZoomFor(18.8f, official25d = true))
        assertEquals(13, CampusMapViewportMath.tileZoomFor(12.6f, official25d = false))
        assertEquals(18, CampusMapViewportMath.tileZoomFor(18.8f, official25d = false))
    }

    @Test
    fun gestureViewportKeepsPinchCentroidGeoFocusStable() {
        val centerLat = 34.8295
        val centerLon = 113.5455
        val initialZoom = 16.1f
        val tileZ = 16
        val baseTilePx = 500f
        val width = 1000f
        val height = 800f
        val centroidX = 700f
        val centroidY = 500f
        val rawPinchZoom = 0.8f // 捏合缩小，但不跨越整数 tileZ，便于验证单次手势焦点公式

        val initialCenterTile = CampusMapViewportMath.latLonToTileXY(centerLat, centerLon, tileZ)
        val focalTileX = initialCenterTile.first + (centroidX - width / 2f) / baseTilePx
        val focalTileY = initialCenterTile.second + (centroidY - height / 2f) / baseTilePx

        val viewport = CampusMapViewportMath.viewportAfterGesture(
            centerLatGcj = centerLat,
            centerLonGcj = centerLon,
            currentZoom = initialZoom,
            tileZ = tileZ,
            baseTileRenderPx = baseTilePx,
            canvasWidthPx = width,
            canvasHeightPx = height,
            centroidX = centroidX,
            centroidY = centroidY,
            panX = 0f,
            panY = 0f,
            rawGestureZoom = rawPinchZoom
        )
        assertTrue(viewport.zoom < initialZoom)
        assertNotEquals(centerLat, viewport.centerLatGcj, 1e-12)
        assertNotEquals(centerLon, viewport.centerLonGcj, 1e-12)

        val newCenterTile = CampusMapViewportMath.latLonToTileXY(
            viewport.centerLatGcj,
            viewport.centerLonGcj,
            tileZ
        )
        val zoomRatio = 2.0.pow((viewport.zoom - initialZoom).toDouble()).toFloat()
        val newBaseTilePx = baseTilePx * zoomRatio
        val focalScreenX = width / 2f + ((focalTileX - newCenterTile.first).toFloat() * newBaseTilePx)
        val focalScreenY = height / 2f + ((focalTileY - newCenterTile.second).toFloat() * newBaseTilePx)
        assertEquals(centroidX, focalScreenX, 0.02f)
        assertEquals(centroidY, focalScreenY, 0.02f)
    }

    @Test
    fun gestureViewportCenterIsStableWhenPinchCentroidIsCanvasCenterAndPanFollowsFinger() {
        val centerLat = 34.8295
        val centerLon = 113.5455
        val zoomed = CampusMapViewportMath.viewportAfterGesture(
            centerLatGcj = centerLat,
            centerLonGcj = centerLon,
            currentZoom = 16.1f,
            tileZ = 16,
            baseTileRenderPx = 500f,
            canvasWidthPx = 1000f,
            canvasHeightPx = 800f,
            centroidX = 500f,
            centroidY = 400f,
            panX = 0f,
            panY = 0f,
            rawGestureZoom = 0.8f
        )
        assertEquals(centerLat, zoomed.centerLatGcj, 1e-9)
        assertEquals(centerLon, zoomed.centerLonGcj, 1e-9)
        assertTrue(zoomed.zoom < 16.1f)

        val panned = CampusMapViewportMath.viewportAfterGesture(
            centerLatGcj = centerLat,
            centerLonGcj = centerLon,
            currentZoom = 16.1f,
            tileZ = 16,
            baseTileRenderPx = 500f,
            canvasWidthPx = 1000f,
            canvasHeightPx = 800f,
            centroidX = 500f,
            centroidY = 400f,
            panX = 40f,
            panY = 0f,
            rawGestureZoom = 1.0f
        )
        assertEquals(16.1f, panned.zoom, 1e-6f)
        assertTrue("手指向右拖动时，地图中心必须西移从而让地物跟随手指", panned.centerLonGcj < centerLon)
        assertEquals(centerLat, panned.centerLatGcj, 1e-9)
    }
}
