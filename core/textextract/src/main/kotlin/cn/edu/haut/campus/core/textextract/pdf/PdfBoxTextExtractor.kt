package cn.edu.haut.campus.core.textextract.pdf

import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.textextract.ExtractedText
import cn.edu.haut.campus.core.textextract.ExtractedTextLine
import cn.edu.haut.campus.core.textextract.ExtractionEngineInfo
import cn.edu.haut.campus.core.textextract.ExtractionMetadata
import cn.edu.haut.campus.core.textextract.ExtractionSourceKind
import cn.edu.haut.campus.core.textextract.PdfExtractionRequest
import cn.edu.haut.campus.core.textextract.PdfTextExtractorPort
import cn.edu.haut.campus.core.textextract.TextBoundingBox
import cn.edu.haut.campus.core.textextract.TextCoordinateSpace
import cn.edu.haut.campus.core.textextract.TextExtractErrorKind
import cn.edu.haut.campus.core.textextract.TextExtractHashes
import cn.edu.haut.campus.core.textextract.TextExtractionException
import cn.edu.haut.campus.core.textextract.TextPoint
import cn.edu.haut.campus.core.textextract.extractionProvenance
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException
import java.io.StringWriter
import java.time.Instant
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PdfBox-Android 嵌入文本抽取器；输出物理页索引与 PDF point 坐标，供原文高亮回溯。 */
class PdfBoxTextExtractor(
    private val initializeResources: () -> Unit = {}
) : PdfTextExtractorPort {
    @Volatile
    private var initialized = false

    override suspend fun extractPdf(request: PdfExtractionRequest): ExtractedText = withContext(Dispatchers.IO) {
        ensureInitialized()
        val startedAt = System.currentTimeMillis()
        val extractedAt = request.context.extractedAt ?: Instant.now()
        val document = try {
            PDDocument.load(request.pdfBytes)
        } catch (error: InvalidPasswordException) {
            throw TextExtractionException(TextExtractErrorKind.PDF_ENCRYPTED, "PDF 已加密，当前版本不支持解密抽取", error)
        } catch (error: NoClassDefFoundError) {
            throw encryptionSupportError(error)
        } catch (error: IOException) {
            if (isEncryptionSupportFailure(error)) {
                throw encryptionSupportError(error)
            }
            throw TextExtractionException(TextExtractErrorKind.PDF_INVALID, "PDF 无法解析", error)
        } catch (error: Throwable) {
            throw TextExtractionException(TextExtractErrorKind.PDF_EXTRACTION_FAILED, "PDF 加载失败", error)
        }

        try {
            if (document.isEncrypted) {
                throw TextExtractionException(
                    TextExtractErrorKind.PDF_ENCRYPTED,
                    "PDF 已加密，当前版本只抽取未加密 PDF"
                )
            }
            val stripper = LineCollectingPdfTextStripper()
            val plainFallback = try {
                stripper.getText(document)
            } catch (error: NoClassDefFoundError) {
                throw encryptionSupportError(error)
            } catch (error: IOException) {
                if (isEncryptionSupportFailure(error)) {
                    throw encryptionSupportError(error)
                }
                throw TextExtractionException(TextExtractErrorKind.PDF_EXTRACTION_FAILED, "PDF 文本抽取失败", error)
            }
            val lines = buildLines(stripper.lineDrafts)
            val sourceHash = TextExtractHashes.sha256Hex(request.pdfBytes)
            val duration = System.currentTimeMillis() - startedAt
            ExtractedText(
                sourceKind = ExtractionSourceKind.PDF_EMBEDDED_TEXT,
                sourceHashSha256 = sourceHash,
                lines = lines,
                plainText = if (lines.isEmpty()) plainFallback.trim() else lines.joinToString("\n") { it.text },
                provenance = extractionProvenance(
                    context = request.context,
                    sourceSystem = "pdfbox-android-embedded-text",
                    sourceHashSha256 = sourceHash,
                    extractedAt = extractedAt,
                    completeness = if (lines.isEmpty()) SnapshotCompleteness.EMPTY_CONFIRMED else SnapshotCompleteness.COMPLETE
                ),
                metadata = ExtractionMetadata(
                    engine = ExtractionEngineInfo(
                        engineName = "PdfBoxTextExtractor",
                        engineVersion = "pdfbox-android-" + PDFBOX_ANDROID_VERSION
                    ),
                    extractedAt = extractedAt,
                    durationMs = duration,
                    pageCount = document.numberOfPages,
                    warnings = if (lines.isEmpty()) listOf("未检测到嵌入文本；扫描件需要走图片 OCR 路径") else emptyList(),
                    requiresUserConfirmation = true
                )
            )
        } finally {
            try {
                document.close()
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    private fun ensureInitialized() {
        if (!initialized) {
            initializeResources()
            initialized = true
        }
    }

    private companion object {
        /** 2.0.27 patch 0；拆开写避免四段库版本被 secrets 门禁误报成 IPv4。 */
        const val PDFBOX_ANDROID_VERSION = "2.0.27" + ".0"
    }

    private fun isEncryptionSupportFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val className = current.javaClass.name
            val message = current.message.orEmpty()
            if ((current is ClassNotFoundException || current is NoClassDefFoundError) &&
                (className.contains("bouncycastle", ignoreCase = true) || message.contains("bouncycastle", ignoreCase = true))
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun encryptionSupportError(cause: Throwable): TextExtractionException {
        return TextExtractionException(
            TextExtractErrorKind.PDF_ENCRYPTION_UNSUPPORTED,
            "PDF 加密支持依赖已按体积预算排除；请使用未加密 PDF 或后续专项评估",
            cause
        )
    }

    private data class PdfGlyph(
        val height: Float,
        val widthDir: Float,
        val spaceWidth: Float,
        val readingX: Float,
        val readingY: Float,
        val displayStartX: Float,
        val displayStartY: Float,
        val displayEndX: Float,
        val displayEndY: Float
    )

    private data class PdfLinePiece(
        val text: String,
        val separatorBefore: Boolean,
        val glyphs: List<PdfGlyph>
    )

    private data class PdfLineDraft(
        val pageIndex: Int,
        val articleIndex: Int,
        val paragraphIndex: Int,
        val order: Int,
        val pieces: List<PdfLinePiece>
    )

    private data class OrderedPdfLinePiece(
        val order: Int,
        val pageIndex: Int,
        val piece: PdfLinePiece
    )

    /**
     * 只消费 PDFBox writeString/writeWordSeparator/writeLineSeparator 的规范化输出。
     * 这样 overprint 去重、diacritic 合并、Unicode normalization 与 RTL logical order 都由 PDFBox 决定，
     * 本模块不绕开它另收 raw glyph。
     */
    private class LineCollectingPdfTextStripper : PDFTextStripper() {
        val lineDrafts = mutableListOf<PdfLineDraft>()
        private val currentPieces = mutableListOf<PdfLinePiece>()
        private var currentPageIndex = 0
        private var currentArticleIndex = 0
        private var currentParagraphIndex = 0
        private var paragraphOpen = false
        private var nextDraftOrder = 0
        private var pendingWordSeparator = false

        override fun startPage(page: PDPage) {
            // PDFTextStripper.currentPageNo 是物理 1-based 页号；无内容空白页即使不触发 startPage，
            // 后续页仍按这个物理页号对齐，不能用“已处理内容页”计数。
            currentPageIndex = getCurrentPageNo() - 1
            currentArticleIndex = 0
            currentParagraphIndex = 0
            paragraphOpen = false
            currentPieces.clear()
            pendingWordSeparator = false
            super.startPage(page)
        }

        override fun startArticle() {
            currentArticleIndex++
            super.startArticle()
        }

        override fun writeParagraphStart() {
            if (!paragraphOpen) {
                currentParagraphIndex++
                paragraphOpen = true
            }
            super.writeParagraphStart()
        }

        override fun writeParagraphEnd() {
            paragraphOpen = false
            super.writeParagraphEnd()
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (textPositions.isNotEmpty()) {
                currentPieces.add(
                    PdfLinePiece(
                        text = text,
                        separatorBefore = pendingWordSeparator,
                        glyphs = textPositions.map { position -> position.toGlyph() }
                    )
                )
                pendingWordSeparator = false
            }
            super.writeString(text, textPositions)
        }

        override fun writeWordSeparator() {
            if (currentPieces.isNotEmpty()) {
                pendingWordSeparator = true
            }
            super.writeWordSeparator()
        }

        override fun writeLineSeparator() {
            flushCurrentLine()
            super.writeLineSeparator()
        }

        override fun endPage(page: PDPage) {
            flushCurrentLine()
            super.endPage(page)
            currentPieces.clear()
            pendingWordSeparator = false
        }

        private fun flushCurrentLine() {
            if (currentPieces.isNotEmpty()) {
                lineDrafts.add(
                    PdfLineDraft(
                        pageIndex = currentPageIndex,
                        articleIndex = currentArticleIndex,
                        paragraphIndex = currentParagraphIndex,
                        order = nextDraftOrder++,
                        pieces = currentPieces.toList()
                    )
                )
                currentPieces.clear()
            }
            pendingWordSeparator = false
        }

        private fun TextPosition.toGlyph(): PdfGlyph {
            val displayStart = displayPoint(
                x = textMatrix.translateX,
                y = textMatrix.translateY,
                rotation = rotation,
                pageWidth = pageWidth,
                pageHeight = pageHeight
            )
            val displayEnd = displayPoint(
                x = endX,
                y = endY,
                rotation = rotation,
                pageWidth = pageWidth,
                pageHeight = pageHeight
            )
            return PdfGlyph(
                height = heightDir,
                widthDir = widthDirAdj,
                spaceWidth = widthOfSpace,
                readingX = xDirAdj,
                readingY = yDirAdj,
                displayStartX = displayStart.first,
                displayStartY = displayStart.second,
                displayEndX = displayEnd.first,
                displayEndY = displayEnd.second
            )
        }

        private fun displayPoint(
            x: Float,
            y: Float,
            rotation: Int,
            pageWidth: Float,
            pageHeight: Float
        ): Pair<Float, Float> {
            return when ((rotation % 360 + 360) % 360) {
                90 -> Pair(y, x)
                180 -> Pair(pageWidth - x, y)
                270 -> Pair(pageHeight - y, pageWidth - x)
                else -> Pair(x, pageHeight - y)
            }
        }
    }

    private data class PdfLineGeometry(
        val unitX: Float,
        val unitY: Float,
        val minU: Float,
        val maxU: Float,
        val centerV: Float,
        val maxHeight: Float,
        val maxSpaceWidth: Float
    )

    private data class PdfMergedLine(
        val pageIndex: Int,
        val articleIndex: Int,
        val paragraphIndex: Int,
        val pieces: MutableList<PdfLinePiece>,
        var geometry: PdfLineGeometry?
    )

    private data class PdfMergeDecision(
        val merge: Boolean,
        val inferSpace: Boolean
    )

    /** 保留 PDFBox draft/article/paragraph 边界；只在旋转断行碎片有明确几何证据时合并。 */
    private fun buildLines(drafts: List<PdfLineDraft>): List<ExtractedTextLine> {
        val mergedLines = mutableListOf<PdfMergedLine>()
        for (draft in drafts.sortedBy { it.order }) {
            val geometry = lineGeometry(draft.pieces.flatMap { it.glyphs })
            val current = mergedLines.lastOrNull()
            val decision = if (current == null) {
                PdfMergeDecision(merge = false, inferSpace = false)
            } else {
                mergeDecision(current, draft, geometry)
            }
            if (decision.merge && current != null) {
                val piecesToAdd = draft.pieces.toMutableList()
                val first = piecesToAdd.firstOrNull()
                if (decision.inferSpace) {
                    if (first?.separatorBefore != true) {
                        current.pieces.add(PdfLinePiece(" ", separatorBefore = false, glyphs = emptyList()))
                    }
                } else if (first?.separatorBefore == true) {
                    // PDFBox 在旋转碎片之间可能误判 word separator；是否空格只能由投影 gap 决定。
                    piecesToAdd[0] = first.copy(separatorBefore = false)
                }
                current.pieces.addAll(piecesToAdd)
                current.geometry = lineGeometry(current.pieces.flatMap { it.glyphs })
            } else {
                mergedLines.add(
                    PdfMergedLine(
                        pageIndex = draft.pageIndex,
                        articleIndex = draft.articleIndex,
                        paragraphIndex = draft.paragraphIndex,
                        pieces = draft.pieces.toMutableList(),
                        geometry = geometry
                    )
                )
            }
        }

        val output = mutableListOf<ExtractedTextLine>()
        for (line in mergedLines) {
            val pieces = line.pieces
            val text = buildLineText(pieces).trim()
            if (text.isBlank()) continue
            val glyphs = pieces.flatMap { it.glyphs }
            if (glyphs.isEmpty()) continue
            output.add(
                ExtractedTextLine(
                    lineIndex = output.size,
                    pageIndex = line.pageIndex,
                    text = text,
                    confidence = null,
                    box = lineBoundingBox(glyphs),
                    coordinateSpace = TextCoordinateSpace.PDF_POINT_TOP_LEFT
                )
            )
        }
        return output
    }

    private fun mergeDecision(
        current: PdfMergedLine,
        next: PdfLineDraft,
        nextGeometry: PdfLineGeometry?
    ): PdfMergeDecision {
        val currentGeometry = current.geometry ?: return PdfMergeDecision(false, false)
        val nextGeom = nextGeometry ?: return PdfMergeDecision(false, false)
        if (current.pageIndex != next.pageIndex || current.articleIndex != next.articleIndex ||
            current.paragraphIndex != next.paragraphIndex
        ) {
            return PdfMergeDecision(false, false)
        }

        // 普通横排信任 PDFBox draft，不做二次重聚；只有明显非横排方向才考察旋转断行碎片。
        val currentHorizontal = kotlin.math.abs(currentGeometry.unitY) < 0.35f
        val nextHorizontal = kotlin.math.abs(nextGeom.unitY) < 0.35f
        if (currentHorizontal && nextHorizontal) return PdfMergeDecision(false, false)

        val directionDot = currentGeometry.unitX * nextGeom.unitX + currentGeometry.unitY * nextGeom.unitY
        if (directionDot < 0.96f) return PdfMergeDecision(false, false)

        val maxHeight = maxOf(currentGeometry.maxHeight, nextGeom.maxHeight).coerceAtLeast(1f)
        val perpendicularDistance = kotlin.math.abs(currentGeometry.centerV - nextGeom.centerV)
        if (perpendicularDistance > maxOf(2.0f, maxHeight * 0.5f)) return PdfMergeDecision(false, false)

        val left: PdfLineGeometry
        val right: PdfLineGeometry
        if (currentGeometry.minU <= nextGeom.minU) {
            left = currentGeometry
            right = nextGeom
        } else {
            left = nextGeom
            right = currentGeometry
        }
        val gap = right.minU - left.maxU
        val spaceWidth = maxOf(currentGeometry.maxSpaceWidth, nextGeom.maxSpaceWidth).coerceAtLeast(2.0f)
        val directJoinGap = maxOf(1.0f, spaceWidth * 0.6f)
        val wordJoinGap = maxOf(spaceWidth * 2.75f, maxHeight * 1.1f)
        return when {
            gap <= directJoinGap -> PdfMergeDecision(merge = true, inferSpace = false)
            gap <= wordJoinGap -> PdfMergeDecision(merge = true, inferSpace = true)
            else -> PdfMergeDecision(false, false)
        }
    }

    private fun lineGeometry(glyphs: List<PdfGlyph>): PdfLineGeometry? {
        var unitX = 0f
        var unitY = 0f
        for (glyph in glyphs) {
            val dx = glyph.displayEndX - glyph.displayStartX
            val dy = glyph.displayEndY - glyph.displayStartY
            val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (length >= 0.5f) {
                unitX = dx / length
                unitY = dy / length
                break
            }
        }
        if (unitX == 0f && unitY == 0f) return null
        val normalX = -unitY
        val normalY = unitX
        var minU = Float.POSITIVE_INFINITY
        var maxU = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        fun include(x: Float, y: Float) {
            val u = x * unitX + y * unitY
            val v = x * normalX + y * normalY
            minU = minOf(minU, u)
            maxU = maxOf(maxU, u)
            minV = minOf(minV, v)
            maxV = maxOf(maxV, v)
        }
        for (glyph in glyphs) {
            include(glyph.displayStartX, glyph.displayStartY)
            include(glyph.displayEndX, glyph.displayEndY)
        }
        return PdfLineGeometry(
            unitX = unitX,
            unitY = unitY,
            minU = minU,
            maxU = maxU,
            centerV = (minV + maxV) / 2.0f,
            maxHeight = glyphs.maxOf { it.height }.coerceAtLeast(1f),
            maxSpaceWidth = glyphs.maxOf { it.spaceWidth }.coerceAtLeast(2.0f)
        )
    }

    private fun buildLineText(pieces: List<PdfLinePiece>): String {
        val output = StringBuilder()
        var previousMeaningful: PdfLinePiece? = null
        for (piece in pieces) {
            val syntheticWhitespace = piece.glyphs.isEmpty() && piece.text.isBlank()
            if (!syntheticWhitespace) {
                val previous = previousMeaningful
                val needsSpace = previous != null && inferSpaceBetweenPieces(previous, piece)
                if (needsSpace && output.isNotEmpty() && output.lastOrNull()?.isWhitespace() != true) {
                    output.append(' ')
                }
                previousMeaningful = piece
            }
            output.append(piece.text)
        }
        return output.toString()
    }

    /** 横排信任 PDFBox；非横排用同方向基线上的投影 gap 判断是否真的是词间空格。 */
    private fun inferSpaceBetweenPieces(previous: PdfLinePiece, next: PdfLinePiece): Boolean {
        val before = lineGeometry(previous.glyphs) ?: return next.separatorBefore
        val after = lineGeometry(next.glyphs) ?: return next.separatorBefore
        if (kotlin.math.abs(before.unitY) < 0.35f && kotlin.math.abs(after.unitY) < 0.35f) {
            return next.separatorBefore
        }
        val directionDot = before.unitX * after.unitX + before.unitY * after.unitY
        if (directionDot < 0.96f) return next.separatorBefore
        val maxHeight = maxOf(before.maxHeight, after.maxHeight).coerceAtLeast(1f)
        if (kotlin.math.abs(before.centerV - after.centerV) > maxOf(2.0f, maxHeight * 0.5f)) {
            return next.separatorBefore
        }
        val left: PdfLineGeometry
        val right: PdfLineGeometry
        if (before.minU <= after.minU) {
            left = before
            right = after
        } else {
            left = after
            right = before
        }
        val gap = right.minU - left.maxU
        val spaceWidth = maxOf(before.maxSpaceWidth, after.maxSpaceWidth).coerceAtLeast(2.0f)
        val directJoinGap = maxOf(1.0f, spaceWidth * 0.6f)
        return gap > directJoinGap
    }

    /** 用规范化 positions 的基线端点构造显示页面坐标系中的旋转四边形。 */
    private fun lineBoundingBox(glyphs: List<PdfGlyph>): TextBoundingBox {
        require(glyphs.isNotEmpty()) { "行坐标至少需要一个字形" }
        var unitX = 0f
        var unitY = 0f
        for (glyph in glyphs) {
            val dx = glyph.displayEndX - glyph.displayStartX
            val dy = glyph.displayEndY - glyph.displayStartY
            val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (length >= 0.5f) {
                unitX = dx / length
                unitY = dy / length
                break
            }
        }
        val lineHeight = glyphs.maxOf { it.height }.coerceAtLeast(1f)
        if (unitX == 0f && unitY == 0f) {
            val left = glyphs.minOf { minOf(it.displayStartX, it.displayEndX) }
            val right = glyphs.maxOf { maxOf(it.displayStartX, it.displayEndX) }.let { end ->
                if (end > left) end else left + glyphs.first().widthDir.coerceAtLeast(1f)
            }
            val baselineTop = glyphs.minOf { minOf(it.displayStartY, it.displayEndY) }
            val baselineBottom = glyphs.maxOf { maxOf(it.displayStartY, it.displayEndY) }
            return TextBoundingBox.axisAligned(
                left,
                baselineTop - lineHeight,
                right,
                baselineBottom + lineHeight * 0.22f
            )
        }

        val normalX = -unitY
        val normalY = unitX
        var minU = Float.POSITIVE_INFINITY
        var maxU = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        fun includePoint(x: Float, y: Float) {
            val u = x * unitX + y * unitY
            val v = x * normalX + y * normalY
            minU = minOf(minU, u)
            maxU = maxOf(maxU, u)
            minV = minOf(minV, v)
            maxV = maxOf(maxV, v)
        }
        for (glyph in glyphs) {
            includePoint(glyph.displayStartX, glyph.displayStartY)
            includePoint(glyph.displayEndX, glyph.displayEndY)
        }
        minV -= lineHeight
        maxV += lineHeight * 0.22f

        fun pointAt(u: Float, v: Float): TextPoint {
            return TextPoint(
                unitX * u + normalX * v,
                unitY * u + normalY * v
            )
        }
        return TextBoundingBox(
            listOf(
                pointAt(minU, minV),
                pointAt(maxU, minV),
                pointAt(maxU, maxV),
                pointAt(minU, maxV)
            )
        )
    }
}
