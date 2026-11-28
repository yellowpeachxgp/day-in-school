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

    private fun extractedLine(index: Int, page: Int, text: String): ExtractedTextLine = ExtractedTextLine(
        lineIndex = index,
        pageIndex = page,
        text = text,
        confidence = null,
        box = TextBoundingBox.axisAligned(0f, 0f, 100f, 10f),
        coordinateSpace = TextCoordinateSpace.PDF_POINT_TOP_LEFT
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
        override suspend fun extractImage(request: ImageExtractionRequest): ExtractedText = throw UnsupportedOperationException()
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
