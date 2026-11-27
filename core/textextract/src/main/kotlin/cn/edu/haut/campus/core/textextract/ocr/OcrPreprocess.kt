/*
 * OCR pipeline semantics adapted from PaddleOCR deploy/ppocr-android.
 * Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; see core/textextract/licenses/Apache-2.0.txt.
 * This implementation replaces OpenCV/Mat operations with auditable pure Kotlin code.
 */

package cn.edu.haut.campus.core.textextract.ocr

import cn.edu.haut.campus.core.textextract.ArgbImage
import cn.edu.haut.campus.core.textextract.internal.Geometry
import cn.edu.haut.campus.core.textextract.internal.ImageOps
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

internal data class TensorInput(
    val data: FloatArray,
    val shape: LongArray,
    val originalWidth: Int,
    val originalHeight: Int
)

internal object DetPreprocessor {
    private val mean = doubleArrayOf(0.485, 0.456, 0.406)
    private val std = doubleArrayOf(0.229, 0.224, 0.225)
    private const val scale = 1.0 / 255.0

    fun preprocess(image: ArgbImage, config: PaddleOcrConfig): TensorInput {
        val ratioInfo = targetDetectionSize(image.width, image.height, config)
        val resized = ImageOps.resizeBilinear(image, ratioInfo.first, ratioInfo.second)
        val channelSize = resized.width * resized.height
        val tensor = FloatArray(3 * channelSize)
        val useBgr = config.detImgMode.equals("BGR", ignoreCase = true)
        for (y in 0 until resized.height) {
            for (x in 0 until resized.width) {
                val pixel = resized.pixel(x, y)
                val r = (pixel ushr 16) and 0xff
                val g = (pixel ushr 8) and 0xff
                val b = pixel and 0xff
                val channels = if (useBgr) intArrayOf(b, g, r) else intArrayOf(r, g, b)
                val offset = y * resized.width + x
                for (channel in 0..2) {
                    tensor[channel * channelSize + offset] =
                        ((channels[channel] * scale - mean[channel]) / std[channel]).toFloat()
                }
            }
        }
        return TensorInput(
            data = tensor,
            shape = longArrayOf(1, 3, resized.height.toLong(), resized.width.toLong()),
            originalWidth = image.width,
            originalHeight = image.height
        )
    }

    private fun targetDetectionSize(width: Int, height: Int, config: PaddleOcrConfig): Pair<Int, Int> {
        var ratio = when (config.detLimitType.lowercase()) {
            "max" -> if (maxOf(width, height) > config.detLimitSideLen) {
                config.detLimitSideLen.toDouble() / maxOf(width, height)
            } else 1.0
            "min" -> if (minOf(width, height) < config.detLimitSideLen) {
                config.detLimitSideLen.toDouble() / minOf(width, height)
            } else 1.0
            "resize_long" -> config.detLimitSideLen.toDouble() / maxOf(width, height)
            else -> throw IllegalArgumentException("不支持的检测缩放模式: " + config.detLimitType)
        }
        var newWidth = (width * ratio).toInt()
        var newHeight = (height * ratio).toInt()
        if (maxOf(newWidth, newHeight) > config.detMaxSideLimit) {
            ratio = config.detMaxSideLimit.toDouble() / maxOf(newWidth, newHeight)
            newWidth = (newWidth * ratio).toInt()
            newHeight = (newHeight * ratio).toInt()
        }
        newWidth = maxOf(Geometry.roundHalfToEven(newWidth / 32.0) * 32, 32)
        newHeight = maxOf(Geometry.roundHalfToEven(newHeight / 32.0) * 32, 32)
        if (newWidth.toLong() * newHeight.toLong() > config.detMaxDetectionPixels) {
            val budgetScale = sqrt(config.detMaxDetectionPixels.toDouble() / (newWidth.toDouble() * newHeight.toDouble()))
            newWidth = maxOf(floor(newWidth * budgetScale / 32.0).toInt() * 32, 32)
            newHeight = maxOf(floor(newHeight * budgetScale / 32.0).toInt() * 32, 32)
            while (newWidth.toLong() * newHeight.toLong() > config.detMaxDetectionPixels && (newWidth > 32 || newHeight > 32)) {
                if (newWidth >= newHeight && newWidth > 32) {
                    newWidth -= 32
                } else if (newHeight > 32) {
                    newHeight -= 32
                } else {
                    newWidth -= 32
                }
            }
        }
        return Pair(newWidth, newHeight)
    }
}

internal object RecPreprocessor {
    const val FIXED_HEIGHT = 48
    private const val MAX_IMAGE_WIDTH = 3200

    fun preprocessBatch(crops: List<ArgbImage>): TensorInput {
        require(crops.isNotEmpty()) { "识别裁剪不能为空" }
        val resized = crops.map { crop ->
            val aspect = crop.width.toDouble() / crop.height.toDouble()
            val width = ceil(FIXED_HEIGHT * aspect).toInt().coerceIn(1, MAX_IMAGE_WIDTH)
            ImageOps.resizeBilinear(crop, width, FIXED_HEIGHT)
        }
        val maxWidth = resized.maxOf { it.width }
        val batch = resized.size
        val channelSize = FIXED_HEIGHT * maxWidth
        val tensor = FloatArray(batch * 3 * channelSize)
        for ((batchIndex, image) in resized.withIndex()) {
            for (y in 0 until FIXED_HEIGHT) {
                for (x in 0 until image.width) {
                    val pixel = image.pixel(x, y)
                    val rgb = intArrayOf((pixel ushr 16) and 0xff, (pixel ushr 8) and 0xff, pixel and 0xff)
                    val offset = y * maxWidth + x
                    for (channel in 0..2) {
                        tensor[(batchIndex * 3 + channel) * channelSize + offset] = rgb[channel] / 127.5f - 1.0f
                    }
                }
            }
        }
        return TensorInput(
            data = tensor,
            shape = longArrayOf(batch.toLong(), 3, FIXED_HEIGHT.toLong(), maxWidth.toLong()),
            originalWidth = maxWidth,
            originalHeight = FIXED_HEIGHT
        )
    }
}
