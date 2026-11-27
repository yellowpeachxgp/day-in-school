package cn.edu.haut.campus.core.textextract

import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.textextract.pdf.PdfBoxTextExtractor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
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

    @Test
    fun generatedMultiPagePdfPreservesPageIndexesAndRotatedCoordinates() = runBlocking {
        val pdfBytes = pdfWithPages(
            0 to listOf("First page"),
            90 to listOf("Rotated page")
        )
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(pdfBytes, TextExtractionContext.synthetic())
        )

        val first = result.lines.single { it.text.contains("First page") }
        val rotated = result.lines.single { it.text.contains("Rotated page") }
        assertEquals(0, first.pageIndex)
        assertEquals(1, rotated.pageIndex)
        assertEquals(2, result.metadata.pageCount)
        assertTrue("普通页横排宽度应大于高度", first.box.width > first.box.height)
        assertTrue("旋转页横排内容在显示坐标中应沿纵向延展", rotated.box.height > rotated.box.width)
        assertEquals(72f, rotated.box.minY, 5f)
        assertTrue("旋转后的行应靠近显示页右侧", rotated.box.minX > 690f)
        assertTrue(rotated.box.height > 45f)
    }

    @Test
    fun blankPdfReturnsEmptyExtractionWithWarning() = runBlocking {
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(pdfWithPages(0 to emptyList()), TextExtractionContext.synthetic())
        )

        assertTrue(result.lines.isEmpty())
        assertEquals("", result.plainText)
        assertEquals(1, result.metadata.pageCount)
        assertEquals(SnapshotCompleteness.EMPTY_CONFIRMED, result.provenance.completeness)
        assertTrue(result.metadata.warnings.any { it.contains("未检测到嵌入文本") })
    }

    @Test
    fun encryptedPdfIsRejectedEvenWhenEmptyPasswordCanOpenIt() = runBlocking {
        try {
            PdfBoxTextExtractor().extractPdf(
                PdfExtractionRequest(encryptedPdf(), TextExtractionContext.synthetic())
            )
            org.junit.Assert.fail("加密 PDF 不应被静默抽取")
        } catch (error: TextExtractionException) {
            assertEquals(TextExtractErrorKind.PDF_ENCRYPTED, error.kind)
        }
    }

    @Test
    fun contentlessFirstPageDoesNotShiftPhysicalPageIndexes() = runBlocking {
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(contentlessFirstPagePdf(), TextExtractionContext.synthetic())
        )

        val line = result.lines.single { it.text.contains("Second physical page") }
        assertEquals(2, result.metadata.pageCount)
        assertEquals(1, line.pageIndex)
    }

    @Test
    fun overprintedDuplicateTextUsesPdfBoxDeduplicatedPositions() = runBlocking {
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(overprintedPdf(), TextExtractionContext.synthetic())
        )

        assertEquals(1, Regex("DUPLICATE").findAll(result.plainText).count())
        assertEquals("DUPLICATE", result.lines.single().text)
        assertTrue(result.lines.single().box.width > 40f)
    }

    @Test
    fun rtlHebrewUsesPdfBoxLogicalTextAndKeepsTraceableBox() = runBlocking {
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(rtlHebrewPdf(), TextExtractionContext.synthetic())
        )

        val line = result.lines.single()
        assertEquals("שלום", line.text)
        assertEquals(TextCoordinateSpace.PDF_POINT_TOP_LEFT, line.coordinateSpace)
        assertTrue(line.box.width > 20f)
        assertEquals(4, line.box.points.size)
    }

    private fun pdfWithPages(vararg pages: Pair<Int, List<String>>): ByteArray {
        val document = PDDocument()
        try {
            for ((rotation, lines) in pages) {
                val page = PDPage(PDRectangle(612f, 792f))
                page.rotation = rotation
                document.addPage(page)
                val contentStream = PDPageContentStream(document, page)
                try {
                    var baselineY = 720f
                    for (line in lines) {
                        contentStream.beginText()
                        contentStream.setFont(PDType1Font.HELVETICA, 12f)
                        contentStream.newLineAtOffset(72f, baselineY)
                        contentStream.showText(line)
                        contentStream.endText()
                        baselineY -= 24f
                    }
                } finally {
                    contentStream.close()
                }
            }
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun contentlessFirstPagePdf(): ByteArray {
        val document = PDDocument()
        try {
            document.addPage(PDPage(PDRectangle(612f, 792f)))
            val secondPage = PDPage(PDRectangle(612f, 792f))
            document.addPage(secondPage)
            val contentStream = PDPageContentStream(document, secondPage)
            try {
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA, 12f)
                contentStream.newLineAtOffset(72f, 720f)
                contentStream.showText("Second physical page")
                contentStream.endText()
            } finally {
                contentStream.close()
            }
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun overprintedPdf(): ByteArray {
        val document = PDDocument()
        try {
            val page = PDPage(PDRectangle(612f, 792f))
            document.addPage(page)
            val contentStream = PDPageContentStream(document, page)
            try {
                repeat(2) {
                    contentStream.beginText()
                    contentStream.setFont(PDType1Font.HELVETICA, 12f)
                    contentStream.newLineAtOffset(72f, 720f)
                    contentStream.showText("DUPLICATE")
                    contentStream.endText()
                }
            } finally {
                contentStream.close()
            }
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun rtlHebrewPdf(): ByteArray {
        val document = PDDocument()
        try {
            val page = PDPage(PDRectangle(612f, 792f))
            document.addPage(page)
            val fontBytes = requireNotNull(
                javaClass.getResourceAsStream("/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")
            ) { "缺少 LiberationSans 测试字体" }.use { it.readBytes() }
            val font = PDType0Font.load(document, fontBytes.inputStream(), true)
            val contentStream = PDPageContentStream(document, page)
            try {
                contentStream.beginText()
                contentStream.setFont(font, 18f)
                contentStream.newLineAtOffset(300f, 720f)
                // 按视觉顺序写入；PDFBox 规范化输出应恢复 Hebrew logical order。
                contentStream.showText("םולש")
                contentStream.endText()
            } finally {
                contentStream.close()
            }
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        } finally {
            document.close()
        }
    }

    private fun encryptedPdf(): ByteArray {
        val source = pdfWithPages(0 to listOf("Encrypted secret"))
        val document = PDDocument.load(source)
        try {
            val policy = StandardProtectionPolicy("owner-password", "", AccessPermission())
            policy.encryptionKeyLength = 128
            document.protect(policy)
            val output = ByteArrayOutputStream()
            document.save(output)
            return output.toByteArray()
        } finally {
            document.close()
        }
    }
}
