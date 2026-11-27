/*
 * OCR pipeline semantics adapted from PaddleOCR deploy/ppocr-android.
 * Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; see core/textextract/licenses/Apache-2.0.txt.
 * This implementation replaces OpenCV/Mat operations with auditable pure Kotlin code.
 */

package cn.edu.haut.campus.core.textextract.ocr

import cn.edu.haut.campus.core.textextract.internal.FPoint
import cn.edu.haut.campus.core.textextract.internal.Geometry
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

internal object DbPostProcessor {
    private const val MIN_SIZE_BEFORE_UNCLIP = 3.0
    private const val MIN_SIZE_AFTER_UNCLIP = 5.0

    fun process(
        pred: FloatArray,
        predShape: LongArray,
        thresh: Float,
        boxThresh: Float,
        unclipRatio: Float,
        maxCandidates: Int,
        useDilation: Boolean,
        scoreMode: String,
        originalWidth: Int,
        originalHeight: Int
    ): List<List<FPoint>> {
        require(predShape.size == 4) { "DB 输出必须是 NCHW 四维" }
        val outputHeight = predShape[2].toInt()
        val outputWidth = predShape[3].toInt()
        require(outputHeight > 0 && outputWidth > 0) { "DB 输出尺寸非法" }
        require(pred.size >= outputWidth * outputHeight) { "DB 输出数据长度不足" }

        val mask = BooleanArray(outputWidth * outputHeight)
        for (i in mask.indices) mask[i] = pred[i] > thresh
        val contourMask = if (useDilation) dilate(mask, outputWidth, outputHeight) else mask
        val components = ConnectedComponents.extract(contourMask, outputWidth, outputHeight)
        val scaleX = originalWidth.toDouble() / outputWidth
        val scaleY = originalHeight.toDouble() / outputHeight
        val boxes = mutableListOf<List<FPoint>>()

        for (component in components.take(maxCandidates)) {
            val rect = Geometry.minimumAreaRect(component.boundaryPoints)
            if (minOf(rect.width, rect.height) < MIN_SIZE_BEFORE_UNCLIP) continue
            val ordered = Geometry.orderQuadPoints(rect.corners)
            val score = computeBoxScore(pred, outputWidth, outputHeight, ordered)
            if (score < boxThresh) continue

            val expanded = PolygonUnclip.unclip(ordered, unclipRatio)
            val expandedRect = Geometry.minimumAreaRect(expanded)
            if (minOf(expandedRect.width, expandedRect.height) < MIN_SIZE_AFTER_UNCLIP) continue
            val expandedOrdered = Geometry.orderQuadPoints(expandedRect.corners)
            val scaled = expandedOrdered.map { point ->
                FPoint(
                    Geometry.roundHalfToEven(point.x * scaleX).coerceIn(0, originalWidth).toDouble(),
                    Geometry.roundHalfToEven(point.y * scaleY).coerceIn(0, originalHeight).toDouble()
                )
            }
            val boxWidth = hypot(scaled[1].x - scaled[0].x, scaled[1].y - scaled[0].y)
            val boxHeight = hypot(scaled[3].x - scaled[0].x, scaled[3].y - scaled[0].y)
            if (boxWidth <= 3.0 || boxHeight <= 3.0) continue
            boxes.add(scaled)
        }
        return boxes
    }

    private fun computeBoxScore(
        prob: FloatArray,
        width: Int,
        height: Int,
        polygon: List<FPoint>
    ): Float {
        if (polygon.isEmpty()) return 0f
        val xmin = floor(polygon.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val xmax = ceil(polygon.maxOf { it.x }).toInt().coerceIn(0, width - 1)
        val ymin = floor(polygon.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val ymax = ceil(polygon.maxOf { it.y }).toInt().coerceIn(0, height - 1)
        var sum = 0.0
        var count = 0
        for (y in ymin..ymax) {
            for (x in xmin..xmax) {
                if (pointInPolygonOrBoundary(x + 0.5, y + 0.5, polygon)) {
                    sum += prob[y * width + x]
                    count++
                }
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun pointInPolygonOrBoundary(x: Double, y: Double, polygon: List<FPoint>): Boolean {
        if (Geometry.pointInPolygon(x, y, polygon)) return true
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if (distanceToSegment(x, y, a, b) <= 0.51) return true
        }
        return false
    }

    private fun distanceToSegment(x: Double, y: Double, a: FPoint, b: FPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-12) return hypot(x - a.x, y - a.y)
        val t = (((x - a.x) * dx + (y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        val px = a.x + t * dx
        val py = a.y + t * dy
        return hypot(x - px, y - py)
    }

    private fun dilate(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var value = false
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height && mask[ny * width + nx]) value = true
                    }
                }
                out[y * width + x] = value
            }
        }
        return out
    }
}
