/*
 * OCR pipeline semantics adapted from PaddleOCR deploy/ppocr-android.
 * Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0; see core/textextract/licenses/Apache-2.0.txt.
 * This implementation replaces OpenCV/Mat operations with auditable pure Kotlin code.
 */

package cn.edu.haut.campus.core.textextract.ocr

import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.textextract.ArgbImage
import cn.edu.haut.campus.core.textextract.ExtractedText
import cn.edu.haut.campus.core.textextract.ExtractedTextLine
import cn.edu.haut.campus.core.textextract.ExtractionEngineInfo
import cn.edu.haut.campus.core.textextract.ExtractionMetadata
import cn.edu.haut.campus.core.textextract.ExtractionSourceKind
import cn.edu.haut.campus.core.textextract.ImageExtractionRequest
import cn.edu.haut.campus.core.textextract.OcrEnginePort
import cn.edu.haut.campus.core.textextract.TextBoundingBox
import cn.edu.haut.campus.core.textextract.TextCoordinateSpace
import cn.edu.haut.campus.core.textextract.TextExtractErrorKind
import cn.edu.haut.campus.core.textextract.TextExtractHashes
import cn.edu.haut.campus.core.textextract.TextExtractionException
import cn.edu.haut.campus.core.textextract.TextPoint
import cn.edu.haut.campus.core.textextract.extractionProvenance
import cn.edu.haut.campus.core.textextract.internal.FPoint
import cn.edu.haut.campus.core.textextract.internal.Geometry
import cn.edu.haut.campus.core.textextract.internal.ImageOps
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PP-OCRv6 tiny ONNX 端侧引擎。公开输入/输出不暴露 Paddle、ONNX 或 OpenCV 类型；
 * 图像缩放、轮廓、最小外接矩形、透视裁剪全部使用本模块纯 Kotlin/Android 实现。
 */
class PaddleOcrOnnxEngine(
    detModelBytes: ByteArray,
    recModelBytes: ByteArray,
    characterList: List<String>,
    private val config: PaddleOcrConfig = PaddleOcrConfig(),
    detModelSha256: String = PaddleOcrConfig.DET_SHA256,
    recModelSha256: String = PaddleOcrConfig.REC_SHA256
) : OcrEnginePort {
    private val actualDetModelSha256 = TextExtractHashes.sha256Hex(detModelBytes)
    private val actualRecModelSha256 = TextExtractHashes.sha256Hex(recModelBytes)

    init {
        verifyModelHash("det", actualDetModelSha256, detModelSha256)
        verifyModelHash("rec", actualRecModelSha256, recModelSha256)
    }

    private val backend = OnnxRuntimeBackend(detModelBytes, recModelBytes, config.numThreads)
    private val characterList = characterList.toList()
    private val modelArtifactsSha256 = mapOf("det" to actualDetModelSha256, "rec" to actualRecModelSha256)

    @Volatile
    private var closed = false

    override suspend fun extractImage(request: ImageExtractionRequest): ExtractedText = withContext(Dispatchers.IO) {
        synchronized(this@PaddleOcrOnnxEngine) {
            recognizeLocked(request)
        }
    }

    private fun recognizeLocked(request: ImageExtractionRequest): ExtractedText {
        if (closed) {
            throw TextExtractionException(TextExtractErrorKind.ENGINE_CLOSED, "OCR 引擎已关闭")
        }
        val startedAt = System.currentTimeMillis()
        val extractedAt = request.context.extractedAt ?: Instant.now()
        val image = request.image
        val inputPixels = image.width.toLong() * image.height.toLong()
        if (inputPixels > config.maxInputPixels) {
            throw TextExtractionException(
                TextExtractErrorKind.INPUT_TOO_LARGE,
                "图片像素超过 OCR 输入预算: " + inputPixels + " > " + config.maxInputPixels
            )
        }
        val detInput = DetPreprocessor.preprocess(image, config)
        val (detOutput, detShape) = backend.runDetection(detInput.data, detInput.shape)
        val boxes = DbPostProcessor.process(
            pred = detOutput,
            predShape = detShape,
            thresh = config.detThresh,
            boxThresh = config.detBoxThresh,
            unclipRatio = config.detUnclipRatio,
            maxCandidates = config.detMaxCandidates,
            useDilation = config.detUseDilation,
            scoreMode = "fast",
            originalWidth = detInput.originalWidth,
            originalHeight = detInput.originalHeight
        )
        val sortedBoxes = BoxSorter.sortInReadingOrder(boxes)
        val lines = mutableListOf<ExtractedTextLine>()

        var index = 0
        while (index < sortedBoxes.size) {
            val batchBoxes = sortedBoxes.subList(index, minOf(sortedBoxes.size, index + config.recBatchSize))
            val crops = batchBoxes.map { box -> cropForRecognition(image, box) }
            val recInput = RecPreprocessor.preprocessBatch(crops)
            val (recOutput, recShape) = backend.runRecognition(recInput.data, recInput.shape)
            val decoded = CtcDecoder.decode(recOutput, recShape, characterList)
            for ((batchIndex, pair) in decoded.withIndex()) {
                val text = pair.first
                val confidence = pair.second
                if (text.isNotBlank() && confidence >= config.recScoreThresh) {
                    val box = batchBoxes[batchIndex]
                    lines.add(
                        ExtractedTextLine(
                            lineIndex = lines.size,
                            pageIndex = 0,
                            text = text,
                            confidence = confidence.coerceIn(0f, 1f),
                            box = TextBoundingBox(box.map { TextPoint(it.x.toFloat(), it.y.toFloat()) }),
                            coordinateSpace = TextCoordinateSpace.IMAGE_PIXEL_TOP_LEFT
                        )
                    )
                }
            }
            index += batchBoxes.size
        }

        val duration = System.currentTimeMillis() - startedAt
        val sourceHash = request.sourceHashSha256 ?: TextExtractHashes.sha256Hex(image)
        return ExtractedText(
            sourceKind = ExtractionSourceKind.IMAGE_OCR,
            sourceHashSha256 = sourceHash,
            lines = lines,
            plainText = lines.joinToString("\n") { it.text },
            provenance = extractionProvenance(
                context = request.context,
                sourceSystem = "on-device-paddleocr-onnx",
                sourceHashSha256 = sourceHash,
                extractedAt = extractedAt,
                completeness = if (lines.isEmpty()) SnapshotCompleteness.EMPTY_CONFIRMED else SnapshotCompleteness.COMPLETE
            ),
            metadata = ExtractionMetadata(
                engine = ExtractionEngineInfo(
                    engineName = "PaddleOcrOnnxEngine",
                    engineVersion = "onnxruntime-android-1.21.1",
                    modelName = PaddleOcrConfig.MODEL_NAME,
                    modelVersion = PaddleOcrConfig.MODEL_VERSION,
                    modelArtifactsSha256 = modelArtifactsSha256
                ),
                extractedAt = extractedAt,
                durationMs = duration,
                sourceWidth = image.width.toFloat(),
                sourceHeight = image.height.toFloat(),
                warnings = if (lines.isEmpty()) listOf("未检测到可读文本行") else emptyList(),
                requiresUserConfirmation = true
            )
        )
    }

    private fun verifyModelHash(component: String, actual: String, expected: String) {
        if (!actual.equals(expected, ignoreCase = true)) {
            throw TextExtractionException(
                TextExtractErrorKind.MODEL_INTEGRITY_MISMATCH,
                "OCR 模型 SHA-256 不匹配: " + component
            )
        }
    }

    private fun cropForRecognition(image: ArgbImage, box: List<FPoint>): ArgbImage {
        val rect = Geometry.minimumAreaRect(box)
        val ordered = Geometry.orderQuadPoints(rect.corners)
        return ImageOps.cropPerspective(image, ordered)
    }

    override fun close() {
        synchronized(this) {
            if (!closed) {
                closed = true
                backend.close()
            }
        }
    }

    companion object {
        fun fromModelBytes(
            detModelBytes: ByteArray,
            recModelBytes: ByteArray,
            recConfigYaml: String,
            config: PaddleOcrConfig = PaddleOcrConfig()
        ): PaddleOcrOnnxEngine {
            val characters = ModelConfigParser.parseCharacterList(recConfigYaml, "recConfigYaml")
            return PaddleOcrOnnxEngine(detModelBytes, recModelBytes, characters, config)
        }

    }
}
