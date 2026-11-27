/*
 * OCR pipeline semantics adapted from PaddleOCR deploy/ppocr-android.
 * Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; see core/textextract/licenses/Apache-2.0.txt.
 * This implementation replaces OpenCV/Mat operations with auditable pure Kotlin code.
 */

package cn.edu.haut.campus.core.textextract.ocr

import cn.edu.haut.campus.core.textextract.internal.FPoint
import cn.edu.haut.campus.core.textextract.internal.Geometry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** DB 文本框扩张；算法语义移植自 PaddleOCR Android 示例，但使用本模块纯 Kotlin 几何类型。 */
internal object PolygonUnclip {
    private const val EPS = 1e-6
    private const val ARC_TOLERANCE = 0.25

    fun unclip(points: List<FPoint>, unclipRatio: Float): List<FPoint> {
        if (points.size < 3) return points

        val signedArea = Geometry.polygonSignedArea(points)
        val area = abs(signedArea)
        val perimeter = Geometry.polygonPerimeter(points)
        if (!area.isFinite() || !perimeter.isFinite() || area <= EPS || perimeter <= EPS) return points

        val distance = area * unclipRatio / perimeter
        if (!distance.isFinite() || distance <= EPS) return points

        val clockwiseInImageCoords = signedArea > 0.0
        val normals = mutableListOf<FPoint>()
        for (i in points.indices) {
            val start = points[i]
            val end = points[(i + 1) % points.size]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val len = hypot(dx, dy)
            if (!len.isFinite() || len <= EPS) return points
            normals.add(
                if (clockwiseInImageCoords) FPoint(dy / len, -dx / len) else FPoint(-dy / len, dx / len)
            )
        }

        val expanded = mutableListOf<FPoint>()
        for (i in points.indices) {
            appendRoundJoin(
                output = expanded,
                center = points[i],
                fromNormal = normals[(i - 1 + normals.size) % normals.size],
                toNormal = normals[i],
                distance = distance,
                clockwiseInImageCoords = clockwiseInImageCoords
            )
        }
        return if (expanded.size >= 3) expanded else points
    }

    private fun appendRoundJoin(
        output: MutableList<FPoint>,
        center: FPoint,
        fromNormal: FPoint,
        toNormal: FPoint,
        distance: Double,
        clockwiseInImageCoords: Boolean
    ) {
        var startAngle = atan2(fromNormal.y, fromNormal.x)
        var endAngle = atan2(toNormal.y, toNormal.x)
        if (clockwiseInImageCoords) {
            while (endAngle < startAngle) endAngle += 2.0 * PI
        } else {
            while (endAngle > startAngle) endAngle -= 2.0 * PI
        }

        val sweep = endAngle - startAngle
        val steps = ceil(abs(sweep) / arcStepAngle(distance)).toInt().coerceAtLeast(1)
        for (step in 0..steps) {
            val angle = startAngle + sweep * step.toDouble() / steps.toDouble()
            appendPoint(output, FPoint(center.x + cos(angle) * distance, center.y + sin(angle) * distance))
        }
    }

    private fun arcStepAngle(distance: Double): Double {
        val ratio = (1.0 - ARC_TOLERANCE / distance).coerceIn(-1.0, 1.0)
        val step = 2.0 * acos(ratio)
        return if (step.isFinite() && step > EPS) step else PI / 8.0
    }

    private fun appendPoint(output: MutableList<FPoint>, point: FPoint) {
        val last = output.lastOrNull()
        if (last == null || hypot(point.x - last.x, point.y - last.y) > EPS) output.add(point)
    }
}
