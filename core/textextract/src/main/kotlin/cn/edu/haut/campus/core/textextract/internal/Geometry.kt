package cn.edu.haut.campus.core.textextract.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

internal data class FPoint(val x: Double, val y: Double) {
    fun scaled(scaleX: Double, scaleY: Double): FPoint = FPoint(x * scaleX, y * scaleY)
}

internal data class OrientedRect(
    val center: FPoint,
    val width: Double,
    val height: Double,
    /** OpenCV minAreaRect 风格的四个角点，尚未按阅读方向排序。 */
    val corners: List<FPoint>
)

internal object Geometry {
    fun orderQuadPoints(points: List<FPoint>): List<FPoint> {
        require(points.size == 4) { "四边形必须恰好有四个点" }
        val sorted = points.sortedBy { it.x }
        val topLeft: FPoint
        val bottomLeft: FPoint
        if (sorted[1].y > sorted[0].y) {
            topLeft = sorted[0]
            bottomLeft = sorted[1]
        } else {
            topLeft = sorted[1]
            bottomLeft = sorted[0]
        }

        val topRight: FPoint
        val bottomRight: FPoint
        if (sorted[3].y > sorted[2].y) {
            topRight = sorted[2]
            bottomRight = sorted[3]
        } else {
            topRight = sorted[3]
            bottomRight = sorted[2]
        }
        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    fun convexHull(input: List<FPoint>): List<FPoint> {
        val points = input.distinct().sortedWith(compareBy<FPoint> { it.x }.thenBy { it.y })
        if (points.size <= 2) return points

        val lower = mutableListOf<FPoint>()
        for (point in points) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0.0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(point)
        }
        val upper = mutableListOf<FPoint>()
        for (point in points.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0.0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(point)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /**
     * 用凸包边投影求最小面积外接矩形。OCR 文本行的凸包规模较小，
     * O(n^2) 实现比依赖 OpenCV 更可审计，也避免 16 KB 页兼容风险。
     */
    fun minimumAreaRect(input: List<FPoint>): OrientedRect {
        val hull = convexHull(input)
        if (hull.isEmpty()) {
            return OrientedRect(FPoint(0.0, 0.0), 0.0, 0.0, List(4) { FPoint(0.0, 0.0) })
        }
        if (hull.size == 1) {
            val p = hull[0]
            return OrientedRect(p, 0.0, 0.0, listOf(p, p, p, p))
        }
        if (hull.size == 2) {
            return degenerateRect(hull[0], hull[1])
        }

        var bestArea = Double.POSITIVE_INFINITY
        var bestWidth = 0.0
        var bestHeight = 0.0
        var bestCorners: List<FPoint>? = null
        var bestCenter = FPoint(0.0, 0.0)

        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[(i + 1) % hull.size]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val length = hypot(ex, ey)
            if (length <= 1e-9) continue
            val ux = ex / length
            val uy = ey / length
            val vx = -uy
            val vy = ux

            var minU = Double.POSITIVE_INFINITY
            var maxU = Double.NEGATIVE_INFINITY
            var minV = Double.POSITIVE_INFINITY
            var maxV = Double.NEGATIVE_INFINITY
            for (point in hull) {
                val u = point.x * ux + point.y * uy
                val v = point.x * vx + point.y * vy
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val width = maxU - minU
            val height = maxV - minV
            val area = width * height
            if (area < bestArea) {
                bestArea = area
                bestWidth = width
                bestHeight = height
                val p1 = fromBasis(ux, uy, vx, vy, minU, minV)
                val p2 = fromBasis(ux, uy, vx, vy, maxU, minV)
                val p3 = fromBasis(ux, uy, vx, vy, maxU, maxV)
                val p4 = fromBasis(ux, uy, vx, vy, minU, maxV)
                bestCorners = listOf(p1, p2, p3, p4)
                bestCenter = FPoint((p1.x + p3.x) / 2.0, (p1.y + p3.y) / 2.0)
            }
        }

        val corners = bestCorners ?: return axisAlignedRect(hull)
        return OrientedRect(bestCenter, bestWidth, bestHeight, corners)
    }

    fun pointInPolygon(x: Double, y: Double, polygon: List<FPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val intersects = (pi.y > y) != (pj.y > y) &&
                x < (pj.x - pi.x) * (y - pi.y) / (pj.y - pi.y) + pi.x
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    fun polygonSignedArea(points: List<FPoint>): Double {
        var sum = 0.0
        for (i in points.indices) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            sum += p1.x * p2.y - p2.x * p1.y
        }
        return sum / 2.0
    }

    fun polygonPerimeter(points: List<FPoint>): Double {
        var length = 0.0
        for (i in points.indices) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            length += hypot(p2.x - p1.x, p2.y - p1.y)
        }
        return length
    }

    fun roundHalfToEven(value: Double): Int {
        val floored = floor(value)
        val diff = value - floored
        return when {
            diff < 0.5 -> floored.toInt()
            diff > 0.5 -> floored.toInt() + 1
            floored.toInt() % 2 == 0 -> floored.toInt()
            else -> floored.toInt() + 1
        }
    }

    fun clampToIntRound(value: Double, min: Int, max: Int): Int {
        return Geometry.roundHalfToEven(value).coerceIn(min, max)
    }

    private fun cross(origin: FPoint, a: FPoint, b: FPoint): Double {
        return (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)
    }

    private fun fromBasis(ux: Double, uy: Double, vx: Double, vy: Double, u: Double, v: Double): FPoint {
        return FPoint(ux * u + vx * v, uy * u + vy * v)
    }

    private fun degenerateRect(a: FPoint, b: FPoint): OrientedRect {
        val center = FPoint((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)
        return OrientedRect(center, abs(a.x - b.x), abs(a.y - b.y), listOf(a, b, b, a))
    }

    private fun axisAlignedRect(points: List<FPoint>): OrientedRect {
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val corners = listOf(
            FPoint(minX, minY),
            FPoint(maxX, minY),
            FPoint(maxX, maxY),
            FPoint(minX, maxY)
        )
        return OrientedRect(
            center = FPoint((minX + maxX) / 2.0, (minY + maxY) / 2.0),
            width = maxX - minX,
            height = maxY - minY,
            corners = corners
        )
    }
}
