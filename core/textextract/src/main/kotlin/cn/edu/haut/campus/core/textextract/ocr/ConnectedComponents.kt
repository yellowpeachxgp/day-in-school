package cn.edu.haut.campus.core.textextract.ocr

import cn.edu.haut.campus.core.textextract.internal.FPoint

internal data class BinaryComponent(
    val pixelCount: Int,
    val boundaryPoints: List<FPoint>
)

/** 8 连通二值组件提取。用边界点凸包替代 OpenCV contour/minAreaRect 链路。 */
internal object ConnectedComponents {
    fun extract(mask: BooleanArray, width: Int, height: Int): List<BinaryComponent> {
        require(mask.size == width * height) { "二值掩码尺寸不一致" }
        val parent = IntArray(width * height) { -1 }

        fun find(value: Int): Int {
            var current = value
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            if (ra < rb) parent[rb] = ra else parent[ra] = rb
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!mask[index]) continue
                parent[index] = index
                if (x > 0 && mask[index - 1]) union(index, index - 1)
                if (y > 0) {
                    val up = index - width
                    if (mask[up]) union(index, up)
                    if (x > 0 && mask[up - 1]) union(index, up - 1)
                    if (x + 1 < width && mask[up + 1]) union(index, up + 1)
                }
            }
        }

        val counts = HashMap<Int, Int>()
        val boundaries = HashMap<Int, MutableList<FPoint>>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!mask[index] || parent[index] < 0) continue
                val root = find(index)
                counts[root] = (counts[root] ?: 0) + 1
                if (isBoundary(mask, width, height, x, y)) {
                    boundaries.getOrPut(root) { mutableListOf() }.add(FPoint(x.toDouble(), y.toDouble()))
                }
            }
        }

        return counts.mapNotNull { (root, count) ->
            val boundary = boundaries[root].orEmpty()
            if (boundary.isEmpty()) null else BinaryComponent(count, boundary)
        }
    }

    private fun isBoundary(mask: BooleanArray, width: Int, height: Int, x: Int, y: Int): Boolean {
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height || !mask[ny * width + nx]) return true
            }
        }
        return false
    }
}
