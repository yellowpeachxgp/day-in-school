package cn.edu.haut.campus.feature.map.ui.indoor

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

internal data class IndoorBounds(
    val midLat: Double,
    val midLon: Double,
    val cosLat: Double,
    val spanXMeters: Double,
    val spanYMeters: Double
)

internal fun isPointInsidePolygon(pt: Offset, ring: List<Offset>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.lastIndex
    for (i in ring.indices) {
        val xi = ring[i].x
        val yi = ring[i].y
        val xj = ring[j].x
        val yj = ring[j].y
        val intersect = ((yi > pt.y) != (yj > pt.y)) &&
            (pt.x < (xj - xi) * (pt.y - yi) / ((yj - yi).takeIf { abs(it) > 1e-5f } ?: 1e-5f) + xi)
        if (intersect) inside = !inside
        j = i
    }
    return inside
}
