package cn.edu.haut.campus.feature.map.ui.map

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs

/**
 * 射线法判断屏幕点是否落在多边形内（用于点击室内房间或官方建筑轮廓）。
 */
internal fun isScreenPointInRing(pt: Offset, ring: List<Offset>): Boolean {
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
