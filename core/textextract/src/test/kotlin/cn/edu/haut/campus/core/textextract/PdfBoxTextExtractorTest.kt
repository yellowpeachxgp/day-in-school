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
import com.tom_roush.pdfbox.pdmodel.interactive.pagenavigation.PDThread
import com.tom_roush.pdfbox.pdmodel.interactive.pagenavigation.PDThreadBead
import com.tom_roush.pdfbox.util.Matrix
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

        val lineSummary = result.lines.map { it.pageIndex.toString() + ":" + it.text }
        assertTrue("未抽出普通页文本: " + lineSummary, result.lines.any { it.text.contains("First page") })
        assertTrue("未抽出旋转页文本: " + lineSummary, result.lines.any { it.text.contains("Rotated page") })
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

    @Test
    fun rotate90PositionedWordGapDoesNotFuseIntoIlllit() = runBlocking {
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(rotate90PositionedWordsPdf(), TextExtractionContext.synthetic())
        )

        assertTrue("不应融合为 illlit，实际: " + result.plainText, !result.plainText.contains("illlit"))
        val hasSpacedLine = result.lines.any { it.text == "ill lit" }
        val hasSeparateWords = result.lines.any { it.text == "ill" } && result.lines.any { it.text == "lit" }
        assertTrue("positioned words 应保留为空格或独立边界: " + result.lines.map { it.text }, hasSpacedLine || hasSeparateWords)
    }

    @Test
    fun articleBoundaryPreventsVerticalLabelMerge() = runBlocking {
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(articleBoundaryVerticalLabelsPdf(), TextExtractionContext.synthetic())
        )

        val articleLines = result.lines.map { it.text }
        assertTrue("缺少 AAA: " + articleLines, result.lines.any { it.text.contains("AAA") })
        assertTrue("缺少 BBB: " + articleLines, result.lines.any { it.text.contains("BBB") })
        assertTrue("文章边界内文本不应跨 bead 融合: " + result.lines.map { it.text }, result.lines.none { it.text.contains("AAA") && it.text.contains("BBB") })
    }

    @Test
    fun mixedFontCaptionStaysOutsideBodyLine() = runBlocking {
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(mixedFontCaptionPdf(), TextExtractionContext.synthetic())
        )

        assertTrue(result.lines.any { it.text == "Body" })
        assertTrue(result.lines.any { it.text == "caption" })
        assertTrue(result.lines.none { it.text.contains("Body") && it.text.contains("caption") })
    }

    @Test
    fun rotatedLabelsWithLargeProjectionGapStaySeparate() = runBlocking {
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(rotatedSeparateLabelsPdf(), TextExtractionContext.synthetic())
        )

        val rotatedLines = result.lines.map { it.text }
        assertTrue("缺少 FIRST: " + rotatedLines, result.lines.any { it.text == "FIRST" })
        assertTrue("缺少 SECOND: " + rotatedLines, result.lines.any { it.text == "SECOND" })
        assertTrue(result.lines.none { it.text.contains("FIRST") && it.text.contains("SECOND") })
    }

    @Test
    fun raisedReferenceKeepsPdfBoxNormalizedLineOrder() = runBlocking {
        val result = PdfBoxTextExtractor().extractPdf(
            PdfExtractionRequest(raisedReferencePdf(), TextExtractionContext.synthetic())
        )

        val line = result.lines.single { it.text.contains("Report") }
        assertTrue("raised reference 行不匹配，全部行: " + result.lines.map { it.text }, line.text.contains("[1]"))
        assertTrue(line.text.indexOf("Report") < line.text.indexOf("[1]"))
    }

    @Test
    fun debugArticleDraftMetadata() {
        org.junit.Assert.fail(probeDrafts(articleBoundaryVerticalLabelsPdf()))
    }

    private fun probeDrafts(pdfBytes: ByteArray): String {
        val stripperClass = Class.forName("cn.edu.haut.campus.core.textextract.pdf.PdfBoxTextExtractor\$LineCollectingPdfTextStripper")
        val constructor = stripperClass.getDeclaredConstructor()
        constructor.isAccessible = true
        val stripper = constructor.newInstance()
        val document = PDDocument.load(pdfBytes)
        try {
            val getText = stripperClass.methods.first { it.name == "getText" && it.parameterCount == 1 }
            getText.invoke(stripper, document)
            val drafts = readField(stripper, "lineDrafts") as List<*>
            return drafts.joinToString(prefix = "drafts=", separator = "|") { draft ->
                val pieces = readField(draft!!, "pieces") as List<*>
                val text = pieces.joinToString("") { piece -> readField(piece!!, "text") as String }
                val glyphs = pieces.flatMap { piece -> readField(piece!!, "glyphs") as List<*> }
                val xs = glyphs.flatMap { glyph -> listOf(readField(glyph!!, "displayStartX") as Float, readField(glyph, "displayEndX") as Float) }
                val ys = glyphs.flatMap { glyph -> listOf(readField(glyph!!, "displayStartY") as Float, readField(glyph, "displayEndY") as Float) }
                "o" + readField(draft, "order") + "/a" + readField(draft, "articleIndex") + "/p" + readField(draft, "paragraphIndex") + ":" + text + "@x" + (xs.minOrNull() ?: 0f) + ".." + (xs.maxOrNull() ?: 0f) + ",y" + (ys.minOrNull() ?: 0f) + ".." + (ys.maxOrNull() ?: 0f)
            }
        } finally {
            document.close()
        }
    }

    private fun readField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
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

    private fun rotate90PositionedWordsPdf(): ByteArray {
        return singlePagePdf(rotation = 90) { contentStream ->
            writeText(contentStream, "ill", 72f, 720f, 12f)
            writeText(contentStream, "lit", 88f, 720f, 12f)
        }
    }

    private fun articleBoundaryVerticalLabelsPdf(): ByteArray {
        return singlePagePdf(rotation = 90, configurePage = { page ->
            // PdfBox 2.0 的 bead rectangle 按未旋转页面坐标换算；这里按 /Rotate 90 后的
            // display x/y 目标区域反写 PDF 坐标，确保两块 vertical label 落入不同 article bead。
            val firstBead = PDThreadBead()
            firstBead.page = page
            firstBead.rectangle = PDRectangle(250f, 715f, 300f, 57f)
            val secondBead = PDThreadBead()
            secondBead.page = page
            secondBead.rectangle = PDRectangle(250f, 642f, 300f, 73f)
            val thread = PDThread()
            thread.firstBead = firstBead
            firstBead.appendBead(secondBead)
            page.threadBeads = listOf(firstBead, secondBead)
        }) { contentStream ->
            writeText(contentStream, "AAA", 50f, 400f, 12f)
            writeText(contentStream, "BBB", 80f, 400f, 12f)
        }
    }

    private fun mixedFontCaptionPdf(): ByteArray {
        return singlePagePdf { contentStream ->
            writeText(contentStream, "Body", 72f, 720f, 16f)
            writeText(contentStream, "caption", 76f, 704f, 7f)
        }
    }

    private fun rotatedSeparateLabelsPdf(): ByteArray {
        return singlePagePdf(rotation = 90) { contentStream ->
            writeText(contentStream, "FIRST", 72f, 720f, 12f)
            writeText(contentStream, "SECOND", 180f, 720f, 12f)
        }
    }

    private fun raisedReferencePdf(): ByteArray {
        return singlePagePdf { contentStream ->
            writeText(contentStream, "Report", 72f, 720f, 12f)
            writeText(contentStream, "[1]", 110f, 723f, 7f)
        }
    }

    private fun singlePagePdf(
        rotation: Int = 0,
        configurePage: (PDPage) -> Unit = {},
        writeContent: (PDPageContentStream) -> Unit
    ): ByteArray {
        val document = PDDocument()
        try {
            val page = PDPage(PDRectangle(612f, 792f))
            page.rotation = rotation
            document.addPage(page)
            configurePage(page)
            val contentStream = PDPageContentStream(document, page)
            try {
                writeContent(contentStream)
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

    private fun writeText(
        contentStream: PDPageContentStream,
        text: String,
        x: Float,
        y: Float,
        fontSize: Float
    ) {
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, fontSize)
        contentStream.newLineAtOffset(x, y)
        contentStream.showText(text)
        contentStream.endText()
    }

    private fun writeText(
        contentStream: PDPageContentStream,
        text: String,
        matrix: Matrix,
        fontSize: Float
    ) {
        contentStream.beginText()
        contentStream.setFont(PDType1Font.HELVETICA, fontSize)
        contentStream.setTextMatrix(matrix)
        contentStream.showText(text)
        contentStream.endText()
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
