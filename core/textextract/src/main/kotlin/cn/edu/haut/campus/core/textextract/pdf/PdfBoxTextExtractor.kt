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
import cn.edu.haut.campus.core.textextract.extractionProvenance
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException
import java.io.StringWriter
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** PdfBox-Android 嵌入文本抽取器；输出页索引与 PDF point 坐标，供原文高亮回溯。 */
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
            throw TextExtractionException(TextExtractErrorKind.PDF_INVALID, "PDF 无法解析", error)
        } catch (error: Throwable) {
            throw TextExtractionException(TextExtractErrorKind.PDF_EXTRACTION_FAILED, "PDF 加载失败", error)
        }

        try {
            val stripper = LineCollectingPdfTextStripper()
            val plainFallback = try {
                stripper.getText(document)
            } catch (error: NoClassDefFoundError) {
                throw encryptionSupportError(error)
            } catch (error: IOException) {
                throw TextExtractionException(TextExtractErrorKind.PDF_EXTRACTION_FAILED, "PDF 文本抽取失败", error)
            }
            val lines = buildLines(stripper.glyphs)
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
                        engineVersion = "pdfbox-android-2.0.27.0"
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

    private fun encryptionSupportError(cause: Throwable): TextExtractionException {
        return TextExtractionException(
            TextExtractErrorKind.PDF_ENCRYPTION_UNSUPPORTED,
            "PDF 加密支持依赖已按体积预算排除；请使用未加密 PDF 或后续专项评估",
            cause
        )
    }

    private data class PdfGlyph(
        val pageIndex: Int,
        val text: String,
        val x: Float,
        val baselineY: Float,
        val width: Float,
        val height: Float,
        val pageWidth: Float,
        val pageHeight: Float
    )

    private class LineCollectingPdfTextStripper : PDFTextStripper() {
        val glyphs = mutableListOf<PdfGlyph>()
        private var currentPageIndex = -1
        private var currentPageWidth = 0f
        private var currentPageHeight = 0f

        override fun startPage(page: PDPage) {
            currentPageIndex++
            currentPageWidth = page.cropBox.width
            currentPageHeight = page.cropBox.height
            super.startPage(page)
        }

        override fun processTextPosition(text: TextPosition) {
            super.processTextPosition(text)
            val unicode = text.unicode ?: return
            if (unicode.isEmpty()) return
            glyphs.add(
                PdfGlyph(
                    pageIndex = currentPageIndex,
                    text = unicode,
                    x = text.xDirAdj,
                    baselineY = text.yDirAdj,
                    width = text.widthDirAdj,
                    height = text.heightDir,
                    pageWidth = currentPageWidth,
                    pageHeight = currentPageHeight
                )
            )
        }
    }

    private fun buildLines(glyphs: List<PdfGlyph>): List<ExtractedTextLine> {
        val output = mutableListOf<ExtractedTextLine>()
        val pageIndexes = glyphs.map { it.pageIndex }.distinct().sorted()
        for (pageIndex in pageIndexes) {
            val pageGlyphs = glyphs.filter { it.pageIndex == pageIndex }.sortedWith(compareBy({ it.baselineY }, { it.x }))
            val groups = mutableListOf<MutableList<PdfGlyph>>()
            for (glyph in pageGlyphs) {
                val current = groups.lastOrNull()
                if (current == null) {
                    groups.add(mutableListOf(glyph))
                } else {
                    val referenceY = current.map { it.baselineY }.average().toFloat()
                    val threshold = max(2.0f, max(glyph.height, current.maxOf { it.height }) * 0.45f)
                    if (abs(glyph.baselineY - referenceY) <= threshold) {
                        current.add(glyph)
                    } else {
                        groups.add(mutableListOf(glyph))
                    }
                }
            }

            for (group in groups) {
                val ordered = group.sortedBy { it.x }
                val text = mergeGlyphText(ordered)
                if (text.isBlank()) continue
                val left = ordered.minOf { it.x }
                val right = ordered.maxOf { it.x + it.width }
                val top = ordered.minOf { it.baselineY - it.height }
                val bottom = ordered.maxOf { it.baselineY + it.height * 0.22f }
                output.add(
                    ExtractedTextLine(
                        lineIndex = output.size,
                        pageIndex = pageIndex,
                        text = text.trim(),
                        confidence = null,
                        box = TextBoundingBox.axisAligned(left, top, right, bottom),
                        coordinateSpace = TextCoordinateSpace.PDF_POINT_TOP_LEFT
                    )
                )
            }
        }
        return output
    }

    private fun mergeGlyphText(glyphs: List<PdfGlyph>): String {
        val output = StringBuilder()
        var previousEnd = 0f
        var previousHeight = 0f
        for ((index, glyph) in glyphs.withIndex()) {
            if (index > 0) {
                val gap = glyph.x - previousEnd
                val needsSpace = gap > max(1.0f, previousHeight * 0.28f)
                if (needsSpace && output.lastOrNull()?.isWhitespace() != true && !glyph.text.first().isWhitespace()) {
                    output.append(' ')
                }
            }
            output.append(glyph.text)
            previousEnd = glyph.x + glyph.width
            previousHeight = glyph.height
        }
        return output.toString()
    }
}
