package cn.edu.haut.campus.core.textextract

interface OcrEnginePort : AutoCloseable {
    suspend fun extractImage(request: ImageExtractionRequest): ExtractedText

    override fun close()
}

interface PdfTextExtractorPort {
    suspend fun extractPdf(request: PdfExtractionRequest): ExtractedText
}

interface TextExtractPort : OcrEnginePort, PdfTextExtractorPort
