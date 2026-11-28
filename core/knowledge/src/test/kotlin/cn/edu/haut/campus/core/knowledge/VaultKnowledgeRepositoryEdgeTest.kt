package cn.edu.haut.campus.core.knowledge

import androidx.room.Room
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.textextract.ExtractionConfirmationState
import cn.edu.haut.campus.core.textextract.ExtractionEngineInfo
import cn.edu.haut.campus.core.textextract.ExtractionMetadata
import cn.edu.haut.campus.core.textextract.ExtractionSourceKind
import cn.edu.haut.campus.core.textextract.ExtractedText
import cn.edu.haut.campus.core.textextract.ExtractedTextLine
import cn.edu.haut.campus.core.textextract.ImageExtractionRequest
import cn.edu.haut.campus.core.textextract.PdfExtractionRequest
import cn.edu.haut.campus.core.textextract.TextBoundingBox
import cn.edu.haut.campus.core.textextract.TextCoordinateSpace
import cn.edu.haut.campus.core.textextract.TextExtractPort
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultKnowledgeRepositoryEdgeTest {
    private lateinit var database: HautDatabase
    private lateinit var repository: KnowledgeRepository
    private lateinit var extractor: EdgeFakeTextExtractPort
    private var now: Instant = Instant.parse("2026-03-01T08:00:00Z")

    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_edge"),
        profileId = ProfileId("prof_edge")
    )
    private val course = CourseAssociation(
        courseId = "linear-algebra",
        courseName = "线性代数",
        courseCode = "MATH201",
        termId = "2025-autumn",
        teachingWeek = 7
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        extractor = EdgeFakeTextExtractPort(scope)
        repository = KnowledgeRuntimeFactory.create(database, scope, extractor, clock = { now })
    }

    @After
    fun tearDown() {
        repository.close()
        database.close()
    }

    @Test
    fun largeMaterialCanBeExportedThroughChunkedBlobReads() = runBlocking {
        val bytes = ByteArray(3 * 1024 * 1024) { index -> (index % 251).toByte() }
        val material = repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.SLIDES,
                title = "大体积课件",
                note = null,
                fileName = "large.pptx",
                mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                contentBytes = bytes
            )
        )
        val output = ByteArrayOutputStream()
        repository.exportCoursePackage(scope, course.courseId, output)
        val entries = zipEntries(output.toByteArray())
        val exported = entries.entries.single { it.key.startsWith("materials/" + material.id + "/") }.value
        assertEquals(bytes.size, exported.size)
        assertEquals(sha256Hex(bytes), sha256Hex(exported))
    }

    @Test
    fun reimportWithoutDeadlineDeletesStaleTimelineEvent() = runBlocking {
        val bytes = "作业正文".toByteArray(Charsets.UTF_8)
        val first = repository.archiveMaterial(assignmentRequest(bytes, title = "第七周作业", due = Instant.parse("2026-03-09T12:00:00Z")))
        val eventId = VaultKnowledgeRepository.assignmentEventId(first.id)
        assertNotNull(database.vaultDao().getPersonalEvent(eventId.value))

        val second = repository.archiveMaterial(assignmentRequest(bytes, title = "第七周作业", due = null))
        assertNull(second.assignment)
        assertNull(database.vaultDao().getPersonalEvent(eventId.value))
    }

    @Test
    fun longestAllowedAssignmentTitleStillArchivesWithBoundedDeadlineTitle() = runBlocking {
        val material = repository.archiveMaterial(
            assignmentRequest(
                bytes = "内容".toByteArray(Charsets.UTF_8),
                title = "长".repeat(256),
                due = Instant.parse("2026-03-09T12:00:00Z")
            )
        )
        val row = database.vaultDao().getPersonalEvent(VaultKnowledgeRepository.assignmentEventId(material.id).value)
        assertNotNull(row)
        assertTrue(row!!.title.length <= 256)
        assertTrue(row.title.startsWith("作业截止："))
    }

    @Test
    fun sameBytesWithDifferentMimeCanBeArchivedInDifferentCourses() = runBlocking {
        val bytes = "同一资料内容".toByteArray(Charsets.UTF_8)
        val otherCourse = course.copy(courseId = "advanced-algebra", courseName = "高等代数")
        val first = repository.archiveMaterial(noteRequest(course, bytes, "text/plain", "第一份"))
        val second = repository.archiveMaterial(noteRequest(otherCourse, bytes, "text/markdown", "第二份"))
        assertTrue(first.id != second.id)
        assertEquals(2, repository.listMaterials(scope).size)
    }

    @Test
    fun noteLineLongerThanModelChunkIsIndexedAcrossBoundary() = runBlocking {
        val longText = "一".repeat(16 * 1024) + "边界关键词"
        val material = repository.archiveMaterial(
            noteRequest(course, longText.toByteArray(Charsets.UTF_8), "text/plain", "长行笔记")
        )
        val hits = repository.searchMaterials(scope, "边界关键词")
        assertEquals(1, hits.size)
        assertEquals(material.id, hits[0].materialId)
        assertTrue(hits[0].snippet.contains("边界关键词"))
    }

    @Test
    fun courseFilterIsAppliedBeforeSearchCandidateLimit() = runBlocking {
        val wanted = repository.archiveMaterial(noteRequest(course, "共同词".toByteArray(Charsets.UTF_8), "text/plain", "目标笔记"))
        now = now.plusSeconds(20)
        extractor.pdfLines = (0 until 90).map { page ->
            extractedLine(page, page, "共同词 页眉 " + page)
        }
        repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course.copy(courseId = "other-course", courseName = "其它课程"),
                kind = CourseMaterialKind.PDF,
                title = "其它课程长课件",
                note = null,
                fileName = "other.pdf",
                mimeType = "application/pdf",
                contentBytes = byteArrayOf(9, 8, 7)
            )
        )
        val hits = repository.searchMaterials(scope, "共同词", courseId = course.courseId, limit = 5)
        assertEquals(listOf(wanted.id), hits.map { it.materialId }.distinct())
    }

    @Test
    fun titleOnlyMatchDoesNotClaimUnrelatedExtractedLineCoordinates() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 0, "完全无关的正文"))
        repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.PDF,
                title = "题名命中词课件",
                note = null,
                fileName = "title.pdf",
                mimeType = "application/pdf",
                contentBytes = byteArrayOf(1, 2, 3)
            )
        )
        val hits = repository.searchMaterials(scope, "题名命中词")
        assertEquals(1, hits.size)
        assertNull(hits[0].pageIndex)
        assertNull(hits[0].lineIndex)
        assertNull(hits[0].bounds)
        assertNull(hits[0].coordinateSpace)
        assertTrue(!hits[0].snippet.contains("完全无关的正文"))
    }

    @Test
    fun blankLinesDoNotShiftNoteLineIndexes() = runBlocking {
        repository.archiveMaterial(
            noteRequest(
                course,
                "第一行\n\n边界关键词".toByteArray(Charsets.UTF_8),
                "text/plain",
                "空行笔记"
            )
        )
        val hits = repository.searchMaterials(scope, "边界关键词")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].lineIndex)
    }

    @Test
    fun multiLineBodyMatchKeepsPageWithoutClaimingExactLine() = runBlocking {
        extractor.pdfLines = listOf(
            extractedLine(0, 2, "特征值与"),
            extractedLine(1, 2, "特征向量")
        )
        repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.PDF,
                title = "特征值 特征向量课件",
                note = null,
                fileName = "chapter.pdf",
                mimeType = "application/pdf",
                contentBytes = byteArrayOf(1, 2, 3)
            )
        )
        val indexedDocs = database.vaultDao().getSearchDocsSnapshot()
        assertEquals(2, indexedDocs.size)
        val pageDoc = indexedDocs.single { it.entityType == cn.edu.haut.campus.core.model.EntityType.COURSE_MATERIAL_PAGE.name }
        assertTrue(pageDoc.tokenStream.contains("征值"))
        assertTrue(pageDoc.tokenStream.contains("征向"))
        assertEquals(1, repository.searchMaterials(scope, "特征值").size)
        assertEquals(1, repository.searchMaterials(scope, "特征向量").size)
        val hits = repository.searchMaterials(scope, "特征值 特征向量")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].pageIndex)
        assertNull(hits[0].lineIndex)
        assertNull(hits[0].bounds)
        assertTrue(hits[0].snippet.contains("特征值"))
        assertTrue(hits[0].snippet.contains("特征向量"))
    }

    @Test
    fun asciiTitleMatchDoesNotUseSubstringInsideUnrelatedBodyWordsAsPageMatch() = runBlocking {
        extractor.pdfLines = listOf(
            extractedLine(0, 4, "nonlinear systems"),
            extractedLine(1, 4, "prealgebra notes")
        )
        repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.PDF,
                title = "linear algebra lecture",
                note = null,
                fileName = "linear.pdf",
                mimeType = "application/pdf",
                contentBytes = byteArrayOf(4, 5, 6)
            )
        )
        val hits = repository.searchMaterials(scope, "linear algebra")
        assertEquals(1, hits.size)
        assertNull(hits[0].pageIndex)
        assertNull(hits[0].lineIndex)
    }

    @Test
    fun asciiTermAdjacentToCjkKeepsExactLineAndPageLocation() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 1, "缓存CPU指令"))
        repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.PDF,
                title = "CPU 指令课件",
                note = null,
                fileName = "cpu.pdf",
                mimeType = "application/pdf",
                contentBytes = byteArrayOf(7, 8, 9)
            )
        )
        val hits = repository.searchMaterials(scope, "CPU")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].pageIndex)
        assertEquals(0, hits[0].lineIndex)
        assertNotNull(hits[0].bounds)
    }

    @Test
    fun singleChineseCharacterUsesBodyFallbackLocation() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 2, "矩阵分析"))
        repository.archiveMaterial(pdfRequest("矩阵课件", byteArrayOf(1)))
        val hits = repository.searchMaterials(scope, "阵")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].pageIndex)
        assertEquals(0, hits[0].lineIndex)
    }

    @Test
    fun mixedScriptMatchDoesNotCrossPunctuationBoundary() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 3, "CPU、缓存"))
        repository.archiveMaterial(pdfRequest("CPU缓存课件", byteArrayOf(2)))
        val hits = repository.searchMaterials(scope, "CPU缓存")
        assertEquals(1, hits.size)
        assertNull(hits[0].pageIndex)
        assertNull(hits[0].lineIndex)
    }

    @Test
    fun asciiTailPrefixAcceptsUnicodeContinuation() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 1, "café notes"))
        repository.archiveMaterial(pdfRequest("咖啡课件", byteArrayOf(3)))
        val hits = repository.searchMaterials(scope, "caf")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].pageIndex)
        assertEquals(0, hits[0].lineIndex)
    }

    @Test
    fun mixedScriptMatchDoesNotEmbedInLongerAsciiWord() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 2, "vCPU缓存"))
        repository.archiveMaterial(pdfRequest("CPU缓存课件", byteArrayOf(4)))
        val hits = repository.searchMaterials(scope, "CPU缓存")
        assertEquals(1, hits.size)
        assertNull(hits[0].pageIndex)
        assertNull(hits[0].lineIndex)
    }

    @Test
    fun mixedScriptMatchAcceptsNaturalScriptBoundaries() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 4, "CPU缓存RAM"))
        repository.archiveMaterial(pdfRequest("混排边界课件", byteArrayOf(6)))

        val forward = repository.searchMaterials(scope, "CPU缓存")
        assertEquals(1, forward.size)
        assertEquals(4, forward[0].pageIndex)
        assertEquals(0, forward[0].lineIndex)
        assertNotNull(forward[0].bounds)

        val backward = repository.searchMaterials(scope, "缓存RAM")
        assertEquals(1, backward.size)
        assertEquals(4, backward[0].pageIndex)
        assertEquals(0, backward[0].lineIndex)
        assertNotNull(backward[0].bounds)
    }

    @Test
    fun mixedScriptTailPrefixCanLocateLongFinalWordRun() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 1, "缓存protocol"))
        repository.archiveMaterial(pdfRequest("协议课件", byteArrayOf(7)))

        val hits = repository.searchMaterials(scope, "缓存pro")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].pageIndex)
        assertEquals(0, hits[0].lineIndex)
        assertNotNull(hits[0].bounds)
    }

    @Test
    fun mixedScriptPrefixDoesNotLocateShortOrNonfinalWordRun() = runBlocking {
        extractor.pdfLines = listOf(
            extractedLine(0, 2, "缓存protocol"),
            extractedLine(1, 2, "x pr")
        )
        repository.archiveMaterial(pdfRequest("缓存pr 缓存pro x 课件", byteArrayOf(8)))

        val shortRunHits = repository.searchMaterials(scope, "缓存pr")
        assertEquals(1, shortRunHits.size)
        assertNull("短尾词不是 FTS 前缀，不得定位到缓存praxis/protocol 类长词", shortRunHits[0].pageIndex)
        assertNull(shortRunHits[0].lineIndex)

        val nonfinalHits = repository.searchMaterials(scope, "缓存pro x")
        assertEquals(1, nonfinalHits.size)
        assertNull("非查询尾部混排片不得享受 pro 前缀续接", nonfinalHits[0].pageIndex)
        assertNull(nonfinalHits[0].lineIndex)
    }

    @Test
    fun photoOcrArchivesIndexesAndKeepsImageCoordinates() = runBlocking {
        extractor.imageLines = listOf(
            extractedLine(0, 0, "黑板：缓存一致性", coordinateSpace = TextCoordinateSpace.IMAGE_PIXEL_TOP_LEFT)
        )
        val pngBytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        )
        val material = repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.PHOTO,
                title = "黑板照片",
                note = null,
                fileName = "board.png",
                mimeType = "image/png",
                contentBytes = pngBytes
            )
        )

        assertEquals(1, extractor.imageExtractionCalls)
        assertEquals(1, extractor.lastImageWidth)
        assertEquals(1, extractor.lastImageHeight)
        assertEquals(ExtractionSourceKind.IMAGE_OCR, material.extraction?.sourceKind)
        val hits = repository.searchMaterials(scope, "缓存一致性")
        assertEquals(1, hits.size)
        assertEquals(material.id, hits[0].materialId)
        assertEquals(0, hits[0].pageIndex)
        assertEquals(0, hits[0].lineIndex)
        assertEquals(KnowledgeCoordinateSpace.IMAGE_PIXEL_TOP_LEFT, hits[0].coordinateSpace)
        val bounds = checkNotNull(hits[0].bounds) { "OCR 命中必须保留图片坐标" }
        assertEquals(0f, bounds.left)
        assertEquals(0f, bounds.top)
        assertEquals(100f, bounds.right)
        assertEquals(10f, bounds.bottom)
    }

    @Test
    fun maliciousFileNameStaysInsideExportAndManifestMatchesBytes() = runBlocking {
        val bytes = "导出完整性".toByteArray(Charsets.UTF_8)
        val material = repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.NOTE,
                title = "恶意文件名课件",
                note = null,
                fileName = "../../evil\\payload\"'.txt",
                mimeType = "text/plain",
                contentBytes = bytes
            )
        )
        val output = ByteArrayOutputStream()
        val summary = repository.exportCoursePackage(scope, course.courseId, output)
        val entries = zipEntries(output.toByteArray())

        assertEquals(2, summary.fileCount)
        assertEquals("zip 条目数必须等于 manifest + 原文 + 抽取 payload", summary.fileCount + 1, entries.size)
        assertTrue(entries.contains("extractions/" + material.id + ".json"))
        val materialEntryName = entries.keys.single { it.startsWith("materials/") }
        val exportFileName = materialEntryName.removePrefix("materials/" + material.id + "/")
        assertTrue(exportFileName.none { it == '/' || it == '\\' })
        assertTrue(!exportFileName.startsWith(".."))

        val manifest = Json.parse(entries.getValue("manifest.json").toString(Charsets.UTF_8)).asObjOrNull
        assertNotNull(manifest)
        val manifestMaterials = manifest!!.requiredArray("materials")
        assertEquals(1, manifestMaterials.size)
        val manifestMaterial = manifestMaterials[0].asObjOrNull!!
        assertEquals(material.contentSha256, manifestMaterial.requiredString("contentSha256"))
        assertEquals(material.byteCount, manifestMaterial.requiredLong("byteCount"))
        assertEquals(material.contentSha256, sha256Hex(entries.getValue(materialEntryName)))
    }

    @Test
    fun courseIdRejectsQuoteAndBackslashAlignedWithSearchQueryFacets() {
        listOf("bad\"course", "bad\\course").forEach { badCourseId ->
            assertTrue(
                "courseId 引号/反斜线必须与 SearchQuery facet 约束一致: " + badCourseId,
                runCatching { course.copy(courseId = badCourseId) }.isFailure
            )
        }
    }

    @Test
    fun pptxWithoutExtractionUsesNoteMetadataWithoutFabricatedPage() = runBlocking {
        val material = repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.SLIDES,
                title = "操作系统课件",
                note = "汇编入口与中断向量表",
                fileName = "os-week7.pptx",
                mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                contentBytes = byteArrayOf(11, 12, 13)
            )
        )
        assertNull(material.extraction)

        val hits = repository.searchMaterials(scope, "汇编入口")
        assertEquals(1, hits.size)
        assertEquals(material.id, hits[0].materialId)
        assertNull("无抽取行时 note 命中不得伪造页码", hits[0].pageIndex)
        assertNull(hits[0].lineIndex)
        assertNull(hits[0].bounds)
        assertNull(hits[0].coordinateSpace)
        assertTrue(
            database.vaultDao().getSearchDocsSnapshot().none { it.facetsJson.contains("\"pageIndex\"") }
        )
    }

    @Test
    fun titleCpuAndBodyCacheCannotCombineIntoBodyPageHit() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 5, "缓存一致性协议"))
        repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.PDF,
                title = "CPU 组成原理",
                note = null,
                fileName = "cpu.pdf",
                mimeType = "application/pdf",
                contentBytes = byteArrayOf(21, 22)
            )
        )

        val compactHits = repository.searchMaterials(scope, "CPU缓存")
        val separatedHits = repository.searchMaterials(scope, "CPU 缓存")
        assertTrue("跨字段拼合不得命中正文页码", compactHits.all { it.pageIndex == null })
        assertTrue("跨字段拼合不得命中正文页码", separatedHits.all { it.pageIndex == null })
        assertTrue(compactHits.isEmpty())
        assertTrue(separatedHits.isEmpty())
    }

    @Test
    fun unicodeCombiningMarkCanContinueTailPrefixMatch() = runBlocking {
        extractor.pdfLines = listOf(extractedLine(0, 1, "किताबें"))
        repository.archiveMaterial(pdfRequest("हिन्दी课件", byteArrayOf(5)))
        val hits = repository.searchMaterials(scope, "कित")
        assertEquals(1, hits.size)
        assertEquals(1, hits[0].pageIndex)
        assertEquals(0, hits[0].lineIndex)
    }

    private fun assignmentRequest(bytes: ByteArray, title: String, due: Instant?): ArchiveMaterialRequest =
        ArchiveMaterialRequest(
            scope = scope,
            course = course,
            kind = CourseMaterialKind.ASSIGNMENT,
            title = title,
            note = null,
            fileName = "assignment.txt",
            mimeType = "text/plain",
            contentBytes = bytes,
            assignmentDueAt = due,
            deadlineZone = ZoneId.of("Asia/Shanghai")
        )

    private fun noteRequest(course: CourseAssociation, bytes: ByteArray, mime: String, title: String): ArchiveMaterialRequest =
        ArchiveMaterialRequest(
            scope = scope,
            course = course,
            kind = CourseMaterialKind.NOTE,
            title = title,
            note = null,
            fileName = "note.txt",
            mimeType = mime,
            contentBytes = bytes
        )

    private fun pdfRequest(title: String, bytes: ByteArray): ArchiveMaterialRequest =
        ArchiveMaterialRequest(
            scope = scope,
            course = course,
            kind = CourseMaterialKind.PDF,
            title = title,
            note = null,
            fileName = "fixture.pdf",
            mimeType = "application/pdf",
            contentBytes = bytes
        )

    private fun extractedLine(
        index: Int,
        page: Int,
        text: String,
        coordinateSpace: TextCoordinateSpace = TextCoordinateSpace.PDF_POINT_TOP_LEFT
    ): ExtractedTextLine = ExtractedTextLine(
        lineIndex = index,
        pageIndex = page,
        text = text,
        confidence = null,
        box = TextBoundingBox.axisAligned(0f, 0f, 100f, 10f),
        coordinateSpace = coordinateSpace
    )

    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private class EdgeFakeTextExtractPort(private val scope: SchoolAccountScope) : TextExtractPort {
        var pdfLines: List<ExtractedTextLine> = emptyList()
        var imageLines: List<ExtractedTextLine> = emptyList()
        var imageExtractionCalls = 0
        var lastImageWidth = 0
        var lastImageHeight = 0

        override suspend fun extractImage(request: ImageExtractionRequest): ExtractedText {
            imageExtractionCalls++
            lastImageWidth = request.image.width
            lastImageHeight = request.image.height
            val provenance = DataProvenance(
                providerId = "fake-textextract",
                schoolId = scope.schoolId,
                accountId = scope.accountId,
                sourceSystem = "fake-image-ocr",
                sourceRecordId = "fixture",
                completeness = SnapshotCompleteness.COMPLETE,
                trust = VerificationLevel.MOCK_TESTED
            )
            return ExtractedText(
                sourceKind = ExtractionSourceKind.IMAGE_OCR,
                sourceHashSha256 = request.sourceHashSha256 ?: "0".repeat(64),
                lines = imageLines,
                plainText = imageLines.joinToString(" ") { it.text },
                provenance = provenance,
                metadata = ExtractionMetadata(
                    engine = ExtractionEngineInfo("fake-ocr", "1"),
                    extractedAt = Instant.parse("2026-03-01T08:00:00Z"),
                    durationMs = 1,
                    sourceWidth = request.image.width.toFloat(),
                    sourceHeight = request.image.height.toFloat(),
                    pageCount = 1
                ),
                confirmationState = ExtractionConfirmationState.UNCONFIRMED
            )
        }

        override suspend fun extractPdf(request: PdfExtractionRequest): ExtractedText {
            val provenance = DataProvenance(
                providerId = "fake-textextract",
                schoolId = scope.schoolId,
                accountId = scope.accountId,
                sourceSystem = "fake-pdf",
                sourceRecordId = "fixture",
                completeness = SnapshotCompleteness.COMPLETE,
                trust = VerificationLevel.MOCK_TESTED
            )
            return ExtractedText(
                sourceKind = ExtractionSourceKind.PDF_EMBEDDED_TEXT,
                sourceHashSha256 = "0".repeat(64),
                lines = pdfLines,
                plainText = pdfLines.joinToString(" ") { it.text },
                provenance = provenance,
                metadata = ExtractionMetadata(
                    engine = ExtractionEngineInfo("fake-pdf", "1"),
                    extractedAt = Instant.parse("2026-03-01T08:00:00Z"),
                    durationMs = 1,
                    pageCount = pdfLines.maxOfOrNull { it.pageIndex }?.plus(1) ?: 0
                ),
                confirmationState = ExtractionConfirmationState.UNCONFIRMED
            )
        }
        override fun close() = Unit
    }
}
