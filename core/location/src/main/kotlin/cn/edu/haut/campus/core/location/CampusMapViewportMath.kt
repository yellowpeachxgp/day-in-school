package cn.edu.haut.campus.core.location

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 校园室外地图视口的纯函数数学缝。
 *
 * #6 的回归点不只是「缩放数值变大/变小」：双指缩放必须围绕手势质心保持地理焦点，
 * 且最小缩放不得再回到旧版只能放到 15.x/13.8 的不可缩小状态。将该计算抽到这里，
 * 让 JVM 单测可以在没有 Android 触摸事件的情况下锁定同一份生产算法。
 */
object CampusMapViewportMath {
    const val MIN_ZOOM: Float = 12.6f
    const val MAX_ZOOM: Float = 18.8f
    const val BUTTON_ZOOM_STEP: Float = 0.55f

    /** 瓦片缩放层级必须与 2.5D 官方栅格可用层级和街道底图层级分开钳制。 */
    fun tileZoomFor(zoom: Float, official25d: Boolean): Int =
        zoom.roundToInt().coerceIn(12, if (official25d) 17 else 18)

    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    fun zoomInByButton(currentZoom: Float): Float = clampZoom(currentZoom + BUTTON_ZOOM_STEP)

    fun zoomOutByButton(currentZoom: Float): Float = clampZoom(currentZoom - BUTTON_ZOOM_STEP)

    /** Compose calculateZoom 的异常值（NaN/Inf/0/负值）等价于无缩放；极端双指事件限制单次变化。 */
    fun sanitizeGestureZoom(rawZoom: Float): Float =
        if (rawZoom.isFinite() && rawZoom > 0f) rawZoom.coerceIn(0.25f, 4.0f) else 1.0f

    /** 手势缩放是乘法因子；地图 zoom level 是二分对数加法。 */
    fun zoomAfterPinch(currentZoom: Float, rawGestureZoom: Float): Float =
        clampZoom(currentZoom + (ln(sanitizeGestureZoom(rawGestureZoom).toDouble()) / ln(2.0)).toFloat())

    fun latLonToTileXY(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z.toDouble())
        val x = (lon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = (1.0 - asinh(tan(latRad)) / PI) / 2.0 * n
        return x to y
    }

    fun tileXYToLatLon(tx: Double, ty: Double, z: Int): Pair<Double, Double> {
        val n = 2.0.pow(z.toDouble())
        val lon = tx / n * 360.0 - 180.0
        val latRad = atan(sinh(PI * (1.0 - 2.0 * ty / n)))
        val lat = Math.toDegrees(latRad)
        return lat to lon
    }

    data class GestureViewport(
        val centerLatGcj: Double,
        val centerLonGcj: Double,
        val zoom: Float
    )

    /**
     * 由一次拖移/双指事件计算新视口。
     *
     * [panX]/[panY] 使用 Compose calculatePan 的屏幕像素方向；[centroidX]/[centroidY]
     * 是缩放锚点。公式把锚点相对屏幕中心的偏移折算进中心点，避免缩放时地理焦点漂移。
     */
    fun viewportAfterGesture(
        centerLatGcj: Double,
        centerLonGcj: Double,
        currentZoom: Float,
        tileZ: Int,
        baseTileRenderPx: Float,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        rawGestureZoom: Float
    ): GestureViewport {
        val (centerTileX, centerTileY) = latLonToTileXY(centerLatGcj, centerLonGcj, tileZ)
        val newZoom = zoomAfterPinch(currentZoom, rawGestureZoom)
        val zoomRatio = 2.0.pow((newZoom - currentZoom).toDouble()).toFloat()
        val offsetFromCenterX = centroidX - (canvasWidthPx / 2f)
        val offsetFromCenterY = centroidY - (canvasHeightPx / 2f)
        val dxPx = -panX + offsetFromCenterX * (1f - 1f / zoomRatio)
        val dyPx = -panY + offsetFromCenterY * (1f - 1f / zoomRatio)
        val dTileX = dxPx / baseTileRenderPx
        val dTileY = dyPx / baseTileRenderPx
        val (newLat, newLon) = tileXYToLatLon(
            centerTileX + dTileX,
            centerTileY + dTileY,
            tileZ
        )
        return GestureViewport(
            centerLatGcj = newLat.coerceIn(18.0, 54.0),
            centerLonGcj = newLon.coerceIn(73.0, 136.0),
            zoom = newZoom
        )
    }
}
