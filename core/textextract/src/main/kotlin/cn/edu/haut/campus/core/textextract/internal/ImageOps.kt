package cn.edu.haut.campus.core.textextract.internal

import cn.edu.haut.campus.core.textextract.ArgbImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

internal object ImageOps {
    fun resizeBilinear(image: ArgbImage, targetWidth: Int, targetHeight: Int): ArgbImage {
        require(targetWidth > 0 && targetHeight > 0) { "缩放目标尺寸必须为正数" }
        if (targetWidth == image.width && targetHeight == image.height) {
            return ArgbImage(image.width, image.height, image.pixels.copyOf())
        }
        val out = IntArray(targetWidth * targetHeight)
        val xScale = image.width.toDouble() / targetWidth
        val yScale = image.height.toDouble() / targetHeight
        for (y in 0 until targetHeight) {
            val srcY = (y + 0.5) * yScale - 0.5
            for (x in 0 until targetWidth) {
                val srcX = (x + 0.5) * xScale - 0.5
                out[y * targetWidth + x] = sampleBilinearReplicate(image, srcX, srcY)
            }
        }
        return ArgbImage(targetWidth, targetHeight, out)
    }

    fun rotate90CounterClockwise(image: ArgbImage): ArgbImage {
        val out = IntArray(image.width * image.height)
        val newWidth = image.height
        val newHeight = image.width
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val nx = y
                val ny = image.width - 1 - x
                out[ny * newWidth + nx] = image.pixel(x, y)
            }
        }
        return ArgbImage(newWidth, newHeight, out)
    }

    /** orderedPoints 顺序为左上、右上、右下、左下。 */
    fun cropPerspective(image: ArgbImage, orderedPoints: List<FPoint>): ArgbImage {
        require(orderedPoints.size == 4) { "透视裁剪需要四个点" }
        val widthTop = hypot(orderedPoints[0].x - orderedPoints[1].x, orderedPoints[0].y - orderedPoints[1].y)
        val widthBottom = hypot(orderedPoints[2].x - orderedPoints[3].x, orderedPoints[2].y - orderedPoints[3].y)
        val heightLeft = hypot(orderedPoints[0].x - orderedPoints[3].x, orderedPoints[0].y - orderedPoints[3].y)
        val heightRight = hypot(orderedPoints[1].x - orderedPoints[2].x, orderedPoints[1].y - orderedPoints[2].y)
        val targetWidth = max(widthTop, widthBottom).roundToInt().coerceAtLeast(1)
        val targetHeight = max(heightLeft, heightRight).roundToInt().coerceAtLeast(1)

        val dst = listOf(
            FPoint(0.0, 0.0),
            FPoint(targetWidth.toDouble(), 0.0),
            FPoint(targetWidth.toDouble(), targetHeight.toDouble()),
            FPoint(0.0, targetHeight.toDouble())
        )
        val dstToSrc = solveHomography(dst, orderedPoints)
        val out = IntArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            for (x in 0 until targetWidth) {
                val source = applyHomography(dstToSrc, x + 0.5, y + 0.5)
                out[y * targetWidth + x] = sampleBilinearReplicate(image, source.first - 0.5, source.second - 0.5)
            }
        }

        var crop = ArgbImage(targetWidth, targetHeight, out)
        if (crop.height.toDouble() / crop.width.toDouble() >= 1.5) {
            crop = rotate90CounterClockwise(crop)
        }
        return crop
    }

    fun sampleBilinearReplicate(image: ArgbImage, x: Double, y: Double): Int {
        val clampedX = x.coerceIn(0.0, image.width - 1.0)
        val clampedY = y.coerceIn(0.0, image.height - 1.0)
        val x0 = floor(clampedX).toInt()
        val y0 = floor(clampedY).toInt()
        val x1 = (x0 + 1).coerceAtMost(image.width - 1)
        val y1 = (y0 + 1).coerceAtMost(image.height - 1)
        val dx = clampedX - x0
        val dy = clampedY - y0

        val p00 = image.pixel(x0, y0)
        val p10 = image.pixel(x1, y0)
        val p01 = image.pixel(x0, y1)
        val p11 = image.pixel(x1, y1)
        val a = interpolateChannel(p00, p10, p01, p11, dx, dy, 24)
        val r = interpolateChannel(p00, p10, p01, p11, dx, dy, 16)
        val g = interpolateChannel(p00, p10, p01, p11, dx, dy, 8)
        val b = interpolateChannel(p00, p10, p01, p11, dx, dy, 0)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun interpolateChannel(
        p00: Int,
        p10: Int,
        p01: Int,
        p11: Int,
        dx: Double,
        dy: Double,
        shift: Int
    ): Int {
        val c00 = (p00 ushr shift) and 0xff
        val c10 = (p10 ushr shift) and 0xff
        val c01 = (p01 ushr shift) and 0xff
        val c11 = (p11 ushr shift) and 0xff
        val top = c00 + (c10 - c00) * dx
        val bottom = c01 + (c11 - c01) * dx
        return (top + (bottom - top) * dy).roundToInt().coerceIn(0, 255)
    }

    /** 求 dst 坐标到 src 坐标的单应矩阵，避免依赖 OpenCV。 */
    private fun solveHomography(dst: List<FPoint>, src: List<FPoint>): DoubleArray {
        require(dst.size == 4 && src.size == 4) { "单应变换需要四组对应点" }
        val matrix = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val u = dst[i].x
            val v = dst[i].y
            val x = src[i].x
            val y = src[i].y
            val row = i * 2
            matrix[row][0] = u
            matrix[row][1] = v
            matrix[row][2] = 1.0
            matrix[row][6] = -u * x
            matrix[row][7] = -v * x
            matrix[row][8] = x
            matrix[row + 1][3] = u
            matrix[row + 1][4] = v
            matrix[row + 1][5] = 1.0
            matrix[row + 1][6] = -u * y
            matrix[row + 1][7] = -v * y
            matrix[row + 1][8] = y
        }

        for (column in 0 until 8) {
            var pivot = column
            for (row in column + 1 until 8) {
                if (kotlin.math.abs(matrix[row][column]) > kotlin.math.abs(matrix[pivot][column])) pivot = row
            }
            if (pivot != column) {
                val tmp = matrix[column]
                matrix[column] = matrix[pivot]
                matrix[pivot] = tmp
            }
            val divisor = matrix[column][column]
            require(kotlin.math.abs(divisor) > 1e-12) { "透视裁剪点共线或退化" }
            for (row in 0 until 8) {
                if (row == column) continue
                val factor = matrix[row][column] / divisor
                if (factor == 0.0) continue
                for (col in column until 9) {
                    matrix[row][col] -= factor * matrix[column][col]
                }
            }
        }
        val result = DoubleArray(8)
        for (i in 0 until 8) result[i] = matrix[i][8] / matrix[i][i]
        return result
    }

    private fun applyHomography(h: DoubleArray, u: Double, v: Double): Pair<Double, Double> {
        val denominator = h[6] * u + h[7] * v + 1.0
        val x = (h[0] * u + h[1] * v + h[2]) / denominator
        val y = (h[3] * u + h[4] * v + h[5]) / denominator
        return Pair(x, y)
    }
}
