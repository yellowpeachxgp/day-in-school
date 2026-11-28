package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.textextract.ExtractionConfirmationState
import cn.edu.haut.campus.core.textextract.TextExtractionContext
import cn.edu.haut.campus.core.textextract.TextExtractPort
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.PersonalEventDraft
import cn.edu.haut.campus.core.timeline.PersonalEventStore
import cn.edu.haut.campus.core.timeline.TimelineEventId
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import cn.edu.haut.campus.core.vault.EntityWriter
import cn.edu.haut.campus.core.vault.RoomSearchIndex
import cn.edu.haut.campus.core.vault.SearchDocument
import cn.edu.haut.campus.core.vault.SearchQuery
import cn.edu.haut.campus.core.vault.SearchTextNormalizer
import cn.edu.haut.campus.core.vault.VaultBlobStore
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** W3-04 Vault 实现：结构 metadata 走版本化 blob，原文走 blob_store，正文索引复用 search_docs/FTS。 */
class VaultKnowledgeRepository internal constructor(
    private val database: HautDatabase,
    private val scope: SchoolAccountScope,
    private val blobStore: VaultBlobStore,
    private val searchIndex: RoomSearchIndex,
    private val writer: EntityWriter,
    private val materialDocuments: VersionedBlobDocumentStore,
    private val reviewDocuments: VersionedBlobDocumentStore,
    private val personalEventStore: PersonalEventStore,
    private val textExtractor: TextExtractPort,
    private val clock: () -> Instant = Instant::now
) : KnowledgeRepository {

    override suspend fun archiveMaterial(request: ArchiveMaterialRequest, actor: Actor): CourseMaterial {
        requireScope(request.scope)
        val extraction = prepareExtraction(
            request = request,
            extractor = textExtractor,
            context = TextExtractionContext(scope = scope, sourceRecordId = request.fileName, extractedAt = clock())
        )
        val contentSha256 = sha256Hex(request.contentBytes)
        val materialId = stableMaterialId(
            listOf(
                scope.schoolId.value,
                scope.accountId.value,
                scope.profileId.value,
                request.course.courseId,
                request.course.teachingWeek.toString(),
                request.kind.name,
                contentSha256
            ).joinToString("|")
        )
        val existing = getMaterial(scope, materialId)
        val now = clock()
        val eventId = request.assignmentDueAt?.let { assignmentEventId(materialId) }
        val ref = EntityRef(EntityType.COURSE_MATERIAL, materialId, scope)
        return writer.write(
            ref = ref,
            actor = actor,
            actorDetail = "knowledge-material-archive"
        ) {
            val rawRef = blobStore.put(
                content = request.contentBytes.copyOf(),
                mimeType = request.mimeType,
                scope = scope,
                owner = null
            )
            var extractionSummary: MaterialExtraction? = null
            if (extraction != null) {
                val extractionBytes = canonicalJson(encodeExtractionPayload(extraction.payload)).toByteArray(Charsets.UTF_8)
                val extractionRef = blobStore.put(
                    content = extractionBytes,
                    mimeType = EXTRACTION_MIME,
                    scope = scope,
                    owner = null
                )
                extractionSummary = MaterialExtraction(
                    blobId = extractionRef.blobId,
                    sourceKind = extraction.sourceKind,
                    plainTextSha256 = extraction.plainTextSha256,
                    lineCount = extraction.payload.lines.size,
                    pageCount = extraction.pageCount,
                    confirmationState = extraction.confirmationState,
                    engineName = extraction.engineName,
                    warnings = extraction.warnings
                )
            }
            val provenance = DataProvenance(
                providerId = "local-knowledge",
                schoolId = scope.schoolId,
                accountId = scope.accountId,
                sourceSystem = "user-material-import",
                sourceRecordId = materialId,
                fetchedAt = now,
                completeness = SnapshotCompleteness.COMPLETE,
                trust = VerificationLevel.SOURCE_CONFIRMED,
                schemaVersion = 1,
                contentRevision = contentSha256
            )
            val material = CourseMaterial(
                id = materialId,
                scope = scope,
                course = request.course,
                kind = request.kind,
                title = request.title,
                note = request.note,
                fileName = request.fileName,
                mimeType = request.mimeType,
                contentBlobId = rawRef.blobId,
                contentSha256 = contentSha256,
                byteCount = request.contentBytes.size.toLong(),
                extraction = extractionSummary,
                assignment = request.assignmentDueAt?.let { dueAt ->
                    AssignmentDeadline(dueAt = dueAt, zone = request.deadlineZone, timelineEventId = eventId)
                },
                provenance = provenance,
                revision = (existing?.revision ?: 0L) + 1L,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            materialDocuments.writeDocument(materialId, encodeCourseMaterialDocument(material))
            replaceSearchProjection(existing, material, extraction?.payload?.lines)
            upsertDeadlineIfNeeded(material, actor)
            material
        }
    }

    override suspend fun getMaterial(scope: SchoolAccountScope, materialId: String): CourseMaterial? {
        requireScope(scope)
        val document = materialDocuments.readDocument(materialId) ?: return null
        return decodeCourseMaterialDocument(document)?.takeIf { it.id == materialId && it.scope == scope }
    }

    override suspend fun listMaterials(scope: SchoolAccountScope, courseId: String?, teachingWeek: Int?): List<CourseMaterial> {
        requireScope(scope)
        return materialDocuments.listDocuments().values.mapNotNull(::decodeCourseMaterialDocument)
            .filter { courseId == null || it.course.courseId == courseId }
            .filter { teachingWeek == null || it.course.teachingWeek == teachingWeek }
            .sortedWith(compareBy<CourseMaterial> { it.course.courseName }.thenBy { it.course.teachingWeek }.thenBy { it.title })
    }

    override suspend fun searchMaterials(
        scope: SchoolAccountScope,
        text: String,
        courseId: String?,
        teachingWeek: Int?,
        limit: Int
    ): List<KnowledgeSearchHit> {
        requireScope(scope)
        require(limit in 1..50) { "知识搜索 limit 必须在 1..50" }
        if (text.isBlank()) return emptyList()
        val page = searchIndex.query(
            scope = scope,
            q = SearchQuery(
                scope = scope,
                text = text,
                types = setOf(EntityType.COURSE_MATERIAL_PAGE),
                limit = (limit * 4).coerceAtMost(100)
            )
        )
        val results = mutableListOf<KnowledgeSearchHit>()
        for (hit in page.hits) {
            val materialId = hit.document.facets["materialId"] ?: continue
            val material = getMaterial(scope, materialId) ?: continue
            if (courseId != null && material.course.courseId != courseId) continue
            if (teachingWeek != null && material.course.teachingWeek != teachingWeek) continue
            val pageIndex = hit.document.facets["pageIndex"]?.toIntOrNull()
            val extractionLines = material.extraction?.let { readExtractionLines(it.blobId) }.orEmpty()
            val located = locateLine(extractionLines, text, pageIndex)
            val snippet = located?.text?.let { makeSnippet(it, text) } ?: hit.snippet
            results += KnowledgeSearchHit(
                materialId = material.id,
                title = material.title,
                course = material.course,
                kind = material.kind,
                pageIndex = located?.pageIndex ?: pageIndex,
                lineIndex = located?.lineIndex,
                snippet = snippet,
                bounds = located?.bounds,
                coordinateSpace = located?.coordinateSpace,
                confirmationState = material.extraction?.confirmationState,
                updatedAt = material.updatedAt
            )
        }
        return results.distinctBy { listOf(it.materialId, it.pageIndex, it.lineIndex).joinToString("/") }.take(limit)
    }

    override suspend fun setExtractionConfirmed(
        scope: SchoolAccountScope,
        materialId: String,
        confirmed: Boolean,
        actor: Actor
    ): CourseMaterial {
        requireScope(scope)
        val existing = getMaterial(scope, materialId) ?: throw IllegalArgumentException("课程资料不存在: " + materialId)
        val extraction = existing.extraction ?: throw IllegalArgumentException("课程资料没有可确认的抽取结果")
        val nextState = if (confirmed) ExtractionConfirmationState.USER_CONFIRMED else ExtractionConfirmationState.UNCONFIRMED
        if (extraction.confirmationState == nextState) return existing
        val lines = readExtractionLines(extraction.blobId)
        val ref = EntityRef(EntityType.COURSE_MATERIAL, materialId, scope)
        return writer.write(ref = ref, actor = actor, actorDetail = "knowledge-extraction-confirmation") {
            val updated = existing.copy(
                extraction = extraction.copy(confirmationState = nextState),
                revision = existing.revision + 1L,
                updatedAt = clock()
            )
            materialDocuments.writeDocument(materialId, encodeCourseMaterialDocument(updated))
            replaceSearchProjection(existing, updated, lines)
            updated
        }
    }

    override suspend fun upsertReviewTask(task: ReviewTask, actor: Actor): ReviewTask {
        requireScope(task.scope)
        val existingDocument = reviewDocuments.readDocument(task.id)
        val existing = existingDocument?.let(::decodeReviewTaskDocument)
        require(task.revision == (existing?.revision ?: 0L) + 1L) { "复习任务 revision 必须单调递增" }
        return writer.write(ref = task.ref, actor = actor, actorDetail = "knowledge-review-task-upsert") {
            reviewDocuments.writeDocument(task.id, encodeReviewTaskDocument(task))
            task
        }
    }

    override suspend fun listReviewTasks(scope: SchoolAccountScope, courseId: String?, state: ReviewTaskState?): List<ReviewTask> {
        requireScope(scope)
        return reviewDocuments.listDocuments().values.mapNotNull(::decodeReviewTaskDocument)
            .filter { courseId == null || it.course.courseId == courseId }
            .filter { state == null || it.state == state }
            .sortedWith(compareBy<ReviewTask> { it.dueAt ?: Instant.MAX }.thenBy { it.title })
    }

    override suspend fun exportCoursePackage(
        scope: SchoolAccountScope,
        courseId: String,
        output: OutputStream,
        teachingWeeks: Set<Int>
    ): KnowledgeExportSummary {
        requireScope(scope)
        val materials = listMaterials(scope, courseId).filter { teachingWeeks.isEmpty() || it.course.teachingWeek in teachingWeeks }
        var fileCount = 0
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val manifest = jsonObjectOf(
                "format" to JsonValue.Str(EXPORT_FORMAT),
                "exportedAt" to JsonValue.Str(clock().toString()),
                "courseId" to JsonValue.Str(courseId),
                "teachingWeeks" to JsonValue.Arr(teachingWeeks.sorted().map { JsonValue.Num(it.toString()) }),
                "materials" to JsonValue.Arr(materials.map(::encodeCourseMaterialDocument))
            )
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(canonicalJson(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            materials.forEach { material ->
                val content = blobStore.get(material.contentBlobId, scope)
                if (content != null) {
                    zip.putNextEntry(ZipEntry("materials/" + material.id + "/" + safeExportFileName(material.fileName, "material.bin")))
                    zip.write(content.content())
                    zip.closeEntry()
                    fileCount++
                }
                material.extraction?.let { extraction ->
                    val extracted = blobStore.get(extraction.blobId, scope)
                    if (extracted != null) {
                        zip.putNextEntry(ZipEntry("extractions/" + material.id + ".json"))
                        zip.write(extracted.content())
                        zip.closeEntry()
                        fileCount++
                    }
                }
            }
        }
        return KnowledgeExportSummary(
            format = EXPORT_FORMAT,
            materialCount = materials.size,
            fileCount = fileCount,
            courseId = courseId
        )
    }

    private suspend fun upsertDeadlineIfNeeded(material: CourseMaterial, actor: Actor) {
        val assignment = material.assignment ?: return
        val materialRef = material.ref
        val courseRef = material.course.courseRef(scope)
        val eventId = assignment.timelineEventId ?: assignmentEventId(material.id)
        personalEventStore.upsert(
            PersonalEventDraft(
                id = eventId,
                scope = scope,
                kind = TimelineKind.TASK_DUE,
                title = "作业截止：" + material.title,
                note = material.note,
                start = assignment.dueAt,
                end = null,
                timeSemantics = TimelineTimeSemantics.DEADLINE,
                zone = assignment.zone,
                status = EventStatus.SCHEDULED,
                busy = BusyLevel.FREE,
                termId = material.course.termId,
                provenance = material.provenance,
                sourceEntity = materialRef,
                links = listOf(materialRef, courseRef).distinct()
            ),
            actor = actor
        )
    }

    private suspend fun replaceSearchProjection(
        oldMaterial: CourseMaterial?,
        newMaterial: CourseMaterial,
        newLines: List<KnowledgeTextLine>?
    ) {
        if (oldMaterial != null) {
            val oldLines = oldMaterial.extraction?.let { readExtractionLines(it.blobId) }
            projectionDocuments(oldMaterial, oldLines).forEach { searchIndex.delete(it.ref) }
        }
        projectionDocuments(newMaterial, newLines).forEach { searchIndex.upsert(it) }
    }

    private fun projectionDocuments(material: CourseMaterial, lines: List<KnowledgeTextLine>?): List<SearchDocument> {
        val chunks = if (lines.isNullOrEmpty()) {
            listOf(SearchTextChunk(pageIndex = 0, partIndex = 0, text = listOfNotNull(material.title, material.note, material.course.courseName).joinToString("
")))
        } else {
            chunkLinesForSearch(lines)
        }
        return chunks.map { chunk ->
            val pageId = material.id + "/page-" + chunk.pageIndex + "-part-" + chunk.partIndex
            val ref = EntityRef(EntityType.COURSE_MATERIAL_PAGE, pageId, scope)
            SearchDocument(
                ref = ref,
                type = EntityType.COURSE_MATERIAL_PAGE,
                title = material.title,
                body = chunk.text,
                code = material.course.courseCode,
                aliases = listOf(material.course.courseName, material.fileName).map { it.take(128) }.filter { it.isNotBlank() }.distinct(),
                termId = material.course.termId,
                at = material.updatedAt,
                deeplink = "dis://knowledge/material/" + material.id + "/page/" + chunk.pageIndex,
                facets = mapOf(
                    "materialId" to material.id,
                    "courseId" to material.course.courseId,
                    "teachingWeek" to material.course.teachingWeek.toString(),
                    "kind" to material.kind.name,
                    "pageIndex" to chunk.pageIndex.toString(),
                    "partIndex" to chunk.partIndex.toString()
                )
            )
        }
    }

    private suspend fun readExtractionLines(blobId: String): List<KnowledgeTextLine> {
        val blob = blobStore.get(blobId, scope) ?: return emptyList()
        return decodeExtractionPayload(blob.content().toString(Charsets.UTF_8)).lines
    }

    private fun locateLine(lines: List<KnowledgeTextLine>, query: String, pageIndex: Int?): KnowledgeTextLine? {
        if (lines.isEmpty()) return null
        val units = SearchTextNormalizer.normalizePlain(query).split(' ').filter { it.isNotBlank() }
        fun matches(line: KnowledgeTextLine): Boolean {
            val normalized = SearchTextNormalizer.normalizePlain(line.text)
            return units.all { normalized.contains(it) }
        }
        return lines.firstOrNull { (pageIndex == null || it.pageIndex == pageIndex) && matches(it) }
            ?: lines.firstOrNull { pageIndex == null || it.pageIndex == pageIndex }
            ?: lines.firstOrNull()
    }

    private fun makeSnippet(text: String, query: String): String {
        val normalizedText = SearchTextNormalizer.normalizePlain(text)
        val firstUnit = SearchTextNormalizer.normalizePlain(query).split(' ').firstOrNull { it.isNotBlank() }.orEmpty()
        val index = if (firstUnit.isEmpty()) -1 else normalizedText.indexOf(firstUnit)
        if (text.length <= 180 || index < 0) return text.replace(Regex("\s+"), " ").take(180)
        val ratio = index.toDouble() / normalizedText.length.coerceAtLeast(1)
        val start = (text.length * ratio).toInt().coerceIn(0, (text.length - 120).coerceAtLeast(0))
        return text.substring(start, (start + 180).coerceAtMost(text.length)).replace(Regex("\s+"), " ")
    }

    private fun requireScope(candidate: SchoolAccountScope) {
        require(candidate == scope) { "KnowledgeRepository 只能访问本 profile scope" }
    }

    companion object {
        const val EXTRACTION_MIME = "application/vnd.dis.knowledge-extraction+json"
        const val EXPORT_FORMAT = "dis-course-knowledge/v1"
        fun assignmentEventId(materialId: String): TimelineEventId = TimelineEventId("personal:knowledge-assignment-" + materialId)
    }
}
