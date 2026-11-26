package cn.edu.haut.campus.core.textextract

import cn.edu.haut.campus.core.textextract.pdf.PdfBoxTextExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfBoxTextExtractorTest {
    @Test
    fun syntheticPdfExtractsEmbeddedLinesAndCoordinates() = runBlocking {
        val extractor = PdfBoxTextExtractor()
        val result = extractor.extractPdf(
            PdfExtractionRequest(
                pdfBytes = FixtureLoader.bytes("synthetic_notice.pdf"),
                context = TextExtractionContext.synthetic()
            )
        )

        assertEquals(ExtractionSourceKind.PDF_EMBEDDED_TEXT, result.sourceKind)
        assertEquals(4, result.lines.size)
        assertEquals("NOTICE INBOX", result.lines[0].text)
        assertEquals("Course update: Wednesday 3-4 period", result.lines[1].text)
        assertEquals("Time: 2026-12-02 14:00-15:40", result.lines[2].text)
        assertEquals("Place: Building 2 Room 201", result.lines[3].text)
        assertEquals(TextCoordinateSpace.PDF_POINT_TOP_LEFT, result.lines[0].coordinateSpace)
        assertNull(result.lines[0].confidence)
        assertTrue(result.lines[0].box.minX >= 70f)
        assertTrue(result.lines[0].box.minY >= 35f)
        assertTrue(result.lines[0].box.width > 80f)
        assertTrue(result.lines[1].box.minY > result.lines[0].box.minY)
        assertEquals(1, result.metadata.pageCount)
        assertEquals(ExtractionConfirmationState.UNCONFIRMED, result.confirmationState)
    }
    @Test
    fun invalidPdfFailsWithExplicitError() = runBlocking {
        val extractor = PdfBoxTextExtractor()
        try {
            extractor.extractPdf(
                PdfExtractionRequest(
                    pdfBytes = byteArrayOf(1, 2, 3, 4),
                    context = TextExtractionContext.synthetic()
                )
            )
            org.junit.Assert.fail("非法 PDF 不应成功")
        } catch (error: TextExtractionException) {
            assertEquals(TextExtractErrorKind.PDF_INVALID, error.kind)
        }
    }

}
