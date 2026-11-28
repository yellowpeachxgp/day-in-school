package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.textextract.ExtractionConfirmationState
import cn.edu.haut.campus.core.textextract.ExtractionSourceKind
import cn.edu.haut.campus.core.textextract.ExtractedText
import cn.edu.haut.campus.core.textextract.ImageExtractionRequest
import cn.edu.haut.campus.core.textextract.PdfExtractionRequest
import cn.edu.haut.campus.core.textextract.TextCoordinateSpace
import cn.edu.haut.campus.core.textextract.TextExtractionContext
import cn.edu.haut.campus.core.textextract.TextExtractHashes
import cn.edu.haut.campus.core.textextract.TextExtractPort
import cn.edu.haut.campus.core.textextract.android.decodeArgbImage
import java.security.MessageDigest

internal data class PreparedExtraction(
    val payload: KnowledgeExtractionPayload,
    val sourceKind: ExtractionSourceKind?,
    val plainTextSha256: String,
    val pageCount: Int,
    val confirmationState: ExtractionConfirmationState,
    val engineName: String?,
    val warnings: List<String>
)

internal data class SearchTextChunk(
    val pageIndex: Int,
    val partIndex: Int,
    val text: String
)

internal suspend fun prepareExtraction(
    request: ArchiveMaterialRequest,
    extractor: TextExtractPort,
    context: TextExtractionContext
): PreparedExtraction? {
    val lowerName = request.fileName.lowercase()
    val lowerMime = request.mimeType.lowercase()
    return when {
        request.kind == CourseMaterialKind.NOTE -> prepareNoteExtraction(request.contentBytes)
        request.kind == CourseMaterialKind.PDF || lowerMime == "application/pdf" || lowerName.endsWith(".pdf") -> {
            val extracted = extractor.extractPdf(PdfExtractionRequest(request.contentBytes.copyOf(), context))
            prepareFromExtractedText(extracted)
        }
        request.kind == CourseMaterialKind.PHOTO || lowerMime.startsWith("image/") -> {
            val image = request.contentBytes.copyOf().decodeArgbImage()
            val extracted = extractor.extractImage(
                ImageExtractionRequest(
                    image = image,
                    context = context,
                    sourceHashSha256 = TextExtractHashes.sha256Hex(request.contentBytes)
                )
            )
            prepareFromExtractedText(extracted)
        }
        else -> null
    }
}

internal fun prepareNoteExtraction(bytes: ByteArray): PreparedExtraction {
    val text = bytes.toString(Charsets.UTF_8)
    val rows = text.split(10.toChar()).mapIndexed { index, raw ->
        KnowledgeTextLine(
            lineIndex = index,
            pageIndex = 0,
            text = raw.removeSuffix(13.toChar().toString()),
            confidence = null,
            bounds = null,
            coordinateSpace = KnowledgeCoordinateSpace.TEXT_LINE
        )
    }.filter { it.text.isNotBlank() }
    val payload = KnowledgeExtractionPayload(plainText = text, lines = rows)
    return PreparedExtraction(
        payload = payload,
        sourceKind = null,
        plainTextSha256 = TextExtractHashes.sha256Hex(text.toByteArray(Charsets.UTF_8)),
        pageCount = if (rows.isEmpty()) 0 else 1,
        confirmationState = ExtractionConfirmationState.USER_CONFIRMED,
        engineName = null,
        warnings = emptyList()
    )
}

internal fun prepareFromExtractedText(extracted: ExtractedText): PreparedExtraction {
    val lines = extracted.lines.map { line ->
        KnowledgeTextLine(
            lineIndex = line.lineIndex,
            pageIndex = line.pageIndex,
            text = line.text,
            confidence = line.confidence,
            bounds = KnowledgeTextBounds(
                left = line.box.minX,
                top = line.box.minY,
                right = line.box.maxX,
                bottom = line.box.maxY
            ),
            coordinateSpace = when (line.coordinateSpace) {
                TextCoordinateSpace.IMAGE_PIXEL_TOP_LEFT -> KnowledgeCoordinateSpace.IMAGE_PIXEL_TOP_LEFT
                TextCoordinateSpace.PDF_POINT_TOP_LEFT -> KnowledgeCoordinateSpace.PDF_POINT_TOP_LEFT
            }
        )
    }
    val pageCount = extracted.metadata.pageCount ?: (lines.maxOfOrNull { it.pageIndex }?.plus(1) ?: 0)
    return PreparedExtraction(
        payload = KnowledgeExtractionPayload(plainText = extracted.plainText, lines = lines),
        sourceKind = extracted.sourceKind,
        plainTextSha256 = TextExtractHashes.sha256Hex(extracted.plainText.toByteArray(Charsets.UTF_8)),
        pageCount = pageCount,
        confirmationState = extracted.confirmationState,
        engineName = extracted.metadata.engine.engineName,
        warnings = extracted.metadata.warnings
    )
}

/** 按页切分为 search_docs 32 KiB 上限以下的块；切块只影响索引，不改变抽取行级定位。 */
internal fun chunkLinesForSearch(lines: List<KnowledgeTextLine>, maxChars: Int = 24 * 1024): List<SearchTextChunk> {
    val result = mutableListOf<SearchTextChunk>()
    lines.groupBy { it.pageIndex }.toSortedMap().forEach { (pageIndex, pageLines) ->
        var partIndex = 0
        val current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                result += SearchTextChunk(pageIndex = pageIndex, partIndex = partIndex, text = current.toString())
                partIndex++
                current.clear()
            }
        }
        pageLines.sortedBy { it.lineIndex }.forEach { line ->
            var text = line.text
            if (text.length > maxChars) {
                flush()
                text.chunked(maxChars).forEach { piece ->
                    result += SearchTextChunk(pageIndex = pageIndex, partIndex = partIndex, text = piece)
                    partIndex++
                }
                return@forEach
            }
            val extra = if (current.isEmpty()) text.length else text.length + 1
            if (current.length + extra > maxChars) flush()
            if (current.isNotEmpty()) current.append(10.toChar())
            current.append(text)
        }
        flush()
        if (partIndex == 0) {
            result += SearchTextChunk(pageIndex = pageIndex, partIndex = 0, text = "")
        }
    }
    return result
}

internal fun stableMaterialId(seed: String): String = "mat-" + sha256Hex(seed.toByteArray(Charsets.UTF_8)).take(32)
internal fun stableReviewTaskId(seed: String): String = "review-" + sha256Hex(seed.toByteArray(Charsets.UTF_8)).take(28)

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun safeExportFileName(raw: String, fallback: String): String {
    val forbidden = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
    val cleaned = raw.map { ch -> if (ch in forbidden) '_' else ch }.joinToString("").trim().trim('.')
    return cleaned.ifBlank { fallback }.take(120)
}
