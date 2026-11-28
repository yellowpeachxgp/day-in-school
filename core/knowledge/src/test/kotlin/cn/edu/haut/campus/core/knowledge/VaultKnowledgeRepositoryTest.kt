package cn.edu.haut.campus.core.knowledge

import androidx.room.Room
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
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
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.PersonalTimelineSource
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultKnowledgeRepositoryTest {
    private lateinit var database: HautDatabase
    private lateinit var repository: KnowledgeRepository
    private lateinit var extractor: FakeTextExtractPort
    private var now: Instant = Instant.parse("2026-03-01T08:00:00Z")

    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_knowledge"),
        profileId = ProfileId("prof_knowledge")
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
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
        extractor = FakeTextExtractPort(scope)
        repository = KnowledgeRuntimeFactory.create(database, scope, extractor, clock = { now })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun archiveNotePersistsVaultDocumentsIndexesChineseTextAndExportsZip() = runBlocking {
        val content = "第一章 矩阵\n矩阵的秩与初等变换\n作业：第 3 题".toByteArray(Charsets.UTF_8)
        val material = repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.NOTE,
                title = "线代第七周笔记",
                note = "课堂整理",
                fileName = "week7.txt",
                mimeType = "text/plain",
                contentBytes = content
            )
        )

        assertEquals(1L, material.revision)
        assertEquals(ExtractionConfirmationState.USER_CONFIRMED, material.extraction?.confirmationState)
        assertTrue(database.vaultDao().countBlobStore() >= 2)
        assertEquals(1, database.vaultDao().countEntityJournal())
        assertEquals(1, database.vaultDao().countDomainOutbox())

        val hits = repository.searchMaterials(scope, "矩阵")
        assertEquals(1, hits.size)
        assertEquals(material.id, hits[0].materialId)
        assertEquals(0, hits[0].pageIndex)
        assertEquals(KnowledgeCoordinateSpace.TEXT_LINE, hits[0].coordinateSpace)
        assertTrue(hits[0].snippet.contains("矩阵"))

        val output = ByteArrayOutputStream()
        val summary = repository.exportCoursePackage(scope, course.courseId, output)
        assertEquals(2, summary.fileCount)
        val entries = zipEntries(output.toByteArray())
        assertTrue(entries.contains("manifest.json"))
        assertTrue(entries.any { it.startsWith("materials/" + material.id + "/") })
        assertTrue(entries.contains("extractions/" + material.id + ".json"))
    }

    @Test
    fun pdfSearchHitKeepsZeroBasedPageAndBoundingBoxUntilUiProjection() = runBlocking {
        extractor.pdfLines = listOf(
            extractedLine(0, 2, "特征值与特征向量", 10f, 20f, 180f, 34f),
            extractedLine(1, 2, "矩阵可对角化的判定", 10f, 40f, 200f, 54f)
        )
        val material = repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.PDF,
                title = "第五章课件",
                note = null,
                fileName = "chapter5.pdf",
                mimeType = "application/pdf",
                contentBytes = byteArrayOf(1, 2, 3, 4)
            )
        )

        val hits = repository.searchMaterials(scope, "对角化")
        assertEquals(1, hits.size)
        assertEquals(material.id, hits[0].materialId)
        assertEquals(2, hits[0].pageIndex)
        assertEquals(1, hits[0].lineIndex)
        assertEquals(KnowledgeCoordinateSpace.PDF_POINT_TOP_LEFT, hits[0].coordinateSpace)
        assertEquals(10f, hits[0].bounds?.left)
        assertEquals(ExtractionConfirmationState.UNCONFIRMED, hits[0].confirmationState)

        val confirmed = repository.setExtractionConfirmed(scope, material.id, confirmed = true)
        assertEquals(ExtractionConfirmationState.USER_CONFIRMED, confirmed.extraction?.confirmationState)
        assertEquals(2L, confirmed.revision)
        assertEquals(2, database.vaultDao().countEntityJournal())
    }

    @Test
    fun assignmentDeadlineUsesTaskDuePersonalEventAndTimelineProjection() = runBlocking {
        val due = Instant.parse("2026-03-09T12:00:00Z")
        val material = repository.archiveMaterial(
            ArchiveMaterialRequest(
                scope = scope,
                course = course,
                kind = CourseMaterialKind.ASSIGNMENT,
                title = "第七周作业",
                note = "提交 PDF",
                fileName = "assignment.txt",
                mimeType = "text/plain",
                contentBytes = "作业要求".toByteArray(Charsets.UTF_8),
                assignmentDueAt = due,
                deadlineZone = ZoneId.of("Asia/Shanghai")
            )
        )

        val eventId = VaultKnowledgeRepository.assignmentEventId(material.id)
        val row = database.vaultDao().getPersonalEvent(eventId.value)
        assertNotNull(row)
        assertEquals(TimelineKind.TASK_DUE.name, row?.kind)
        assertEquals(TimelineTimeSemantics.DEADLINE.name, row?.timeSemantics)
        assertEquals(EventStatus.SCHEDULED.name, row?.status)
        assertEquals(due.toEpochMilli(), row?.startAtEpochMs)
        assertEquals(material.id, row?.sourceEntityId)

        val window = TimeWindow(Instant.parse("2026-03-09T00:00:00Z"), Instant.parse("2026-03-10T00:00:00Z"))
        val personalStore = KnowledgeRuntimeFactory.create(database, scope, extractor, clock = { now })
        assertNotNull(personalStore)
        val timelineEvents = PersonalTimelineSource(
            cn.edu.haut.campus.core.vault.RoomPersonalEventStore(
                database = database,
                scope = scope,
                writer = knowledgeJournalForTimeline(database),
                searchIndex = cn.edu.haut.campus.core.vault.RoomSearchIndex(database)
            )
        ).observe(scope, window).first()
        assertTrue(timelineEvents.any { it.id == eventId && it.kind == TimelineKind.TASK_DUE && it.isDeadline })
    }

    @Test
    fun reviewTasksRoundTripThroughVersionedVaultDocuments() = runBlocking {
        val created = ReviewTask(
            id = stableReviewTaskId("linear-algebra|chapter5"),
            scope = scope,
            course = course,
            materialId = null,
            title = "复习特征值",
            note = "先看课件第 3 页",
            state = ReviewTaskState.TODO,
            dueAt = Instant.parse("2026-03-11T12:00:00Z"),
            estimatedMinutes = 90,
            revision = 1,
            createdAt = now,
            updatedAt = now
        )
        repository.upsertReviewTask(created)
        val tasks = repository.listReviewTasks(scope, course.courseId, ReviewTaskState.TODO)
        assertEquals(listOf(created), tasks)
        assertEquals(1, database.vaultDao().countEntityJournal())
    }

    private fun knowledgeJournalForTimeline(database: HautDatabase): cn.edu.haut.campus.core.vault.RoomVaultJournal {
        val searchIndex = cn.edu.haut.campus.core.vault.RoomSearchIndex(database)
        return cn.edu.haut.campus.core.vault.RoomVaultJournal(
            database = database,
            outbox = cn.edu.haut.campus.core.vault.RoomEventOutbox(database),
            snapshotStores = listOf(cn.edu.haut.campus.core.vault.PersonalEventSnapshotStore(database, searchIndex)),
            searchIndex = searchIndex
        )
    }

    private fun extractedLine(index: Int, page: Int, text: String, left: Float, top: Float, right: Float, bottom: Float): ExtractedTextLine {
        return ExtractedTextLine(
            lineIndex = index,
            pageIndex = page,
            text = text,
            confidence = null,
            box = TextBoundingBox.axisAligned(left, top, right, bottom),
            coordinateSpace = TextCoordinateSpace.PDF_POINT_TOP_LEFT
        )
    }

    private fun zipEntries(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        return names
    }

    private class FakeTextExtractPort(private val scope: SchoolAccountScope) : TextExtractPort {
        var pdfLines: List<ExtractedTextLine> = emptyList()

        override suspend fun extractImage(request: ImageExtractionRequest): ExtractedText {
            throw UnsupportedOperationException("image OCR is not used by these tests")
        }

        override suspend fun extractPdf(request: PdfExtractionRequest): ExtractedText {
            val plainText = pdfLines.joinToString("\n") { it.text }
            val provenance = DataProvenance(
                providerId = "fake-textextract",
                schoolId = scope.schoolId,
                accountId = scope.accountId,
                sourceSystem = "fake-pdf",
                sourceRecordId = "fixture",
                fetchedAt = Instant.parse("2026-03-01T08:00:00Z"),
                completeness = SnapshotCompleteness.COMPLETE,
                trust = VerificationLevel.MOCK_TESTED,
                schemaVersion = 1,
                contentRevision = "fixture"
            )
            return ExtractedText(
                sourceKind = ExtractionSourceKind.PDF_EMBEDDED_TEXT,
                sourceHashSha256 = "0".repeat(64),
                lines = pdfLines,
                plainText = plainText,
                provenance = provenance,
                metadata = ExtractionMetadata(
                    engine = ExtractionEngineInfo(engineName = "fake-pdf", engineVersion = "1"),
                    extractedAt = Instant.parse("2026-03-01T08:00:00Z"),
                    durationMs = 1,
                    pageCount = 3
                ),
                confirmationState = ExtractionConfirmationState.UNCONFIRMED
            )
        }

        override fun close() = Unit
    }
}
