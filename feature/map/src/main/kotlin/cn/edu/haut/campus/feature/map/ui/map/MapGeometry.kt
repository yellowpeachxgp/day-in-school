package cn.edu.haut.campus.feature.map.ui.map

import androidx.compose.ui.geometry.Offset

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

/**
 * 一级校园室外地图画布（纯粹聚焦室外导航、实测建筑轮廓与防重叠地标标注；室内平面图已独立至二级页面）。
 */
