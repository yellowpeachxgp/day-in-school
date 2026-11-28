package cn.edu.haut.campus.core.knowledge

import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.vault.RoomSearchIndex
import cn.edu.haut.campus.core.vault.SearchDocument
import cn.edu.haut.campus.core.vault.VaultBlobStore

/** 课程资料页级全文索引投影；普通写入与 journal revert 共用，避免撤销后留下 FTS 幽灵结果。 */
internal class KnowledgeSearchProjector(
    private val scope: SchoolAccountScope,
    private val searchIndex: RoomSearchIndex,
    private val blobStore: VaultBlobStore
) {
    suspend fun replace(
        oldMaterial: CourseMaterial?,
        newMaterial: CourseMaterial?,
        newLines: List<KnowledgeTextLine>? = null
    ) {
        if (oldMaterial != null) {
            val oldLines = oldMaterial.extraction?.let { readExtractionLines(it.blobId) }
            projectionDocuments(oldMaterial, oldLines).forEach { searchIndex.delete(it.ref) }
        }
        if (newMaterial != null) {
            val effectiveLines = newLines ?: newMaterial.extraction?.let { readExtractionLines(it.blobId) }
            projectionDocuments(newMaterial, effectiveLines).forEach { searchIndex.upsert(it) }
        }
    }

    suspend fun readExtractionLines(blobId: String): List<KnowledgeTextLine> {
        val blob = blobStore.get(blobId, scope) ?: return emptyList()
        return decodeExtractionPayload(blob.content().toString(Charsets.UTF_8)).lines
    }

    private fun projectionDocuments(material: CourseMaterial, lines: List<KnowledgeTextLine>?): List<SearchDocument> {
        val chunks = if (lines.isNullOrEmpty()) {
            listOf(
                SearchTextChunk(
                    pageIndex = 0,
                    partIndex = 0,
                    text = listOfNotNull(material.title, material.note, material.course.courseName).joinToString(10.toChar().toString())
                )
            )
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
}
