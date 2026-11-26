package cn.edu.haut.campus.core.textextract

import org.junit.Assert.assertEquals
import org.junit.Test

class TextExtractModelsTest {
    @Test
    fun emptyPdfInputFailsWithExplicitError() {
        try {
            PdfExtractionRequest(byteArrayOf(), TextExtractionContext.synthetic())
            org.junit.Assert.fail("空 PDF 输入不应成功")
        } catch (error: TextExtractionException) {
            assertEquals(TextExtractErrorKind.INVALID_INPUT, error.kind)
        }
    }

    @Test
    fun malformedImageSourceHashFailsWithExplicitError() {
        val image = ArgbImage(1, 1, intArrayOf(0xFF000000.toInt()))
        try {
            ImageExtractionRequest(
                image = image,
                context = TextExtractionContext.synthetic(),
                sourceHashSha256 = "not-a-sha256"
            )
            org.junit.Assert.fail("非法图片哈希不应成功")
        } catch (error: TextExtractionException) {
            assertEquals(TextExtractErrorKind.INVALID_INPUT, error.kind)
        }
    }
}
