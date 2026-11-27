/*
 * OCR pipeline semantics adapted from PaddleOCR deploy/ppocr-android.
 * Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; see core/textextract/licenses/Apache-2.0.txt.
 * This implementation replaces OpenCV/Mat operations with auditable pure Kotlin code.
 */

package cn.edu.haut.campus.core.textextract.ocr

import cn.edu.haut.campus.core.textextract.internal.FPoint
import kotlin.math.abs

internal object BoxSorter {
    private const val ROW_THRESHOLD_Y = 10.0

    fun sortInReadingOrder(boxes: List<List<FPoint>>): List<List<FPoint>> {
        if (boxes.size <= 1) return boxes
        val list = boxes.toMutableList()
        list.sortWith(compareBy({ it[0].y }, { it[0].x }))
        for (i in 0 until list.size - 1) {
            var j = i
            while (j >= 0) {
                val next = list[j + 1]
                val current = list[j]
                if (abs(next[0].y - current[0].y) < ROW_THRESHOLD_Y && next[0].x < current[0].x) {
                    list[j] = next
                    list[j + 1] = current
                    j--
                } else {
                    break
                }
            }
        }
        return list
    }
}
