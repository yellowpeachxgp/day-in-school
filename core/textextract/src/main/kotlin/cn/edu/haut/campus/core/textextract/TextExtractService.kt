package cn.edu.haut.campus.core.textextract

/** 组合 OCR 与 PDF 嵌入文本抽取的默认 Port；原件存储与擦除属于后续 inbox/vault 模块职责。 */
class DefaultTextExtractService(
    private val ocrEngine: OcrEnginePort,
    private val pdfExtractor: PdfTextExtractorPort
) : TextExtractPort {
    override suspend fun extractImage(request: ImageExtractionRequest): ExtractedText {
        return ocrEngine.extractImage(request)
    }

    override suspend fun extractPdf(request: PdfExtractionRequest): ExtractedText {
        return pdfExtractor.extractPdf(request)
    }

    override fun close() {
        ocrEngine.close()
    }
}
